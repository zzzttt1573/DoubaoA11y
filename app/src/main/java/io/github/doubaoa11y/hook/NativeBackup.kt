package io.github.doubaoa11y.hook

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import io.github.doubaoa11y.ConfigSpec
import io.github.doubaoa11y.NativeRestoreFiles
import io.github.doubaoa11y.SettingsProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 豆包输入法原生设置与本地词库的离线备份/恢复引擎（运行在豆包进程内）。
 *
 * 恢复采用「两阶段冷启动原子替换」：
 * - 阶段一（运行中）：校验 ZIP 与 SHA-256，解包到暂存区并写入 restore.ready，不碰运行期实时文件。
 * - 阶段二（冷启动 attachBaseContext 最早时机）：在 Keva / SharedPreferences / liboime-config /
 *   LevelDB / SQLite 初始化之前加进程锁，备份旧文件到 rollback，清理 WAL/锁残留后原子替换，异常自动回滚。
 */
object NativeBackup {
    private const val TAG = "DoubaoA11y"
    private const val FORMAT_VERSION = 1
    private const val MANIFEST_NAME = "manifest.json"
    private const val PENDING_DIR = "doubaoa11y_pending_restore"
    private const val PAYLOAD_DIR = "payload"
    private const val READY_FILE = "restore.ready"
    private const val WORK_DIR = "doubaoa11y_restore_work"
    private const val ROLLBACK_DIR = "doubaoa11y_restore_rollback"
    private const val STATUS_FILE = "doubaoa11y_restore_status.json"

    private const val MAX_FILES = NativeRestoreFiles.FILE_LIMIT
    private const val MAX_SINGLE_BYTES = 16L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024

    // ---------------- 基于豆包 1.5.6 实机目录盘点的精准筛选规则 ----------------

    private val CORE_SHARED_PREFS = setOf(
        "com.bytedance.android.doubaoime_preferences.xml",
        "local_settings_sp.xml",
        "pref_onboarding_page.xml",
        "settings_guide_banner.xml",
        "ime_globals_data_com.bytedance.android.doubaoime.xml",
        "ime_globals_data_Setting.xml"
    )

    private val CORE_KEVA_REPOS = setOf(
        "common_kv",
        "multi_process_common",
        "only_main_process",
        "only_settings_process",
        "single_process_common"
    )

    private val EXCLUDED_KEYWORDS = listOf(
        "account", "passport", "token", "cookie", "cdid", "oaid", "ticket",
        "push", "monitor", "applog", "webview", "turing", "metasec", "mssdk",
        "salamander", "timon", "ttnet", "tt_stack", "client_key", "bd_tea_agent",
        "cross_process_event", "verifystorage", "experiment", "ab_exposed",
        "ab_vid", "drop_message", "jsb_permission", "sandboxed", "upgrade",
        "log_report", "hybrid_monitor", "apm_", "session", "zlink", "deep_link",
        "ug_install", "obric", "upc_sdk", "header_custom", "doubao_a11y_module"
    )

    private val BUSINESS_KEYWORDS = listOf(
        "ime", "oime", "doubao", "keyboard", "kbd", "setting", "input",
        "voice", "asr", "phrase", "clipboard", "toolbar", "skin", "emoji",
        "symbol", "user", "dict", "custom", "layout", "theme", "handwrite", "bihua"
    )

    @Volatile
    private var earlyChecked = false

    /**
     * 在 HookEntry 最早期执行：直接用 lpparam.appInfo.dataDir 在 Application/ContentProvider/Keva
     * 全部尚未初始化之前完成原子恢复；同时 Hook ContextWrapper.attachBaseContext 作为兜底。
     */
    fun installEarlyRestoreHook(dataDirPath: String?) {
        if (!dataDirPath.isNullOrEmpty()) {
            applyPendingRestoreForDataDir(File(dataDirPath))
        }
        if (earlyChecked) return
        try {
            Hooker.method(
                android.content.ContextWrapper::class.java,
                "attachBaseContext",
                arrayOf(Context::class.java),
                SafeHook("NativeBackup.attachBaseContext", before = { p ->
                    if (earlyChecked) return@SafeHook
                    if (p.thisObject !is Application) return@SafeHook
                    val base = p.args.firstOrNull() as? Context ?: return@SafeHook
                    applyPendingRestoreIfNeeded(base)
                })
            )
        } catch (t: Throwable) {
            Log.e(TAG, "安装冷启动恢复钩子失败", t)
        }
    }

