package io.github.doubaoa11y.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import io.github.doubaoa11y.ConfigSpec
import io.github.doubaoa11y.ConfigSpec.Group

/**
 * 模块设置页。首页放状态、更新和几个入口，每个入口是单独一页（同一个 Activity，用 page 区分）。
 * 改动后立即通过广播发给豆包输入法进程；输入法进程要运行着（弹出过一次键盘）才能收到。
 */
class SettingsActivity : Activity() {
    companion object {
        const val EXTRA_PAGE = "page"
        const val HOME = "home"
        const val INPUT = "input"
        const val CANDIDATE = "candidate"
        const val STATE = "state"
        const val VOICE = "voice"
        const val SOUND = "sound"
        const val SOUND_IMPORT = "sound_import"
        const val GESTURE = "gesture"
        const val EDITOPTS = "editopts"
        const val SENTENCE_SYMBOLS = "sentence_symbols"
        const val WORDS = "words"
        const val WORDS_IMPORT = "words_import"
        const val BACKUP = "backup"
        const val BACKUP_RESTORE = "backup_restore"
        const val SPONSOR = "sponsor"
        const val ADVANCED = "advanced"
        const val PATCH_WIZARD = "patch"

        const val AUTHOR = "乐天知命故不忧"
        const val QQ_GROUP = "1135031490"
        const val GITHUB = "https://github.com/zzzttt1573/DoubaoA11y"

        /** 设置页自己保存的自定义解释词（和广播里的格式一样） */
        const val PREF_WORDS = ConfigSpec.EXTRA_CUSTOM_WORDS
        const val LOG_SINCE = "runLogSince"
    }

