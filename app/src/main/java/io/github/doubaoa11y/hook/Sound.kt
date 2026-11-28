package io.github.doubaoa11y.hook

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import io.github.doubaoa11y.SettingsProvider
import io.github.doubaoa11y.SoundEngine
import io.github.doubaoa11y.ui.ZipBackup
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * 在豆包输入法进程内直接播放音效：
 * - 内置音效从宿主/模块 APK 的 assets/sfx 提取到豆包本地缓存并用 SoundPool 播放；
 * - 用户导入的自定义音效通过 SettingsProvider 同步缓存在豆包私有目录并用 MediaPlayer 播放；
 * - 关闭模块设置页后台后无需跨进程唤醒模块进程，音效依然正常发声。
 */
object Sound {
    private const val BUILTIN_CACHE_DIR = "sfx_builtin_v1"
    private const val CUSTOM_CACHE_DIR = "sounds_cache"

    private var context: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(8),
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )

    private var pool: SoundPool? = null
    private var player: MediaPlayer? = null
    private var usage = -1
    private val ids = HashMap<String, Int>()
    private val ready = HashSet<Int>()
    private var stream = 0
    private data class Pending(val id: Int, val volume: Float, val at: Long, val sequence: Long)
    private var pending: Pending? = null
    @Volatile private var sequence = 0L
    private var last = 0L
    private var lastEvent = ""

    private val missingCustomSlots = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun init(c: Context) {
        val app = c.applicationContext ?: c
        context = app
        worker.execute {
            try {
                for (slot in SoundEngine.slots) materializeBuiltin(app, slot)
                main.post { preloadBuiltins(app) }
                syncCustomNow(app, force = false)
            } catch (t: Throwable) {
                L.hookError("Sound.init", t)
            }
        }
    }

    private fun preloadBuiltins(c: Context) {
        if (!Config.b("soundOn")) return
        val wantUsage = if (Config.b("ttsA11yStream")) {
            AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
        } else {
            AudioAttributes.USAGE_MEDIA
        }
        try {
            ensurePool(wantUsage)
            val p = pool ?: return
            for (slot in SoundEngine.slots) {
                if (ids.containsKey(slot)) continue
                val file = File(File(c.cacheDir, BUILTIN_CACHE_DIR), SoundEngine.assets[SoundEngine.slots.indexOf(slot)])
                if (!file.isFile || file.length() <= 100L) continue
                val loadedId = p.load(file.absolutePath, 1)
                if (loadedId != 0) ids[slot] = loadedId
            }
        } catch (t: Throwable) {
            L.hookError("Sound.preloadBuiltins", t)
        }
    }

    fun onConfigChanged() {
        val c = context ?: return
        missingCustomSlots.clear()
        if (!A11yState.moduleOn || !Config.b("soundOn")) {
            stop()
            return
        }
        worker.execute {
            try {
                syncCustomNow(c, force = true)
            } catch (t: Throwable) {
                L.hookError("Sound.onConfigChanged", t)
            }
        }
    }

    fun syncCustomSounds() {
        val c = context ?: return
        worker.execute {
            try {
                syncCustomNow(c, force = false)
            } catch (t: Throwable) {
                L.hookError("Sound.syncCustomSounds", t)
            }
        }
    }

    @Synchronized fun play(event: String) {
        if (!A11yState.recording || !Config.b("soundOn")) return
        if (event !in SoundEngine.events) return
        val now = SystemClock.uptimeMillis()
        // “voice” 起录提示声优先：不被紧邻的按键/双击/手势声节流吞掉，也不被刚结束的手势尾音打断
        if (event == "voice") {
            if (lastEvent == "voice" && now - last < 35) return
        } else {
            if (lastEvent == "voice" && now - last < 120) return
            if (now - last < 35) return
        }
        last = now
        lastEvent = event
        val c = context ?: return
        val volume = Config.i("soundVolume").coerceIn(0, 100) / 100f
        if (volume <= 0f) return
        val request = ++sequence
        val selected = selectedFor(c, event)
        val fallbackSlot = selected.removePrefix("builtin.").takeIf { it in SoundEngine.slots }
            ?: event.takeIf { it in SoundEngine.slots } ?: "key"
        val wantUsage = if (Config.b("ttsA11yStream")) {
            AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
        } else {
            AudioAttributes.USAGE_MEDIA
        }

        if (!selected.startsWith("builtin.") && ZipBackup.validSlot(selected)) {
            val cached = customFile(c, selected)
            if (cached.isFile && cached.length() > 0L) {
                main.post { playCustomOrFallback(request, c, cached, fallbackSlot, wantUsage, volume) }
            } else {
                worker.execute {
                    if (request != sequence) return@execute
                    val fetched = fetchCustom(c, selected)
                    main.post {
                        if (fetched != null && fetched.isFile && fetched.length() > 0L) {
                            playCustomOrFallback(request, c, fetched, fallbackSlot, wantUsage, volume)
                        } else {
                            playBuiltin(request, c, fallbackSlot, wantUsage, volume)
                        }
                    }
                }
            }
            return
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            playBuiltin(request, c, fallbackSlot, wantUsage, volume)
        } else {
            main.post { playBuiltin(request, c, fallbackSlot, wantUsage, volume) }
        }
    }

    private fun selectedFor(c: Context, event: String): String {
        val key = SoundEngine.keys[event] ?: return "builtin.$event"
        val saved = Config.s(key + "Id")
        if (saved.isNotEmpty()) {
            if (saved.startsWith("builtin.") && saved.removePrefix("builtin.") in SoundEngine.slots) return saved
            if (ZipBackup.validSlot(saved)) return saved
            return "builtin.$event"
        }
        val old = SoundEngine.slots.getOrNull(Config.i(key)) ?: event
        return if (customFile(c, old).isFile) old else "builtin.$old"
    }

    private fun playCustomOrFallback(request: Long, c: Context, file: File, fallbackSlot: String, wantUsage: Int, volume: Float) {
        if (request != sequence) return
        if (!A11yState.recording || !Config.b("soundOn")) {
            stopNow()
            return
        }
        try {
            stopNow()
            val p = MediaPlayer()
            player = p
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(wantUsage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            p.setVolume(volume, volume)
            p.setDataSource(file.absolutePath)
            p.setOnPreparedListener {
                try {
                    if (player === it && request == sequence) it.start() else if (player === it) stopNow()
                } catch (_: Throwable) {
                    if (player === p) stopNow()
                }
            }
            p.setOnCompletionListener { if (player === it) stopNow() }
            p.setOnErrorListener { _, _, _ ->
                if (player === p) {
                    stopNow()
                    playBuiltin(request, c, fallbackSlot, wantUsage, volume)
                }
                true
            }
            p.prepareAsync()
            main.postDelayed({ if (player === p) stopNow() }, 5500)
        } catch (_: Throwable) {
            stopNow()
            playBuiltin(request, c, fallbackSlot, wantUsage, volume)
        }
    }

    private fun ensurePool(wantUsage: Int) {
        if (pool != null && usage == wantUsage) return
        pool?.release()
        ids.clear()
        ready.clear()
        pending = null
        usage = wantUsage
        pool = SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
            .also { current ->
                current.setOnLoadCompleteListener { p, id, status ->
                    main.post {
                        if (pool === p && status == 0) {
                            ready.add(id)
                            pending?.takeIf { it.id == id && it.sequence == sequence }?.let { request ->
                                pending = null
                                if (SystemClock.uptimeMillis() - request.at < 600) {
                                    stream = p.play(id, request.volume, request.volume, 1, 0, 1f)
                                }
                            }
                        }
                    }
                }
            }
    }

    private fun playBuiltin(request: Long, c: Context, slot: String, wantUsage: Int, volume: Float) {
        if (request != sequence) return
        if (!A11yState.recording || !Config.b("soundOn")) {
            stopNow()
            return
        }
        try {
            stopNow()
            ensurePool(wantUsage)
            val p = pool ?: return
            val existingId = ids[slot]
            if (existingId != null) {
                if (existingId in ready) {
                    stream = p.play(existingId, volume, volume, 1, 0, 1f)
                } else {
                    pending = Pending(existingId, volume, SystemClock.uptimeMillis(), request)
                }
                return
            }
            val file = materializeBuiltin(c, slot) ?: return
            val loadedId = p.load(file.absolutePath, 1)
            if (loadedId != 0) {
                ids[slot] = loadedId
                pending = Pending(loadedId, volume, SystemClock.uptimeMillis(), request)
            }
        } catch (t: Throwable) {
            pending = null
            L.hookError("Sound.playBuiltin", t)
        }
    }

    private fun materializeBuiltin(c: Context, slot: String): File? {
        val idx = SoundEngine.slots.indexOf(slot)
        if (idx < 0) return null
        val assetName = SoundEngine.assets[idx]
        val dir = File(c.cacheDir, BUILTIN_CACHE_DIR)
        val out = File(dir, assetName)
        if (out.isFile && out.length() > 100L) return out
        try {
            dir.mkdirs()
        } catch (_: Throwable) {
        }

        // 1) 宿主合并后的 assets（LSPatch 本地/集成模式）
        try {
            c.assets.open("sfx/$assetName").use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            if (out.length() > 100L) return out
        } catch (_: Throwable) {
        }

        // 2) 模块包上下文的 assets（无需模块后台运行，直接读已安装 APK）
        try {
            val moduleCtx = c.createPackageContext("io.github.doubaoa11y", 0)
            moduleCtx.assets.open("sfx/$assetName").use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            if (out.length() > 100L) return out
        } catch (_: Throwable) {
        }

        // 3) 从 classLoader / PackageManager 找到模块 APK 路径，按 zip 读取
        for (apk in moduleApks(c)) {
            try {
                ZipFile(apk).use { z ->
                    val entry = z.getEntry("assets/sfx/$assetName") ?: return@use
                    z.getInputStream(entry).use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                if (out.length() > 100L) return out
            } catch (_: Throwable) {
            }
        }
        try {
            out.delete()
        } catch (_: Throwable) {
        }
        return null
    }

    private fun moduleApks(c: Context): List<String> {
        val out = ArrayList<String>()
        try {
            val src = c.packageManager.getApplicationInfo("io.github.doubaoa11y", 0).sourceDir
            if (!src.isNullOrBlank()) out.add(src)
        } catch (_: Throwable) {
        }
        try {
            var cl: ClassLoader? = Sound::class.java.classLoader
            val baseCls = Class.forName("dalvik.system.BaseDexClassLoader")
            val pathListField = baseCls.getDeclaredField("pathList").apply { isAccessible = true }
            while (cl != null) {
                if (baseCls.isInstance(cl)) {
                    val pathList = pathListField.get(cl)
                    val elements = pathList?.javaClass?.getDeclaredField("dexElements")
                        ?.apply { isAccessible = true }?.get(pathList) as? Array<*>
                    if (elements != null) {
                        for (e in elements) {
                            if (e == null) continue
                            for (fName in arrayOf("path", "zip", "file")) {
                                val v = try {
                                    e.javaClass.getDeclaredField(fName).apply { isAccessible = true }.get(e)
                                } catch (_: Throwable) {
                                    null
                                }
                                val path = (v as? File)?.absolutePath ?: (v as? String)
                                if (!path.isNullOrBlank() && path.endsWith(".apk", true) && path !in out) {
                                    out.add(path)
                                }
                            }
                        }
                    }
                }
                cl = cl.parent
            }
        } catch (_: Throwable) {
        }
        return out
    }

    private fun customFile(c: Context, id: String): File = File(c.filesDir, "$CUSTOM_CACHE_DIR/$id.audio")

    private fun syncCustomNow(c: Context, force: Boolean) {
        val needed = LinkedHashSet<String>()
        for (key in SoundEngine.keys.values) {
            val id = Config.s(key + "Id")
            if (id.isNotEmpty() && !id.startsWith("builtin.") && ZipBackup.validSlot(id)) {
                needed.add(id)
            }
        }
        for (slot in SoundEngine.slots) {
            needed.add(slot)
        }
        for (id in needed) {
            if (!force && id in missingCustomSlots) continue
            val target = customFile(c, id)
            if (force || !target.isFile || target.length() <= 0L) {
                fetchCustom(c, id)
            }
        }
    }

    private fun fetchCustom(c: Context, id: String): File? {
        if (!ZipBackup.validSlot(id)) return null
        return try {
            val b = c.contentResolver.call(SettingsProvider.URI, "openSound", id, null) ?: return null
            @Suppress("DEPRECATION")
            val pfd = b.getParcelable<ParcelFileDescriptor>("fd")
            if (pfd == null) {
                // Provider 正常响应但确无此自定义文件：记为负缓存，配置变化时自动清空
                missingCustomSlots.add(id)
                return null
            }
            missingCustomSlots.remove(id)
            val target = customFile(c, id)
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, "$id.tmp")
            pfd.use { d ->
                FileInputStream(d.fileDescriptor).use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                }
            }
            if (temp.length() > 0L) {
                if (target.exists()) target.delete()
                if (temp.renameTo(target)) target else null
            } else {
                temp.delete()
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun stopNow() {
        pending = null
        try {
            player?.release()
        } catch (_: Throwable) {
        }
        player = null
        try {
            if (stream != 0) pool?.stop(stream)
        } catch (_: Throwable) {
        }
        stream = 0
    }

    @Synchronized fun stop() {
        sequence++
        // play 与 stop 串行入队；后来的播放会在本次清理之后执行。
        if (Looper.myLooper() == Looper.getMainLooper()) stopNow() else main.post { stopNow() }
    }
}
