package io.github.doubaoa11y.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import io.github.doubaoa11y.ConfigSpec
import io.github.doubaoa11y.SoundEngine
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

object SoundPage {
    private const val IMPORT = 4813
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val audioExts = setOf("ogg", "wav", "mp3", "m4a", "aac", "flac", "opus", "amr", "audio")

    private data class SoundCandidate(
        val file: File,
        val durationMs: Long,
    )

    private fun sounds(a: SettingsActivity) = SoundEngine.slots.map { "builtin.$it" } +
        SoundEngine.imports(a).map { it.nameWithoutExtension }

    fun fill(a: SettingsActivity, root: LinearLayout) {
        a.addKeys(root, "soundOn", "soundVolume")
        root.addView(a.switchOf(ConfigSpec.items.first { it.key == "ttsA11yStream" }))
        root.addView(a.heading("每种操作使用的声音"))
        for ((event, key) in SoundEngine.keys) {
            val title = ConfigSpec.intItems.first { it.key == key }.label
            val selected = SoundEngine.selection(a, event)
            root.addView(a.button("$title：${SoundEngine.label(a, selected)}") {
                val ids = sounds(a)
                AlertDialog.Builder(a).setTitle(title).setSingleChoiceItems(
                    ids.map { SoundEngine.label(a, it) }.toTypedArray(), ids.indexOf(selected)) { dialog, index ->
                    a.prefs.edit().putString(key + "Id", ids[index]).apply()
                    dialog.dismiss()
                    a.send(null)
                    a.rebuild()
                }.setNegativeButton("取消", null).show()
            })
        }
        root.addView(a.heading("音效管理"))
        root.addView(a.note("音效导入导出目录：${AppStorage.displayDir(AppStorage.DIR_SOUNDS)}"))
        val ids = sounds(a)
        root.addView(a.button("导入音频") {
            AppStorage.ensurePermission(a) {
                a.startActivity(Intent(a, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PAGE, SettingsActivity.SOUND_IMPORT))
            }
        })
        root.addView(a.button("导出音频") {
            AlertDialog.Builder(a).setTitle("选择要导出的音效").setItems(
                ids.map { SoundEngine.label(a, it) }.toTypedArray()) { _, i ->
                val id = ids[i]
                AppStorage.ensurePermission(a) { exportToStorage(a, id) }
            }.setNegativeButton("取消", null).show()
        })
        root.addView(a.button("自定义音效") {
            a.startActivity(Intent(a, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PAGE, SettingsActivity.SOUND))
        })
    }

    fun manage(a: SettingsActivity, root: LinearLayout) {
        val ids = sounds(a)
        root.addView(a.note("点击试听，长按导入的音效可删除或重命名"))
        root.addView(TextView(a).apply {
            text = "共 ${ids.size} 个音效"
            isFocusable = true
            setPadding(0, a.pad / 4, 0, a.pad / 4)
        })
        a.addList(
            root,
            ids.map { SoundEngine.label(a, it) },
            onClick = { index -> SoundEngine.play(a, ids[index], true, true) },
            onLongClick = { index ->
                val id = ids[index]
                if (id.startsWith("builtin.")) {
                    false
                } else {
                    val name = SoundEngine.label(a, id)
                    AlertDialog.Builder(a).setTitle(name).setItems(arrayOf("重命名", "删除")) { _, action ->
                        if (action == 0) {
                            val input = EditText(a).apply { setSingleLine(); setText(name); selectAll() }
                            AlertDialog.Builder(a).setTitle("重命名").setView(input).setPositiveButton("保存") { _, _ ->
                                val label = input.text.toString().trim()
                                if (label.isEmpty() || label.length > 80) a.toast("名称应为 1 到 80 个字符")
                                else {
                                    synchronized(BackupResourceTransaction.lock) {
                                        a.prefs.edit().putString("soundNames", SoundEngine.names(a).put(id, label).toString()).apply()
                                    }
                                    a.send(null)
                                    a.rebuild()
                                }
                            }.setNegativeButton("取消", null).show()
                        } else AlertDialog.Builder(a).setTitle("删除“$name”？").setMessage("使用它的操作将恢复默认音效。")
                            .setPositiveButton("删除") { _, _ -> remove(a, id) }.setNegativeButton("取消", null).show()
                    }.show()
                    true
                }
            },
        )
    }

