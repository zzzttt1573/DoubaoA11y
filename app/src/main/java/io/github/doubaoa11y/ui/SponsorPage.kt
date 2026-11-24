package io.github.doubaoa11y.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.doubaoa11y.ConfigSpec
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 赞赏与贡献页：
 * 1. 内置赞赏码图片，点击图片或下方按钮可保存到系统相册；
 * 2. 赞赏名单从 GitHub 仓库的 data/sponsors.json 实时拉取（jsDelivr 国内加速多源回退），
 *    默认展示前 5 位，支持点击按钮展开/收起；
 * 3. 贡献名单从 GitHub 仓库的 data/contributors.json 实时拉取，本地缓存保底，
 *    无需更新模块即可随时增改名单。
 */
object SponsorPage {
    private const val ASSET_QR = "sponsor_qr.png"
    private const val PREF_CACHE = "sponsorListCache"
    private const val PREF_CONTRIB_CACHE = "contributorListCache"
    private const val MAX_BYTES = 256 * 1024
    private const val DEFAULT_SPONSOR_LIMIT = 5
    private const val REPO = "zzzttt1573/DoubaoA11y"
    private const val SPONSOR_PATH = "data/sponsors.json"
    private const val CONTRIB_PATH = "data/contributors.json"

    private val main = Handler(Looper.getMainLooper())

    private data class SponsorItem(
        val name: String,
        val note: String,
    )

    private data class SponsorData(
        val updatedAt: String,
        val notice: String,
        val sponsors: List<SponsorItem>,
    )

    private data class ContributorItem(
        val name: String,
        val qq: String,
        val github: String,
        val githubUrl: String,
        val contributions: List<String>,
    )

    private data class ContributorData(
        val updatedAt: String,
        val notice: String,
        val contributors: List<ContributorItem>,
    )

