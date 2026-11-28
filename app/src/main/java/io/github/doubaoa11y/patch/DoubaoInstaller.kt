package io.github.doubaoa11y.patch

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import io.github.doubaoa11y.ConfigSpec
import io.github.doubaoa11y.SettingsProvider
import io.github.doubaoa11y.ui.AppStorage
import java.io.File
import java.security.SecureRandom
import java.util.WeakHashMap

/**
 * 豆包输入法的卸载、单包/分包安装与系统输入法设置跳转封装。
 *
 * 兼容性策略：
 * - 单 APK 优先通过标准 Intent.ACTION_VIEW + SettingsProvider 一次性授权 content:// URI 拉起系统安装器，
 *   避免部分 Android 10–13 / 国产 ROM 对 PackageInstaller.Session 回调弹窗的拦截（直接报 STATUS_FAILURE_ABORTED）；
 * - 分包（Split APKs）或 ACTION_VIEW 失败时走加固后的 PackageInstaller.Session：
 *   前台 Activity 启动 confirm Intent 不盲目加 FLAG_ACTIVITY_NEW_TASK，API 33+ 使用带 sessionId 校验的
 *   RECEIVER_EXPORTED 随机 action，并完整记录与透出 EXTRA_STATUS_MESSAGE。
 */
object DoubaoInstaller {
    private const val ACTION_INSTALL_PREFIX = "io.github.doubaoa11y.DOUBAO_INSTALL_RESULT"
    private const val APK_MIME = "application/vnd.android.package-archive"
    private val KNOWN_SYSTEM_INSTALLERS = listOf(
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.miui.packageinstaller",
        "com.oplus.appdetail",
        "com.coloros.appdetail",
        "com.vivo.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.huawei.appmarket",
        "com.hihonor.appmarket"
    )
    private val main = Handler(Looper.getMainLooper())
    private val activeReceivers = WeakHashMap<Activity, BroadcastReceiver>()

    fun canInstall(context: Context): Boolean {
        return Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()
    }