    fun fillImportList(a: SettingsActivity, root: LinearLayout) {
        root.addView(a.note("目录：${AppStorage.displayDir(AppStorage.DIR_SOUNDS)}\n点击音频可试听或导入（需 5 秒以内、5 MB 以下），长按可删除文件。"))
        if (!AppStorage.hasPermission(a)) {
            root.addView(a.button("授予存储权限") {
                AppStorage.ensurePermission(a) { a.rebuild() }
            })
            root.addView(a.button("从其他位置选择音频文件") { pickExternal(a) })
            return
        }
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        box.addView(a.note("正在扫描可导入的音频文件…"))
        root.addView(box)
        root.addView(a.button("刷新列表") { a.rebuild() })
        root.addView(a.button("从其他位置选择音频文件") { pickExternal(a) })

        worker.execute {
            val items = scanSoundsDir()
            main.post {
                if (a.isFinishing || a.isDestroyed) return@post
                box.removeAllViews()
                if (items.isEmpty()) {
                    box.addView(
                        a.note(
                            "“${AppStorage.displayDir(AppStorage.DIR_SOUNDS)}”中暂无可导入的有效音频。\n" +
                                "请将 5 秒以内的音频（ogg/wav/mp3/m4a 等）放入该文件夹后点击刷新列表。"
                        )
                    )
                    return@post
                }
                box.addView(TextView(a).apply {
                    text = "共 ${items.size} 个可导入的音频"
                    isFocusable = true
                    setPadding(0, a.pad / 4, 0, a.pad / 4)
                })
                a.addList(
                    box,
                    items.map { item ->
                        val sec = String.format(Locale.US, "%.1f 秒", item.durationMs / 1000.0)
                        "${item.file.name}\n$sec · ${AppStorage.formatSize(item.file.length())} · ${AppStorage.formatTime(item.file.lastModified())}"
                    },
                    onClick = { idx -> showImportChoice(a, items[idx]) },
                    onLongClick = { idx ->
                        val f = items[idx].file
                        AlertDialog.Builder(a)
                            .setTitle("删除音频文件？")
                            .setMessage("确定要从本地文件夹删除“${f.name}”吗？已导入到音效库的副本不受影响。")
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
        }
    }

    private fun showImportChoice(a: SettingsActivity, item: SoundCandidate) {
        val sec = String.format(Locale.US, "%.1f 秒", item.durationMs / 1000.0)
        val d = AlertDialog.Builder(a)
            .setTitle(item.file.name)
            .setMessage("时长：$sec，大小：${AppStorage.formatSize(item.file.length())}\n可先试听，确认后导入到音效库。")
            .setNegativeButton("取消", null)
            .setNeutralButton("试听", null)
            .setPositiveButton("导入") { _, _ -> importFromFile(a, item.file) }
            .create()
        d.show()
        // 点击“试听”不关闭弹窗，方便试听后直接点导入
        d.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            SoundEngine.previewFile(a, item.file)
        }
    }

    private fun scanSoundsDir(): List<SoundCandidate> {
        AppStorage.ensureDirs()
        val files = AppStorage.soundsDir().listFiles().orEmpty()
            .filter {
                it.isFile && it.length() in 1L..ZipBackup.MAX_AUDIO.toLong() &&
                    (it.extension.lowercase() in audioExts || it.extension.isEmpty())
            }
            .sortedByDescending { it.lastModified() }
        val out = ArrayList<SoundCandidate>()
        for (f in files) {
            val dur = readValidAudioDuration(f)
            if (dur != null) out.add(SoundCandidate(f, dur))
        }
        return out
    }

    private fun readValidAudioDuration(file: File): Long? {
        val meta = MediaMetadataRetriever()
        return try {
            meta.setDataSource(file.absolutePath)
            if (meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != "yes") return null
            val duration = meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (duration in 1L..5000L) duration else null
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { meta.release() }
        }
    }

    private fun exportToStorage(a: SettingsActivity, id: String) {
        worker.execute {
            val outcome = runCatching {
                val label = SoundEngine.label(a, id)
                val ext = if (id.startsWith("builtin.")) {
                    SoundEngine.assets[SoundEngine.slots.indexOf(id.removePrefix("builtin."))].substringAfterLast('.')
                } else {
                    label.substringAfterLast('.', "").takeIf { it.lowercase() in audioExts } ?: "ogg"
                }
                val base = if (label.endsWith(".$ext", ignoreCase = true)) label.dropLast(ext.length + 1) else label
                val target = AppStorage.uniqueFile(AppStorage.soundsDir(), base, ext)
                val input = if (id.startsWith("builtin.")) {
                    val index = SoundEngine.slots.indexOf(id.removePrefix("builtin."))
                    a.assets.open("sfx/" + SoundEngine.assets[index])
                } else {
                    SoundEngine.file(a, id).inputStream()
                }
                input.use { source -> AppStorage.publish(target) { source.copyTo(it) } }
            }
            main.post {
                if (a.isFinishing || a.isDestroyed) return@post
                outcome.fold(
                    onSuccess = { file ->
                        AlertDialog.Builder(a)
                            .setTitle("导出成功")
                            .setMessage("音频已导出。\n保存位置：${AppStorage.displayPath(file)}")
                            .setPositiveButton("确定", null)
                            .show()
                    },
                    onFailure = { e -> a.toast("音频导出失败：${e.message.orEmpty()}") },
                )
            }
        }
    }

    private fun importFromFile(a: SettingsActivity, src: File) {
        worker.execute {
            val message = try {
                val name = src.name.trim().take(80).ifEmpty { "导入音频" }
                val temp = File.createTempFile("sound-", ".audio", a.cacheDir)
                try {
                    require(src.isFile && src.length() in 1L..ZipBackup.MAX_AUDIO.toLong()) { "音频为空或超过 5 MB" }
                    FileInputStream(src).use { input -> FileOutputStream(temp).use { AppStorage.copyLimited(input, it, ZipBackup.MAX_AUDIO.toLong()) } }
                    installTempAudio(a, temp, name)
                } finally {
                    temp.delete()
                }
                "已导入音频“$name”"
            } catch (e: Exception) {
                "音频导入失败：${e.message.orEmpty()}"
            }
            main.post {
                if (!a.isFinishing && !a.isDestroyed) {
                    a.send(null)
                    a.toast(message)
                }
            }
        }
    }

    private fun installTempAudio(a: SettingsActivity, temp: File, name: String) {
        ZipBackup.validateAudio(temp)
        synchronized(BackupResourceTransaction.lock) {
            val files = SoundEngine.imports(a)
            require(files.size + 2 <= ZipBackup.MAX_ENTRIES && files.sumOf { it.length() } + temp.length() < ZipBackup.MAX_TOTAL - BackupFormat.MAX_BYTES) {
                "音效库超过完整备份容量"
            }
            val key = "clip_" + UUID.randomUUID().toString().replace("-", "")
            val target = SoundEngine.file(a, key)
            target.parentFile!!.mkdirs()
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            a.prefs.edit().putString("soundNames", SoundEngine.names(a).put(key, name).toString()).apply()
        }
    }

    private fun remove(a: SettingsActivity, id: String) {
        synchronized(BackupResourceTransaction.lock) {
            val used = SoundEngine.keys.filterKeys { SoundEngine.selection(a, it) == id }
            SoundEngine.stop()
            if (!SoundEngine.file(a, id).delete()) { a.toast("删除失败"); return }
            val edit = a.prefs.edit()
            for ((event, key) in used) edit.putString(key + "Id", "builtin.$event")
            val names = SoundEngine.names(a).apply { remove(id) }
            edit.putString("soundNames", names.toString()).apply()
        }
        a.send(null)
        a.rebuild()
    }

    private fun pickExternal(a: SettingsActivity) {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                .setType("audio/*").addCategory(Intent.CATEGORY_OPENABLE)
            a.startActivityForResult(intent, IMPORT)
        } catch (_: Throwable) {
            a.toast("无法打开文件选择器")
        }
    }

    fun onResult(a: SettingsActivity, request: Int, result: Int, uri: Uri?): Boolean {
        if (request != IMPORT) return false
        if (result != Activity.RESULT_OK || uri == null) return true
        worker.execute {
            val message = try {
                val name = a.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }.orEmpty().trim().take(80).ifEmpty { "导入音频" }
                val temp = File.createTempFile("sound-", ".audio", a.cacheDir)
                try {
                    requireNotNull(a.contentResolver.openInputStream(uri)).use { source ->
                        temp.outputStream().use { out ->
                            AppStorage.copyLimited(source, out, ZipBackup.MAX_AUDIO.toLong())
                        }
                    }
                    installTempAudio(a, temp, name)
                } finally {
                    temp.delete()
                }
                "已导入音频“$name”"
            } catch (e: Exception) {
                "音频导入失败：${e.message.orEmpty()}"
            }
            main.post {
                if (!a.isFinishing && !a.isDestroyed) {
                    a.send(null)
                    a.toast(message)
                    a.rebuild()
                }
            }
        }
        return true
    }
}
