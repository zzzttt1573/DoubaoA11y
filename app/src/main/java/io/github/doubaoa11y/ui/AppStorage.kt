package io.github.doubaoa11y.ui

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap

/**
 * 手机存储根目录“豆包输入法无障碍”及子文件夹（备份、解释词库、音效）管理与权限申请。
 */
object AppStorage {
    const val ROOT_NAME = "豆包输入法无障碍"
    const val DIR_BACKUP = "备份"
    const val DIR_WORDS = "解释词库"
    const val DIR_SOUNDS = "音效"
    const val DIR_LOGS = "日志"
    const val DIR_APKS = "安装包"

    private const val REQ_LEGACY_STORAGE = 1901
    private const val REQ_ALL_FILES = 1902

    private val pendingActions = WeakHashMap<SettingsActivity, () -> Unit>()
    private val dialogs = WeakHashMap<SettingsActivity, AlertDialog>()

    fun rootDir(): File = File(Environment.getExternalStorageDirectory(), ROOT_NAME)
    fun backupDir(): File = File(rootDir(), DIR_BACKUP)
    fun wordsDir(): File = File(rootDir(), DIR_WORDS)
    fun soundsDir(): File = File(rootDir(), DIR_SOUNDS)
    fun logsDir(): File = File(rootDir(), DIR_LOGS)
    fun apksDir(): File = File(rootDir(), DIR_APKS)

    fun displayDir(subDirName: String): String = "内部存储/$ROOT_NAME/$subDirName"
    fun displayPath(file: File): String {
        val parent = file.parentFile?.name.orEmpty()
        return if (parent.isNotEmpty()) "内部存储/$ROOT_NAME/$parent/${file.name}" else "内部存储/$ROOT_NAME/${file.name}"
    }

    fun hasPermission(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun ensureDirs(): Boolean {
        return runCatching {
            val dirs = listOf(rootDir(), backupDir(), wordsDir(), soundsDir(), logsDir())
            dirs.all { it.isDirectory || it.mkdirs() }
        }.getOrDefault(false)
    }

    fun ensureDirsIfPermitted(ctx: Context) {
        if (hasPermission(ctx)) ensureDirs()
    }

    fun detach(a: SettingsActivity) {
        pendingActions.remove(a)
        dialogs.remove(a)?.dismiss()
    }

    /**
     * 确保已拿到外部存储权限并建好目录；未授权时引导授权，授权返回后自动继续执行 onGranted。
     */
    fun ensurePermission(a: SettingsActivity, onGranted: () -> Unit) {
        if (hasPermission(a)) {
            ensureDirs()
            onGranted()
            return
        }
        pendingActions[a] = onGranted
        dialogs.remove(a)?.dismiss()
        val msg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            "模块需要在手机存储根目录创建“$ROOT_NAME”文件夹，并在内部“$DIR_BACKUP”“$DIR_WORDS”“$DIR_SOUNDS”“$DIR_LOGS”子文件夹中保存和读取文件。\n\n请在下一页打开“允许管理所有文件”开关，完成后返回即可自动继续。"
        } else {
            "模块需要在手机存储根目录创建“$ROOT_NAME”文件夹，并在内部“$DIR_BACKUP”“$DIR_WORDS”“$DIR_SOUNDS”“$DIR_LOGS”子文件夹中保存和读取文件。\n\n请授予存储读写权限后继续。"
        }
        val d = AlertDialog.Builder(a)
            .setTitle("需要存储访问权限")
            .setMessage(msg)
            .setNegativeButton("取消") { _, _ -> pendingActions.remove(a) }
            .setPositiveButton("去授权") { _, _ -> requestPermission(a) }
            .create()
        d.setOnDismissListener { if (dialogs[a] === d) dialogs.remove(a) }
        dialogs[a] = d
        d.show()
    }

    private fun requestPermission(a: SettingsActivity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${a.packageName}"))
                a.startActivityForResult(i, REQ_ALL_FILES)
            } catch (_: Throwable) {
                try {
                    a.startActivityForResult(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), REQ_ALL_FILES)
                } catch (_: Throwable) {
                    pendingActions.remove(a)
                    a.toast("无法打开文件访问权限设置页，请手动在系统设置中授权")
                }
            }
        } else {
            a.requestPermissions(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQ_LEGACY_STORAGE,
            )
        }
    }

    fun onActivityResult(a: SettingsActivity, requestCode: Int): Boolean {
        if (requestCode != REQ_ALL_FILES) return false
        finishPending(a)
        return true
    }

    fun onPermissionResult(a: SettingsActivity, requestCode: Int): Boolean {
        if (requestCode != REQ_LEGACY_STORAGE) return false
        finishPending(a)
        return true
    }

    fun onResume(a: SettingsActivity) {
        if (hasPermission(a)) {
            ensureDirs()
            pendingActions.remove(a)?.invoke()
        }
    }

    private fun finishPending(a: SettingsActivity) {
        val action = pendingActions.remove(a)
        if (hasPermission(a)) {
            ensureDirs()
            if (action != null) action()
            else a.rebuild()
        } else {
            a.toast("未授予存储权限，无法读写“$ROOT_NAME”文件夹")
        }
    }

    fun timestamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

    fun sanitizeName(raw: String, fallback: String): String {
        val cleaned = raw.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim('.')
            .take(60)
        return cleaned.ifBlank { fallback }
    }

    fun uniqueFile(dir: File, baseName: String, ext: String): File {
        dir.mkdirs()
        val dotExt = if (ext.isBlank()) "" else if (ext.startsWith('.')) ext else ".$ext"
        val cleanBase = sanitizeName(baseName.removeSuffix(dotExt), "file_${timestamp()}")
        var candidate = File(dir, "$cleanBase$dotExt")
        var n = 2
        while (candidate.exists()) {
            candidate = File(dir, "${cleanBase}_$n$dotExt")
            n++
        }
        return candidate
    }

    /** 同目录完成写入后独占目标名，其他导出只能选择下一个后缀。 */
    fun publish(target: File, write: (java.io.OutputStream) -> Unit): File {
        val dir = requireNotNull(target.parentFile)
        check(dir.isDirectory || dir.mkdirs() || dir.isDirectory) { "无法创建导出目录" }
        val tmp = File.createTempFile(".export-", ".part", dir)
        try {
            tmp.outputStream().use(write)
            var candidate = target
            var suffix = 2
            while (true) {
                // 用同目录隐藏文件预订名字；进程中断也不会露出空的最终文件。
                val reservation = File(dir, "." + candidate.name + ".publishing")
                if (!candidate.exists() && reservation.createNewFile()) {
                    try {
                        // 同目录移动，保留默认的“不覆盖目标”语义。
                        java.nio.file.Files.move(tmp.toPath(), candidate.toPath())
                        return candidate
                    } catch (_: java.nio.file.FileAlreadyExistsException) {
                        // 等待写入时外部程序占用了名字，继续尝试下一个。
                    } finally {
                        reservation.delete()
                    }
                }
                candidate = File(dir, target.nameWithoutExtension + "_" + suffix++ + "." + target.extension)
            }
        } finally {
            tmp.delete()
        }
    }

    fun copyLimited(input: java.io.InputStream, output: java.io.OutputStream, limit: Long) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "文件超过大小限制（$limit 字节）" }
            output.write(buffer, 0, count)
        }
    }

    fun formatSize(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "${(bytes + 512L) / 1024L} KB"
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    fun formatTime(ms: Long): String =
        if (ms > 0L) SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms)) else "未知时间"
}
