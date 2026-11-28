package io.github.doubaoa11y.ui

import io.github.doubaoa11y.NativeRestoreFiles

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.LinearLayout
import io.github.doubaoa11y.ConfigSpec
import io.github.doubaoa11y.SettingsProvider
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap
import java.util.zip.ZipFile

/**
 * 豆包输入法原生设置离线备份/恢复界面（不依赖豆包账号云同步）。
 */
object NativeBackupUi {
    private const val REQ_EXPORT_NATIVE = 1211
    private const val REQ_IMPORT_NATIVE = 1212
    private const val MAX_PREVIEW_BYTES = 64L * 1024 * 1024
    private val main = Handler(Looper.getMainLooper())
    private val dialogs = WeakHashMap<SettingsActivity, AlertDialog>()

    internal data class Preview(
        val tempZip: File,
        val doubaoVersionName: String,
        val doubaoVersionCode: Long,
        val timestamp: Long,
        val fileCount: Int,
        val totalBytes: Long
    )

    internal fun inspectNativeZip(zipFile: File): Preview {
        require(zipFile.length() in 1L..MAX_PREVIEW_BYTES) { "原生备份大小无效" }
        val raw = ZipFile(zipFile).use { zip ->
            require(zip.size() <= NativeRestoreFiles.ENTRY_LIMIT) { "原生备份条目过多" }
            val entry = zip.getEntry("manifest.json") ?: error("原生备份缺少 manifest.json")
            zip.getInputStream(entry).use { String(NativeRestoreFiles.readManifest(it), Charsets.UTF_8) }
        }
        val j = JSONObject(raw)
        require(j.optInt("formatVersion", -1) == 1) { "不支持的豆包原生备份格式版本" }
        require(j.optString("packageName") == ConfigSpec.TARGET_PACKAGE) { "该备份不是豆包输入法原生备份" }
        val fileCount = j.getJSONArray("files").length()
        require(fileCount in 1..NativeRestoreFiles.FILE_LIMIT && j.optInt("fileCount", fileCount) == fileCount) {
            "豆包原生备份清单数量无效"
        }
        return Preview(
            tempZip = zipFile,
            doubaoVersionName = j.optString("doubaoVersionName", "未知"),
            doubaoVersionCode = j.optLong("doubaoVersionCode", 0L),
            timestamp = j.optLong("timestamp", 0L),
            fileCount = fileCount,
            totalBytes = j.optLong("totalBytes", zipFile.length())
        )
    }

    fun detach(a: SettingsActivity) {
        dialogs.remove(a)?.dismiss()
    }

    fun fill(a: SettingsActivity, root: LinearLayout) {
        root.addView(a.heading("豆包原生设置离线备份（免云同步）"))
        root.addView(
            a.note(
                "直接备份豆包输入法自带的全部设置（键盘布局、双拼、候选字大小、键盘尺寸、定制工具栏、音效震动、语音设置及本地用户词库），" +
                    "不包含账号凭证、风控数据与系统缓存。卸载重装或换机后可直接离线恢复。"
            )
        )
        root.addView(a.button("导出豆包原生设置（ZIP）") { pick(a, REQ_EXPORT_NATIVE) })
        root.addView(a.button("恢复豆包原生设置（ZIP）") { pick(a, REQ_IMPORT_NATIVE) })
        root.addView(a.button("查看上次原生恢复结果") { queryRestoreStatus(a) })
    }

    fun onResult(a: SettingsActivity, code: Int, result: Int, uri: Uri?): Boolean {
        if (code != REQ_EXPORT_NATIVE && code != REQ_IMPORT_NATIVE) return false
        if (result != Activity.RESULT_OK || uri == null) return true
        if (code == REQ_EXPORT_NATIVE) {
            startExport(a, uri)
        } else {
            startImportPreview(a, uri)
        }
        return true
    }