    fun openInstallPermissionSettings(act: Activity) {
        try {
            act.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}"))
            )
        } catch (_: Throwable) {
            try {
                act.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${act.packageName}"))
                )
            } catch (_: Throwable) {
            }
        }
    }

    fun requestUninstallDoubao(act: Activity): Boolean {
        return try {
            PatchLog.i(act, "请求卸载豆包输入法（${ConfigSpec.TARGET_PACKAGE}）")
            val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:${ConfigSpec.TARGET_PACKAGE}"))
            act.startActivity(intent)
            true
        } catch (t: Throwable) {
            PatchLog.e(act, "打开系统卸载界面失败", t)
            false
        }
    }

    fun openInputMethodSettings(act: Activity): Boolean {
        return try {
            act.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun showImePicker(act: Activity): Boolean {
        return try {
            val imm = act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun detach(act: Activity) {
        val r = activeReceivers.remove(act) ?: return
        try {
            act.unregisterReceiver(r)
        } catch (_: Throwable) {
        }
    }

    /**
     * 安装修补后的豆包 APK（自动选择最兼容的安装通道）。
     *
     * onStatus(done, ok, msg):
     * - done=false, ok=true：已调起系统安装界面，等待用户在系统弹窗中确认；
     * - done=true, ok=true：已确认安装完成（仅 Session 回调）；
     * - done=true, ok=false：拉起或安装失败，msg 含详细原因。
     */
    fun installPrepared(
        act: Activity,
        prepared: ApkPatcher.PreparedOutput,
        forceSession: Boolean = false,
        onStatus: (done: Boolean, ok: Boolean, msg: String) -> Unit
    ) {
        val hasInstalledSplits = try {
            act.packageManager.getApplicationInfo(ConfigSpec.TARGET_PACKAGE, 0).splitSourceDirs?.isNotEmpty() == true
        } catch (_: Throwable) {
            false
        }
        val canUseSingleView = !forceSession &&
            prepared.splitApks.isEmpty() &&
            (!prepared.isHotUpdate || !hasInstalledSplits)

        PatchLog.i(
            act,
            "准备安装修补包：hotUpdate=${prepared.isHotUpdate} splits=${prepared.splitApks.size} " +
                "installedSplits=$hasInstalledSplits forceSession=$forceSession canUseSingleView=$canUseSingleView " +
                "baseSize=${prepared.baseApk.length() / (1024 * 1024)}MB"
        )

        if (canUseSingleView) {
            val err = launchActionViewInstall(act, prepared.baseApk)
            if (err == null) {
                onStatus(
                    false,
                    true,
                    "已打开系统安装界面，请在弹出的界面中确认安装，安装完成后返回本页。" +
                        "如未弹出安装界面，请点击下方“换用系统会话模式安装”或“导出修补安装包到文件夹”手动安装。"
                )
                return
            }
            PatchLog.w(act, "ACTION_VIEW 拉起安装界面失败（$err），降级改用 PackageInstaller.Session")
        }

        installViaSession(
            act,
            prepared,
            allowActionViewFallback = !forceSession && prepared.splitApks.isEmpty() && !canUseSingleView,
            onStatus = onStatus
        )
    }

    /**
     * 使用标准 Intent.ACTION_VIEW + SettingsProvider 一次性授权 .apk URI 拉起系统安装器（仅单 APK）。
     * - 附带 ClipData、FLAG_GRANT_READ_URI_PERMISSION 与 FLAG_ACTIVITY_NEW_TASK（避免小米等 ROM 同栈中转自闭）；
     * - 优先定向解析到的系统安装器组件，避免被第三方文件管理器劫持或弹出无关选择框；
     * - 详细记录解析结果与最终启动目标到 PatchLog。
     * 返回 null 表示成功拉起，否则返回错误原因。
     */
    fun launchActionViewInstall(act: Activity, apkFile: File): String? {
        if (!apkFile.isFile) {
            val msg = "待安装的修补包文件不存在：${apkFile.absolutePath}"
            PatchLog.e(act, msg)
            return msg
        }
        val uri = SettingsProvider.issuePatchedApkUri(act, apkFile)

        fun buildViewIntent(): Intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addCategory(Intent.CATEGORY_DEFAULT)
            clipData = ClipData.newRawUri("apk", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        @Suppress("DEPRECATION")
        fun buildInstallIntent(): Intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri
            clipData = ClipData.newRawUri("apk", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, false)
        }

        val viewProbe = buildViewIntent()
        val installProbe = buildInstallIntent()
        val viewResolvers = queryResolvers(act, viewProbe)
        val installResolvers = queryResolvers(act, installProbe)
        val defaultView = resolveDefaultComponent(act, viewProbe)
        val defaultInstall = resolveDefaultComponent(act, installProbe)

        PatchLog.i(
            act,
            "安装器解析：uri=$uri viewDefault=$defaultView viewCandidates=${formatResolvers(viewResolvers)} " +
                "installDefault=$defaultInstall installCandidates=${formatResolvers(installResolvers)}"
        )

        grantUriToPackages(act, uri, viewResolvers + installResolvers)

        val attempts = mutableListOf<Pair<String, Intent>>()
        val sysView = pickSystemInstaller(viewResolvers)
        if (sysView != null) {
            val ai = sysView.activityInfo
            attempts.add(
                "VIEW(${ai.packageName}/${ai.name})" to buildViewIntent().setClassName(ai.packageName, ai.name)
            )
        }
        attempts.add("VIEW(generic)" to buildViewIntent())

        val sysInstall = pickSystemInstaller(installResolvers)
        if (sysInstall != null) {
            val ai = sysInstall.activityInfo
            attempts.add(
                "INSTALL_PACKAGE(${ai.packageName}/${ai.name})" to buildInstallIntent().setClassName(ai.packageName, ai.name)
            )
        }
        attempts.add("INSTALL_PACKAGE(generic)" to buildInstallIntent())

        var lastError: String? = null
        for ((label, intent) in attempts) {
            try {
                act.startActivity(intent)
                PatchLog.i(act, "已通过 $label 拉起系统安装界面（size=${apkFile.length()} B）")
                return null
            } catch (t: Throwable) {
                lastError = "$label: ${t.javaClass.simpleName}: ${t.message}"
                PatchLog.e(act, "通过 $label 拉起安装界面失败", t)
            }
        }
        return lastError ?: "无法打开系统安装界面"
    }

    private fun queryResolvers(act: Activity, intent: Intent): List<ResolveInfo> {
        return try {
            val flags = PackageManager.MATCH_DEFAULT_ONLY
            if (Build.VERSION.SDK_INT >= 33) {
                act.packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                act.packageManager.queryIntentActivities(intent, flags)
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun resolveDefaultComponent(act: Activity, intent: Intent): String {
        return try {
            val flags = PackageManager.MATCH_DEFAULT_ONLY
            val ri = if (Build.VERSION.SDK_INT >= 33) {
                act.packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                act.packageManager.resolveActivity(intent, flags)
            }
            val ai = ri?.activityInfo ?: return "none"
            "${ai.packageName}/${ai.name}"
        } catch (_: Throwable) {
            "err"
        }
    }

    private fun formatResolvers(infos: List<ResolveInfo>): String {
        if (infos.isEmpty()) return "[]"
        return infos.joinToString(prefix = "[", postfix = "]") { ri ->
            val ai = ri.activityInfo
            val sys = ((ai?.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM) != 0
            "${ai?.packageName}/${ai?.name}(sys=$sys)"
        }
    }

    private fun pickSystemInstaller(infos: List<ResolveInfo>): ResolveInfo? {
        val valid = infos.filter { ri ->
            val ai = ri.activityInfo ?: return@filter false
            val pkg = ai.packageName ?: return@filter false
            pkg != "android" && ai.exported
        }
        // 1. 优先系统应用且包名命中已知系统安装器或包含 packageinstaller
        valid.firstOrNull { ri ->
            val ai = ri.activityInfo
            val isSys = ((ai.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM) != 0
            val pkg = ai.packageName
            isSys && (pkg in KNOWN_SYSTEM_INSTALLERS || pkg.contains("packageinstaller", ignoreCase = true))
        }?.let { return it }
        // 2. 其次系统应用且包名包含 installer
        valid.firstOrNull { ri ->
            val ai = ri.activityInfo
            val isSys = ((ai.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM) != 0
            isSys && ai.packageName.contains("installer", ignoreCase = true)
        }?.let { return it }
        return null
    }

    private fun grantUriToPackages(act: Activity, uri: Uri, infos: List<ResolveInfo>) {
        val pkgs = LinkedHashSet<String>()
        for (ri in infos) {
            ri.activityInfo?.packageName?.takeIf { it.isNotEmpty() && it != "android" }?.let { pkgs.add(it) }
        }
        pkgs.addAll(KNOWN_SYSTEM_INSTALLERS)
        for (pkg in pkgs) {
            runCatching {
                act.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    private fun installViaSession(
        act: Activity,
        prepared: ApkPatcher.PreparedOutput,
        allowActionViewFallback: Boolean,
        onStatus: (done: Boolean, ok: Boolean, msg: String) -> Unit
    ) {
        val files = mutableListOf<Pair<String, File>>()
        files.add("base.apk" to prepared.baseApk)
        prepared.splitApks.forEachIndexed { idx, f ->
            files.add("split_$idx.apk" to f)
        }

        val mode = if (prepared.isHotUpdate) {
            PackageInstaller.SessionParams.MODE_INHERIT_EXISTING
        } else {
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        }

        Thread {
            try {
                val pi = act.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(mode).apply {
                    setAppPackageName(ConfigSpec.TARGET_PACKAGE)
                    setSize(files.sumOf { it.second.length() })
                }
                val sessionId = pi.createSession(params)
                PatchLog.i(act, "已创建 PackageInstaller.Session id=$sessionId mode=$mode files=${files.size}")
                val session = pi.openSession(sessionId)
                try {
                    for ((name, file) in files) {
                        session.openWrite(name, 0, file.length()).use { out ->
                            file.inputStream().use { it.copyTo(out, 64 * 1024) }
                            session.fsync(out)
                        }
                    }

                    main.post {
                        if (act.isFinishing) {
                            try { session.abandon() } catch (_: Throwable) {}
                            try { session.close() } catch (_: Throwable) {}
                            return@post
                        }
                        detach(act)
                        val nonce = ByteArray(6).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
                        val action = "$ACTION_INSTALL_PREFIX.$sessionId.$nonce"
                        val receiver = object : BroadcastReceiver() {
                            override fun onReceive(c: Context, intent: Intent) {
                                val rxSessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
                                if (rxSessionId != sessionId) return
                                val status = intent.getIntExtra(
                                    PackageInstaller.EXTRA_STATUS,
                                    PackageInstaller.STATUS_FAILURE
                                )
                                val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty().trim()
                                PatchLog.i(act, "收到安装会话回调：session=$sessionId status=$status detail=$detail")
                                when (status) {
                                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                                        @Suppress("DEPRECATION")
                                        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                                        if (confirm != null) {
                                            val started = startConfirmActivity(act, confirm)
                                            if (started == null) {
                                                onStatus(false, true, "请在系统弹窗中确认安装豆包输入法")
                                            } else {
                                                detach(act)
                                                if (allowActionViewFallback && launchActionViewInstall(act, prepared.baseApk) == null) {
                                                    onStatus(false, true, "已改用系统安装界面打开，请在弹窗中确认安装并返回本页")
                                                } else {
                                                    onStatus(true, false, "无法打开系统安装界面：$started")
                                                }
                                            }
                                        } else {
                                            PatchLog.w(act, "STATUS_PENDING_USER_ACTION 缺少 EXTRA_INTENT")
                                            detach(act)
                                            if (allowActionViewFallback && launchActionViewInstall(act, prepared.baseApk) == null) {
                                                onStatus(false, true, "已改用系统安装界面打开，请在弹窗中确认安装并返回本页")
                                            } else {
                                                onStatus(true, false, "系统安装器未返回确认界面，请尝试“导出安装包到文件夹”后手动安装")
                                            }
                                        }
                                    }
                                    PackageInstaller.STATUS_SUCCESS -> {
                                        detach(act)
                                        ApkPatcher.clearPrepared(act)
                                        onStatus(true, true, "修补版豆包输入法安装成功")
                                    }
                                    PackageInstaller.STATUS_FAILURE_ABORTED -> {
                                        detach(act)
                                        if (allowActionViewFallback && launchActionViewInstall(act, prepared.baseApk) == null) {
                                            PatchLog.w(act, "Session 返回 ABORTED，已自动降级为 ACTION_VIEW")
                                            onStatus(false, true, "系统会话安装被取消，已改用标准安装界面打开，请确认安装后返回本页")
                                        } else {
                                            val extra = if (detail.isNotEmpty()) "（系统返回：$detail）" else "（状态码 $status，可能被系统拦截或手动取消）"
                                            onStatus(true, false, "已取消安装$extra。可尝试“导出安装包到文件夹”后用文件管理器手动安装。")
                                        }
                                    }
                                    else -> {
                                        detach(act)
                                        val friendly = when (status) {
                                            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                                                "安装冲突：请先卸载原版豆包输入法，再点安装"
                                            PackageInstaller.STATUS_FAILURE_STORAGE ->
                                                "手机存储空间不足，请清理空间后重试"
                                            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                                                "安装包不兼容当前系统或架构"
                                            PackageInstaller.STATUS_FAILURE_INVALID ->
                                                "安装包解析或校验未通过"
                                            else -> "安装失败（状态码 $status）"
                                        }
                                        val suffix = if (detail.isNotEmpty()) "：$detail" else ""
                                        onStatus(true, false, "$friendly$suffix")
                                    }
                                }
                            }
                        }
                        activeReceivers[act] = receiver
                        val filter = IntentFilter(action)
                        // Android 13 (API 33) 上 system_server 通过 PendingIntent 投递到动态 RECEIVER_NOT_EXPORTED
                        // 在部分 ROM 存在拦截问题；此处用每次会话唯一的随机 action + 校验 EXTRA_SESSION_ID，配合 RECEIVER_EXPORTED
                        if (Build.VERSION.SDK_INT >= 33) {
                            act.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                        } else {
                            act.registerReceiver(receiver, filter)
                        }
                        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                        val pending = PendingIntent.getBroadcast(
                            act,
                            sessionId,
                            Intent(action).setPackage(act.packageName),
                            piFlags
                        )
                        session.commit(pending.intentSender)
                        session.close()
                        PatchLog.i(act, "已提交 PackageInstaller.Session id=$sessionId")
                    }
                } catch (t: Throwable) {
                    try { session.abandon() } catch (_: Throwable) {}
                    try { session.close() } catch (_: Throwable) {}
                    throw t
                }
            } catch (t: Throwable) {
                PatchLog.e(act, "PackageInstaller.Session 提交失败", t)
                main.post {
                    if (!act.isFinishing) {
                        onStatus(true, false, "打开安装器失败：${t.message ?: t.javaClass.simpleName}")
                    }
                }
            }
        }.start()
    }

    /**
     * 前台 Activity 直接启动 confirm Intent（不盲目加 FLAG_ACTIVITY_NEW_TASK，避免 Android 10–13 ROM 直接取消会话）；
     * 若抛异常再加 FLAG_ACTIVITY_NEW_TASK 重试一次。返回 null 表示成功启动。
     */
    private fun startConfirmActivity(act: Activity, confirm: Intent): String? {
        try {
            act.startActivity(confirm)
            PatchLog.i(act, "已由前台 Activity 直接拉起系统安装确认页")
            return null
        } catch (t1: Throwable) {
            PatchLog.w(act, "前台直接拉起确认页失败（${t1.javaClass.simpleName}: ${t1.message}），尝试附加 NEW_TASK")
            return try {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                act.startActivity(confirm)
                PatchLog.i(act, "已通过 NEW_TASK 拉起系统安装确认页")
                null
            } catch (t2: Throwable) {
                PatchLog.e(act, "附加 NEW_TASK 拉起系统安装确认页仍失败", t2)
                t2.message ?: t2.javaClass.simpleName
            }
        }
    }

    /** 将修补好的安装包导出到 内部存储/豆包输入法无障碍/安装包/ 供特殊机型通过文件管理器手动安装 */
    fun exportPreparedApk(ctx: Context, prepared: ApkPatcher.PreparedOutput): File {
        val dir = AppStorage.apksDir()
        dir.mkdirs()
        val name = "豆包输入法_${prepared.doubaoVersionName}_含无障碍${ConfigSpec.MODULE_VERSION_NAME}.apk"
        val target = AppStorage.uniqueFile(dir, name, ".apk")
        val published = prepared.baseApk.inputStream().use { input ->
            AppStorage.publish(target) { output ->
                input.copyTo(output, 64 * 1024)
            }
        }
        PatchLog.i(ctx, "已导出修补安装包到：${published.absolutePath}（${published.length()} B）")
        return published
    }
}