    /**
     * 阶段二：在豆包冷启动最早时机应用已暂存的恢复包。
     */
    fun applyPendingRestoreIfNeeded(ctx: Context) {
        if (earlyChecked) return
        val dataDir = resolveDataDir(ctx) ?: return
        applyPendingRestoreForDataDir(dataDir)
    }

    fun applyPendingRestoreForDataDir(dataDir: File) {
        if (earlyChecked) return
        val filesDir = File(dataDir, "files")
        val pendingDir = File(filesDir, PENDING_DIR)
        val readyFile = File(pendingDir, READY_FILE)
        filesDir.mkdirs()
        try {
            NativeRestoreFiles.withPending(pendingDir) {
                if (!earlyChecked) {
                    // 此专用目录只保存尚未发布或已结束的事务，持锁期间可安全清理。
                    val work = File(filesDir, WORK_DIR)
                    if (work.exists() && !work.deleteRecursively()) Log.w(TAG, "未能清理旧事务临时文件")
                }
                if (!earlyChecked && (readyFile.isFile || File(filesDir, ROLLBACK_DIR).exists())) {
                    Log.i(TAG, "检测到挂起的豆包原生设置恢复包，开始冷启动原子恢复")
                    val status = executeStagedRestore(dataDir, pendingDir, filesDir)
                    writeStatus(filesDir, status)
                }
                earlyChecked = true
            }
        } catch (t: Throwable) {
            Log.e(TAG, "冷启动恢复原生设置异常", t)
            earlyChecked = true
            runCatching {
                writeStatus(
                    filesDir,
                    JSONObject().apply {
                        put("ok", false)
                        put("timestamp", System.currentTimeMillis())
                        put("message", "冷启动恢复异常：${t.message ?: t.javaClass.simpleName}")
                    }
                )
            }
        }
    }

