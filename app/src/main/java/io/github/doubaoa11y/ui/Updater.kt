package io.github.doubaoa11y.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import io.github.doubaoa11y.ConfigSpec
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 检查更新：直连 GitHub（优先 GitHub Releases API，限流或失败时回退直连 GitHub Raw 的 data/update.json），
 * 不走第三方 CDN 缓存，连不上 GitHub 直接提示检查更新失败；下载 APK 支持 SHA-256 校验，不收集、不上传任何信息。
 */
object Updater {
    private const val REPO = "zzzttt1573/DoubaoA11y"
    private const val UPDATE_PATH = "data/update.json"
    private const val RAW_UPDATE_URL = "https://raw.githubusercontent.com/$REPO/main/$UPDATE_PATH"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases/latest"
    private const val MAX_MANIFEST_BYTES = 256 * 1024

    private val main = Handler(Looper.getMainLooper())

    private val dialogs = mutableMapOf<Activity, MutableMap<AlertDialog, () -> Unit>>()

    private fun track(act: Activity, dialog: AlertDialog, cancel: () -> Unit = {}) {
        dialogs.getOrPut(act) { mutableMapOf() }[dialog] = cancel
        dialog.setOnDismissListener {
            dialogs[act]?.let { group ->
                group.remove(dialog)
                if (group.isEmpty()) dialogs.remove(act)
            }
        }
    }

    fun detach(act: Activity) {
        dialogs.remove(act)?.forEach { (dialog, cancel) ->
            cancel()
            dialog.dismiss()
        }
        io.github.doubaoa11y.patch.InstallSession.detach(act)
    }

    private class Release(
        val version: String,
        val versionCode: Int,
        val notes: String,
        val apkUrls: List<String>,
        val apkSize: Long,
        val sha256: String,
    )

    private val versionPattern = Regex("""[vV]?([0-9]+(?:\.[0-9]+)*)(-?(?:alpha|beta|rc|preview|dev)[.-]?[0-9]*)?""", RegexOption.IGNORE_CASE)

    private fun parseVersion(raw: String): Pair<List<Int>, Boolean>? {
        val match = versionPattern.matchEntire(raw.trim()) ?: return null
        val numbers = match.groupValues[1].split('.').map { it.toIntOrNull() ?: return null }
        return numbers to match.groupValues[2].isNotEmpty()
    }

    private fun version(json: JSONObject, key: String): String {
        val value = (json.opt(key) as? String)?.trim().orEmpty()
        require(parseVersion(value) != null) { "无效版本号" }
        return value.removePrefix("v").removePrefix("V")
    }

