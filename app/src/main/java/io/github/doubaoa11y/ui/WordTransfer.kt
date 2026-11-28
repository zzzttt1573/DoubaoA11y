package io.github.doubaoa11y.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import io.github.doubaoa11y.hook.CharWords
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** 只交换原格式解释词，不改变内置词库，也不自动读取任何读屏词库。 */
object WordTransfer {
    private const val IMPORT = 1301
    private val main = Handler(Looper.getMainLooper())
    private val dialogs = java.util.WeakHashMap<SettingsActivity, AlertDialog>()

    private data class WordCandidate(
        val file: File,
        val words: Map<String, String>,
    )

    fun detach(a: SettingsActivity) { dialogs.remove(a)?.dismiss() }

    fun fill(a: SettingsActivity, root: LinearLayout) {
        root.addView(a.note("词库保存目录：${AppStorage.displayDir(AppStorage.DIR_WORDS)}"))
        root.addView(a.button("导入解释词") {
            AppStorage.ensurePermission(a) {
                a.startActivity(Intent(a, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PAGE, SettingsActivity.WORDS_IMPORT))
            }
        })
        root.addView(a.button("导出解释词") {
            AlertDialog.Builder(a).setTitle("导出解释词")
                .setItems(arrayOf("自定义解释词", "全部解释词（内置和自定义）")) { _, i ->
                    val exportAll = i == 1
                    AppStorage.ensurePermission(a) { promptExportName(a, exportAll) }
                }.setNegativeButton("取消", null).show()
        })
    }

    fun fillImportList(a: SettingsActivity, root: LinearLayout) {
        root.addView(a.note("目录：${AppStorage.displayDir(AppStorage.DIR_WORDS)}\n点击词库文件导入，长按可删除文件。"))
        if (!AppStorage.hasPermission(a)) {
            root.addView(a.button("授予存储权限") {
                AppStorage.ensurePermission(a) { a.rebuild() }
            })
            root.addView(a.button("从其他位置选择词库文件") { pickExternal(a) })
            return
        }
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        box.addView(a.note("正在扫描可导入的解释词库…"))
        root.addView(box)
        root.addView(a.button("刷新列表") { a.rebuild() })
        root.addView(a.button("从其他位置选择词库文件") { pickExternal(a) })

        Thread({
            val items = scanWordsDir()
            main.post {
                if (a.isFinishing || a.isDestroyed) return@post
                box.removeAllViews()
                if (items.isEmpty()) {
                    box.addView(
                        a.note(
                            "“${AppStorage.displayDir(AppStorage.DIR_WORDS)}”中暂无可导入的解释词库。\n" +
                                "请先导出解释词，或将 .tsv / .txt 词库文件放入该文件夹后点击刷新列表。"
                        )
                    )
                    return@post
                }
                box.addView(TextView(a).apply {
                    text = "共 ${items.size} 个可导入的词库文件"
                    isFocusable = true
                    setPadding(0, a.pad / 4, 0, a.pad / 4)
                })
                a.addList(
                    box,
                    items.map { item ->
                        "${item.file.name}\n${item.words.size} 个词条 · ${AppStorage.formatSize(item.file.length())} · ${AppStorage.formatTime(item.file.lastModified())}"
                    },
                    onClick = { idx -> confirm(a, items[idx].words) },
                    onLongClick = { idx ->
                        val f = items[idx].file
                        AlertDialog.Builder(a)
                            .setTitle("删除词库文件？")
                            .setMessage("确定要删除“${f.name}”吗？不会影响当前已生效的解释词。")
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
        }, "ScanWords").start()
    }

    private fun scanWordsDir(): List<WordCandidate> {
        AppStorage.ensureDirs()
        val exts = setOf("tsv", "txt")
        val files = AppStorage.wordsDir().listFiles().orEmpty()
            .filter { it.isFile && it.extension.lowercase() in exts && it.length() in 1L..2L * 1024 * 1024 }
            .sortedByDescending { it.lastModified() }
        val out = ArrayList<WordCandidate>()
        for (f in files) {
            runCatching {
                FileInputStream(f).use { WordTransferFormat.read(it) }
            }.getOrNull()?.let { words ->
                out.add(WordCandidate(f, words))
            }
        }
        return out
    }

    private fun promptExportName(a: SettingsActivity, exportAll: Boolean) {
        val prefix = if (exportAll) "全部解释词" else "自定义解释词"
        val defaultName = "${prefix}_${AppStorage.timestamp()}"
        val input = EditText(a).apply {
            setSingleLine()
            setText(defaultName)
            hint = "输入词库文件名（自动添加 .tsv）"
            selectAll()
        }
        val d = AlertDialog.Builder(a)
            .setTitle("导出$prefix")
            .setMessage("将保存到：${AppStorage.displayDir(AppStorage.DIR_WORDS)}")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("导出") { _, _ ->
                val target = AppStorage.uniqueFile(AppStorage.wordsDir(), input.text.toString(), "tsv")
                exportToFile(a, target, exportAll)
            }
            .create()
        d.setOnDismissListener { if (dialogs[a] === d) dialogs.remove(a) }
        dialogs[a] = d
        d.show()
    }

    private fun exportToFile(a: SettingsActivity, target: File, exportAll: Boolean) {
        val custom = current(a)
        work(a, "正在导出解释词") {
            val words = LinkedHashMap<String, String>()
            if (exportAll) {
                for ((ch, value) in CharWords.builtIn()) words[ch.toString()] = value
            }
            words.putAll(custom)
            val bytes = WordTransferFormat.encode(words)
            target.parentFile?.mkdirs()
            val published = AppStorage.publish(target) { it.write(bytes) }
            return@work {
                AlertDialog.Builder(a)
                    .setTitle("导出成功")
                    .setMessage("已导出 ${words.size} 个解释词。\n保存位置：${AppStorage.displayPath(published)}")
                    .setPositiveButton("确定", null)
                    .show()
            }
        }
    }

    private fun pickExternal(a: SettingsActivity) {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "text/tab-separated-values", "application/octet-stream"))
            a.startActivityForResult(intent, IMPORT)
        } catch (_: Throwable) {
            a.toast("无法打开系统文件选择器")
        }
    }