    private fun executeStagedRestore(
        dataDir: File,
        pendingDir: File,
        filesDir: File
    ): JSONObject {
        val payloadDir = File(pendingDir, PAYLOAD_DIR)
        val manifestFile = File(pendingDir, MANIFEST_NAME)
        val rollbackDir = File(filesDir, ROLLBACK_DIR)

        if (rollbackDir.exists()) {
            val recovered = finishTransaction(dataDir, pendingDir, rollbackDir)
            if (!File(pendingDir, READY_FILE).isFile) return recovered
        }
        if (!manifestFile.isFile || !payloadDir.isDirectory) {
            pendingDir.deleteRecursively()
            return JSONObject().apply {
                put("ok", false)
                put("timestamp", System.currentTimeMillis())
                put("message", "暂存恢复包不完整，已自动清理")
            }
        }

        val manifest = JSONObject(manifestFile.inputStream().use { String(NativeRestoreFiles.readManifest(it), Charsets.UTF_8) })
        val entries = manifest.getJSONArray("files")
        val items = mutableListOf<Triple<String, String, Long>>()
        for (i in 0 until entries.length()) {
            val obj = entries.getJSONObject(i)
            val rel = obj.getString("path")
            val sha = obj.getString("sha256")
            val size = obj.getLong("size")
            require(isAllowedRestorePath(rel)) { "恢复包包含非法路径：$rel" }
            val staged = File(payloadDir, rel)
            require(staged.isFile && staged.length() == size) { "暂存文件缺失或大小不符：$rel" }
            require(sha256Hex(staged) == sha) { "暂存文件校验和不匹配：$rel" }
            items.add(Triple(rel, sha, size))
        }

        val dirUnits = LinkedHashSet<String>()
        val fileUnits = LinkedHashSet<String>()

        for ((rel, _, _) in items) {
            val kevaUnit = kevaRepoDirOf(rel)
            when {
                kevaUnit != null -> dirUnits.add(kevaUnit)
                rel.startsWith("files/.dict/user/engine_storage.leveldb/") ->
                    dirUnits.add("files/.dict/user/engine_storage.leveldb")
                else -> fileUnits.add(rel)
            }
        }

        val units = LinkedHashSet(dirUnits)
        units.addAll(fileUnits)
        for (rel in fileUnits) {
            if (rel.startsWith("databases/") && listOf("-wal", "-shm", "-journal").none { rel.endsWith(it) }) {
                for (suffix in listOf("-wal", "-shm", "-journal")) {
                    if (File(dataDir, rel + suffix).exists()) units.add(rel + suffix)
                }
            }
        }
        val entriesToMove = JSONObject()
        for (rel in units) {
            val target = File(dataDir.canonicalFile, rel)
            require(target.canonicalFile == target) { "原生目标路径包含符号链接：$rel" }
            entriesToMove.put(rel, target.exists())
        }
        val journal = JSONObject().put("units", entriesToMove)
            .put("ready", File(pendingDir, READY_FILE).readText())
        val work = File(filesDir, WORK_DIR)
        check(work.isDirectory || work.mkdir()) { "无法创建恢复工作目录" }
        val prepared = java.nio.file.Files.createTempDirectory(work.toPath(), "init-").toFile()
        try {
            writeSynced(File(prepared, "journal.json"), journal.toString())
            atomicMove(prepared, rollbackDir)
        } finally {
            prepared.deleteRecursively()
        }
        try {
            for (rel in units) {
                if (entriesToMove.getBoolean(rel)) atomicMove(File(dataDir, rel), File(rollbackDir, rel))
            }
            for ((rel, _, _) in items) copyFile(File(payloadDir, rel), File(dataDir, rel))
            // 所有新文件写完后才发布提交标记，标记存在即可安全清理旧件。
            check(File(rollbackDir, "committed").createNewFile()) { "无法记录恢复提交状态" }
        } catch (failure: Throwable) {
            Log.e(TAG, "原生设置恢复中断", failure)
            return finishTransaction(dataDir, pendingDir, rollbackDir)
                .put("message", "恢复失败，已回滚：" + failure.javaClass.simpleName)
        }
        return finishTransaction(dataDir, pendingDir, rollbackDir)
            .put("fileCount", items.size).put("sourceVersion", manifest.optString("doubaoVersionName"))
            .put("message", "已恢复 " + items.size + " 个豆包原生文件")
    }

    private fun finishTransaction(dataDir: File, pending: File, rollback: File): JSONObject {
        val record = File(rollback, "journal.json")
        check(record.isFile) { "发现缺少事务清单的旧回滚目录，已保留原件，请检查" }
        val journal = JSONObject(record.readText(Charsets.UTF_8))
        val committed = File(rollback, "committed").isFile
        if (!committed) {
            val units = journal.getJSONObject("units")
            val failures = linkedMapOf<String, Exception>()
            for (rel in units.keys()) {
                val target = File(dataDir.canonicalFile, rel)
                require(target.canonicalFile == target &&
                    (isAllowedRestorePath(rel) || kevaRepoDirOf("$rel/member") == rel)) { "回滚路径无效，已保留原件" }
                val original = File(rollback, rel)
                try {
                    if (original.exists()) {
                        check(!target.exists() || target.deleteRecursively())
                        atomicMove(original, target)
                    } else if (!units.getBoolean(rel)) {
                        check(!target.exists() || target.deleteRecursively())
                    } else check(target.exists()) { "原件缺失" }
                } catch (failure: Exception) {
                    failures[rel] = failure
                }
            }
            if (failures.isNotEmpty()) {
                val error = IllegalStateException("回滚未完成，已保留原件：" + failures.keys.joinToString())
                failures.values.forEach(error::addSuppressed)
                throw error
            }
        }
        // 若期间发布了新的暂存包，仅清理与本事务匹配的旧包。
        val ready = File(pending, READY_FILE)
        if (ready.isFile && ready.readText() == journal.getString("ready")) {
            // 先移出固定入口，再清理，进程中断也不会二次执行。
            retire(pending)
        }
        retire(rollback)
        return JSONObject().put("ok", committed).put("timestamp", System.currentTimeMillis())
            .put("message", if (committed) "原生恢复已提交" else "已回滚中断的原生恢复")
    }

