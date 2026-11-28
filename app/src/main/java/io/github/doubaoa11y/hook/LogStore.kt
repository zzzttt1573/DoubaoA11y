package io.github.doubaoa11y.hook

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.provider.MediaStore
import android.view.accessibility.AccessibilityManager
import io.github.doubaoa11y.ConfigSpec
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 给普通用户用的日志：写进豆包自己的文件夹（最多约 1MB，豆包重启也不丢），
 * 设置页可以让豆包把日志复制到剪贴板，或者存一份到“下载”文件夹，用户直接发给作者。
 * - 没打开“日志包含输入的文字”时，写进去之前把「」和引号里的文字换成星号（按键名称保留）。
 * - “记录日志”打开 1 小时后自动关闭，免得忘了关。
 */
object LogStore {
    private const val FILE = "doubao_a11y_log.txt"
    private const val OLD = "doubao_a11y_log.1.txt"
    private const val CRASH_FILE = "doubao_a11y_crash.txt"
    private const val PREF_LAST_EXIT_TS = "lastRecordedExitTs"
    private const val MAX_BYTES = 512 * 1024L       // 两个文件加起来约 1MB
    private const val MAX_CRASH_BYTES = 128 * 1024L
    private const val COPY_CHARS = 6000             // 优先完整保留崩溃/退出信息，其余放最近运行日志
    private const val COPY_CRASH_BUDGET = 3200
    private const val AUTO_OFF_MS = 60 * 60 * 1000L
    const val PREF_SINCE = "runLogSince"

    @Volatile
    private var ctx: Context? = null
    @Volatile
    private var crashWrapper: Thread.UncaughtExceptionHandler? = null
    private val crashLock = Any()
    private val exitLock = Any()
    private val timeLock = Any()
    private val handlingUncaught = ThreadLocal<Boolean>()
    private val procStartMs = System.currentTimeMillis()
    private var handler: Handler? = null
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(c: Context) {
        val app = c.applicationContext ?: c
        if (ctx == null) {
            ctx = app
            try {
                val t = HandlerThread("DoubaoA11yLog").apply { start() }
                handler = Handler(t.looper)
            } catch (_: Throwable) {
            }
            if (Build.VERSION.SDK_INT >= 30) {
                handler?.postDelayed({ recordHistoricalExits(app) }, 1200L)
            }
        }
        ensureCrashHandler(app)
    }

