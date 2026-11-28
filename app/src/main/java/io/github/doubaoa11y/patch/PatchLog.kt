package io.github.doubaoa11y.patch

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import android.util.Log
import io.github.doubaoa11y.ConfigSpec
import io.github.doubaoa11y.ui.AppStorage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 注入/修补与安装向导的独立诊断日志（运行在模块 App 进程）。
 * - 始终写入应用私有目录 files/patch_install_log.txt（无需存储权限）；
 * - 若已授予存储权限，同步追加到 内部存储/豆包输入法无障碍/日志/安装修补日志.txt；
 * - 供排查老版安卓 / Android 13 无法拉起安装界面或安装失败问题。
 */
object PatchLog {
    private const val TAG = "DoubaoA11yPatch"
    private const val PRIVATE_FILE = "patch_install_log.txt"
    private const val PUBLIC_FILE = "安装修补日志.txt"
    private const val MAX_BYTES = 256 * 1024L

    private val lock = Any()
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private fun privateFile(ctx: Context): File = File(ctx.filesDir, PRIVATE_FILE)

    fun deviceSummary(ctx: Context): String {
        val abis = runCatching { Build.SUPPORTED_ABIS.joinToString(",") }.getOrDefault("未知")
        val memInfo = try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mi)
            "可用内存=${mi.availMem / (1024 * 1024)}MB/总内存=${mi.totalMem / (1024 * 1024)}MB"
        } catch (_: Throwable) {
            "内存未知"
        }
        val diskInfo = try {
            val sf = StatFs(ctx.cacheDir.absolutePath)
            "可用存储=${sf.availableBytes / (1024 * 1024)}MB"
        } catch (_: Throwable) {
            "存储未知"
        }
        return "设备=${Build.MANUFACTURER} ${Build.MODEL} (${Build.BRAND}/${Build.DEVICE})，" +
            "Android=${Build.VERSION.RELEASE}(API ${Build.VERSION.SDK_INT})，" +
            "ROM=${Build.DISPLAY}，ABI=$abis，$memInfo，$diskInfo，" +
            "模块=${ConfigSpec.MODULE_VERSION_NAME}(${ConfigSpec.MODULE_VERSION_CODE})"
    }

    fun i(ctx: Context, msg: String) {
        Log.i(TAG, msg)
        append(ctx, "I", msg)
    }

    fun w(ctx: Context, msg: String) {
        Log.w(TAG, msg)
        append(ctx, "W", msg)
    }

    fun e(ctx: Context, msg: String, t: Throwable? = null) {
        val full = if (t == null) msg else "$msg\n${Log.getStackTraceString(t)}"
        Log.e(TAG, full)
        append(ctx, "E", full)
    }

    private fun append(ctx: Context, level: String, msg: String) {
        synchronized(lock) {
            val line = "${fmt.format(Date())} $level $msg\n"
            try {
                writeBounded(privateFile(ctx), line)
            } catch (_: Throwable) {
            }
            try {
                if (AppStorage.hasPermission(ctx) && AppStorage.ensureDirs()) {
                    writeBounded(File(AppStorage.logsDir(), PUBLIC_FILE), line)
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun writeBounded(f: File, line: String) {
        f.parentFile?.mkdirs()
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (f.length() + bytes.size > MAX_BYTES) {
            val tail = if (f.isFile) readTail(f, (MAX_BYTES / 2).toInt()) else byteArrayOf()
            f.outputStream().use { out ->
                out.write(tail)
                val count = minOf(bytes.size, MAX_BYTES.toInt() - tail.size)
                out.write(bytes, bytes.size - count, count)
            }
        } else f.appendBytes(bytes)
    }

    private fun readTail(file: File, limit: Int): ByteArray =
        java.io.RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            val bytes = ByteArray(minOf(length, limit.toLong()).toInt())
            input.seek(length - bytes.size)
            input.readFully(bytes)
            bytes
        }

    fun readAll(ctx: Context): String {
        val body = synchronized(lock) {
            val f = privateFile(ctx)
            if (f.isFile) runCatching { String(readTail(f, MAX_BYTES.toInt()), Charsets.UTF_8) }.getOrDefault("") else ""
        }
        return "==== 豆包输入法无障碍 安装与修补日志 ====\n" +
            deviceSummary(ctx) + "\n" +
            "==== 记录 ====\n" +
            body.ifBlank { "（暂无记录）\n" }
    }

    fun copyToClipboard(ctx: Context): String {
        val full = readAll(ctx)
        val text = if (full.length > 6000) {
            val head = full.substringBefore("==== 记录 ====\n") + "==== 记录（最近部分） ====\n"
            val tail = full.takeLast((6000 - head.length).coerceAtLeast(1500)).substringAfter('\n', "")
            head + tail
        } else {
            full
        }
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("豆包安装修补日志", text))
        return "已复制安装修补日志（共 ${text.length} 字），可直接粘贴发给作者排查。"
    }
}