    private fun retire(dir: File) {
        val retired = File(File(dir.parentFile, WORK_DIR), "done-" + java.util.UUID.randomUUID())
        atomicMove(dir, retired)
        if (!retired.deleteRecursively()) Log.w(TAG, "已结束事务的临时目录清理失败")
    }

    private fun atomicMove(src: File, dst: File) {
        dst.parentFile?.mkdirs()
        java.nio.file.Files.move(src.toPath(), dst.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    private fun writeSynced(file: File, text: String) {
        file.outputStream().use { out -> out.write(text.toByteArray(Charsets.UTF_8)); out.fd.sync() }
    }

    /**
     * 备份导出：打包豆包原生设置与用户词库，经 SettingsProvider 写入中转 ZIP。
     */
    fun exportBackup(ctx: Context, token: String, xferUri: android.net.Uri?, includeClipboard: Boolean): String {
        val dataDir = resolveDataDir(ctx) ?: error("无法定位豆包数据目录")
        val collected = collectBackupFiles(dataDir, includeClipboard)
        if (collected.isEmpty()) error("未找到可备份的豆包原生设置文件")

        require(collected.size <= MAX_FILES) { "原生文件超过 512 个，无法完整备份" }
        val pfd = openXferFd(ctx, xferUri, token, write = true)
            ?: error("无法打开模块中转写入通道")

        val (vCode, vName) = readDoubaoVersion(ctx)
        val manifestFiles = JSONArray()
        var totalBytes = 0L

        pfd.use { fd ->
            ZipOutputStream(BufferedOutputStream(FileOutputStream(fd.fileDescriptor), 64 * 1024)).use { zos ->
                val buffer = ByteArray(64 * 1024)
                for ((rel, file) in collected) {
                    require(file.isFile && isAllowedRestorePath(rel)) { "无法完整收录原生文件：$rel" }
                    val md = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    zos.putNextEntry(ZipEntry(rel).apply { time = file.lastModified() })
                    file.inputStream().use { input ->
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            size += n
                            totalBytes += n
                            require(size <= MAX_SINGLE_BYTES && totalBytes <= MAX_TOTAL_BYTES) { "原生文件超限，无法完整备份：$rel" }
                            md.update(buffer, 0, n)
                            zos.write(buffer, 0, n)
                        }
                    }
                    zos.closeEntry()
                    val sha = hex(md.digest())
                    manifestFiles.put(JSONObject().apply {
                        put("path", rel)
                        put("size", size)
                        put("sha256", sha)
                    })
                }

                require(collectBackupFiles(dataDir, includeClipboard).map { it.first } == collected.map { it.first }) {
                    "原生文件集合在导出期间变化，请重试"
                }
                val manifest = JSONObject().apply {
                    put("formatVersion", FORMAT_VERSION)
                    put("packageName", ConfigSpec.TARGET_PACKAGE)
                    put("doubaoVersionCode", vCode)
                    put("doubaoVersionName", vName)
                    put("timestamp", System.currentTimeMillis())
                    put("includeClipboard", includeClipboard)
                    put("fileCount", manifestFiles.length())
                    put("totalBytes", totalBytes)
                    put("files", manifestFiles)
                }
                zos.putNextEntry(ZipEntry(MANIFEST_NAME))
                zos.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
                zos.closeEntry()
                zos.finish()
                zos.flush()
                require(fd.statSize in 1L..MAX_TOTAL_BYTES) { "原生 ZIP 超过 64 MB，无法完整备份" }
            }
        }
        return "已打包 ${manifestFiles.length()} 个豆包原生文件（${totalBytes / 1024} KB）"
    }

    /**
     * 阶段一（运行中）：读取中转 ZIP，安全解压并校验 SHA-256 到暂存目录，写入 restore.ready。
     */
    fun stageRestore(ctx: Context, token: String, xferUri: android.net.Uri?): String {
        val dataDir = resolveDataDir(ctx) ?: error("无法定位豆包数据目录")
        val filesDir = File(dataDir, "files")
        val pendingDir = File(filesDir, PENDING_DIR)
        filesDir.mkdirs()
        val staged = File(filesDir, "doubaoa11y_stage_" + java.util.UUID.randomUUID())
        check(staged.mkdir()) { "无法创建暂存目录" }
        val payloadDir = File(staged, PAYLOAD_DIR)

        try {
            check(payloadDir.mkdir()) { "无法创建恢复数据目录" }
            var manifestBytes: ByteArray? = null
            val payloadNames = HashSet<String>()
            var totalBytes = 0L

            val pfd = openXferFd(ctx, xferUri, token, write = false)
                ?: error("无法打开模块中转读取通道")
            pfd.use { fd ->
                ZipInputStream(BufferedInputStream(FileInputStream(fd.fileDescriptor), 64 * 1024)).use { zis ->
                    val buf = ByteArray(64 * 1024)
                    var count = 0
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        require(++count <= NativeRestoreFiles.ENTRY_LIMIT) { "原生备份条目过多" }
                        if (entry.isDirectory) {
                            require(zis.read() == -1) { "目录条目含有数据" }
                            zis.closeEntry()
                            continue
                        }
                        val name = entry.name
                        if (name == MANIFEST_NAME) {
                            require(manifestBytes == null) { "重复的 manifest.json" }
                            manifestBytes = NativeRestoreFiles.readManifest(zis)
                            zis.closeEntry()
                            continue
                        }
                        require(isAllowedRestorePath(name)) { "备份包包含不允许恢复的路径：$name" }
                        require(payloadNames.add(name)) { "备份包包含重复路径：$name" }
                        require(payloadNames.size <= MAX_FILES) { "备份包文件数量超过上限（$MAX_FILES）" }

                        val outFile = File(payloadDir, name)
                        outFile.parentFile?.mkdirs()
                        var singleSize = 0L
                        FileOutputStream(outFile).use { fos ->
                            while (true) {
                                val n = zis.read(buf)
                                if (n < 0) break
                                singleSize += n
                                totalBytes += n
                                require(singleSize <= MAX_SINGLE_BYTES) { "单个文件超过 16 MB：$name" }
                                require(totalBytes <= MAX_TOTAL_BYTES) { "备份总大小超过 64 MB" }
                                fos.write(buf, 0, n)
                            }
                        }
                        zis.closeEntry()
                    }
                }
            }

            val rawManifest = manifestBytes ?: error("备份包缺少 manifest.json")
            val manifest = JSONObject(String(rawManifest, Charsets.UTF_8))
            require(manifest.optInt("formatVersion", -1) == FORMAT_VERSION) { "不支持的豆包原生备份格式版本" }
            require(manifest.optString("packageName") == ConfigSpec.TARGET_PACKAGE) { "该备份不是豆包输入法原生备份" }

            val entries = manifest.getJSONArray("files")
            require(entries.length() > 0) { "备份包内没有可恢复的设置文件" }
            for (i in 0 until entries.length()) {
                val obj = entries.getJSONObject(i)
                val rel = obj.getString("path")
                val expectedSha = obj.getString("sha256")
                val expectedSize = obj.getLong("size")
                require(isAllowedRestorePath(rel)) { "清单包含非法路径：$rel" }
                require(payloadNames.remove(rel)) { "清单路径重复或缺少对应文件：$rel" }
                val f = File(payloadDir, rel)
                require(f.isFile) { "缺少备份文件：$rel" }
                require(f.length() == expectedSize) { "文件大小校验失败：$rel" }
                require(sha256Hex(f) == expectedSha) { "文件完整性校验失败：$rel" }
            }

            require(payloadNames.isEmpty()) { "备份包包含清单以外的文件" }
            File(staged, MANIFEST_NAME).writeBytes(rawManifest)
            File(staged, READY_FILE).writeText(java.util.UUID.randomUUID().toString(), Charsets.UTF_8)
            NativeRestoreFiles.publish(staged, pendingDir)

            return "已校验并暂存 ${entries.length()} 个豆包原生设置文件。请强行停止豆包输入法后重新弹出键盘，即可生效。"
        } finally {
            staged.deleteRecursively()
        }
    }