    /**
     * 透明捕获豆包进程 Java 闪退；若豆包自身 CrashSDK 后续覆盖了 DefaultUncaughtExceptionHandler，
     * 会自动重新链式包一层，绝不吞掉异常也不破坏原有上报。
     */
    fun ensureCrashHandler(c: Context? = null) {
        val app = (c?.applicationContext ?: c ?: ctx) ?: return
        synchronized(crashLock) {
            try {
                val cur = Thread.getDefaultUncaughtExceptionHandler()
                val existing = crashWrapper
                if (existing != null && cur === existing) return
                val prev = cur
                val wrapper = Thread.UncaughtExceptionHandler { thread, throwable ->
                    if (handlingUncaught.get() != true) {
                        handlingUncaught.set(true)
                        try {
                            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                            val sb = StringBuilder()
                            sb.append("\n[Java闪退 ").append(ts).append("] PID=").append(Process.myPid())
                                .append(" 线程=").append(thread.name)
                                .append(" 机型=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                                .append(" Android=").append(Build.VERSION.RELEASE).append("(API ").append(Build.VERSION.SDK_INT).append(")")
                                .append(" 模块=").append(ConfigSpec.MODULE_VERSION_NAME).append('\n')
                            sb.append(android.util.Log.getStackTraceString(throwable)).append('\n')
                            appendCrashSync(app, sb.toString())
                        } catch (_: Throwable) {
                        } finally {
                            handlingUncaught.set(false)
                        }
                    }
                    if (prev != null && prev !== crashWrapper) {
                        prev.uncaughtException(thread, throwable)
                    }
                }
                crashWrapper = wrapper
                Thread.setDefaultUncaughtExceptionHandler(wrapper)
            } catch (_: Throwable) {
            }
        }
    }

    private fun appendCrashSync(app: Context, text: String) {
        synchronized(exitLock) {
            try {
                val f = File(app.filesDir, CRASH_FILE)
                if (f.exists() && f.length() > MAX_CRASH_BYTES) {
                    val keep = runCatching { f.readText() }.getOrDefault("")
                    f.writeText(keep.takeLast((MAX_CRASH_BYTES / 2).toInt()))
                }
                f.appendText(text)
            } catch (_: Throwable) {
            }
        }
    }

    @android.annotation.TargetApi(30)
    private fun reasonLabel(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "主动退出/自杀(EXIT_SELF)"
        ApplicationExitInfo.REASON_SIGNALED -> "致命信号终止(SIGNALED)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "内存不足被杀(LOW_MEMORY)"
        ApplicationExitInfo.REASON_CRASH -> "Java崩溃(CRASH)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native闪退(CRASH_NATIVE)"
        ApplicationExitInfo.REASON_ANR -> "无响应(ANR)"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "启动初始化失败(INIT_FAILURE)"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "权限变更重启(PERMISSION_CHANGE)"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "资源占用过高(EXCESSIVE_RESOURCE)"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "用户手动关闭/强停(USER_REQUESTED)"
        ApplicationExitInfo.REASON_USER_STOPPED -> "系统/用户停止(USER_STOPPED)"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "依赖进程退出(DEPENDENCY_DIED)"
        ApplicationExitInfo.REASON_OTHER -> "其他/系统策略终止(OTHER)"
        14 -> "后台冻结被杀(FREEZER)"
        15 -> "应用状态变更(PACKAGE_STATE_CHANGE)"
        16 -> "应用更新重启(PACKAGE_UPDATED)"
        else -> "未知原因($reason)"
    }

    @android.annotation.TargetApi(30)
    private fun signalLabel(reason: Int, status: Int): String {
        val sig = when (reason) {
            ApplicationExitInfo.REASON_SIGNALED,
            ApplicationExitInfo.REASON_CRASH_NATIVE -> when (status) {
                4 -> "SIGILL"
                5 -> "SIGTRAP"
                6 -> "SIGABRT"
                7 -> "SIGBUS"
                8 -> "SIGFPE"
                9 -> "SIGKILL"
                11 -> "SIGSEGV"
                13 -> "SIGPIPE"
                15 -> "SIGTERM"
                31 -> "SIGSYS"
                else -> ""
            }
            else -> ""
        }
        return if (sig.isNotEmpty()) "$status($sig)" else status.toString()
    }

    @android.annotation.TargetApi(30)
    private fun shouldPersistExit(reason: Int): Boolean = when (reason) {
        ApplicationExitInfo.REASON_USER_REQUESTED,
        ApplicationExitInfo.REASON_USER_STOPPED,
        16 -> false
        else -> true
    }

    @android.annotation.TargetApi(30)
    private fun formatExitInfo(info: ApplicationExitInfo, includeTrace: Boolean): String {
        val whenStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(info.timestamp))
        val sb = StringBuilder()
        sb.append("[系统退出 ").append(whenStr).append("] 进程=").append(info.processName)
            .append(" PID=").append(info.pid)
            .append(" 原因=").append(reasonLabel(info.reason))
            .append(" status=").append(signalLabel(info.reason, info.status))
            .append(" imp=").append(info.importance)
            .append(" PSS=").append(info.pss / 1024).append("MB")
            .append(" RSS=").append(info.rss / 1024).append("MB")
            .append(" 描述=").append(info.description?.trim().takeIf { !it.isNullOrEmpty() } ?: "无")
            .append('\n')
        if (includeTrace) {
            val trace = extractTraceSummary(info)
            if (trace.isNotEmpty()) {
                sb.append("  [Trace摘要]\n").append(trace).append('\n')
            }
        }
        return sb.toString()
    }

    @android.annotation.TargetApi(30)
    private fun extractTraceSummary(info: ApplicationExitInfo): String {
        return try {
            val stream = info.traceInputStream ?: return ""
            stream.use { TombstoneParser.summarize(it) }
        } catch (t: Throwable) {
            "  (读取trace失败: ${t.javaClass.simpleName}: ${t.message})"
        }
    }

