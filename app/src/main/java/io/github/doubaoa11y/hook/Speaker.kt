package io.github.doubaoa11y.hook

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import io.github.doubaoa11y.ConfigSpec

/** 模块自己的提示可选独立 TTS；失败立刻走原有读屏路径，不打断或隐藏读屏。 */
object Speaker {
    private var ctx: Context? = null
    private var am: AccessibilityManager? = null
    private var pkg = ""
    private var muteUntil = 0L
    @Volatile private var ttsRequested = false
    @Volatile private var speakGen = 0L

    private sealed class TtsCmd {
        object Warmup : TtsCmd()
        object Stop : TtsCmd()
        class Speak(val text: String, val usePunct: Boolean, val gen: Long) : TtsCmd()
    }

    private val queueLock = Any()
    private val pendingSpeaks = ArrayDeque<TtsCmd.Speak>()
    private var pendingStop = false
    private var pendingWarmup = false
    private var workerRunning = false
    private val ttsExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "DoubaoA11ySpeaker").apply { isDaemon = true }
    }

    private fun scheduleDrainLocked() {
        if (workerRunning) return
        workerRunning = true
        ttsExecutor.execute { drainTtsQueue() }
    }

    private fun drainTtsQueue() {
        while (true) {
            val cmd: TtsCmd = synchronized(queueLock) {
                when {
                    pendingStop -> {
                        pendingStop = false
                        TtsCmd.Stop
                    }
                    pendingWarmup -> {
                        pendingWarmup = false
                        TtsCmd.Warmup
                    }
                    pendingSpeaks.isNotEmpty() -> pendingSpeaks.removeFirst()
                    else -> {
                        workerRunning = false
                        return
                    }
                }
            }
            try {
                when (cmd) {
                    TtsCmd.Stop -> {
                        if (ttsRequested) {
                            try { ctx?.contentResolver?.call(io.github.doubaoa11y.SettingsProvider.URI, "stop", null, null) } catch (_: Throwable) { }
                            ttsRequested = false
                        }
                    }
                    TtsCmd.Warmup -> {
                        if (A11yState.recording && !Config.b("speakByReader")) {
                            ttsRequested = true
                            try { ctx?.contentResolver?.call(io.github.doubaoa11y.SettingsProvider.URI, "warmup", null, null) } catch (_: Throwable) { }
                        }
                    }
                    is TtsCmd.Speak -> {
                        if (cmd.gen != speakGen) continue
                        ttsRequested = true
                        val spoken = if (cmd.usePunct) Names.punctuationText(cmd.text) else cmd.text
                        val b = Bundle().apply { putString("text", spoken) }
                        val ok = try {
                            ctx?.contentResolver?.call(io.github.doubaoa11y.SettingsProvider.URI, "speak", null, b)?.getBoolean("accepted") == true
                        } catch (t: Throwable) {
                            L.hookError("Speaker.tts", t)
                            false
                        }
                        if (ok || cmd.gen != speakGen) continue
                        mainHandler.post {
                            if (cmd.gen == speakGen) readerSay(cmd.text)
                        }
                    }
                }
            } catch (t: Throwable) {
                L.hookError("Speaker.drain", t)
            }
        }
    }

    fun prepareTts() {
        if (!A11yState.recording || Config.b("speakByReader")) return
        synchronized(queueLock) {
            pendingWarmup = true
            scheduleDrainLocked()
        }
    }
    fun init(c: Context) {
        ctx = c.applicationContext ?: c
        am = c.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        pkg = c.packageName
    }
    fun mute(ms: Long) { muteUntil = SystemClock.uptimeMillis() + ms }
    fun unmute() { muteUntil = 0L }
    fun readerWillSpeak(): Boolean = Config.b("speakByReader") && !TatansBridge.readerEventsSuppressed
    fun hushReader() { /* 不压制用户读屏，宿主应用的选区提示由读屏自身决定。 */ }
    fun echo(text: String) = speak(text)

    fun speak(text: String) {
        if (!A11yState.recording || text.isBlank() || SystemClock.uptimeMillis() < muteUntil) return
        val byReader = Config.b("speakByReader")
        // 走读屏模式时发送无障碍事件必须在主线程，直接调用即可，避免主线程 -> 工作线程 -> 主线程的无效切换
        if (byReader) {
            readerSay(text)
            return
        }
        // 独立 TTS 需要跨进程 contentResolver.call，放到单线程串行队列异步执行；最多保留最近 8 条，永不丢弃 stop
        val usePunct = Config.b("ttsPunctuationNames")
        synchronized(queueLock) {
            val gen = speakGen
            while (pendingSpeaks.size >= 8) pendingSpeaks.removeFirst()
            pendingSpeaks.addLast(TtsCmd.Speak(text, usePunct, gen))
            scheduleDrainLocked()
        }
    }

    private const val PKG_TALKMAN = "com.nirenr.talkman"

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun readerSay(text: String) {
        // sendAccessibilityEvent 必须在主线程调用；从朗读队列过来时投递回去
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            mainHandler.post { readerSay(text) }
            return
        }
        val m = am ?: return
        try {
            if (!m.isEnabled) return
            val now = SystemClock.uptimeMillis()
            // 解说收到无 source 的 HOVER_ENTER 时，若当前未按屏且上一条事件恰好是编辑框的
            // TYPE_VIEW_TEXT_SELECTION_CHANGED(8192)（常见于抬手打字刚写入组字串后），会静默丢弃播报。
            // 仅在解说下先发一次无副作用的 HOVER_EXIT(256) 刷新上一事件类型，避免候选和提示偶发漏读。
            if (A11yState.readerPackage == PKG_TALKMAN) {
                val exit = event(AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
                exit.packageName = pkg
                exit.className = "android.inputmethodservice.Keyboard\$Key"
                exit.eventTime = now
                m.sendAccessibilityEvent(exit)
            }
            val ev = event(AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)
            ev.packageName = pkg
            ev.className = "android.inputmethodservice.Keyboard\$Key"
            ev.contentDescription = text
            ev.eventTime = now
            m.sendAccessibilityEvent(ev)
        } catch (t: Throwable) { L.hookError("Speaker.reader", t) }
    }
    fun stop() {
        synchronized(queueLock) {
            speakGen++
            pendingSpeaks.clear()
            if (ttsRequested) {
                pendingStop = true
                scheduleDrainLocked()
            }
        }
        val m = am ?: return
        try {
            if (!m.isEnabled) return
            val ev = event(AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
            ev.packageName = pkg
            ev.className = "android.inputmethodservice.Keyboard\$Key"
            ev.eventTime = SystemClock.uptimeMillis()
            m.sendAccessibilityEvent(ev)
        } catch (t: Throwable) { L.hookError("Speaker.stop", t) }
    }
    private fun event(type: Int): AccessibilityEvent = if (Build.VERSION.SDK_INT >= 30) AccessibilityEvent(type)
        else @Suppress("DEPRECATION") AccessibilityEvent.obtain(type)
}