    /**
     * 查询最近一次冷启动恢复结果。
     */
    fun readRestoreStatus(ctx: Context): String {
        val dataDir = resolveDataDir(ctx) ?: return "无法定位豆包数据目录"
        val filesDir = File(dataDir, "files")
        val pendingReady = File(File(filesDir, PENDING_DIR), READY_FILE)
        val hasPending = try {
            filesDir.isDirectory && NativeRestoreFiles.withPending(pendingReady.parentFile!!) { pendingReady.isFile }
        } catch (t: Throwable) {
            Log.e(TAG, "读取暂存恢复状态失败", t)
            return "无法读取暂存恢复状态。"
        }
        if (hasPending) {
            return "有待应用的豆包原生恢复包，请先强行停止豆包输入法并重新弹出键盘。"
        }
        val statusFile = File(filesDir, STATUS_FILE)
        if (!statusFile.isFile) {
            return "尚未执行过豆包原生设置恢复。"
        }
        return try {
            val j = JSONObject(statusFile.readText(Charsets.UTF_8))
            val ok = j.optBoolean("ok", false)
            val msg = j.optString("message", if (ok) "恢复成功" else "恢复失败")
            if (ok) "上次恢复成功：$msg" else "上次恢复未完成：$msg"
        } catch (_: Throwable) {
            "无法读取上次恢复状态。"
        }
    }