    @android.annotation.TargetApi(30)
    private fun recordHistoricalExits(app: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        synchronized(exitLock) {
            try {
                val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
                val reasons = am.getHistoricalProcessExitReasons(app.packageName, 0, 10)
                if (reasons.isEmpty()) return
                val p = app.getSharedPreferences(ConfigSpec.PREFS_NAME, Context.MODE_PRIVATE)
                val lastTs = p.getLong(PREF_LAST_EXIT_TS, 0L)
                var newestTs = lastTs
                val sb = StringBuilder()
                for (info in reasons.asReversed()) {
                    val ts = info.timestamp
                    if (ts <= lastTs) continue
                    if (ts > newestTs) newestTs = ts
                    if (!shouldPersistExit(info.reason)) continue
                    sb.append(formatExitInfo(info, includeTrace = true))
                }
                if (newestTs > lastTs) {
                    p.edit().putLong(PREF_LAST_EXIT_TS, newestTs).apply()
                }
                if (sb.isNotEmpty()) {
                    appendCrashSync(app, sb.toString())
                    addRaw('E', "检测到豆包上次异常退出：\n$sb")
                }
            } catch (_: Throwable) {
            }
        }
    }

    @android.annotation.TargetApi(30)
    private fun snapshotRecentExits(app: Context): String {
        if (Build.VERSION.SDK_INT < 30) return ""
        return try {
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return ""
            val reasons = am.getHistoricalProcessExitReasons(app.packageName, 0, 10)
            if (reasons.isEmpty()) return ""
            val sb = StringBuilder("==== 系统最近进程退出记录（共 ${reasons.size} 条，最新在前） ====\n")
            reasons.forEachIndexed { idx, info ->
                // 前 3 条异常退出尝试附带 trace 摘要，其余只列元信息避免过长
                val wantTrace = idx < 3 && shouldPersistExit(info.reason)
                sb.append(formatExitInfo(info, includeTrace = wantTrace))
            }
            sb.append("==== 退出记录结束 ====\n")
            sb.toString()
        } catch (t: Throwable) {
            "==== 读取系统退出记录失败：${t.javaClass.simpleName}: ${t.message} ====\n"
        }
    }

    private fun detectRuntimeMode(c: Context): String = try {
        RuntimeEnv.detect(c).describe()
    } catch (_: Throwable) {
        "未知"
    }

    private fun flushPending() {
        val h = handler ?: return
        if (h.looper.thread === Thread.currentThread()) return
        val latch = CountDownLatch(1)
        if (h.post { latch.countDown() }) {
            runCatching { latch.await(250L, TimeUnit.MILLISECONDS) }
        }
    }

    private fun dir(): File? = ctx?.filesDir

    /** L 每写一条都调这里（已经判断过要不要记） */
    fun add(level: Char, msg: String) {
        appendLogLine(level, if (Config.b("logText")) msg else redact(msg))
    }

    /** 系统崩溃/退出摘要专用：不含用户输入，不做引号打码，避免破坏栈信息 */
    private fun addRaw(level: Char, msg: String) {
        appendLogLine(level, msg)
    }

    private fun appendLogLine(level: Char, body: String) {
        val h = handler ?: return
        val ts = synchronized(timeLock) { time.format(Date()) }
        val line = "$ts $level $body\n"
        h.post {
            try {
                val d = dir() ?: return@post
                val f = File(d, FILE)
                if (f.length() > MAX_BYTES) {
                    val o = File(d, OLD)
                    o.delete()
                    f.renameTo(o)
                }
                File(d, FILE).appendText(line)
            } catch (_: Throwable) {
            }
        }
    }

    /** 这些按键名在引号里也照常显示，其它文字（字母、汉字、候选、语音结果）换成星号 */
    private val keepWords = setOf(
        "空格", "删除", "回车", "换行", "发送", "搜索", "完成", "下一项", "前往", "符号", "数字", "123", "中英切换",
        "大写", "小写", "上一个候选", "下一个候选", "左移", "右移", "逗号", "句号", "表情", "语音", "返回", "tool",
    )
    private val quoted = Regex("「([^」]*)」|\"([^\"]*)\"")

    fun redact(s: String): String = quoted.replace(s) { m ->
        val inner = m.groupValues[1].ifEmpty { m.groupValues[2] }
        if (inner.isEmpty() || inner in keepWords) m.value
        else {
            val stars = "*".repeat(minOf(inner.length, 20))
            if (m.value.startsWith("「")) "「$stars」" else "\"$stars\""
        }
    }

    // ---------------- 自动关闭 ----------------