    private fun pick(a: SettingsActivity, code: Int) {
        try {
            val isExport = code == REQ_EXPORT_NATIVE
            val i = Intent(if (isExport) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(if (isExport) "application/zip" else "*/*")
            if (isExport) {
                i.putExtra(Intent.EXTRA_TITLE, "DoubaoNative-backup.zip")
            } else {
                i.putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
                )
            }
            a.startActivityForResult(i, code)
        } catch (_: Throwable) {
            a.toast("无法打开系统文件选择器")
        }
    }

    private fun startExport(a: SettingsActivity, destUri: Uri) {
        val token = randomToken()
        val progress = showProgress(a, "正在导出豆包原生设置", "正在从豆包输入法打包配置与用户词库…")
        sendNativeBroadcast(
            a = a,
            cmd = ConfigSpec.CMD_NATIVE_BACKUP,
            token = token,
            includeClipboard = false
        ) { ok, msg ->
            if (!ok) {
                SettingsProvider.cleanupXfer(a, token)
                dismissProgress(a, progress)
                showAlert(a, "导出未完成", msg)
                return@sendNativeBroadcast
            }
            Thread({
                try {
                    val src = SettingsProvider.xferFile(a, token)
                    check(src != null && src.isFile) { "未收到豆包导出的中转文件" }
                    a.contentResolver.openOutputStream(destUri, "wt")?.use { out ->
                        FileInputStream(src).use { it.copyTo(out, 64 * 1024) }
                        out.flush()
                    } ?: error("无法写入所选文件")
                    SettingsProvider.cleanupXfer(a, token)
                    main.post {
                        dismissProgress(a, progress)
                        if (!a.isFinishing && !a.isDestroyed) {
                            showAlert(a, "导出成功", "豆包原生设置备份已保存。\n$msg")
                        }
                    }
                } catch (t: Throwable) {
                    SettingsProvider.cleanupXfer(a, token)
                    main.post {
                        dismissProgress(a, progress)
                        if (!a.isFinishing && !a.isDestroyed) {
                            showAlert(a, "导出失败", t.message ?: "无法保存备份文件")
                        }
                    }
                }
            }, "NativeBackupExportCopy").start()
        }
    }