    /**
     * 只读扫描豆包私有目录结构（仅记录相对路径和文件大小，绝不读取文件内容）。
     */
    fun scanDataTree(ctx: Context): String {
        val dataDir = resolveDataDir(ctx) ?: return "无法定位豆包数据目录"
        val topDirs = listOf("shared_prefs", "files", "databases", "no_backup")
        var totalFiles = 0
        var totalBytes = 0L
        Log.i(TAG, "=== NATIVE_SCAN_BEGIN dataDir=${dataDir.absolutePath} ===")
        for (name in topDirs) {
            val dir = File(dataDir, name)
            if (!dir.exists()) {
                Log.i(TAG, "SCAN [absent] $name")
                continue
            }
            walkDir(dir, name, depth = 0, maxDepth = 5) { rel, size ->
                totalFiles++
                totalBytes += size
                Log.i(TAG, "SCAN $rel ($size B)")
            }
        }
        Log.i(TAG, "=== NATIVE_SCAN_END files=$totalFiles bytes=$totalBytes ===")
        return "扫描完成：共 $totalFiles 个文件，${totalBytes / 1024} KB"
    }

    // ---------------- 白名单/黑名单收集逻辑 ----------------

    private fun collectBackupFiles(dataDir: File, includeClipboard: Boolean): List<Pair<String, File>> {
        val out = LinkedHashMap<String, File>()

        // 1. shared_prefs/
        val spDir = File(dataDir, "shared_prefs")
        spDir.listFiles()?.sortedBy { it.name }?.forEach { f ->
            if (f.isFile && shouldBackupSharedPref(f.name, includeClipboard)) {
                out["shared_prefs/${f.name}"] = f
            }
        }

        // 2. files/keva/repo/<repo>/
        val kevaRepoRoot = File(dataDir, "files/keva/repo")
        kevaRepoRoot.listFiles()?.sortedBy { it.name }?.forEach { repoDir ->
            if (repoDir.isDirectory && shouldBackupKevaRepo(repoDir.name, includeClipboard)) {
                val children = repoDir.listFiles() ?: error("无法完整读取 Keva 目录")
                    children.sortedBy { it.name }.forEach { f ->
                        require(!f.isDirectory) { "Keva 目录结构不受支持，无法完整备份" }
                    if (f.isFile && !isTransientLockOrTemp(f.name)) {
                        out["files/keva/repo/${repoDir.name}/${f.name}"] = f
                    }
                }
            }
        }

        // 3. files/.config/（定制工具栏 toolbar_layout.json 及其他原生配置文件，排除录音 .pcm 与 feedback/）
        val configDir = File(dataDir, "files/.config")
        if (configDir.isDirectory) {
            collectConfigDir(configDir, "files/.config", out, depth = 0)
        }

        // 4. files/.dict/user/（用户词库、语音纠错词库、输入历史与 engine_storage.leveldb）
        val userDictDir = File(dataDir, "files/.dict/user")
        if (userDictDir.isDirectory) {
            collectUserDictDir(userDictDir, "files/.dict/user", out, depth = 0)
        }

        // 5. databases/（排除 SDK/推送/埋点库，自动纳入常用语/短语/词库/可选剪贴板数据库）
        val dbDir = File(dataDir, "databases")
        dbDir.listFiles()?.sortedBy { it.name }?.forEach { f ->
            if (f.isFile && shouldBackupDatabase(f.name, includeClipboard)) {
                out["databases/${f.name}"] = f
            }
        }

        return out.entries.map { it.key to it.value }
    }