    private fun prefs() = ctx?.getSharedPreferences(ConfigSpec.PREFS_NAME, Context.MODE_PRIVATE)

    /** 收到设置后调：“记录日志”刚打开时记下时间；开了超过 1 小时就关掉 */
    fun onConfig(wasOn: Boolean) {
        try {
            val p = prefs() ?: return
            val on = Config.b("runLog")
            if (on && !wasOn) p.edit().putLong(PREF_SINCE, System.currentTimeMillis()).apply()
            checkAutoOff()
        } catch (_: Throwable) {
        }
    }

    private var lastCheck = 0L

    fun checkAutoOff() {
        val now = System.currentTimeMillis()
        if (now - lastCheck < 30_000) return
        lastCheck = now
        try {
            if (!Config.b("runLog")) return
            val since = prefs()?.getLong(PREF_SINCE, 0L) ?: 0L
            if (since == 0L) {
                prefs()?.edit()?.putLong(PREF_SINCE, now)?.apply()
                return
            }
            if (now - since > AUTO_OFF_MS) {
                add('I', "日志已经开了 1 小时，自动关闭")
                Config.set("runLog", false)
            }
        } catch (_: Throwable) {
        }
    }

    // ---------------- 导出 ----------------

    private fun readCrashSection(c: Context): String {
        val app = c.applicationContext ?: c
        val sb = StringBuilder()
        val liveExits = snapshotRecentExits(app)
        if (liveExits.isNotBlank()) sb.append(liveExits)
        val d = dir() ?: app.filesDir
        val crash = File(d, CRASH_FILE)
        if (crash.exists()) try {
            val ct = crash.readText()
            if (ct.isNotBlank()) {
                sb.append("==== 持久化崩溃与异常退出历史 ====\n").append(ct.trim()).append("\n==== 历史结束 ====\n")
            }
        } catch (_: Throwable) {
        }
        return sb.toString()
    }

    private fun readRunLogs(c: Context): String {
        val d = dir() ?: (c.applicationContext ?: c).filesDir
        val sb = StringBuilder()
        for (n in listOf(OLD, FILE)) {
            val f = File(d, n)
            if (f.exists()) try {
                sb.append(f.readText())
            } catch (_: Throwable) {
            }
        }
        return sb.toString()
    }

    private fun readAll(c: Context): String {
        val app = c.applicationContext ?: c
        ensureCrashHandler(app)
        recordHistoricalExits(app)
        flushPending()
        val crash = readCrashSection(app)
        val run = readRunLogs(app)
        return crash + run
    }

    /** 日志开头的基本信息：版本、手机、安卓、加载环境、进程、开着的无障碍服务、全部设置 */
    fun header(c: Context): String {
        val app = c.applicationContext ?: c
        val sb = StringBuilder("==== 豆包输入法无障碍模块 日志 ====\n")
        sb.append("导出时间：").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
        sb.append("模块版本：").append(ConfigSpec.MODULE_VERSION_NAME).append("（").append(ConfigSpec.MODULE_VERSION_CODE).append("）\n")
        sb.append(ServiceHooks.versionLine(app)).append('\n')
        sb.append("加载环境：").append(detectRuntimeMode(app))
            .append("，进程PID=").append(Process.myPid())
            .append("，进程启动=").append(SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(procStartMs)))
            .append('\n')
        sb.append("安全库兼容：").append(MetasecCompat.statusLine()).append('\n')
        sb.append("Hook状态：").append(Hooker.summary()).append('\n')
        val hd = Hooker.details()
        if (hd.isNotBlank()) sb.append("Hook异常明细：\n").append(hd.trim()).append('\n')
        sb.append("手机：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append("，安卓 ").append(Build.VERSION.RELEASE).append("（API ").append(Build.VERSION.SDK_INT).append("）\n")
        sb.append("状态：").append(A11yState.describe()).append('\n')
        try {
            val am = app.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val list = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .mapNotNull { it.resolveInfo?.serviceInfo?.let { si -> "${si.packageName}/${si.name.substringAfterLast('.')}" } }
            sb.append("开着的无障碍服务：").append(if (list.isEmpty()) "无" else list.joinToString("，")).append('\n')
        } catch (_: Throwable) {
        }
        sb.append("日志包含输入的文字：").append(if (Config.b("logText")) "是" else "否").append('\n')
        sb.append("设置：").append(Config.summary()).append('\n')
        sb.append("==== 日志 ====\n")
        return sb.toString()
    }