    private fun startImportPreview(a: SettingsActivity, srcUri: Uri) {
        val progress = showProgress(a, "正在检查豆包原生备份", "正在读取并校验备份清单…")
        Thread({
            val tmp = File(a.cacheDir, "native_preview_${randomToken()}.zip")
            try {
                var copied = 0L
                a.contentResolver.openInputStream(srcUri)?.use { input ->
                    FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            copied += n
                            require(copied <= MAX_PREVIEW_BYTES) { "备份文件超过 64 MB" }
                            out.write(buf, 0, n)
                        }
                    }
                } ?: error("无法读取所选文件")

                val preview = inspectNativeZip(tmp)
                main.post {
                    dismissProgress(a, progress)
                    if (a.isFinishing || a.isDestroyed) {
                        tmp.delete()
                        return@post
                    }
                    confirmRestore(a, preview)
                }
            } catch (t: Throwable) {
                tmp.delete()
                main.post {
                    dismissProgress(a, progress)
                    if (!a.isFinishing && !a.isDestroyed) {
                        showAlert(a, "无法恢复备份", t.message ?: "备份文件无效")
                    }
                }
            }
        }, "NativeBackupPreview").start()
    }

    private fun confirmRestore(a: SettingsActivity, preview: Preview) {
        val currentVer = readInstalledDoubaoVersion(a)
        val timeText = if (preview.timestamp > 0L) {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(preview.timestamp))
        } else "未知"
        val verWarn = if (currentVer.isNotEmpty() && preview.doubaoVersionName.isNotEmpty() &&
            currentVer != preview.doubaoVersionName
        ) {
            "\n注意：备份来自豆包 ${preview.doubaoVersionName}，当前安装的是 $currentVer，绝大多数设置仍可兼容恢复。"
        } else ""

        var proceeding = false
        val dialog = AlertDialog.Builder(a)
            .setTitle("确认恢复豆包原生设置")
            .setMessage(
                "备份时间：$timeText\n" +
                    "来源版本：豆包 ${preview.doubaoVersionName}\n" +
                    "包含文件：${preview.fileCount} 个（约 ${preview.totalBytes / 1024} KB）$verWarn\n\n" +
                    "恢复将覆盖豆包输入法当前的原生设置、定制工具栏与本地用户词库，不会影响模块自身设置。\n" +
                    "确认后将先校验并暂存，随后需要强行停止一次豆包输入法才能生效。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("开始恢复") { _, _ ->
                proceeding = true
                executeStageRestore(a, preview)
            }
            .create()
        dialog.setOnDismissListener {
            if (dialogs[a] === dialog) dialogs.remove(a)
            if (!proceeding) preview.tempZip.delete()
        }
        dialogs[a] = dialog
        dialog.show()
    }

    private fun executeStageRestore(a: SettingsActivity, preview: Preview) {
        val token = randomToken()
        val progress = showProgress(a, "正在恢复豆包原生设置", "正在传输并校验恢复包…")
        Thread({
            try {
                val xfer = SettingsProvider.xferFile(a, token) ?: error("无效中转路径")
                xfer.parentFile?.mkdirs()
                FileInputStream(preview.tempZip).use { input ->
                    FileOutputStream(xfer).use { out ->
                        input.copyTo(out, 64 * 1024)
                    }
                }
                preview.tempZip.delete()
                main.post {
                    if (a.isFinishing || a.isDestroyed) {
                        SettingsProvider.cleanupXfer(a, token)
                        dismissProgress(a, progress)
                        return@post
                    }
                    sendNativeBroadcast(
                        a = a,
                        cmd = ConfigSpec.CMD_NATIVE_RESTORE,
                        token = token,
                        includeClipboard = false
                    ) { ok, msg ->
                        SettingsProvider.cleanupXfer(a, token)
                        dismissProgress(a, progress)
                        if (a.isFinishing || a.isDestroyed) return@sendNativeBroadcast
                        if (ok) {
                            val d = AlertDialog.Builder(a)
                                .setTitle("恢复包已就绪，请重启豆包")
                                .setMessage("$msg\n\n请点击下方按钮打开豆包应用详情并点“强行停止”，然后回到任意输入框弹出豆包键盘即可自动完成恢复。")
                                .setPositiveButton("去强行停止豆包输入法") { _, _ ->
                                    openDoubaoAppDetails(a)
                                }
                                .setNegativeButton("稍后手动停止", null)
                                .create()
                            dialogs[a] = d
                            d.show()
                        } else {
                            showAlert(a, "恢复失败", msg)
                        }
                    }
                }
            } catch (t: Throwable) {
                preview.tempZip.delete()
                SettingsProvider.cleanupXfer(a, token)
                main.post {
                    dismissProgress(a, progress)
                    if (!a.isFinishing && !a.isDestroyed) {
                        showAlert(a, "恢复失败", t.message ?: "准备中转文件失败")
                    }
                }
            }
        }, "NativeBackupStageCopy").start()
    }

    internal fun queryRestoreStatus(a: SettingsActivity) {
        val progress = showProgress(a, "正在查询", "正在向豆包输入法查询上次恢复状态…")
        sendNativeBroadcast(
            a = a,
            cmd = ConfigSpec.CMD_NATIVE_RESTORE_STATUS,
            token = "",
            includeClipboard = false
        ) { ok, msg ->
            dismissProgress(a, progress)
            if (!a.isFinishing && !a.isDestroyed) {
                showAlert(a, if (ok) "豆包原生恢复状态" else "查询失败", msg)
            }
        }
    }

    internal fun sendNativeBroadcast(
        a: SettingsActivity,
        cmd: String,
        token: String,
        includeClipboard: Boolean,
        onDone: (ok: Boolean, msg: String) -> Unit
    ) {
        val xferUri = if (token.isNotEmpty()) SettingsProvider.xferUri(token) else null
        val grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (xferUri != null) {
            runCatching { a.grantUriPermission(ConfigSpec.TARGET_PACKAGE, xferUri, grantFlags) }
        }
        val intent = Intent(ConfigSpec.ACTION_CONFIG).apply {
            setPackage(ConfigSpec.TARGET_PACKAGE)
            putExtra(ConfigSpec.EXTRA_CMD, cmd)
            if (token.isNotEmpty()) putExtra(ConfigSpec.EXTRA_NATIVE_TOKEN, token)
            if (xferUri != null) {
                putExtra(ConfigSpec.EXTRA_NATIVE_URI, xferUri.toString())
                clipData = android.content.ClipData.newRawUri("native_xfer", xferUri)
                addFlags(grantFlags)
            }
            putExtra(ConfigSpec.EXTRA_NATIVE_INCLUDE_CLIPBOARD, includeClipboard)
        }
        var finished = false
        val revoke = {
            if (xferUri != null) runCatching { a.revokeUriPermission(xferUri, grantFlags) }
        }
        val timeout = Runnable {
            if (!finished) {
                finished = true
                revoke()
                onDone(false, "豆包输入法超时未回应。请先在任意输入框弹出一次豆包键盘，再重试。")
            }
        }
        main.postDelayed(timeout, 25_000L)
        try {
            a.applicationContext.sendOrderedBroadcast(
                intent,
                null,
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, resIntent: Intent) {
                        if (finished) return
                        finished = true
                        main.removeCallbacks(timeout)
                        revoke()
                        if (resultCode != Activity.RESULT_OK && resultData.isNullOrEmpty()) {
                            onDone(false, "豆包输入法没有回应。请先弹出一次豆包键盘，确保无障碍模块已生效后重试。")
                            return
                        }
                        val extras: Bundle? = getResultExtras(false)
                        val nativeOk = if (extras != null && extras.containsKey("nativeOk")) {
                            extras.getBoolean("nativeOk", false)
                        } else {
                            resultCode == Activity.RESULT_OK
                        }
                        val data = resultData?.takeIf { it.isNotBlank() }
                            ?: if (nativeOk) "操作已完成" else "豆包进程处理失败"
                        onDone(nativeOk, data)
                    }
                },
                main,
                Activity.RESULT_CANCELED,
                null,
                null
            )
        } catch (t: Throwable) {
            if (!finished) {
                finished = true
                main.removeCallbacks(timeout)
                revoke()
                if (token.isNotEmpty()) SettingsProvider.cleanupXfer(a, token)
                onDone(false, "发送请求给豆包失败：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    internal fun openDoubaoAppDetails(a: SettingsActivity) {
        try {
            a.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ConfigSpec.TARGET_PACKAGE}"))
            )
        } catch (_: Throwable) {
            a.toast("无法打开豆包应用详情，请手动强行停止豆包输入法")
        }
    }

    internal fun readInstalledDoubaoVersion(a: SettingsActivity): String {
        return try {
            val pi = a.packageManager.getPackageInfo(ConfigSpec.TARGET_PACKAGE, 0)
            pi.versionName ?: ""
        } catch (_: Throwable) {
            ""
        }
    }

    internal fun randomToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        val sb = StringBuilder(32)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }

    private fun showProgress(a: SettingsActivity, title: String, msg: String): AlertDialog {
        dialogs.remove(a)?.dismiss()
        return AlertDialog.Builder(a)
            .setTitle(title)
            .setMessage(msg)
            .setCancelable(false)
            .create()
            .also {
                dialogs[a] = it
                it.show()
            }
    }

    private fun dismissProgress(a: SettingsActivity, d: AlertDialog) {
        runCatching { d.dismiss() }
        if (dialogs[a] === d) dialogs.remove(a)
    }

    private fun showAlert(a: SettingsActivity, title: String, msg: String) {
        dialogs.remove(a)?.dismiss()
        val d = AlertDialog.Builder(a)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("确定", null)
            .create()
        dialogs[a] = d
        d.show()
    }
}