    lateinit var prefs: SharedPreferences
        private set
    private var status: TextView? = null
    private var fixButton: Button? = null
    private var patchEntryButton: Button? = null
    var pad = 0
        private set
    private var page = HOME
    val currentPage: String get() = page
    private var pausedOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        ConfigSpec.migrateInput(prefs)
        ConfigSpec.migrateGestureDefaults(prefs)
        ConfigSpec.migrateEditOptions(prefs)
        autoOffLog()
        AppStorage.ensureDirsIfPermitted(this)
        pad = (16 * resources.displayMetrics.density).toInt()
        page = when (val raw = intent.getStringExtra(EXTRA_PAGE) ?: HOME) {
            WORDS -> CANDIDATE
            else -> raw
        }
        if (page == HOME) {
            runCatching { if (!Shortcut.shown(this)) Shortcut.setShown(this, true) }
        }
        build()
        if (page == HOME) {
            val agreedBefore = Disclaimer.agreed(this)
            Disclaimer.showIfNeeded(this) { send(ConfigSpec.CMD_STATUS) }
            if (agreedBefore) Updater.afterUpdate(this)
            else prefs.edit().putInt("lastVersionCode", ConfigSpec.MODULE_VERSION_CODE).apply()
        }
    }

    override fun onPause() {
        pausedOnce = true
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        AppStorage.onResume(this)
        PatchWizardPage.onResume(this)
        if (pausedOnce && page in setOf(CANDIDATE, STATE, SOUND, BACKUP)) {
            build()
        }
        // 使用须知还没同意时先不查状态，免得读屏读状态盖过须知
        if (page == HOME && Disclaimer.agreed(this)) send(ConfigSpec.CMD_STATUS)
    }

    override fun onDestroy() {
        AppStorage.detach(this)
        PatchWizardPage.detach(this)
        WordTransfer.detach(this)
        SettingsBackup.detach(this)
        super.onDestroy()
    }

    /** “记录日志”开了 1 小时就关掉（豆包那边也会自己关，这里同步，免得打开设置页又把它打开） */
    private fun autoOffLog() {
        if (!prefs.getBoolean("runLog", false)) return
        val since = prefs.getLong(LOG_SINCE, 0L)
        if (since == 0L) prefs.edit().putLong(LOG_SINCE, System.currentTimeMillis()).apply()
        else if (System.currentTimeMillis() - since > 60 * 60 * 1000L) prefs.edit().putBoolean("runLog", false).apply()
    }

    fun titleOf(p: String) = when (p) {
        INPUT -> "输入方式"
        CANDIDATE -> "候选与朗读"
        STATE -> "提示与震动"
        VOICE -> "语音播报"
        SOUND -> "自定义音效"
        SOUND_IMPORT -> "导入音频"
        GESTURE -> "上半屏操作与手势"
        EDITOPTS -> "自定义编辑菜单"
        SENTENCE_SYMBOLS -> "自定义分隔符号"
        WORDS -> "自定义解释词"
        WORDS_IMPORT -> "导入解释词"
        BACKUP -> "备份与恢复"
        BACKUP_RESTORE -> "恢复备份"
        SPONSOR -> "赞赏支持"
        ADVANCED -> "高级"
        PATCH_WIZARD -> "注入无障碍模块"
        else -> "豆包输入法无障碍模块"
    }

    private fun build() {
        title = titleOf(page)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(heading(titleOf(page), 22f))
        when (page) {
            HOME -> home(root)
            INPUT -> {
                addGroup(root, Group.INPUT)
                root.addView(note("可切换抬手输入与标准输入"))
                root.addView(heading("延迟调整"))
                addKeys(root, "deleteHoldMs")
                root.addView(note("删除键停留可连续删除"))
                addKeys(root, "spaceHoldMs")
                root.addView(note("空格键停留可开始语音输入"))
                addKeys(root, "enterHoldMs")
                root.addView(note("回车键短按发送，长按换行（需打开回车发送）"))
                addKeys(root, "symbolScroll", "symbolScrollMs")
                root.addView(note("开启符号区域停留翻页后，在数字、九键左侧符号区首尾停留可上下翻页，在符号键盘底部首尾停留可左右翻页"))
                root.addView(heading("快速输入"))
                addGroup(root, Group.FAST)
            }
            CANDIDATE -> {
                addKeys(root, "charExplain", "explainMaxLen", "candidateNav")
                root.addView(note("提示：如果26键找不到右侧的右移，请在豆包输入法app内调整键盘布局"))
                WordsPage(this).fill(root)
            }
            STATE -> {
                root.addView(heading("键盘状态提示"))
                addKeys(root, "announceMode", "announceOnShow", "announceHide", "voiceZoneHint")
                root.addView(heading("音效"))
                SoundPage.fill(this, root)
                root.addView(heading("震动"))
                addGroup(root, Group.VIBRATE)
                root.addView(note("提示：豆包原生震动与声音请在豆包输入法app内调整"))
            }
            VOICE -> VoicePage.fill(this, root)
            SOUND -> SoundPage.manage(this, root)
            SOUND_IMPORT -> SoundPage.fillImportList(this, root)
            GESTURE -> GesturePage.fill(this, root)
            EDITOPTS -> EditOptsPage.fill(this, root)
            SENTENCE_SYMBOLS -> GesturePage.fillSentenceSymbols(this, root)
            WORDS_IMPORT -> WordTransfer.fillImportList(this, root)
            BACKUP -> SettingsBackup.fill(this, root)
            BACKUP_RESTORE -> SettingsBackup.fillRestoreList(this, root)
            SPONSOR -> SponsorPage.fill(this, root)
            ADVANCED -> advanced(root)
            PATCH_WIZARD -> PatchWizardPage.fill(this, root)
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    // ---------------- 首页 ----------------

    private fun home(root: LinearLayout) {
        val st = TextView(this).apply {
            text = "当前版本 ${ConfigSpec.MODULE_VERSION_NAME}\n状态：正在查询…"
            isFocusable = true
            setPadding(0, pad / 2, 0, pad / 2)
        }
        status = st
        root.addView(st)
        val fix = button("") {}
        fix.visibility = View.GONE
        fixButton = fix
        root.addView(fix)
        root.addView(button("刷新模块状态") { send(ConfigSpec.CMD_STATUS) })

        root.addView(button("检查更新") { Updater.check(this, st) })
        patchEntryButton = entry(PATCH_WIZARD).also {
            it.visibility = View.GONE
            root.addView(it)
        }

        root.addView(heading("设置"))
        for (p in listOf(INPUT, GESTURE, CANDIDATE, VOICE, STATE, BACKUP, SPONSOR, ADVANCED)) root.addView(entry(p))

        root.addView(heading("关于"))
        root.addView(info("作者：$AUTHOR"))
        root.addView(info("QQ群：$QQ_GROUP"))
        root.addView(button("访问我的github仓库地址") { open(GITHUB) })
    }

    /** 首页状态下面的解决按钮：仅检测到模块异常、版本不匹配或读屏未开时出现 */
    private fun showFix(label: String, showPatchEntry: Boolean = false, onClick: () -> Unit) {
        val b = fixButton ?: return
        b.text = label
        b.setOnClickListener { onClick() }
        b.visibility = View.VISIBLE
        patchEntryButton?.visibility = if (showPatchEntry) View.VISIBLE else View.GONE
    }

    private fun hideFix() {
        fixButton?.visibility = View.GONE
        patchEntryButton?.visibility = View.GONE
    }

    /** 豆包里运行的模块版本和本应用不一样：多半是更新后没有强行停止豆包 */
    private var staleWarned = false

    private fun checkResult(extras: Bundle?, doubaoStatus: io.github.doubaoa11y.patch.ApkPatcher.DoubaoStatus) {
        val running = extras?.getInt("moduleVersion", -1) ?: -1
        val active = extras?.getBoolean("active", true) ?: true
        val localIntegrated = doubaoStatus.kind == io.github.doubaoa11y.patch.ApkPatcher.PatchKind.LOCAL_INTEGRATED
        val mismatchEmbedded = localIntegrated && !doubaoStatus.embeddedModuleMatchesCurrent
        when {
            running != ConfigSpec.MODULE_VERSION_CODE || mismatchEmbedded -> {
                if (localIntegrated) {
                    showFix("一键同步新版模块到豆包", showPatchEntry = false) {
                        startActivity(Intent(this, SettingsActivity::class.java).putExtra(EXTRA_PAGE, PATCH_WIZARD))
                    }
                } else {
                    showFix("去强行停止豆包输入法", showPatchEntry = true) { Updater.openDoubaoInfo(this) }
                }
                if (!staleWarned && !isFinishing && running != ConfigSpec.MODULE_VERSION_CODE) {
                    staleWarned = true
                    val msg = if (localIntegrated) {
                        "豆包输入法里集成的模块与当前安装的不匹配。请点击“一键同步新版模块到豆包”直接覆盖更新。"
                    } else {
                        "豆包输入法里运行的还是旧版模块。请在豆包输入法的应用信息里点“强行停止”，再弹出一次键盘。"
                    }
                    status?.text = "当前版本 ${ConfigSpec.MODULE_VERSION_NAME}\n状态：$msg"
                    if (!localIntegrated) Updater.forceStopDialog(this, "需要强行停止豆包输入法", msg)
                }
            }
            !prefs.getBoolean("moduleOn", true) -> hideFix()
            !active -> showFix("打开无障碍设置") {
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (_: Throwable) {
                }
            }
            else -> hideFix()
        }
    }

    // ---------------- 高级 ----------------

    private fun advanced(root: LinearLayout) {
        addGroup(root, Group.MAIN)

        root.addView(heading("问题反馈"))
        root.addView(note("日志保存目录：${AppStorage.displayDir(AppStorage.DIR_LOGS)}\n遇到问题时：1. 打开“记录日志”；2. 回去重现一次问题；3. 回到这里点“复制日志”粘贴发给作者，日志很长就点“保存日志文件”，把保存好的文件发给作者。"))
        addGroup(root, Group.LOG)
        root.addView(button("复制日志") { send(ConfigSpec.CMD_LOG_COPY) })
        root.addView(button("保存日志文件") { AppStorage.ensurePermission(this) { saveLogToStorage() } })
        root.addView(button("清空日志") { send(ConfigSpec.CMD_LOG_CLEAR) })

        root.addView(heading("调试选项"))
        val debug = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val toggle = button("显示调试选项") {}
        toggle.setOnClickListener {
            val show = debug.visibility != View.VISIBLE
            debug.visibility = if (show) View.VISIBLE else View.GONE
            toggle.text = if (show) "隐藏调试选项" else "显示调试选项"
        }
        root.addView(toggle)
        debug.addView(note("提示：平时用不到，这些开关只在排查问题时打开"))
        addGroup(debug, Group.DEBUG)
        debug.addView(button("立即输出键位表和控件树到日志") { send(ConfigSpec.CMD_DUMP) })
        root.addView(debug)

        root.addView(heading("恢复默认"))
        root.addView(button("全部恢复默认") {
            AlertDialog.Builder(this)
                .setTitle("恢复默认")
                .setMessage("确定要把全部设置恢复默认吗？自定义解释词和已导入音频文件保留。")
                .setPositiveButton("确定") { _, _ ->
                    runCatching { Shortcut.setShown(this, true) }
                    val e = prefs.edit()
                    for (item in ConfigSpec.items) e.remove(item.key)
                    for (item in ConfigSpec.intItems) e.remove(item.key)
                    for (item in ConfigSpec.strItems) if (item.key != "soundNames") e.remove(item.key)
                    e.apply()
                    io.github.doubaoa11y.SpeechEngine.stop()
                    build()
                    send(ConfigSpec.CMD_RESET)
                    Toast.makeText(this, "已恢复默认", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        })
    }

    private fun saveLogToStorage() {
        val token = NativeBackupUi.randomToken()
        val progress = AlertDialog.Builder(this)
            .setTitle("正在保存日志")
            .setMessage("正在从豆包输入法导出日志文件…")
            .setCancelable(false)
            .create()
        progress.show()
        NativeBackupUi.sendNativeBroadcast(
            a = this,
            cmd = ConfigSpec.CMD_LOG_SAVE,
            token = token,
            includeClipboard = false,
        ) { ok, msg ->
            if (!ok) {
                io.github.doubaoa11y.SettingsProvider.cleanupXfer(this, token)
                runCatching { progress.dismiss() }
                if (!isFinishing && !isDestroyed) {
                    AlertDialog.Builder(this)
                        .setTitle("保存日志失败")
                        .setMessage(msg)
                        .setPositiveButton("知道了", null)
                        .show()
                }
                return@sendNativeBroadcast
            }
            Thread({
                val outcome = runCatching {
                    val src = io.github.doubaoa11y.SettingsProvider.xferFile(this, token)
                    check(src != null && src.isFile) { "未收到日志文件" }
                    val dest = AppStorage.uniqueFile(AppStorage.logsDir(), "豆包无障碍日志_${AppStorage.timestamp()}", "txt")
                    java.io.FileInputStream(src).use { input ->
                        java.io.FileOutputStream(dest).use { out ->
                            input.copyTo(out, 32 * 1024)
                            out.flush()
                        }
                    }
                    dest
                }
                io.github.doubaoa11y.SettingsProvider.cleanupXfer(this, token)
                runOnUiThread {
                    runCatching { progress.dismiss() }
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    outcome.fold(
                        onSuccess = { dest ->
                            val extra = if (msg.isNotBlank() && msg != "操作已完成") "\n$msg" else ""
                            AlertDialog.Builder(this)
                                .setTitle("日志已保存")
                                .setMessage("已保存到：${AppStorage.displayPath(dest)}$extra")
                                .setPositiveButton("知道了", null)
                                .show()
                        },
                        onFailure = { t ->
                            AlertDialog.Builder(this)
                                .setTitle("保存日志失败")
                                .setMessage(t.message ?: "无法写入日志文件")
                                .setPositiveButton("知道了", null)
                                .show()
                        },
                    )
                }
            }, "SaveLogFile").start()
        }
    }

    // ---------------- 控件 ----------------

    /** 把一组的开关和选择项按定义顺序加进去：开关在前（下面一行是提示），选择项在后 */
    private fun addGroup(parent: LinearLayout, g: Group) {
        for (item in ConfigSpec.items) if (item.group == g) {
            parent.addView(switch(item), matchWidth())
            if (item.hint.isNotEmpty()) parent.addView(note(item.hint))
        }
        for (item in ConfigSpec.intItems) if (item.group == g) parent.addView(choice(item), matchWidth())
    }

    /** 按给定顺序加开关或选择项（开关的提示跟在下面） */
    fun addKeys(parent: LinearLayout, vararg keys: String) {
        for (k in keys) {
            val b = ConfigSpec.items.firstOrNull { it.key == k }
            if (b != null) {
                parent.addView(switch(b), matchWidth())
                if (b.hint.isNotEmpty()) parent.addView(note(b.hint))
            } else {
                ConfigSpec.intItems.firstOrNull { it.key == k }?.let { parent.addView(choice(it), matchWidth()) }
            }
        }
    }

    private fun switch(item: ConfigSpec.Item) = Switch(this).apply {
        text = item.label
        isChecked = prefs.getBoolean(item.key, item.default)
        setPadding(0, pad / 2, 0, pad / 2)
        setOnCheckedChangeListener { _, checked ->
            val e = prefs.edit().putBoolean(item.key, checked)
            if (item.key == "runLog" && checked) e.putLong(LOG_SINCE, System.currentTimeMillis())
            e.apply()
            if ((item.key == "speakByReader" && checked) || (item.key == "moduleOn" && !checked)) io.github.doubaoa11y.SpeechEngine.stop()
            send(null)
            if (item.key == "speakByReader") rebuild()
        }
    }

    /** 选择项：按钮显示当前值，点一下弹出单选框 */
    private fun choice(item: ConfigSpec.IntItem): View {
        val b = Button(this)
        fun label(): String {
            val cur = prefs.getInt(item.key, item.default)
            val name = item.options.firstOrNull { it.first == cur }?.second
                ?: item.options.first { it.first == item.default }.second
            return "${item.label}：$name"
        }
        b.text = label()
        b.isAllCaps = false
        b.setOnClickListener {
            val cur = prefs.getInt(item.key, item.default)
            val idx = item.options.indexOfFirst { it.first == cur }.let { if (it < 0) item.options.indexOfFirst { o -> o.first == item.default } else it }
            AlertDialog.Builder(this)
                .setTitle(item.label)
                .setSingleChoiceItems(item.options.map { it.second }.toTypedArray(), idx) { d, which ->
                    prefs.edit().putInt(item.key, item.options[which].first).apply()
                    b.text = label()
                    d.dismiss()
                    send(null)
                }
                .setNegativeButton("取消", null)
                .show()
        }
        return b
    }

    /** 与候选解释词相同的列表格式（ListView + TextView 列表项），支持点击与长按 */
    fun addList(
        parent: LinearLayout,
        items: List<String>,
        descriptions: List<String>? = null,
        onClick: (Int) -> Unit,
        onLongClick: ((Int) -> Boolean)? = null,
    ): android.widget.ListView {
        val list = object : android.widget.ListView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val expandSpec = MeasureSpec.makeMeasureSpec(Int.MAX_VALUE shr 2, MeasureSpec.AT_MOST)
                super.onMeasure(widthMeasureSpec, expandSpec)
            }
        }
        list.isFocusable = false
        list.adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(position: Int) = items[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val tv = (convertView as? TextView) ?: TextView(this@SettingsActivity).apply {
                    textSize = 18f
                    setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
                }
                tv.text = items[position]
                tv.contentDescription = descriptions?.getOrNull(position)
                return tv
            }
        }
        list.setOnItemClickListener { _, _, pos, _ -> onClick(pos) }
        if (onLongClick != null) {
            list.setOnItemLongClickListener { _, _, pos, _ -> onLongClick(pos) }
        }
        parent.addView(list, matchWidth())
        return list
    }

    /** 进入另一页的按钮 */
    private fun entry(p: String) = button(titleOf(p)) {
        startActivity(Intent(this, SettingsActivity::class.java).putExtra(EXTRA_PAGE, p))
    }

    fun open(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Throwable) {
        }
    }

    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    fun heading(t: String, size: Float = 18f) = TextView(this).apply {
        text = t
        textSize = size
        isFocusable = true
        setPadding(0, pad, 0, pad / 4)
        if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }

    fun note(t: String) = TextView(this).apply {
        text = t
        setPadding(0, 0, 0, pad / 2)
    }

    private fun info(t: String) = TextView(this).apply {
        text = t
        isFocusable = true
        setPadding(0, pad / 4, 0, pad / 4)
    }

    fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    fun rebuild() = build()
    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    fun switchOf(item: ConfigSpec.Item) = switch(item)
    fun addKeysNoHint(root: LinearLayout, vararg keys: String) = addKeys(root, *keys)
    fun editOptions(): List<Int> = ConfigSpec.parseEditOptions(prefs.getString(ConfigSpec.EDIT_OPTIONS_KEY, ""))
    fun saveEditOptions(ids: List<Int>) {
        prefs.edit().putString(ConfigSpec.EDIT_OPTIONS_KEY, ConfigSpec.encodeEditOptions(ids)).apply()
        send(null)
    }
    fun restoreGestureDefaults() {
        val e = prefs.edit()
        for ((slot, _) in ConfigSpec.GESTURE_SLOTS) e.putInt(ConfigSpec.gestureKey(slot), ConfigSpec.gestureDefault(slot))
        for (key in listOf("upperOperation", "fsDrag", "fsHaptic", "fsSpeakAction", "fsSpeakChar", "fsCandidateFirst")) e.remove(key)
        e.remove("gestureSensitivity").remove("fsDragMs").remove("sentenceSymbols").remove(ConfigSpec.EDIT_OPTIONS_KEY).apply()
        send(null); build()
    }
    fun textField(root: LinearLayout, label: String, key: String, hint: String) {
        root.addView(button(label) {
            val input = android.widget.EditText(this).apply { setText(prefs.getString(key, "")); this.hint = hint }
            AlertDialog.Builder(this).setTitle(label).setView(input).setPositiveButton("保存") { _, _ ->
                prefs.edit().putString(key, input.text.toString().take(16000)).apply(); send(null); build()
            }.setNegativeButton("取消", null).show()
        })
    }

    @Deprecated("系统文件选择器结果")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (AppStorage.onActivityResult(this, requestCode)) return
        if (WordTransfer.onResult(this, requestCode, resultCode, data?.data)) return
        if (SoundPage.onResult(this, requestCode, resultCode, data?.data)) return
        SettingsBackup.onResult(this, requestCode, resultCode, data?.data)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        AppStorage.onPermissionResult(this, requestCode)
    }

    /** 把全部设置值（和可选命令）发给豆包进程；用有序广播拿回执 */
    fun send(cmd: String?) {
        if (!prefs.getBoolean("moduleOn", true) || !prefs.getBoolean("soundOn", false)) io.github.doubaoa11y.SoundEngine.stop()
        val intent = Intent(ConfigSpec.ACTION_CONFIG).setPackage(ConfigSpec.TARGET_PACKAGE)
        intent.clipData = android.content.ClipData.newRawUri("config", io.github.doubaoa11y.SettingsProvider.URI)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        for (item in ConfigSpec.items) intent.putExtra(item.key, prefs.getBoolean(item.key, item.default))
        for (item in ConfigSpec.intItems) intent.putExtra(item.key, prefs.getInt(item.key, item.default))
        for (item in ConfigSpec.strItems) intent.putExtra(item.key, prefs.getString(item.key, item.default) ?: item.default)
        intent.putExtra(ConfigSpec.EXTRA_CUSTOM_WORDS, prefs.getString(PREF_WORDS, "") ?: "")
        if (cmd != null) intent.putExtra(ConfigSpec.EXTRA_CMD, cmd)
        try {
            sendOrderedBroadcast(intent, null, object : BroadcastReceiver() {
                override fun onReceive(context: Context, i: Intent) {
                    val ok = resultCode == RESULT_OK && resultData != null
                    if (ok) {
                        val rx = getResultExtras(false)
                        if (rx != null) {
                            val ed = prefs.edit()
                            var synced = false
                            for (k in listOf("upperOperation") + ConfigSpec.TOOLBAR_TOGGLE_KEYS) {
                                if (rx.containsKey("sync_$k")) {
                                    ed.putBoolean(k, rx.getBoolean("sync_$k"))
                                    synced = true
                                }
                            }
                            if (synced) ed.apply()
                        }
                    }
                    // 改开关时成功就不打扰，只在没送到时提示
                    if (cmd == null && ok) return
                    val msg = if (ok) {
                        when (cmd) {
                            ConfigSpec.CMD_DUMP -> "已输出键位表。$resultData"
                            ConfigSpec.CMD_RESET -> "已恢复默认。$resultData"
                            else -> resultData
                        }
                    } else {
                        "模块没有生效。可能是还没修补豆包，或者没有弹出过输入法界面。请点击“注入无障碍模块”。或者在任意编辑框弹出一次输入法界面。"
                    }
                    val st = status
                    if (st != null) {
                        val doubaoStatus = io.github.doubaoa11y.patch.ApkPatcher.inspect(this@SettingsActivity)
                        val integratedLine = if (
                            doubaoStatus.kind == io.github.doubaoa11y.patch.ApkPatcher.PatchKind.LOCAL_INTEGRATED &&
                            !doubaoStatus.embeddedModuleMatchesCurrent
                        ) "\n集成模块：与当前安装的不匹配，请同步更新" else ""
                        val fullMsg = "$msg$integratedLine"
                        st.text = "当前版本 ${ConfigSpec.MODULE_VERSION_NAME}\n状态：$fullMsg"
                        st.announceForAccessibility(fullMsg)
                        if (ok) checkResult(getResultExtras(false), doubaoStatus)
                        else {
                            val kind = doubaoStatus.kind
                            if (kind == io.github.doubaoa11y.patch.ApkPatcher.PatchKind.ORIGINAL ||
                                kind == io.github.doubaoa11y.patch.ApkPatcher.PatchKind.NOT_INSTALLED
                            ) {
                                showFix("注入无障碍模块", showPatchEntry = false) {
                                    startActivity(Intent(this@SettingsActivity, SettingsActivity::class.java).putExtra(EXTRA_PAGE, PATCH_WIZARD))
                                }
                            } else if (kind == io.github.doubaoa11y.patch.ApkPatcher.PatchKind.LOCAL_INTEGRATED &&
                                !doubaoStatus.embeddedModuleMatchesCurrent
                            ) {
                                showFix("一键同步新版模块到豆包", showPatchEntry = false) {
                                    startActivity(Intent(this@SettingsActivity, SettingsActivity::class.java).putExtra(EXTRA_PAGE, PATCH_WIZARD))
                                }
                            } else {
                                showFix("去强行停止豆包输入法", showPatchEntry = true) { Updater.openDoubaoInfo(this@SettingsActivity) }
                            }
                        }
                    } else if (ok && cmd != null) {
                        // 复制、保存日志这类命令：结果用对话框显示，读屏会读出来
                        if (!isFinishing) AlertDialog.Builder(this@SettingsActivity)
                            .setMessage(msg)
                            .setPositiveButton("知道了", null)
                            .show()
                    } else if (!ok) {
                        Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_LONG).show()
                    }
                }
            }, null, RESULT_CANCELED, null, null)
        } catch (t: Throwable) {
            status?.text = "当前版本 ${ConfigSpec.MODULE_VERSION_NAME}\n状态：发送失败 ${t.message}"
            patchEntryButton?.visibility = View.VISIBLE
        }
    }
}
