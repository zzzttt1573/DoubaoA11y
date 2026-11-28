package io.github.doubaoa11y.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import io.github.doubaoa11y.ConfigSpec
import io.github.doubaoa11y.SettingsProvider
import io.github.doubaoa11y.SpeechEngine
import io.github.doubaoa11y.NativeRestoreFiles
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile

/**
 * 统一备份与恢复：将无障碍模块（设置、自定义解释词、导入音效）与豆包输入法原生设置
 * （键盘布局、双拼、键盘尺寸、定制工具栏与本地用户词库）打包到同一个 ZIP，
 * 默认保存在手机存储根目录“豆包输入法无障碍/备份”文件夹，恢复时直接列出该文件夹内可恢复的备份。
 */
object SettingsBackup {
    private const val IMPORT = 1202
    private val main = Handler(Looper.getMainLooper())
    private val dialogs = java.util.WeakHashMap<SettingsActivity, AlertDialog>()

    private data class Plan(
        val data: BackupFormat.Data? = null,
        val archive: ZipBackup.Contents? = null,
        val nativePreview: NativeBackupUi.Preview? = null,
        val standaloneNativeZip: File? = null,
        val warnings: List<String> = emptyList(),
    ) : Closeable {
        override fun close() {
            archive?.close()
            standaloneNativeZip?.delete()
        }
    }

    private data class BackupCandidate(
        val file: File,
        val kind: String,
    )

    fun detach(a: SettingsActivity) {
        dialogs.remove(a)?.dismiss()
        NativeBackupUi.detach(a)
    }

    fun fill(a: SettingsActivity, root: LinearLayout) {
        root.addView(
            a.note(
                "备份保存目录：${AppStorage.displayDir(AppStorage.DIR_BACKUP)}\n" +
                    "一个压缩包同时备份和恢复两个应用的设置：\n" +
                    "1. 无障碍模块：全部模块设置、自定义解释词与导入音效；\n" +
                    "2. 豆包输入法原生：键盘布局、双拼、候选字大小、键盘尺寸、定制工具栏、音效震动与本地用户词。"
            )
        )
        if (!AppStorage.hasPermission(a)) {
            root.addView(a.button("授予存储权限（启用本地文件夹导入导出）") {
                AppStorage.ensurePermission(a) { a.rebuild() }
            })
        }
        root.addView(a.button("导出备份") {
            AppStorage.ensurePermission(a) { promptExportName(a) }
        })
        root.addView(a.button("恢复备份") {
            AppStorage.ensurePermission(a) {
                a.startActivity(Intent(a, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PAGE, SettingsActivity.BACKUP_RESTORE))
            }
        })
    }

    fun fillRestoreList(a: SettingsActivity, root: LinearLayout) {
        root.addView(a.note("目录：${AppStorage.displayDir(AppStorage.DIR_BACKUP)}\n点击备份文件开始恢复，长按可删除。"))
        if (!AppStorage.hasPermission(a)) {
            root.addView(a.button("授予存储权限") {
                AppStorage.ensurePermission(a) { a.rebuild() }
            })
            root.addView(a.button("从其他位置选择备份文件") { pickExternal(a) })
            return
        }
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val loading = a.note("正在扫描可恢复的备份文件…")
        box.addView(loading)
        root.addView(box)
        root.addView(a.button("刷新列表") { a.rebuild() })
        root.addView(a.button("从其他位置选择备份文件") { pickExternal(a) })

        Thread({
            val items = scanBackupDir()
            main.post {
                if (a.isFinishing || a.isDestroyed) return@post
                box.removeAllViews()
                if (items.isEmpty()) {
                    box.addView(
                        a.note(
                            "“${AppStorage.displayDir(AppStorage.DIR_BACKUP)}”中暂无可用于恢复的备份文件。\n" +
                                "请先导出备份，或将 .zip 备份放入该文件夹后点击刷新列表。"
                        )
                    )
                    return@post
                }
                box.addView(TextView(a).apply {
                    text = "共 ${items.size} 个可恢复的备份"
                    isFocusable = true
                    setPadding(0, a.pad / 4, 0, a.pad / 4)
                })
                a.addList(
                    box,
                    items.map { item ->
                        "${item.file.name}\n${item.kind} · ${AppStorage.formatSize(item.file.length())} · ${AppStorage.formatTime(item.file.lastModified())}"
                    },
                    onClick = { idx -> startRestoreFromFile(a, items[idx].file) },
                    onLongClick = { idx ->
                        val f = items[idx].file
                        AlertDialog.Builder(a)
                            .setTitle("删除备份文件？")
                            .setMessage("确定要删除“${f.name}”吗？此操作不可撤销。")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("删除") { _, _ ->
                                if (f.delete()) {
                                    a.toast("已删除“${f.name}”")
                                    a.rebuild()
                                } else {
                                    a.toast("删除失败")
                                }
                            }
                            .show()
                        true
                    },
                )
            }
        }, "ScanBackups").start()
    }

