package io.github.doubaoa11y.hook

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.view.accessibility.AccessibilityManager
import io.github.doubaoa11y.ConfigSpec
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 给普通用户用的日志：写进豆包自己的文件夹（最多约 1MB，豆包重启也不丢），
 * 设置页可以让豆包把日志复制到剪贴板，或者存一份到“下载”文件夹，用户直接发给作者。
 * - 没打开“日志包含输入的文字”时，写进去之前把「」和引号里的文字换成星号（按键名称保留）。
 * - “记录日志”打开 1 小时后自动关闭，免得忘了关。
 */
object LogStore {
    private const val FILE = "doubao_a11y_log.txt"
    private const val OLD = "doubao_a11y_log.1.txt"
    private const val MAX_BYTES = 512 * 1024L       // 两个文件加起来约 1MB
    private const val COPY_CHARS = 3000             // QQ 一条消息放得下
    private const val AUTO_OFF_MS = 60 * 60 * 1000L
    const val PREF_SINCE = "runLogSince"

    @Volatile
    private var ctx: Context? = null
    private var handler: Handler? = null
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(c: Context) {
        if (ctx != null) return
        ctx = c.applicationContext ?: c
        try {
            val t = HandlerThread("DoubaoA11yLog").apply { start() }
            handler = Handler(t.looper)
        } catch (_: Throwable) {
        }
    }

    private fun dir(): File? = ctx?.filesDir

    /** L 每写一条都调这里（已经判断过要不要记） */
    fun add(level: Char, msg: String) {
        val h = handler ?: return
        val line = "${time.format(Date())} $level ${if (Config.b("logText")) msg else redact(msg)}\n"
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

    private fun readAll(): String {
        val d = dir() ?: return ""
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

    /** 日志开头的基本信息：版本、手机、安卓、开着的无障碍服务、全部设置 */
    fun header(c: Context): String {
        val sb = StringBuilder("==== 豆包输入法无障碍模块 日志 ====\n")
        sb.append("导出时间：").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
        sb.append("模块版本：").append(ConfigSpec.MODULE_VERSION_NAME).append("（").append(ConfigSpec.MODULE_VERSION_CODE).append("）\n")
        sb.append(ServiceHooks.versionLine(c)).append('\n')
        sb.append("手机：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append("，安卓 ").append(Build.VERSION.RELEASE).append("（API ").append(Build.VERSION.SDK_INT).append("）\n")
        sb.append("状态：").append(A11yState.describe()).append('\n')
        try {
            val am = c.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
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

    /** 复制最近的日志到剪贴板，返回给设置页显示的话 */
    fun copy(c: Context): String {
        val all = readAll()
        if (all.isBlank()) return "还没有日志。请先打开“记录日志”，再去重现问题。"
        val head = header(c)
        val room = (COPY_CHARS - head.length).coerceAtLeast(800)
        var tail = if (all.length > room) all.substring(all.length - room) else all
        // 从整行开始
        if (tail.length < all.length) tail = tail.substringAfter('\n', tail)
        val text = head + tail
        val cm = c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("豆包无障碍日志", text))
        return "已复制最近的日志，共 ${text.length} 个字，可以直接粘贴发给作者。日志很长的话，用“保存日志文件”发完整的。" + offHint()
    }

    /** 完整日志存到“下载”文件夹 */
    fun save(c: Context): String {
        val all = readAll()
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
        }
        if (h != null) h.post(r) else r.run()
        return "日志已清空"
    }

    private fun offHint() = if (Config.b("runLog")) "问题记录好了的话，可以关掉“记录日志”。" else ""
}