    fun fill(a: SettingsActivity, root: LinearLayout) {
        root.addView(
            a.note(
                "感谢使用豆包输入法无障碍模块！如果这个模块帮到了你，期待你的赞赏支持，帮助我持续维护。让我们一起把模块做的更好，你的鼓励是我前进最大的动力！"
            )
        )

        val qrBitmap = runCatching {
            a.assets.open(ASSET_QR).use { BitmapFactory.decodeStream(it) }
        }.getOrNull()

        if (qrBitmap != null) {
            val maxSide = minOf(
                a.resources.displayMetrics.widthPixels - a.pad * 2,
                (280 * a.resources.displayMetrics.density).toInt(),
            ).coerceAtLeast(1)
            val qrView = ImageView(a).apply {
                setImageBitmap(qrBitmap)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                isClickable = true
                isFocusable = true
                contentDescription = "作者赞赏码，点击保存到相册"
                setOnClickListener { saveQrCode(a) }
            }
            val lp = LinearLayout.LayoutParams(maxSide, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = a.pad / 2
                bottomMargin = a.pad / 2
            }
            root.addView(qrView, lp)
        } else {
            root.addView(a.note("赞赏码图片加载失败"))
        }

        root.addView(a.button("保存赞赏码到相册") { saveQrCode(a) })

        root.addView(a.heading("赞赏名单"))

        val sponsorMeta = TextView(a).apply {
            isFocusable = true
            setPadding(0, a.pad / 4, 0, a.pad / 4)
        }
        root.addView(sponsorMeta)

        val refreshBtn = a.button("刷新名单") {}
        root.addView(refreshBtn)

        val sponsorListBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        root.addView(sponsorListBox)

        root.addView(a.heading("贡献名单"))

        val contribMeta = TextView(a).apply {
            isFocusable = true
            setPadding(0, a.pad / 4, 0, a.pad / 4)
        }
        root.addView(contribMeta)

        val contribListBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        root.addView(contribListBox)

        var sponsorsExpanded = false
        var currentSponsors: SponsorData? = null
        var sponsorsLoading = true
        var sponsorsFailed = false

        fun renderSponsors() {
            sponsorListBox.removeAllViews()
            val data = currentSponsors
            if (data == null) {
                sponsorMeta.text = when {
                    sponsorsLoading -> "正在加载赞赏名单…"
                    sponsorsFailed -> "暂时无法获取赞赏名单，请检查网络后点击刷新。"
                    else -> "暂无赞赏名单"
                }
                return
            }
            val metaParts = ArrayList<String>()
            if (data.notice.isNotBlank()) metaParts.add(data.notice)
            val countLine = buildString {
                append("共 ${data.sponsors.size} 位赞赏者")
                if (data.updatedAt.isNotBlank()) append("（更新于 ${data.updatedAt}）")
                if (sponsorsLoading) append(" · 正在刷新…")
            }
            metaParts.add(countLine)
            sponsorMeta.text = metaParts.joinToString("\n")

            if (data.sponsors.isEmpty()) {
                sponsorListBox.addView(a.note("名单整理中，感谢每一位支持与使用本模块的朋友！"))
                return
            }
            val hasMore = data.sponsors.size > DEFAULT_SPONSOR_LIMIT
            val shown = if (!hasMore || sponsorsExpanded) data.sponsors else data.sponsors.take(DEFAULT_SPONSOR_LIMIT)
            a.addList(
                sponsorListBox,
                shown.map { item ->
                    if (item.note.isBlank()) item.name else "${item.name}\n${item.note}"
                },
                descriptions = shown.map { item ->
                    if (item.note.isBlank()) item.name else "${item.name}，留言：${item.note}"
                },
                onClick = { idx ->
                    val item = shown[idx]
                    val text = if (item.note.isBlank()) item.name else "${item.name}：${item.note}"
                    a.toast(text)
                },
            )
            if (hasMore) {
                val toggleText = if (sponsorsExpanded) {
                    "收起赞赏名单"
                } else {
                    "展开全部赞赏名单（还有 ${data.sponsors.size - DEFAULT_SPONSOR_LIMIT} 位）"
                }
                sponsorListBox.addView(
                    a.button(toggleText) {
                        sponsorsExpanded = !sponsorsExpanded
                        renderSponsors()
                        val msg = if (sponsorsExpanded) {
                            "已展开全部赞赏名单，共 ${data.sponsors.size} 位"
                        } else {
                            "已收起赞赏名单，当前显示前 $DEFAULT_SPONSOR_LIMIT 位"
                        }
                        sponsorMeta.announceForAccessibility(msg)
                    }
                )
            }
        }

        fun renderContributors(data: ContributorData?, loading: Boolean, failed: Boolean) {
            contribListBox.removeAllViews()
            if (data == null) {
                contribMeta.text = when {
                    loading -> "正在加载贡献名单…"
                    failed -> "暂时无法获取贡献名单，请检查网络后点击刷新。"
                    else -> "暂无贡献名单"
                }
                return
            }
            val metaParts = ArrayList<String>()
            if (data.notice.isNotBlank()) metaParts.add(data.notice)
            val countLine = buildString {
                append("共 ${data.contributors.size} 位代码贡献者")
                if (data.updatedAt.isNotBlank()) append("（更新于 ${data.updatedAt}）")
                if (loading) append(" · 正在刷新…")
            }
            metaParts.add(countLine)
            contribMeta.text = metaParts.joinToString("\n")

            if (data.contributors.isEmpty()) {
                contribListBox.addView(a.note("名单整理中，欢迎通过 GitHub 提交 PR 一起完善本模块！"))
                return
            }
            val labels = data.contributors.mapIndexed { idx, item ->
                buildString {
                    append("${idx + 1}. ${item.name}")
                    val tags = ArrayList<String>()
                    if (item.qq.isNotBlank()) tags.add("QQ ${item.qq}")
                    if (item.github.isNotBlank()) tags.add("GitHub: ${item.github}")
                    if (tags.isNotEmpty()) append("（${tags.joinToString("，")}）")
                    if (item.contributions.isNotEmpty()) {
                        append("\n")
                        append(item.contributions.joinToString("\n") { "• $it" })
                    }
                }
            }
            val descs = data.contributors.mapIndexed { idx, item ->
                buildString {
                    append("第 ${idx + 1} 位，${item.name}")
                    if (item.qq.isNotBlank()) append("，QQ ${item.qq}")
                    if (item.github.isNotBlank()) append("，GitHub ${item.github}")
                    if (item.contributions.isNotEmpty()) {
                        append("，贡献：")
                        append(item.contributions.joinToString("；"))
                    }
                }
            }
            a.addList(
                contribListBox,
                labels,
                descriptions = descs,
                onClick = { idx -> showContributorDetail(a, data.contributors[idx]) },
            )
        }

        fun loadRemote(manual: Boolean) {
            val cachedSponsors = readSponsorCache(a)
            val cachedContribs = readContributorCache(a)
            currentSponsors = cachedSponsors
            sponsorsLoading = true
            sponsorsFailed = false
            renderSponsors()
            renderContributors(cachedContribs, loading = true, failed = false)
            refreshBtn.isEnabled = false
            Thread({
                val fetchedSponsors = fetchRemote(SPONSOR_PATH, ::parseSponsors)
                val fetchedContribs = fetchRemote(CONTRIB_PATH, ::parseContributors)
                main.post {
                    if (a.isFinishing || a.isDestroyed) return@post
                    refreshBtn.isEnabled = true
                    sponsorsLoading = false
                    if (fetchedSponsors != null) {
                        val (raw, data) = fetchedSponsors
                        a.prefs.edit().putString(PREF_CACHE, raw).apply()
                        currentSponsors = data
                        sponsorsFailed = false
                    } else {
                        sponsorsFailed = true
                    }
                    renderSponsors()

                    val finalContribs = if (fetchedContribs != null) {
                        val (raw, data) = fetchedContribs
                        a.prefs.edit().putString(PREF_CONTRIB_CACHE, raw).apply()
                        data
                    } else {
                        cachedContribs
                    }
                    renderContributors(finalContribs, loading = false, failed = fetchedContribs == null)

                    if (manual) {
                        val okAny = fetchedSponsors != null || fetchedContribs != null
                        val msg = if (okAny) {
                            val spCount = currentSponsors?.sponsors?.size ?: 0
                            val ctCount = finalContribs?.contributors?.size ?: 0
                            "名单已刷新：赞赏 $spCount 位，贡献 $ctCount 位"
                        } else {
                            "刷新名单失败，请检查网络连接"
                        }
                        a.toast(msg)
                        sponsorMeta.announceForAccessibility(msg)
                    }
                }
            }, "FetchSponsorAndContribs").start()
        }

        refreshBtn.setOnClickListener { loadRemote(manual = true) }

        currentSponsors = readSponsorCache(a)
        renderSponsors()
        renderContributors(readContributorCache(a), loading = true, failed = false)
        loadRemote(manual = false)
    }