    fun onResult(a: SettingsActivity, code: Int, result: Int, uri: Uri?): Boolean {
        if (code != IMPORT) return false
        if (result != Activity.RESULT_OK || uri == null) return true
        work(a, "正在检查解释词") {
            val imported = a.contentResolver.openInputStream(uri)?.use { WordTransferFormat.read(it) } ?: error("无法读取文件")
            return@work { confirm(a, imported) }
        }
        return true
    }

    /** 与原 WordsPage.load 完全相同地读取已有自定义；导出及合并结果仍需严格校验。 */
    private fun current(a: SettingsActivity): Map<String, String> {
        val words = LinkedHashMap<String, String>()
        for (raw in a.prefs.getString(SettingsActivity.PREF_WORDS, "").orEmpty().split('\n')) {
            val line = raw.removeSuffix("\r")
            val tab = line.indexOf('\t')
            if (tab in 1..2 && tab < line.lastIndex) {
                val key = line.substring(0, tab)
                if (WordTransferFormat.isSingleHan(key)) words[key] = line.substring(tab + 1)
            }
        }
        return words
    }

    private fun confirm(a: SettingsActivity, imported: Map<String, String>) {
        val before = current(a)
        val covered = imported.keys.count { it in before }
        val removed = before.keys.count { it !in imported }
        val message = "已检查 ${imported.size} 个解释词，其中 $covered 个字已自定义。\n" +
            "合并：保留现有自定义，导入覆盖同字。\n" +
            "替换自定义：移除现有但未导入的 $removed 个字，只保留本次导入。\n内置词库始终保留。" +
            if (imported.isEmpty()) "\n文件没有词条；选择替换将清空自定义解释词。" else ""
        val dialog = AlertDialog.Builder(a).setTitle("导入解释词").setMessage(message)
            .setNegativeButton("取消", null)
            .setNeutralButton("替换自定义") { _, _ -> restore(a, before, imported, true) }
            .setPositiveButton("合并（覆盖同字）") { _, _ -> restore(a, before, imported, false) }.create()
        dialogs[a] = dialog
        dialog.setOnDismissListener { if (dialogs[a] === dialog) dialogs.remove(a) }
        dialog.show()
    }

    private fun restore(a: SettingsActivity, before: Map<String, String>, imported: Map<String, String>, replace: Boolean) {
        try {
            check(current(a) == before) { "自定义解释词已变化，请重新导入并确认" }
            val change = WordTransferFormat.plan(before, imported, replace)
            val hadKey = a.prefs.contains(SettingsActivity.PREF_WORDS)
            val old = a.prefs.getString(SettingsActivity.PREF_WORDS, "")
            try {
                check(a.prefs.edit().putString(SettingsActivity.PREF_WORDS, change.text).commit()) { "保存失败" }
            } catch (failure: Throwable) {
                try {
                    val rollback = a.prefs.edit()
                    if (!hadKey) rollback.remove(SettingsActivity.PREF_WORDS) else rollback.putString(SettingsActivity.PREF_WORDS, old)
                    check(rollback.commit()) { "保存失败且回滚未完成，请检查自定义解释词" }
                } catch (rollback: Throwable) {
                    failure.addSuppressed(rollback)
                    throw IllegalStateException("保存失败且回滚未完成，请检查自定义解释词", failure)
                }
                error("保存失败，已保留原自定义解释词")
            }
            a.send(null)
            a.toast("已导入 ${imported.size} 个，覆盖 ${change.overwritten} 个；现有 ${change.total} 个自定义解释词")
            if (a.currentPage == SettingsActivity.WORDS_IMPORT) a.finish() else a.rebuild()
        } catch (t: Throwable) { a.toast("导入失败：${t.message ?: "无法处理这个文件"}") }
    }

    private fun work(a: SettingsActivity, title: String, action: () -> (() -> Unit)) {
        val progress = AlertDialog.Builder(a).setTitle(title).setMessage("请稍候…").setCancelable(false).create()
        dialogs[a] = progress
        progress.show()
        Thread({
            val outcome = runCatching(action)
            main.post {
                runCatching { progress.dismiss() }
                if (dialogs[a] === progress) dialogs.remove(a)
                if (!a.isFinishing && !a.isDestroyed) outcome.fold({ it() }, { a.toast("操作失败：${it.message ?: "无法处理这个文件"}") })
            }
        }, "WordTransfer").start()
    }
}