    private fun scanBackupDir(): List<BackupCandidate> {
        AppStorage.ensureDirs()
        val files = AppStorage.backupDir().listFiles().orEmpty()
            .filter { it.isFile && it.extension.equals("zip", ignoreCase = true) && it.length() in 1L..ZipBackup.MAX_UNIFIED_TOTAL.toLong() }
            .sortedByDescending { it.lastModified() }
        val out = ArrayList<BackupCandidate>()
        for (f in files) {
            inspectCandidateKind(f)?.let { kind -> out.add(BackupCandidate(f, kind)) }
        }
        return out
    }

    /** 快速判断 ZIP 是否是本模块能恢复的有效备份，过滤掉无关或损坏的压缩包。 */
    private fun inspectCandidateKind(file: File): String? {
        return try {
            ZipFile(file).use { zip ->
                if (zip.getEntry("manifest.json") != null) {
                    NativeBackupUi.inspectNativeZip(file)
                    "豆包原生备份"
                } else {
                    require(zip.size() <= ZipBackup.MAX_ENTRIES)
                    when {
                        zip.getEntry("settings.json") != null && zip.getEntry(ZipBackup.NATIVE_ENTRY) != null ->
                            "完整备份（模块+豆包原生）"
                        zip.getEntry("settings.json") != null -> "无障碍模块备份"
                        zip.getEntry("settings.txt") != null -> "社区兼容备份"
                        else -> null
                    }
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun promptExportName(a: SettingsActivity) {
        val defaultName = "豆包无障碍备份_${AppStorage.timestamp()}"
        val input = EditText(a).apply {
            setSingleLine()
            setText(defaultName)
            hint = "输入备份名称（自动添加 .zip）"
            selectAll()
        }
        val d = AlertDialog.Builder(a)
            .setTitle("导出完整备份")
            .setMessage("将保存到：${AppStorage.displayDir(AppStorage.DIR_BACKUP)}")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("导出") { _, _ ->
                val target = AppStorage.uniqueFile(AppStorage.backupDir(), input.text.toString(), "zip")
                startUnifiedExport(a, target)
            }
            .create()
        d.setOnDismissListener { if (dialogs[a] === d) dialogs.remove(a) }
        dialogs[a] = d
        d.show()
    }

    private fun pickExternal(a: SettingsActivity) {
        try {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            a.startActivityForResult(i, IMPORT)
        } catch (_: Throwable) {
            a.toast("无法打开系统文件选择器")
        }
    }

    fun onResult(a: SettingsActivity, code: Int, result: Int, uri: Uri?): Boolean {
        if (NativeBackupUi.onResult(a, code, result, uri)) return true
        if (code != IMPORT) return false
        if (result != Activity.RESULT_OK || uri == null) return true
        val pending = AtomicReference<Plan?>()
        work(a, "正在检查备份", { pending.getAndSet(null)?.close() }) {
            val plan = readUri(a, uri)
            pending.set(plan)
            return@work { pending.set(null); confirm(a, plan) }
        }
        return true
    }

    private fun startRestoreFromFile(a: SettingsActivity, file: File) {
        val pending = AtomicReference<Plan?>()
        work(a, "正在检查备份", { pending.getAndSet(null)?.close() }) {
            val plan = readFile(a, file)
            pending.set(plan)
            return@work { pending.set(null); confirm(a, plan) }
        }
    }

    private fun collectModuleSettings(a: SettingsActivity): ByteArray {
        val values = LinkedHashMap<String, Any>()
        for (i in BackupFormat.exportableBooleans()) values[i.key] = a.prefs.getBoolean(i.key, i.default)
        for (i in ConfigSpec.intItems) values[i.key] = a.prefs.getInt(i.key, i.default)
        for (i in ConfigSpec.strItems) values[i.key] = a.prefs.getString(i.key, i.default) ?: i.default
        val words = a.prefs.getString(ConfigSpec.EXTRA_CUSTOM_WORDS, "").orEmpty()
        return BackupFormat.encode(values, words, true)
    }

    private fun startUnifiedExport(a: SettingsActivity, destFile: File) {
        val moduleBytes = try {
            collectModuleSettings(a)
        } catch (_: Throwable) {
            a.toast("无法读取当前设置，未导出")
            return
        }
        val token = NativeBackupUi.randomToken()
        val progress = showProgress(a, "正在导出完整备份", "正在打包无障碍模块设置与豆包输入法原生设置…")
        NativeBackupUi.sendNativeBroadcast(
            a = a,
            cmd = ConfigSpec.CMD_NATIVE_BACKUP,
            token = token,
            includeClipboard = false,
        ) { ok, nativeMsg ->
            if (!ok) {
                SettingsProvider.cleanupXfer(a, token)
                dismissProgress(a, progress)
                if (a.isFinishing || a.isDestroyed) return@sendNativeBroadcast
                val d = AlertDialog.Builder(a)
                    .setTitle("未能获取豆包原生设置")
                    .setMessage("$nativeMsg\n\n是否仅导出无障碍模块设置与音效到“${AppStorage.displayPath(destFile)}”？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("仅导出模块设置") { _, _ ->
                        writeBackupFile(
                            a, destFile, moduleBytes, null,
                            "已导出无障碍模块备份（未包含豆包原生设置）。"
                        )
                    }
                    .create()
                dialogs[a] = d
                d.show()
                return@sendNativeBroadcast
            }
            Thread({
                try {
                    val nativeSrc = SettingsProvider.xferFile(a, token)
                    check(nativeSrc != null && nativeSrc.isFile) { "未收到豆包导出的中转文件" }
                    destFile.parentFile?.mkdirs()
                    val published = AppStorage.publish(destFile) { output ->
                        ZipBackup.write(output, moduleBytes, File(a.filesDir, "sounds"), nativeSrc)
                        output.flush()
                    }
                    SettingsProvider.cleanupXfer(a, token)
                    main.post {
                        dismissProgress(a, progress)
                        if (!a.isFinishing && !a.isDestroyed) {
                            showAlert(
                                a, "导出成功",
                                "完整备份已保存（包含无障碍模块设置、音效与豆包原生设置）。\n保存位置：${AppStorage.displayPath(published)}\n$nativeMsg"
                            )
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
            }, "UnifiedBackupExport").start()
        }
    }

    private fun writeBackupFile(a: SettingsActivity, destFile: File, moduleBytes: ByteArray, nativeZip: File?, doneMsg: String) {
        work(a, "正在导出备份") {
            val published = AppStorage.publish(destFile) { output ->
                ZipBackup.write(output, moduleBytes, File(a.filesDir, "sounds"), nativeZip)
            }
            return@work { showAlert(a, "导出成功", "$doneMsg\n保存位置：${AppStorage.displayPath(published)}") }
        }
    }

    private fun readUri(a: SettingsActivity, uri: Uri): Plan {
        val tmp = File(a.cacheDir, "backup_in_${NativeBackupUi.randomToken()}.zip")
        try {
            a.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tmp).use { out ->
                    AppStorage.copyLimited(input, out, ZipBackup.MAX_UNIFIED_TOTAL.toLong())
                }
            } ?: error("无法读取文件")
            return parseTempZip(a, tmp, deleteOnStandalone = true)
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    private fun readFile(a: SettingsActivity, srcFile: File): Plan {
        require(srcFile.isFile && srcFile.length() in 1L..ZipBackup.MAX_UNIFIED_TOTAL.toLong()) { "备份文件无效或超过 96 MB" }
        val tmp = File(a.cacheDir, "backup_in_${NativeBackupUi.randomToken()}.zip")
        try {
            FileInputStream(srcFile).use { input ->
                FileOutputStream(tmp).use { out ->
                    AppStorage.copyLimited(input, out, ZipBackup.MAX_UNIFIED_TOTAL.toLong())
                }
            }
            return parseTempZip(a, tmp, deleteOnStandalone = true)
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    private fun parseTempZip(a: SettingsActivity, tmp: File, deleteOnStandalone: Boolean): Plan {
        val head = ByteArray(2)
        FileInputStream(tmp).use { check(it.read(head) == 2 && head[0] == 0x50.toByte() && head[1] == 0x4b.toByte()) { "请选择 ZIP 备份文件" } }

        val hasRootManifest = ZipFile(tmp).use { zip ->
            require(zip.size() <= NativeRestoreFiles.ENTRY_LIMIT) { "备份条目过多" }
            zip.getEntry("manifest.json") != null
        }
        if (hasRootManifest) {
            val nativePreview = NativeBackupUi.inspectNativeZip(tmp)
            return Plan(nativePreview = nativePreview, standaloneNativeZip = if (deleteOnStandalone) tmp else null)
        }

        val archive = FileInputStream(tmp).use { ZipBackup.read(it, a.cacheDir) }
        tmp.delete()
        try {
            val legacy = if (archive.legacy) LegacyBackupFormat.parse(archive.settings) else null
            val data = legacy?.data ?: BackupFormat.parse(archive.settings)
            for (file in archive.sounds.values) ZipBackup.validateAudio(file)
            val nativePreview = archive.nativeZip?.let { NativeBackupUi.inspectNativeZip(it) }
            return Plan(data, archive, nativePreview, null, legacy?.warnings.orEmpty())
        } catch (t: Throwable) {
            archive.close()
            throw t
        }
    }

    private fun confirm(a: SettingsActivity, plan: Plan) {
        val lines = ArrayList<String>()
        val data = plan.data
        if (data != null) {
            val audio = when {
                plan.archive == null -> "导入音频保持现状"
                plan.archive.legacy -> "覆盖社区备份内 ${plan.archive.sounds.size} 个音频槽位，其余保持现状"
                else -> "替换全部导入音频，共 ${plan.archive.sounds.size} 个；未包含的槽位恢复内置音频"
            }
            lines.add(
                "【无障碍模块】覆盖 ${data.settings.size} 项设置；自定义解释词${if (data.words == null) "保持现状" else "将被替换"}；$audio；日志和调试保持现状。"
            )
        }
        val np = plan.nativePreview
        if (np != null) {
            val currentVer = NativeBackupUi.readInstalledDoubaoVersion(a)
            val timeText = if (np.timestamp > 0L) {
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(np.timestamp))
            } else "未知"
            val verWarn = if (currentVer.isNotEmpty() && np.doubaoVersionName.isNotEmpty() && currentVer != np.doubaoVersionName) {
                "（来源豆包 ${np.doubaoVersionName}，当前 $currentVer，绝大多数设置仍可兼容恢复）"
            } else ""
            lines.add(
                "【豆包输入法原生】备份时间 $timeText，来源版本豆包 ${np.doubaoVersionName}$verWarn，共 ${np.fileCount} 个文件（约 ${np.totalBytes / 1024} KB）。将覆盖原生设置、定制工具栏与本地用户词库，确认后需强行停止一次豆包生效。"
            )
        }
        if (plan.warnings.isNotEmpty()) {
            val warnings = plan.warnings.take(24).joinToString("\n") +
                if (plan.warnings.size > 24) "\n另有 ${plan.warnings.size - 24} 项未导入" else ""
            lines.add(warnings)
        }
        lines.add("是否恢复？")

        var restoring = false
        val dialog = AlertDialog.Builder(a)
            .setTitle("恢复备份")
            .setMessage(lines.joinToString("\n\n"))
            .setNegativeButton("取消", null)
            .setPositiveButton("恢复") { _, _ ->
                restoring = true
                executeRestore(a, plan)
            }
            .create()
        dialog.setOnDismissListener {
            if (dialogs[a] === dialog) dialogs.remove(a)
            if (!restoring) plan.close()
        }
        dialogs[a] = dialog
        dialog.show()
    }

    private fun executeRestore(a: SettingsActivity, plan: Plan) {
        val progress = showProgress(a, "正在恢复备份", "正在写入设置并校验恢复包…")
        Thread({
            var moduleApplied = false
            var pendingToken: String? = null
            try {
                val data = plan.data
                if (data != null) {
                    val keys = data.settings.keys + listOfNotNull(ConfigSpec.EXTRA_CUSTOM_WORDS.takeIf { data.words != null })
                    var before: Map<String, Any?> = emptyMap()
                    BackupResourceTransaction.restore(
                        File(a.filesDir, "sounds"),
                        plan.archive?.sounds,
                        plan.archive != null && !plan.archive.legacy,
                        applySettings = {
                            val snapshot = a.prefs.all
                            before = keys.associateWith { snapshot[it] }
                            val e = a.prefs.edit()
                            for ((key, value) in data.settings) put(e, key, value)
                            data.words?.let { e.putString(ConfigSpec.EXTRA_CUSTOM_WORDS, it) }
                            check(e.commit()) { "保存设置失败" }
                        },
                        rollbackSettings = {
                            val e = a.prefs.edit()
                            for ((key, value) in before) put(e, key, value)
                            check(e.commit()) { "设置回滚失败" }
                        },
                        applyIcon = {},
                        rollbackIcon = {},
                    )
                    moduleApplied = true
                    main.post { a.send(null) }
                    SpeechEngine.stop()
                }

                val nativeZip = plan.nativePreview?.tempZip
                if (nativeZip == null) {
                    plan.close()
                    main.post {
                        dismissProgress(a, progress)
                        if (!a.isFinishing && !a.isDestroyed) {
                            a.rebuild()
                            a.toast("模块设置已恢复")
                        }
                    }
                    return@Thread
                }

                val token = NativeBackupUi.randomToken()
                pendingToken = token
                val xfer = SettingsProvider.xferFile(a, token) ?: error("无效中转路径")
                xfer.parentFile?.mkdirs()
                FileInputStream(nativeZip).use { input ->
                    FileOutputStream(xfer).use { out ->
                        input.copyTo(out, 64 * 1024)
                    }
                }
                plan.close()
                main.post {
                    if (data != null && !a.isFinishing && !a.isDestroyed) a.rebuild()
                    NativeBackupUi.sendNativeBroadcast(
                        a = a,
                        cmd = ConfigSpec.CMD_NATIVE_RESTORE,
                        token = token,
                        includeClipboard = false,
                    ) { ok, msg ->
                        SettingsProvider.cleanupXfer(a, token)
                        dismissProgress(a, progress)
                        if (a.isFinishing || a.isDestroyed) return@sendNativeBroadcast
                        if (ok) {
                            val prefix = if (data != null) "无障碍模块设置已恢复，豆包原生恢复包已就绪。\n" else ""
                            val d = AlertDialog.Builder(a)
                                .setTitle("恢复包已就绪，请重启豆包")
                                .setMessage("$prefix$msg\n\n请点击下方按钮打开豆包应用详情并点“强行停止”，然后回到任意输入框弹出豆包键盘即可自动完成豆包原生设置恢复。")
                                .setPositiveButton("去强行停止豆包输入法") { _, _ ->
                                    NativeBackupUi.openDoubaoAppDetails(a)
                                }
                                .setNegativeButton("稍后手动停止", null)
                                .create()
                            dialogs[a] = d
                            d.show()
                        } else {
                            val prefix = if (data != null) "无障碍模块设置已恢复，但豆包原生设置未能暂存：\n" else ""
                            showAlert(a, "部分恢复未完成", "$prefix$msg")
                        }
                    }
                }
            } catch (t: Throwable) {
                pendingToken?.let { SettingsProvider.cleanupXfer(a, it) }
                plan.close()
                main.post {
                    dismissProgress(a, progress)
                    if (!a.isFinishing && !a.isDestroyed) {
                        showAlert(a, if (moduleApplied) "模块已恢复，原生恢复未完成" else "恢复失败", t.message ?: "无法处理这个文件")
                    }
                }
            }
        }, "UnifiedBackupRestore").start()
    }

    private fun put(e: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            null -> e.remove(key)
            is Boolean -> e.putBoolean(key, value)
            is Int -> e.putInt(key, value)
            is Long -> e.putLong(key, value)
            is Float -> e.putFloat(key, value)
            is String -> e.putString(key, value)
            is Set<*> -> { @Suppress("UNCHECKED_CAST") e.putStringSet(key, value as Set<String>) }
        }
    }

    private fun showProgress(a: SettingsActivity, title: String, msg: String): AlertDialog {
        dialogs.remove(a)?.dismiss()
        return AlertDialog.Builder(a).setTitle(title).setMessage(msg).setCancelable(false).create().apply {
            dialogs[a] = this
            show()
        }
    }

    private fun dismissProgress(a: SettingsActivity, d: AlertDialog) {
        runCatching { d.dismiss() }
        if (dialogs[a] === d) dialogs.remove(a)
    }

    private fun showAlert(a: SettingsActivity, title: String, msg: String) {
        dialogs.remove(a)?.dismiss()
        val d = AlertDialog.Builder(a).setTitle(title).setMessage(msg).setPositiveButton("确定", null).create()
        dialogs[a] = d
        d.show()
    }

    private fun work(a: SettingsActivity, title: String, abandoned: () -> Unit = {}, action: () -> (() -> Unit)) {
        val progress = showProgress(a, title, "请稍候…")
        Thread({
            val outcome = runCatching(action)
            main.post {
                dismissProgress(a, progress)
                if (a.isFinishing || a.isDestroyed) { abandoned(); return@post }
                outcome.fold({ it() }, { abandoned(); a.toast("操作失败：${it.message ?: "无法处理这个文件"}") })
            }
        }, "SettingsBackup").start()
    }
}
