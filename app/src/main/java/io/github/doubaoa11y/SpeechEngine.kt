package io.github.doubaoa11y

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** 在模块自身进程运行 TTS，避免改写豆包进程的 PackageManager 查询。 */
object SpeechEngine {
    private val main = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var config = ""
    private var generation = 0
    private var starting = false
    private var ready = false
    private var failedAt = 0L

    @Synchronized
    fun prepare(ctx: Context, p: SharedPreferences) {
        val key = listOf("ttsEngine", "ttsVoice").joinToString("|") { p.getString(it, "").orEmpty() } +
            listOf("ttsRate", "ttsPitch", "ttsVolume").joinToString("|") { p.getInt(it, 100).toString() } + p.getBoolean("ttsA11yStream", false)
        if (key == config && (ready || starting || android.os.SystemClock.uptimeMillis() - failedAt < 5000)) return
        reset()
        config = key
        starting = true
        val token = ++generation
        val pkg = p.getString("ttsEngine", "").orEmpty()
        main.post {
            synchronized(this) {
                if (token != generation) return@post
                try {
                    val listener = TextToSpeech.OnInitListener { status ->
                        main.post {
                            synchronized(this) {
                                if (token != generation) return@post
                                starting = false
                                ready = status == TextToSpeech.SUCCESS && engine != null
                                if (!ready) failedAt = android.os.SystemClock.uptimeMillis()
                                if (ready) configure(p)
                            }
                        }
                    }
                    engine = if (pkg.isEmpty()) TextToSpeech(ctx.applicationContext, listener)
                        else TextToSpeech(ctx.applicationContext, listener, pkg)
                    engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) { }
                        override fun onDone(id: String?) { }
                        @Deprecated("兼容旧 TTS 引擎")
                        override fun onError(id: String?) {
                            synchronized(this@SpeechEngine) {
                                if (token == generation) { ready = false; failedAt = android.os.SystemClock.uptimeMillis() }
                            }
                            android.util.Log.w("DoubaoA11y", "语音库异步播放失败，后续提示回退读屏")
                        }
                    })
                    main.postDelayed({
                        synchronized(this) {
                            if (token == generation && starting) {
                                reset()
                                failedAt = android.os.SystemClock.uptimeMillis()
                            }
                        }
                    }, 5000)
                } catch (t: Throwable) {
                    starting = false
                    ready = false
                    failedAt = android.os.SystemClock.uptimeMillis()
                    android.util.Log.w("DoubaoA11y", "初始化模块语音库失败", t)
                }
            }
        }
    }

    private fun configure(p: SharedPreferences) {
        try {
            val e = engine ?: return
            e.setSpeechRate((p.getInt("ttsRate", 100) / 100f).coerceIn(0.5f, 2f))
            e.setPitch((p.getInt("ttsPitch", 100) / 100f).coerceIn(0.5f, 2f))
            val name = p.getString("ttsVoice", "").orEmpty()
            val v = e.voices?.firstOrNull { it.name == name }
            if (v != null) e.voice = v else e.language = Locale.SIMPLIFIED_CHINESE
            e.setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(if (p.getBoolean("ttsA11yStream", false)) AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY else AudioAttributes.USAGE_MEDIA).build())
        } catch (t: Throwable) {
            ready = false
            failedAt = android.os.SystemClock.uptimeMillis()
            android.util.Log.w("DoubaoA11y", "配置模块语音库失败", t)
        }
    }

    @Synchronized
    fun speak(ctx: Context, p: SharedPreferences, text: String): Boolean {
        prepare(ctx, p)
        if (!ready || text.isBlank()) return false
        return try {
            val e = engine ?: return false
            val b = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, (p.getInt("ttsVolume", 100) / 100f).coerceIn(0f, 1f)) }
            val maxLen = TextToSpeech.getMaxSpeechInputLength().coerceIn(1000, 3500)
            val chunks = splitChunks(text, maxLen)
            val baseId = "module-${generation}-${System.nanoTime()}"
            var ok = true
            for (i in chunks.indices) {
                val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                if (e.speak(chunks[i], mode, b, "$baseId-$i") != TextToSpeech.SUCCESS) {
                    ok = false
                    break
                }
            }
            if (!ok) { ready = false; failedAt = android.os.SystemClock.uptimeMillis() }
            ok
        } catch (t: Throwable) {
            ready = false
            failedAt = android.os.SystemClock.uptimeMillis()
            false
        }
    }

    /** 按系统 TTS 单次上限分段（优先在换行或句末标点切分，避免截断长文） */
    private fun splitChunks(text: String, maxLen: Int): List<String> {
        if (text.length <= maxLen) return listOf(text)
        val out = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(text.length, start + maxLen)
            if (end < text.length) {
                val minBreak = start + (maxLen * 3) / 4
                var cut = -1
                for (i in end - 1 downTo minBreak) {
                    val c = text[i]
                    if (c == '\n' || c == '。' || c == '！' || c == '？' || c == '；' || c == '.' || c == '!' || c == '?') {
                        cut = i + 1
                        break
                    }
                }
                if (cut > start) {
                    end = cut
                } else if (Character.isHighSurrogate(text[end - 1]) && end > start + 1) {
                    end--
                }
            }
            out.add(text.substring(start, end))
            start = end
        }
        return out
    }

    @Synchronized
    fun stop() { try { engine?.stop() } catch (_: Throwable) { } }

    @Synchronized
    private fun reset() {
        generation++
        starting = false
        ready = false
        val old = engine
        engine = null
        try { old?.stop() } catch (t: Throwable) { android.util.Log.w("DoubaoA11y", "停止旧语音引擎失败", t) }
        try { old?.shutdown() } catch (t: Throwable) { android.util.Log.w("DoubaoA11y", "释放旧语音引擎失败", t) }
    }
}
