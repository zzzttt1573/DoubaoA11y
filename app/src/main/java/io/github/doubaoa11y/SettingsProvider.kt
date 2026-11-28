package io.github.doubaoa11y

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

/** 仅向真实目标输入法 UID 提供配置和语音服务，不接受调用方自报的包名。 */
class SettingsProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context ?: return Bundle()
        val uid = Binder.getCallingUid()
        val allowed = uid == Process.myUid() ||
            ctx.packageManager.getPackagesForUid(uid)?.contains(ConfigSpec.TARGET_PACKAGE) == true
        if (!allowed) throw SecurityException("不允许访问模块设置")
        val identity = Binder.clearCallingIdentity()
        try {
        val p = ctx.getSharedPreferences(ConfigSpec.SETTINGS_PREFS, Context.MODE_PRIVATE)
        ConfigSpec.migrateInput(p)
        return when (method) {
            "dump" -> Bundle().apply {
                for (i in ConfigSpec.items) putBoolean(i.key, p.getBoolean(i.key, i.default))
                for (i in ConfigSpec.intItems) putInt(i.key, p.getInt(i.key, i.default))
                for (i in ConfigSpec.strItems) putString(i.key, p.getString(i.key, i.default)?.take(16000))
                val words = p.getString(ConfigSpec.EXTRA_CUSTOM_WORDS, "").orEmpty()
                require(words.length <= io.github.doubaoa11y.ui.WordTransferFormat.MAX_CHARS) { "自定义解释词超过容量，请在设置中调整" }
                val wordsVer = "${words.length}:${words.hashCode()}"
                putString("wordsVer", wordsVer)
                if (extras?.getString("wordsVer") != wordsVer) {
                    putString(ConfigSpec.EXTRA_CUSTOM_WORDS, words)
                }
            }
            "setUpperOperation" -> Bundle().apply {
                require(extras?.get("upperOperation") is Boolean)
                synchronized(Companion) {
                    pendingKeys.remove("upperOperation")
                    putBoolean("saved", p.edit().putBoolean("upperOperation", extras!!.getBoolean("upperOperation")).commit())
                }
            }
            "setToolbar" -> Bundle().apply {
                synchronized(Companion) {
                    val ed = p.edit()
                    var any = false
                    for (k in ConfigSpec.TOOLBAR_TOGGLE_KEYS) {
                        if (extras?.get(k) is Boolean) {
                            pendingKeys.remove(k)
                            ed.putBoolean(k, extras.getBoolean(k))
                            any = true
                        }
                    }
                    if (extras?.get("toolbarCustomOrder") is String) {
                        ed.putString("toolbarCustomOrder", extras.getString("toolbarCustomOrder").orEmpty().take(4000))
                        any = true
                    }
                    require(any)
                    putBoolean("saved", ed.commit())
                }
            }
            "speak" -> Bundle().apply {
                val text = extras?.getString("text").orEmpty()
                require(text.length <= 200_000)
                val enabled = p.getBoolean("moduleOn", true) && !p.getBoolean("speakByReader", true)
                putBoolean("accepted", enabled && SpeechEngine.speak(ctx, p, text))
            }
            "warmup" -> Bundle().apply {
                if (p.getBoolean("moduleOn", true) && !p.getBoolean("speakByReader", true)) SpeechEngine.prepare(ctx, p)
            }
            "stop" -> Bundle().apply { SpeechEngine.stop() }
            "sound" -> Bundle().apply { putBoolean("accepted", SoundEngine.play(ctx, extras?.getString("event").orEmpty())) }
            "openSound" -> Bundle().apply {
                val id = arg.orEmpty()
                if (io.github.doubaoa11y.ui.ZipBackup.validSlot(id)) {
                    val f = SoundEngine.file(ctx, id)
                    if (f.isFile) {
                        putParcelable("fd", android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY))
                    }
                }
            }
            "openNativeBackupWriteFd" -> Bundle().apply {
                val f = xferFile(ctx, arg.orEmpty()) ?: throw IllegalArgumentException("无效传输令牌")
                f.parentFile?.mkdirs()
                putParcelable(
                    "fd",
                    android.os.ParcelFileDescriptor.open(
                        f,
                        android.os.ParcelFileDescriptor.MODE_READ_WRITE or
                            android.os.ParcelFileDescriptor.MODE_CREATE or
                            android.os.ParcelFileDescriptor.MODE_TRUNCATE
                    )
                )
            }
            "openNativeBackupReadFd" -> Bundle().apply {
                val f = xferFile(ctx, arg.orEmpty()) ?: throw IllegalArgumentException("无效传输令牌")
                if (!f.isFile) throw IllegalStateException("未找到待恢复的中转文件")
                putParcelable(
                    "fd",
                    android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                )
            }
            "tatansPassThrough" -> {
                val rect = android.graphics.Rect(
                    extras?.getInt("left", 0) ?: 0,
                    extras?.getInt("top", 0) ?: 0,
                    extras?.getInt("right", 0) ?: 0,
                    extras?.getInt("bottom", 0) ?: 0
                )
                val token = extras?.getBinder("token")
                val disconnect = extras?.getBoolean("disconnect", false) == true
                val suppress = extras?.getBoolean("suppress", false) == true
                ReaderBridge.tatansPassThrough(ctx, rect, token, disconnect, suppress)
            }
            "talkmanPassThrough" -> {
                val rect = android.graphics.Rect(
                    extras?.getInt("left", 0) ?: 0,
                    extras?.getInt("top", 0) ?: 0,
                    extras?.getInt("right", 0) ?: 0,
                    extras?.getInt("bottom", 0) ?: 0
                )
                ReaderBridge.talkmanPassThrough(ctx, rect)
            }
            "readerControl" -> {
                val pkg = extras?.getString("pkg").orEmpty()
                val on = extras?.getBoolean("on", true) != false
                ReaderBridge.readerControl(ctx, pkg, on)
            }
            else -> throw IllegalArgumentException("不支持的设置操作")
        }
        } finally { Binder.restoreCallingIdentity(identity) }
    }
    override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor? {
        val ctx = context ?: return null
        val segments = uri.pathSegments
        // 允许系统安装器通过本应用签发的一次性令牌只读打开修补版豆包 APK（用于 Android 8–13 通用 ACTION_VIEW 安装）
        val apkToken = extractPatchedApkToken(segments)
        if (apkToken != null) {
            val callingUid = Binder.getCallingUid()
            val identity = Binder.clearCallingIdentity()
            val caller = try {
                ctx.packageManager.getPackagesForUid(callingUid)?.joinToString(",") ?: "uid=$callingUid"
            } catch (_: Throwable) {
                "uid=$callingUid"
            }
            return try {
                if (mode.contains('w')) {
                    runCatching { io.github.doubaoa11y.patch.PatchLog.w(ctx, "安装器 openFile 被拒（写模式）：caller=$caller mode=$mode") }
                    throw SecurityException("安装包仅允许只读访问")
                }
                val f = resolvePatchedApk(ctx, apkToken)
                if (f == null) {
                    runCatching { io.github.doubaoa11y.patch.PatchLog.w(ctx, "安装器 openFile 令牌失效：caller=$caller uri=$uri") }
                    throw SecurityException("安装包访问令牌已失效")
                }
                if (!f.isFile) {
                    runCatching { io.github.doubaoa11y.patch.PatchLog.w(ctx, "安装器 openFile 文件不存在：caller=$caller path=${f.absolutePath}") }
                    throw java.io.FileNotFoundException("未找到待安装的修补包")
                }
                val pfd = android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                runCatching { io.github.doubaoa11y.patch.PatchLog.i(ctx, "安装器已打开修补包：caller=$caller mode=$mode size=${f.length()} B") }
                pfd
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }
        val uid = Binder.getCallingUid()
        val allowed = uid == Process.myUid() ||
            ctx.packageManager.getPackagesForUid(uid)?.contains(ConfigSpec.TARGET_PACKAGE) == true
        if (!allowed) throw SecurityException("不允许访问模块中转文件")
        if (segments.size != 2 || segments[0] != "native_xfer") {
            throw IllegalArgumentException("无效中转路径")
        }
        val f = xferFile(ctx, segments[1]) ?: throw IllegalArgumentException("无效传输令牌")
        val identity = Binder.clearCallingIdentity()
        return try {
            val isWrite = mode.contains('w')
            if (isWrite) {
                f.parentFile?.mkdirs()
                android.os.ParcelFileDescriptor.open(
                    f,
                    android.os.ParcelFileDescriptor.MODE_READ_WRITE or
                        android.os.ParcelFileDescriptor.MODE_CREATE or
                        android.os.ParcelFileDescriptor.MODE_TRUNCATE
                )
            } else {
                if (!f.isFile) throw java.io.FileNotFoundException("未找到待恢复的中转文件")
                android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }
    override fun openAssetFile(uri: Uri, mode: String): android.content.res.AssetFileDescriptor? {
        if (extractPatchedApkToken(uri.pathSegments) != null) {
            val pfd = openFile(uri, mode) ?: return null
            val len = try {
                val stat = pfd.statSize
                if (stat >= 0L) stat else android.content.res.AssetFileDescriptor.UNKNOWN_LENGTH
            } catch (_: Throwable) {
                android.content.res.AssetFileDescriptor.UNKNOWN_LENGTH
            }
            return android.content.res.AssetFileDescriptor(pfd, 0L, len)
        }
        return super.openAssetFile(uri, mode)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val ctx = context ?: return null
        val apkToken = extractPatchedApkToken(uri.pathSegments)
        if (apkToken != null) {
            val callingUid = Binder.getCallingUid()
            val identity = Binder.clearCallingIdentity()
            return try {
                val caller = try {
                    ctx.packageManager.getPackagesForUid(callingUid)?.joinToString(",") ?: "uid=$callingUid"
                } catch (_: Throwable) {
                    "uid=$callingUid"
                }
                val f = resolvePatchedApk(ctx, apkToken)
                if (f == null || !f.isFile) {
                    runCatching { io.github.doubaoa11y.patch.PatchLog.w(ctx, "安装器 query 未命中修补包：caller=$caller uri=$uri") }
                    return null
                }
                val cols = projection ?: arrayOf(
                    android.provider.OpenableColumns.DISPLAY_NAME,
                    android.provider.OpenableColumns.SIZE,
                )
                val row = arrayOfNulls<Any>(cols.size)
                for (i in cols.indices) {
                    row[i] = when (cols[i]) {
                        android.provider.OpenableColumns.DISPLAY_NAME -> PATCHED_APK_DISPLAY_NAME
                        android.provider.OpenableColumns.SIZE -> f.length()
                        "mime_type" -> "application/vnd.android.package-archive"
                        else -> null
                    }
                }
                runCatching { io.github.doubaoa11y.patch.PatchLog.i(ctx, "安装器已查询修补包信息：caller=$caller size=${f.length()} B") }
                android.database.MatrixCursor(cols, 1).apply { addRow(row) }
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }
        return null
    }
    override fun getType(uri: Uri): String? {
        val ctx = context ?: return null
        val apkToken = extractPatchedApkToken(uri.pathSegments)
        if (apkToken != null && resolvePatchedApk(ctx, apkToken) != null) {
            return "application/vnd.android.package-archive"
        }
        return null
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    companion object {
        val URI: Uri = Uri.parse("content://${ConfigSpec.SETTINGS_AUTHORITY}/config")
        const val REQUEST_ID = "configRequestId"
        private var sequence = 0L
        private val pendingKeys = mutableMapOf<String, Boolean>()

        @Synchronized
        fun recordRequest(intent: android.content.Intent, prefs: android.content.SharedPreferences): Long {
            val id = ++sequence
            intent.putExtra(REQUEST_ID, id)
            pendingKeys.clear()
            for (item in ConfigSpec.items) {
                if (item.key == "upperOperation" || item.key in ConfigSpec.TOOLBAR_TOGGLE_KEYS) {
                    val value = prefs.getBoolean(item.key, item.default)
                    pendingKeys[item.key] = value
                    intent.putExtra(item.key, value)
                }
            }
            return id
        }

        @Synchronized
        fun applyReceipt(prefs: android.content.SharedPreferences, id: Long, extras: Bundle) {
            // 旧模块没有序号，ordered broadcast 自身仍绑定发送时的 id。
            if (id != sequence || extras.getLong(REQUEST_ID, id) != id) return
            val edit = prefs.edit()
            for ((key, sent) in pendingKeys) {
                val value = extras.get("sync_$key") as? Boolean ?: continue
                if (prefs.getBoolean(key, sent) == sent) edit.putBoolean(key, value)
            }
            pendingKeys.clear()
            edit.apply()
        }

        private const val PATCHED_APK_DISPLAY_NAME = "DoubaoA11y_patched.apk"
        private val TOKEN_RE = Regex("^[a-f0-9]{32}$")

        private fun extractPatchedApkToken(segments: List<String>?): String? {
            if (segments == null || segments.size !in 2..3 || segments[0] != "patched_apk") return null
            if (segments.size == 3 && !segments[2].endsWith(".apk", ignoreCase = true)) return null
            val token = segments[1]
            return if (TOKEN_RE.matches(token)) token else null
        }

        fun issuePatchedApkUri(ctx: Context, apkFile: java.io.File): Uri {
            val token = java.util.UUID.randomUUID().toString().replace("-", "")
            val dir = apkFile.canonicalFile.parentFile!!
            require(dir.parentFile == io.github.doubaoa11y.patch.ApkPatcher.outputDir(ctx).canonicalFile && apkFile.name == "base.apk")
            java.io.File(dir, "grant-$token").createNewFile()
            return Uri.parse("content://${ConfigSpec.SETTINGS_AUTHORITY}/patched_apk/$token/$PATCHED_APK_DISPLAY_NAME")
        }

        private fun resolvePatchedApk(ctx: Context, token: String): java.io.File? {
            if (!TOKEN_RE.matches(token)) return null
            val root = io.github.doubaoa11y.patch.ApkPatcher.outputDir(ctx)
            val generation = root.listFiles()?.firstOrNull { java.io.File(it, "grant-$token").isFile } ?: return null
            return java.io.File(generation, "base.apk").takeIf { it.isFile }
        }

        fun xferUri(token: String): Uri =
            Uri.parse("content://${ConfigSpec.SETTINGS_AUTHORITY}/native_xfer/$token")

        fun xferFile(ctx: Context, token: String): java.io.File? {
            if (!TOKEN_RE.matches(token)) return null
            return java.io.File(java.io.File(ctx.cacheDir, "native_backup_xfer"), "$token.zip")
        }

        fun cleanupXfer(ctx: Context, token: String? = null) {
            val dir = java.io.File(ctx.cacheDir, "native_backup_xfer")
            if (token != null) {
                xferFile(ctx, token)?.delete()
            } else {
                dir.deleteRecursively()
            }
        }
    }
}