    private fun shouldBackupSharedPref(name: String, includeClipboard: Boolean): Boolean {
        if (!name.endsWith(".xml") || isTransientLockOrTemp(name)) return false
        if (name in CORE_SHARED_PREFS) return true
        val lower = name.lowercase(Locale.ROOT)
        if (!includeClipboard && lower.contains("clipboard")) return false
        if (EXCLUDED_KEYWORDS.any { lower.contains(it) }) return false
        return BUSINESS_KEYWORDS.any { lower.contains(it) }
    }

    private fun shouldBackupKevaRepo(repoName: String, includeClipboard: Boolean): Boolean {
        if (repoName in CORE_KEVA_REPOS) return true
        val lower = repoName.lowercase(Locale.ROOT)
        if (!includeClipboard && lower.contains("clipboard")) return false
        if (EXCLUDED_KEYWORDS.any { lower.contains(it) }) return false
        return BUSINESS_KEYWORDS.any { lower.contains(it) }
    }

    private fun collectConfigDir(
        dir: File,
        prefix: String,
        out: MutableMap<String, File>,
        depth: Int
    ) {
        if (depth > 2) return
        dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
            val lower = f.name.lowercase(Locale.ROOT)
            if (f.isDirectory) {
                if (lower == "feedback" || lower == "storage_dir" || lower == "cache" || lower == "tmp") {
                    return@forEach
                }
                collectConfigDir(f, "$prefix/${f.name}", out, depth + 1)
            } else {
                if (lower.endsWith(".pcm") || lower.endsWith(".wav") || lower.endsWith(".log") ||
                    isTransientLockOrTemp(f.name)
                ) {
                    return@forEach
                }
                out["$prefix/${f.name}"] = f
            }
        }
    }

    private fun collectUserDictDir(
        dir: File,
        prefix: String,
        out: MutableMap<String, File>,
        depth: Int
    ) {
        val wholeUnit = prefix == "files/.dict/user/engine_storage.leveldb" ||
            prefix.startsWith("files/.dict/user/engine_storage.leveldb/")
        if (depth > 3) {
            require(!wholeUnit) { "LevelDB 目录过深，无法完整备份" }
            return
        }
        val children = dir.listFiles()
        require(!wholeUnit || children != null) { "无法读取 LevelDB 目录" }
        children?.sortedBy { it.name }?.forEach { f ->
            if (f.isDirectory) {
                collectUserDictDir(f, "$prefix/${f.name}", out, depth + 1)
            } else {
                if (isTransientLockOrTemp(f.name) || f.name == "LOG" || f.name == "LOG.old") {
                    return@forEach
                }
                out["$prefix/${f.name}"] = f
            }
        }
    }

    private fun shouldBackupDatabase(name: String, includeClipboard: Boolean): Boolean {
        if (isTransientLockOrTemp(name)) return false
        val lower = name.lowercase(Locale.ROOT)
        if (!includeClipboard && lower.contains("clipboard")) return false
        if (EXCLUDED_KEYWORDS.any { lower.contains(it) }) return false
        return lower.endsWith(".db") || lower.endsWith(".db-wal") || lower.endsWith(".db-shm")
    }

    private fun isTransientLockOrTemp(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower == "lock" || lower.endsWith(".lock") || lower.endsWith(".tmp") ||
            lower.endsWith(".bak") || lower.endsWith(".guard")
    }

    /**
     * 严格校验恢复包内的相对路径：防止路径穿越，且只允许写入 5 类白名单子树。
     */
    private fun isAllowedRestorePath(rel: String): Boolean {
        if (rel.isEmpty() || rel.length > 240) return false
        if (rel.startsWith("/") || rel.contains("\\") || rel.contains(":") || rel.contains('\u0000')) return false
        val parts = rel.split("/")
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return false
        return when {
            rel.startsWith("shared_prefs/") && parts.size == 2 ->
                !rel.endsWith("/doubao_a11y_module.xml")
            rel.startsWith("files/keva/repo/") && parts.size == 5 ->
                !isTransientLockOrTemp(parts.last())
            rel.startsWith("files/.config/") && parts.size >= 3 ->
                !rel.endsWith(".pcm")
            rel.startsWith("files/.dict/user/") && parts.size >= 4 ->
                !isTransientLockOrTemp(parts.last())
            rel.startsWith("databases/") && parts.size == 2 ->
                !isTransientLockOrTemp(parts.last())
            else -> false
        }
    }

    private fun kevaRepoDirOf(rel: String): String? {
        if (!rel.startsWith("files/keva/repo/")) return null
        val parts = rel.split("/")
        if (parts.size < 5) return null
        return "files/keva/repo/${parts[3]}"
    }

    // ---------------- 工具方法 ----------------

    private fun openXferFd(ctx: Context, xferUri: android.net.Uri?, token: String, write: Boolean): ParcelFileDescriptor? {
        val uri = xferUri ?: SettingsProvider.xferUri(token)
        val mode = if (write) "rw" else "r"
        try {
            ctx.contentResolver.openFileDescriptor(uri, mode)?.let { return it }
        } catch (_: Throwable) {
        }
        val method = if (write) "openNativeBackupWriteFd" else "openNativeBackupReadFd"
        val b = ctx.contentResolver.call(SettingsProvider.URI, method, token, null) ?: return null
        @Suppress("DEPRECATION")
        return b.getParcelable("fd")
    }

    private fun resolveDataDir(ctx: Context): File? {
        return ctx.applicationInfo?.dataDir?.let { File(it) } ?: ctx.filesDir?.parentFile
    }

    private fun readDoubaoVersion(ctx: Context): Pair<Long, String> {
        return try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else {
                @Suppress("DEPRECATION")
                pi.versionCode.toLong()
            }
            code to (pi.versionName ?: "")
        } catch (_: Throwable) {
            0L to ""
        }
    }

    private fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        FileInputStream(file).use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return hex(md.digest())
    }

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }

    private fun copyFile(src: File, dst: File) {
        dst.parentFile?.mkdirs()
        FileInputStream(src).use { input ->
            FileOutputStream(dst).use { output ->
                input.copyTo(output, 64 * 1024)
                output.fd.sync()
            }
        }
    }

    private fun writeStatus(filesDir: File, json: JSONObject) {
        val tmp = File(filesDir, "$STATUS_FILE.tmp")
        val dst = File(filesDir, STATUS_FILE)
        tmp.writeText(json.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(dst)) {
            tmp.copyTo(dst, overwrite = true)
            tmp.delete()
        }
    }

    private fun walkDir(
        dir: File,
        prefix: String,
        depth: Int,
        maxDepth: Int,
        onFile: (String, Long) -> Unit
    ) {
        val children = dir.listFiles()?.sortedBy { it.name } ?: return
        for (f in children) {
            val rel = "$prefix/${f.name}"
            if (f.isDirectory) {
                if (depth >= maxDepth) {
                    val count = f.listFiles()?.size ?: 0
                    Log.i(TAG, "SCAN [dir-truncated] $rel/ ($count entries)")
                } else {
                    walkDir(f, rel, depth + 1, maxDepth, onFile)
                }
            } else {
                onFile(rel, f.length())
            }
        }
    }
}