    /** 复制最近的日志到剪贴板：优先保留最新崩溃/退出记录，剩余空间放最近运行日志 */
    fun copy(c: Context): String {
        val app = c.applicationContext ?: c
        ensureCrashHandler(app)
        recordHistoricalExits(app)
        flushPending()
        val crash = readCrashSection(app)
        val run = readRunLogs(app)
        if (crash.isBlank() && run.isBlank()) return "还没有日志。请先打开“记录日志”，再去重现问题。"
        val head = header(app)
        val totalRoom = (COPY_CHARS - head.length).coerceAtLeast(1600)
        val crashPart = if (crash.length > COPY_CRASH_BUDGET) {
            val keep = crash.takeLast(COPY_CRASH_BUDGET)
            keep.substringAfter('\n', keep)
        } else {
            crash
        }
        val runRoom = (totalRoom - crashPart.length).coerceAtLeast(600)
        var runPart = if (run.length > runRoom) run.substring(run.length - runRoom) else run
        if (runPart.length < run.length) runPart = runPart.substringAfter('\n', runPart)
        val text = head + crashPart + runPart
        writeClipboardOnMain(app, text)
        return "已复制最近的日志，共 ${text.length} 个字，可以直接粘贴发给作者。日志很长的话，用“保存日志文件”发完整的。" + offHint()
    }

    private fun writeClipboardOnMain(app: Context, text: String) {
        val clip = ClipData.newPlainText("豆包无障碍日志", text)
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(clip)
            return
        }
        var err: Throwable? = null
        val latch = CountDownLatch(1)
        Handler(android.os.Looper.getMainLooper()).post {
            try {
                val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(clip)
            } catch (t: Throwable) {
                err = t
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(3000L, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("写入剪贴板超时")
        }
        err?.let { throw it }
    }

    /** 将完整日志写入模块中转文件，供设置页保存到“豆包输入法无障碍/日志”文件夹 */
    fun exportToXfer(c: Context, token: String, xferUri: android.net.Uri?): String {
        val all = readAll(c)
        check(all.isNotBlank()) { "还没有日志。请先打开“记录日志”，再去重现问题。" }
        val uri = xferUri ?: io.github.doubaoa11y.SettingsProvider.xferUri(token)
        val pfd = try {
            c.contentResolver.openFileDescriptor(uri, "rw")
        } catch (_: Throwable) {
            null
        } ?: run {
            val b = c.contentResolver.call(io.github.doubaoa11y.SettingsProvider.URI, "openNativeBackupWriteFd", token, null)
            @Suppress("DEPRECATION")
            b?.getParcelable("fd")
        } ?: error("无法打开日志中转文件")
        pfd.use { fd ->
            java.io.FileOutputStream(fd.fileDescriptor).use { out ->
                out.write((header(c) + all).toByteArray(Charsets.UTF_8))
                out.flush()
            }
        }
        return offHint()
    }

    /** 完整日志存到“下载”文件夹（旧调用兜底） */
    fun save(c: Context): String {
        val all = readAll(c)
        if (all.isBlank()) return "还没有日志。请先打开“记录日志”，再去重现问题。"
        if (Build.VERSION.SDK_INT < 29) return "这台手机的安卓版本太低，不能保存文件，请用“复制日志”。"
        val name = "豆包无障碍日志-" + SimpleDateFormat("MMdd-HHmmss", Locale.US).format(Date()) + ".txt"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download")
        }
        val uri = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return "保存失败，请用“复制日志”。"
        c.contentResolver.openOutputStream(uri)?.use { it.write((header(c) + all).toByteArray(Charsets.UTF_8)) }
        return "已保存到手机的“下载”文件夹，文件名是 $name。在 QQ 里发文件，从“下载”里找到它发给作者。" + offHint()
    }

    fun clear(): String {
        val d = dir() ?: return "清空失败"
        val h = handler
        val r = Runnable {
            File(d, FILE).delete()
            File(d, OLD).delete()
            File(d, CRASH_FILE).delete()
        }
        if (h != null) h.post(r) else r.run()
        return "日志已清空"
    }

    private fun offHint() = if (Config.b("runLog")) "问题记录好了的话，可以关掉“记录日志”。" else ""
}