    private fun showContributorDetail(a: SettingsActivity, item: ContributorItem) {
        val message = buildString {
            if (item.qq.isNotBlank()) append("QQ：${item.qq}\n")
            if (item.github.isNotBlank()) append("GitHub：${item.github}\n")
            if (item.githubUrl.isNotBlank()) append("主页：${item.githubUrl}\n")
            if (item.contributions.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append("主要贡献：\n")
                append(item.contributions.joinToString("\n") { "• $it" })
            }
        }.trim()
        val builder = AlertDialog.Builder(a)
            .setTitle(item.name)
            .setMessage(message.ifBlank { item.name })
            .setPositiveButton("确定", null)
        if (item.qq.isNotBlank()) {
            builder.setNeutralButton("复制QQ") { _, _ ->
                val cm = a.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("QQ", item.qq))
                a.toast("已复制 QQ：${item.qq}")
            }
        }
        if (item.githubUrl.startsWith("https://") || item.githubUrl.startsWith("http://")) {
            builder.setNegativeButton("打开GitHub") { _, _ ->
                runCatching {
                    a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.githubUrl)))
                }.onFailure {
                    a.toast("无法打开链接")
                }
            }
        }
        builder.show()
    }

    private fun readSponsorCache(a: SettingsActivity): SponsorData? {
        val raw = a.prefs.getString(PREF_CACHE, null) ?: return null
        return runCatching { parseSponsors(raw) }.getOrNull()
    }

    private fun readContributorCache(a: SettingsActivity): ContributorData? {
        val raw = a.prefs.getString(PREF_CONTRIB_CACHE, null) ?: return null
        return runCatching { parseContributors(raw) }.getOrNull()
    }

    private fun <T> fetchOne(urlStr: String, timeoutMs: Int, parser: (String) -> T): Pair<String, T>? {
        return runCatching {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                useCaches = false
                setRequestProperty("User-Agent", "DoubaoA11y/${ConfigSpec.MODULE_VERSION_NAME}")
                setRequestProperty("Cache-Control", "no-cache")
            }
            try {
                if (conn.responseCode != 200) return@runCatching null
                val baos = ByteArrayOutputStream()
                conn.inputStream.use { input ->
                    val buf = ByteArray(4096)
                    var total = 0
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) return@runCatching null
                        baos.write(buf, 0, n)
                    }
                }
                val raw = baos.toString("UTF-8")
                val data = parser(raw)
                raw to data
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    private fun <T> fetchRemote(dataPath: String, parser: (String) -> T): Pair<String, T>? {
        val rawUrl = "https://raw.githubusercontent.com/$REPO/main/$dataPath"
        val cdnUrls = listOf(
            "https://fastly.jsdelivr.net/gh/$REPO@main/$dataPath",
            "https://gcore.jsdelivr.net/gh/$REPO@main/$dataPath",
            "https://cdn.jsdelivr.net/gh/$REPO@main/$dataPath",
        )
        val lock = Object()
        var freshRes: Pair<String, T>? = null
        var cdnRes: Pair<String, T>? = null
        var freshDone = false
        var cdnDone = false

        Thread({
            val res = fetchOne(rawUrl, 3500, parser)
            synchronized(lock) {
                freshRes = res
                freshDone = true
                lock.notifyAll()
            }
        }, "RemoteFresh").start()

        Thread({
            var res: Pair<String, T>? = null
            for (u in cdnUrls) {
                if (freshDone && freshRes != null) break
                res = fetchOne(u, 3500, parser)
                if (res != null) break
            }
            synchronized(lock) {
                cdnRes = res
                cdnDone = true
                lock.notifyAll()
            }
        }, "RemoteCdn").start()

        val startMs = System.currentTimeMillis()
        return synchronized(lock) {
            while (true) {
                val f = freshRes
                val c = cdnRes
                if (freshDone && f != null) return@synchronized f
                if (freshDone && cdnDone) return@synchronized (f ?: c)
                val elapsed = System.currentTimeMillis() - startMs
                if (cdnDone && c != null && elapsed >= 2000L) return@synchronized (f ?: c)
                if (elapsed >= 10000L) return@synchronized (f ?: c)
                try {
                    lock.wait(150L)
                } catch (_: InterruptedException) {
                    break
                }
            }
            freshRes ?: cdnRes
        }
    }

    private fun parseSponsors(raw: String): SponsorData {
        val root = JSONObject(raw.removePrefix("\uFEFF"))
        val updatedAt = root.optString("updatedAt", "").trim().take(40)
        val notice = root.optString("notice", "").trim().take(200)
        val arr = root.optJSONArray("sponsors")
        val items = ArrayList<SponsorItem>()
        if (arr != null) {
            val max = minOf(arr.length(), 1000)
            for (i in 0 until max) {
                val elem = arr.opt(i) ?: continue
                when (elem) {
                    is JSONObject -> {
                        val name = elem.optString("name", "").trim().take(60)
                        val note = elem.optString("note", "").trim().take(160)
                        if (name.isNotEmpty()) items.add(SponsorItem(name, note))
                    }
                    is String -> {
                        val name = elem.trim().take(60)
                        if (name.isNotEmpty()) items.add(SponsorItem(name, ""))
                    }
                }
            }
        }
        return SponsorData(updatedAt, notice, items)
    }

    private fun parseContributors(raw: String): ContributorData {
        val root = JSONObject(raw.removePrefix("\uFEFF"))
        val updatedAt = root.optString("updatedAt", "").trim().take(40)
        val notice = root.optString("notice", "").trim().take(200)
        val arr = root.optJSONArray("contributors")
        val items = ArrayList<ContributorItem>()
        if (arr != null) {
            val max = minOf(arr.length(), 500)
            for (i in 0 until max) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name", "").trim().take(60)
                if (name.isEmpty()) continue
                val qq = obj.optString("qq", "").trim().take(32)
                val github = obj.optString("github", "").trim().take(80)
                val githubUrl = obj.optString("githubUrl", "").trim().take(200)
                val contribs = ArrayList<String>()
                val cArr: JSONArray? = obj.optJSONArray("contributions")
                if (cArr != null) {
                    for (j in 0 until minOf(cArr.length(), 50)) {
                        val line = cArr.optString(j, "").trim().take(200)
                        if (line.isNotEmpty()) contribs.add(line)
                    }
                } else {
                    val single = obj.optString("contributions", obj.optString("note", "")).trim().take(400)
                    if (single.isNotEmpty()) contribs.add(single)
                }
                items.add(ContributorItem(name, qq, github, githubUrl, contribs))
            }
        }
        return ContributorData(updatedAt, notice, items)
    }

    private fun saveQrCode(a: SettingsActivity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            doSaveQrQ(a)
        } else {
            AppStorage.ensurePermission(a) { doSaveQrLegacy(a) }
        }
    }

    private fun doSaveQrQ(a: SettingsActivity) {
        Thread({
            val outcome = runCatching {
                val fileName = "豆包输入法无障碍_赞赏码_${AppStorage.timestamp()}.png"
                val relPath = "${Environment.DIRECTORY_PICTURES}/${AppStorage.ROOT_NAME}"
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, relPath)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = a.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("无法创建相册图片")
                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        a.assets.open(ASSET_QR).use { input -> input.copyTo(out, 32 * 1024) }
                        out.flush()
                    } ?: error("无法写入相册图片")
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } catch (t: Throwable) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw t
                }
                "$relPath/$fileName"
            }
            main.post { notifyQrSaved(a, outcome) }
        }, "SaveSponsorQr").start()
    }

    private fun doSaveQrLegacy(a: SettingsActivity) {
        Thread({
            val outcome = runCatching {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), AppStorage.ROOT_NAME)
                val dest = AppStorage.uniqueFile(dir, "豆包输入法无障碍_赞赏码_${AppStorage.timestamp()}", "png")
                a.assets.open(ASSET_QR).use { input ->
                    FileOutputStream(dest).use { out ->
                        input.copyTo(out, 32 * 1024)
                        out.flush()
                    }
                }
                MediaScannerConnection.scanFile(a, arrayOf(dest.absolutePath), arrayOf("image/png"), null)
                "Pictures/${AppStorage.ROOT_NAME}/${dest.name}"
            }
            main.post { notifyQrSaved(a, outcome) }
        }, "SaveSponsorQrLegacy").start()
    }

    private fun notifyQrSaved(a: SettingsActivity, outcome: Result<String>) {
        if (a.isFinishing || a.isDestroyed) return
        outcome.fold(
            onSuccess = { path ->
                AlertDialog.Builder(a)
                    .setTitle("赞赏码已保存到相册")
                    .setMessage("保存位置：$path\n\n可打开微信“扫一扫”，点击右下角相册选择赞赏码图片识别。")
                    .setPositiveButton("知道了", null)
                    .show()
            },
            onFailure = { t ->
                a.toast("保存赞赏码失败：${t.message ?: "未知错误"}")
            },
        )
    }
}