    /** 版本号比较："1.1.10" > "1.1.9"；同主版本时正式版 "2.0.0" > 测试版 "2.0.0Beta" */
    fun newer(a: String, b: String): Boolean {
        val (x, preA) = parseVersion(a) ?: return false
        val (y, preB) = parseVersion(b) ?: return false
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }
            val q = y.getOrElse(i) { 0 }
            if (p != q) return p > q
        }
        return !preA && preB
    }

    private fun isNewer(r: Release): Boolean {
        if (r.versionCode > 0 && r.versionCode != ConfigSpec.MODULE_VERSION_CODE) {
            return r.versionCode > ConfigSpec.MODULE_VERSION_CODE
        }
        return newer(r.version, ConfigSpec.MODULE_VERSION_NAME)
    }

    private fun say(act: Activity, status: TextView, msg: String) {
        if (act.isFinishing || act.isDestroyed) return
        status.visibility = android.view.View.VISIBLE
        status.text = "更新：$msg"
        status.announceForAccessibility(msg)
    }

    private fun buildProgressDialog(
        act: Activity,
        title: String,
        initialMsg: String,
        indeterminate: Boolean,
        onCancel: () -> Unit
    ): Triple<AlertDialog, TextView, ProgressBar> {
        val pad = (20 * act.resources.displayMetrics.density).toInt()
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad / 2)
        }
        val msgView = TextView(act).apply {
            text = initialMsg
            textSize = 16f
            isFocusable = true
            setPadding(0, 0, 0, pad / 2)
        }
        val bar = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = indeterminate
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        box.addView(msgView)
        box.addView(bar)
        val dialog = AlertDialog.Builder(act)
            .setTitle(title)
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("取消") { d, _ ->
                d.dismiss()
                onCancel()
            }
            .create()
        track(act, dialog, onCancel)
        dialog.show()
        return Triple(dialog, msgView, bar)
    }

    fun check(act: Activity, status: TextView) {
        say(act, status, "正在检查更新…")
        val cancelled = AtomicBoolean(false)
        val connRef = AtomicReference<HttpURLConnection?>(null)
        val (dialog, _, _) = buildProgressDialog(
            act = act,
            title = "检查更新",
            initialMsg = "正在检查更新，请稍候…",
            indeterminate = true
        ) {
            cancelled.set(true)
            say(act, status, "已取消检查更新")
            Thread { try { connRef.getAndSet(null)?.disconnect() } catch (_: Throwable) {} }.start()
        }

        Thread {
            val r = try {
                fetch(cancelled, connRef)
            } catch (t: Throwable) {
                null
            }
            main.post {
                try { if (dialog.isShowing) dialog.dismiss() } catch (_: Throwable) {}
                if (act.isFinishing || act.isDestroyed || cancelled.get()) return@post
                when {
                    r == null -> AlertDialog.Builder(act)
                        .setTitle("检查更新失败")
                        .setMessage("连不上 GitHub。可能是网络不好，或者 GitHub 在你的网络下打不开。请稍后再试，或者加入QQ群获取最新版。\n$QQ_LINE")
                        .setPositiveButton("复制QQ群号") { _, _ -> copyQq(act) }
                        .setNegativeButton("关闭", null)
                        .show().also { track(act, it); say(act, status, "检查更新失败") }
                    !isNewer(r) -> {
                        say(act, status, "已是最新版本 ${ConfigSpec.MODULE_VERSION_NAME}")
                        AlertDialog.Builder(act)
                            .setTitle("已是最新版本")
                            .setMessage("当前版本 ${ConfigSpec.MODULE_VERSION_NAME}，最新发布版本为 ${r.version}，不用更新。")
                            .setPositiveButton("确定", null)
                            .show().also { track(act, it) }
                    }
                    else -> offer(act, status, r)
                }
            }
        }.start()
    }

    /**
     * 纯直连 GitHub 检查更新：
     * 1. 优先直连 GitHub Releases API（实时最新，不受任何缓存影响）；
     * 2. API 失败（如匿名限流）时回退直连 GitHub Raw 的 data/update.json；
     * 3. 均失败则直接抛错，由外层提示“检查更新失败 / 连不上 GitHub”，绝不使用可能过期的第三方 CDN 缓存。
     */
    private fun fetch(cancelled: AtomicBoolean, connRef: AtomicReference<HttpURLConnection?>): Release {
        if (cancelled.get()) error("已取消")
        val apiRes = runCatching { fetchFromGitHubApi(cancelled, connRef) }.getOrNull()
        if (apiRes != null) return apiRes
        if (cancelled.get()) error("已取消")
        val rawRes = runCatching { fetchManifest(RAW_UPDATE_URL, cancelled, connRef, timeoutMs = 6000) }.getOrNull()
        if (rawRes != null) return rawRes
        if (cancelled.get()) error("已取消")
        error("连不上 GitHub")
    }

    private fun readManifest(connection: HttpURLConnection, cancelled: AtomicBoolean): JSONObject {
        val baos = java.io.ByteArrayOutputStream()
        connection.inputStream.use { input ->
            val buf = ByteArray(4096)
            var total = 0
            while (true) {
                if (cancelled.get()) error("已取消")
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_MANIFEST_BYTES) error("清单过大")
                baos.write(buf, 0, n)
            }
        }
        return JSONObject(baos.toString("UTF-8").removePrefix("\uFEFF"))
    }

    private fun fetchManifest(
        urlStr: String,
        cancelled: AtomicBoolean,
        connRef: AtomicReference<HttpURLConnection?>,
        timeoutMs: Int = 4000,
        onConnect: (HttpURLConnection) -> Unit = {},
    ): Release {
        val c = URL(urlStr).openConnection() as HttpURLConnection
        connRef.set(c)
        onConnect(c)
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.useCaches = false
        c.setRequestProperty("User-Agent", "DoubaoA11y/${ConfigSpec.MODULE_VERSION_NAME}")
        c.setRequestProperty("Cache-Control", "no-cache")
        try {
            if (cancelled.get()) error("已取消")
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            val j = readManifest(c, cancelled)
            val version = version(j, "version")
            val versionCode = j.optInt("versionCode", 0)
            val notes = j.optString("notes", "")
            val size = j.optLong("apkSize", 0L)
            val sha256 = j.optString("sha256", "").trim().lowercase()
                .also { require(it.isEmpty() || it.matches(Regex("^[a-f0-9]{64}$"))) { "无效 SHA-256" } }
            val urls = LinkedHashSet<String>()
            val arr = j.optJSONArray("apkUrls")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val u = arr.optString(i, "").trim()
                    if (u.startsWith("https://")) urls.add(u)
                }
            }
            val single = j.optString("apkUrl", "").trim()
            if (single.startsWith("https://")) urls.add(single)
            return Release(version, versionCode, notes, urls.toList(), size, sha256)
        } finally {
            connRef.compareAndSet(c, null)
            c.disconnect()
        }
    }

    private fun fetchFromGitHubApi(
        cancelled: AtomicBoolean,
        connRef: AtomicReference<HttpURLConnection?>,
        onConnect: (HttpURLConnection) -> Unit = {},
    ): Release {
        val c = URL(API).openConnection() as HttpURLConnection
        connRef.set(c)
        onConnect(c)
        c.connectTimeout = 5000
        c.readTimeout = 6000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "DoubaoA11y-updater")
        try {
            if (cancelled.get()) error("已取消")
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            val j = readManifest(c, cancelled)
            val assets = j.optJSONArray("assets")
            val urls = ArrayList<String>()
            var size = 0L
            var sha256 = ""
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk", true)) {
                        val u = a.optString("browser_download_url", "").trim()
                        if (u.startsWith("https://")) urls.add(u)
                        size = a.optLong("size")
                        val digest = a.optString("digest", "").trim()
                        if (digest.startsWith("sha256:", true)) {
                            sha256 = digest.substring(7).lowercase()
                                .also { require(it.isEmpty() || it.matches(Regex("^[a-f0-9]{64}$"))) { "无效 SHA-256" } }
                        }
                        break
                    }
                }
            }
            return Release(version(j, "tag_name"), 0, j.optString("body"), urls, size, sha256)
        } finally {
            connRef.compareAndSet(c, null)
            c.disconnect()
        }
    }

    private fun offer(act: Activity, status: TextView, r: Release) {
        say(act, status, "发现新版本 ${r.version}")
        val notes = r.notes.replace("\r", "").replace(Regex("[#*`]"), "").trim().take(1500)
        val size = if (r.apkSize > 0) "，安装包 ${"%.1f".format(r.apkSize / 1048576.0)} MB" else ""
        val b = AlertDialog.Builder(act)
            .setTitle("发现新版本 ${r.version}")
            .setMessage("当前 ${ConfigSpec.MODULE_VERSION_NAME}，最新 ${r.version}$size。\n\n" +
                notes)
            .setNegativeButton("以后再说", null)
        if (r.apkUrls.isNotEmpty()) b.setPositiveButton("下载并安装") { _, _ -> download(act, status, r) }
        else b.setPositiveButton("用浏览器打开下载页") { _, _ -> openPage(act) }
        b.show().also { track(act, it) }
    }

    private const val QQ_LINE = "QQ群：乐天的居家小屋，${SettingsActivity.QQ_GROUP}"

    /** 把 QQ 群号复制到剪贴板 */
    private fun copyQq(act: Activity) {
        try {
            val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("QQ群号", SettingsActivity.QQ_GROUP))
            Toast.makeText(act, "已复制QQ群号 ${SettingsActivity.QQ_GROUP}", Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
        }
    }

    fun openPage(act: Activity) {
        try {
            act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_PAGE)))
        } catch (_: Throwable) {
        }
    }

    private fun download(act: Activity, status: TextView, r: Release) {
        // 安卓 8 起要允许“安装未知应用”
        if (Build.VERSION.SDK_INT >= 26 && !act.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(act)
                .setTitle("需要允许安装应用")
                .setMessage("请允许安装应用")
                .setPositiveButton("去打开") { _, _ ->
                    try {
                        act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}")))
                    } catch (_: Throwable) {
                    }
                }
                .setNegativeButton("取消", null)
                .show().also { track(act, it) }
            return
        }
        say(act, status, "正在下载 ${r.version}…")
        val file = File(act.cacheDir, "update-${java.util.UUID.randomUUID()}.apk")
        val cancelled = AtomicBoolean(false)
        val connRef = AtomicReference<HttpURLConnection?>(null)
        val (dialog, msgView, bar) = buildProgressDialog(
            act = act,
            title = "正在下载 ${r.version}",
            initialMsg = "正在连接下载服务器…",
            indeterminate = true
        ) {
            cancelled.set(true)
            say(act, status, "已取消下载")
            Thread {
                try { connRef.getAndSet(null)?.disconnect() } catch (_: Throwable) {}
                try { file.delete() } catch (_: Throwable) {}
            }.start()
        }

        Thread {
            var ok = false
            var lastErr = ""
            for ((idx, apkUrl) in r.apkUrls.withIndex()) {
                if (cancelled.get()) break
                if (idx > 0) {
                    main.post {
                        if (!act.isFinishing && !act.isDestroyed && !cancelled.get()) {
                            bar.isIndeterminate = true
                            msgView.text = "正在切换备用线路下载（第 ${idx + 1}/${r.apkUrls.size} 条）…"
                        }
                    }
                }
                val attemptOk = try {
                    try { file.delete() } catch (_: Throwable) {}
                    save(apkUrl, file, cancelled, connRef) { pct, done, total ->
                        main.post {
                            if (act.isFinishing || act.isDestroyed || cancelled.get()) return@post
                            if (total > 0) {
                                bar.isIndeterminate = false
                                bar.progress = pct
                                val doneMb = "%.1f".format(done / 1048576.0)
                                val totalMb = "%.1f".format(total / 1048576.0)
                                msgView.text = "正在下载 $pct%（$doneMb MB / $totalMb MB）"
                                status.text = "更新：正在下载 ${r.version}，$pct%"
                            } else {
                                bar.isIndeterminate = true
                                val doneMb = "%.1f".format(done / 1048576.0)
                                msgView.text = "正在下载（已下载 $doneMb MB）"
                                status.text = "更新：正在下载 ${r.version}…"
                            }
                        }
                    }
                    require(file.length() > 0 && (r.apkSize <= 0 || file.length() == r.apkSize)) { "安装包大小不符" }
                    if (r.sha256.isNotEmpty() && !verifySha256(file, r.sha256)) {
                        error("安装包校验失败")
                    }
                    true
                } catch (t: Throwable) {
                    lastErr = t.message.orEmpty()
                    false
                }
                if (attemptOk) {
                    ok = true
                    break
                }
            }
            main.post {
                try { if (dialog.isShowing) dialog.dismiss() } catch (_: Throwable) {}
                if (act.isFinishing || act.isDestroyed || cancelled.get()) {
                    try { file.delete() } catch (_: Throwable) {}
                    return@post
                }
                if (!ok) {
                    try { file.delete() } catch (_: Throwable) {}
                    val reason = if (lastErr == "安装包校验失败") "安装包 SHA-256 校验未通过。" else "下载中断了。"
                    AlertDialog.Builder(act)
                        .setTitle("下载失败")
                        .setMessage("${reason}可以再试一次，或者加入QQ群获取最新版。\n$QQ_LINE")
                        .setPositiveButton("复制QQ群号") { _, _ -> copyQq(act) }
                        .setNegativeButton("关闭", null)
                        .show().also { track(act, it) }
                    say(act, status, "下载失败")
                    return@post
                }
                say(act, status, "下载完成，正在打开安装界面")
                install(act, status, file)
            }
        }.start()
    }

    private fun verifySha256(file: File, expectedHex: String): Boolean {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        val actual = md.digest().joinToString("") { "%02x".format(it) }
        return actual.equals(expectedHex, ignoreCase = true)
    }

    private fun save(
        url: String,
        out: File,
        cancelled: AtomicBoolean,
        connRef: AtomicReference<HttpURLConnection?>,
        progress: (pct: Int, done: Long, total: Long) -> Unit
    ) {
        var u = URL(url)
        // GitHub 附件会跳转到另一个域名，HttpURLConnection 跨协议不自动跳，这里手动跟
        repeat(5) {
            if (cancelled.get()) error("已取消")
            val c = u.openConnection() as HttpURLConnection
            connRef.set(c)
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "DoubaoA11y-updater")
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    u = URL(u, c.getHeaderField("Location") ?: error("跳转没有地址"))
                    return@repeat
                }
                if (code != 200) error("HTTP $code")
                val total = c.contentLengthLong
                var done = 0L
                var lastPct = -1
                var lastReportMs = 0L
                c.inputStream.use { input ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (cancelled.get()) error("已取消")
                            val n = input.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                                if (pct != lastPct && (pct == 100 || now - lastReportMs >= 150L)) {
                                    lastPct = pct
                                    lastReportMs = now
                                    progress(pct, done, total)
                                }
                            } else if (now - lastReportMs >= 300L) {
                                lastReportMs = now
                                progress(0, done, -1L)
                            }
                        }
                    }
                }
                if (cancelled.get()) error("已取消")
                if (done <= 0 || done != out.length() || (total >= 0 && done != total)) error("没下完")
                return
            } finally {
                connRef.compareAndSet(c, null)
                c.disconnect()
            }
        }
        error("跳转次数太多")
    }

    /** 用系统安装器装下载好的 apk（安装会结束本应用，下次打开时提示强行停止豆包） */
    private fun install(act: Activity, status: TextView, file: File) {
        io.github.doubaoa11y.patch.InstallSession.install(act, act.packageName,
            PackageInstaller.SessionParams.MODE_FULL_INSTALL, listOf(file), afterCopy = { file.delete() }) { _, _, msg ->
            say(act, status, msg)
        }
    }

    /** 打开豆包输入法的应用信息页（里面有“强行停止”） */
    fun openDoubaoInfo(act: Activity) {
        try {
            act.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ConfigSpec.TARGET_PACKAGE}")))
        } catch (_: Throwable) {
        }
    }

    fun forceStopDialog(act: Activity, title: String, msg: String) {
        AlertDialog.Builder(act)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("打开豆包输入法的应用信息") { _, _ -> openDoubaoInfo(act) }
            .setNegativeButton("知道了", null)
            .show().also { track(act, it) }
    }

    /** 这次打开时模块版本比上次高（刚更新过）：提示强行停止豆包输入法 */
    fun afterUpdate(act: SettingsActivity, integrated: Boolean) {
        val prefs = act.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val last = prefs.getInt("lastVersionCode", 0)
        prefs.edit().putInt("lastVersionCode", ConfigSpec.MODULE_VERSION_CODE).apply()

        if (last != 0 && last < ConfigSpec.MODULE_VERSION_CODE) {
            if (integrated) {
                AlertDialog.Builder(act).setTitle("模块已更新")
                    .setMessage("请一键注入新模块以同步豆包输入法。")
                    .setPositiveButton("一键注入新模块") { _, _ -> act.openPatchWizard() }
                    .setNegativeButton("稍后", null).show().also { track(act, it) }
                return
            }
            forceStopDialog(act, "模块已更新到 ${ConfigSpec.MODULE_VERSION_NAME}",
                "请在豆包输入法的应用信息里点“强行停止”，再弹出一次键盘，新版本才会生效。")
        }
    }
}
