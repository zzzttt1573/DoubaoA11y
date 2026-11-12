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
        const val WORDS = "words"
        const val HELP = "help"
        const val ADVANCED = "advanced"

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
    var pad = 0
        private set
    private var page = HOME

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        autoOffLog()
        pad = (16 * resources.displayMetrics.density).toInt()
        page = intent.getStringExtra(EXTRA_PAGE) ?: HOME
        if (page == WORDS) WordsPage(this).show() else build()
        if (page == HOME) {
            val agreedBefore = Disclaimer.agreed(this)
            Disclaimer.showIfNeeded(this) { send(ConfigSpec.CMD_STATUS) }
            if (agreedBefore) Updater.afterUpdate(this)
            else prefs.edit().putInt("lastVersionCode", ConfigSpec.MODULE_VERSION_CODE).apply()
        }
    }

    override fun onResume() {
        super.onResume()
        // 使用须知还没同意时先不查状态，免得读屏读状态盖过须知
        if (page == HOME && Disclaimer.agreed(this)) send(ConfigSpec.CMD_STATUS)
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
        WORDS -> "自定义解释词"
        HELP -> "使用帮助"
        ADVANCED -> "高级"
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
                addGroup(root, Group.HOLD)
                root.addView(note("提示：删除键停留可快速删除，空格键停留进行语音输入"))
                root.addView(heading("快速输入"))
                root.addView(note("提示：键盘响应更快，天坦和解说原生支持，其他读屏需在关闭触摸浏览情况下使用"))
                addGroup(root, Group.FAST)
            }
            CANDIDATE -> {
                addKeys(root, "charExplain", "explainMaxLen", "candidateNav")
                root.addView(note("提示：请在豆包输入法app内调整键盘布局"))
            }
            STATE -> {
                root.addView(heading("键盘状态提示"))
                addGroup(root, Group.STATE)
                root.addView(heading("震动"))
                addGroup(root, Group.VIBRATE)
                root.addView(note("提示：豆包原生震动与声音请在豆包输入法app内调整"))
            }
            HELP -> HelpPage.fill(this, root)
            ADVANCED -> advanced(root)
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    // ---------------- 首页 ----------------

    private fun home(root: LinearLayout) {
        val st = TextView(this).apply {
            text = "状态：正在查询…"
            isFocusable = true
            setPadding(0, pad / 2, 0, pad / 2)
        }
        status = st
        root.addView(st)
        val fix = button("") {}
        fix.visibility = View.GONE
        fixButton = fix
        root.addView(fix)
        root.addView(button("查询模块状态") { send(ConfigSpec.CMD_STATUS) })

        val upd = TextView(this).apply {
            text = "当前版本 ${ConfigSpec.MODULE_VERSION_NAME}"
            isFocusable = true
            setPadding(0, pad, 0, pad / 2)
        }
        root.addView(upd)
        root.addView(button("检查更新") { Updater.check(this, upd) })

        root.addView(heading("设置"))
        for (p in listOf(INPUT, CANDIDATE, STATE, WORDS, HELP, ADVANCED)) root.addView(entry(p))

        root.addView(heading("关于"))
        root.addView(info("作者：$AUTHOR"))
        root.addView(info("QQ群：$QQ_GROUP"))
        root.addView(button("GitHub项目地址：$GITHUB") { open(GITHUB) })
    }

    /** 首页状态下面的解决按钮：豆包里是旧版模块、或者读屏没开时出现 */
    private fun showFix(label: String, onClick: () -> Unit) {
        val b = fixButton ?: return
        b.text = label
        b.setOnClickListener { onClick() }
        b.visibility = View.VISIBLE
    }

    /** 豆包里运行的模块版本和本应用不一样：多半是更新后没有强行停止豆包 */
    private var staleWarned = false

    private fun checkResult(extras: Bundle?) {
        val running = extras?.getInt("moduleVersion", -1) ?: -1
        val active = extras?.getBoolean("active", true) ?: true
        when {
            running != ConfigSpec.MODULE_VERSION_CODE -> {
                showFix("去强行停止豆包输入法") { Updater.openDoubaoInfo(this) }
                if (!staleWarned && !isFinishing) {
                    staleWarned = true
                    val msg = "豆包输入法里运行的还是旧版模块。请在豆包输入法的应用信息里点“强行停止”，再弹出一次键盘。" +
                        "用 LSPatch 集成模式的，要用新模块重新修补豆包。"
                    status?.text = "状态：$msg"
                    Updater.forceStopDialog(this, "需要强行停止豆包输入法", msg)
                }
            }
            !prefs.getBoolean("moduleOn", true) -> fixButton?.visibility = View.GONE
            !active -> showFix("打开无障碍设置") {
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (_: Throwable) {
                }
            }
            else -> fixButton?.visibility = View.GONE
        }
    }

    // ---------------- 高级 ----------------

    private fun advanced(root: LinearLayout) {
        addGroup(root, Group.MAIN)

        root.addView(heading("问题反馈"))
        root.addView(note("遇到问题时：1. 打开“记录日志”；2. 回去重现一次问题；3. 回到这里点“复制日志”粘贴发给作者，日志很长就点“保存日志文件”，在 QQ 里把文件发给作者。"))
        addGroup(root, Group.LOG)
        root.addView(button("复制日志") { send(ConfigSpec.CMD_LOG_COPY) })
        root.addView(button("保存日志文件") { send(ConfigSpec.CMD_LOG_SAVE) })
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
                .setMessage("确定要把全部设置恢复默认吗？自定义解释词不受影响。")
                .setPositiveButton("确定") { _, _ ->
                    val e = prefs.edit()
                    for (item in ConfigSpec.items) e.remove(item.key)
                    for (item in ConfigSpec.intItems) e.remove(item.key)
                    e.apply()
                    build()
                    send(ConfigSpec.CMD_RESET)
                    Toast.makeText(this, "已恢复默认", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        })
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
    private fun addKeys(parent: LinearLayout, vararg keys: String) {
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
            send(null)
        }
    }

    /** 选择项：按钮显示当前值，点一下弹出单选框 */
    private fun choice(item: ConfigSpec.IntItem): Button {
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

    /** 把全部设置值（和可选命令）发给豆包进程；用有序广播拿回执 */
    fun send(cmd: String?) {
        val intent = Intent(ConfigSpec.ACTION_CONFIG).setPackage(ConfigSpec.TARGET_PACKAGE)
        for (item in ConfigSpec.items) intent.putExtra(item.key, prefs.getBoolean(item.key, item.default))
        for (item in ConfigSpec.intItems) intent.putExtra(item.key, prefs.getInt(item.key, item.default))
        intent.putExtra(ConfigSpec.EXTRA_CUSTOM_WORDS, prefs.getString(PREF_WORDS, "") ?: "")
        if (cmd != null) intent.putExtra(ConfigSpec.EXTRA_CMD, cmd)
        try {
            sendOrderedBroadcast(intent, null, object : BroadcastReceiver() {
                override fun onReceive(context: Context, i: Intent) {
                    val ok = resultCode == RESULT_OK && resultData != null
                    // 改开关时成功就不打扰，只在没送到时提示
                    if (cmd == null && ok) return
                    val msg = if (ok) {
                        when (cmd) {
                            ConfigSpec.CMD_DUMP -> "已输出键位表。$resultData"
                            ConfigSpec.CMD_RESET -> "已恢复默认。$resultData"
                            else -> resultData
                        }
                    } else {
                        "模块没有回应，可能是模块没有生效。请先在任意输入框弹出一次豆包键盘，再点“查询模块状态”。还不行的话，请到 LSPatch 里确认豆包输入法的模块作用域勾选了本模块，再强行停止一次豆包输入法。"
                    }
                    val st = status
                    if (st != null) {
                        st.text = "状态：$msg"
                        st.announceForAccessibility(msg)
                        if (ok) checkResult(getResultExtras(false))
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
            status?.text = "状态：发送失败 ${t.message}"
        }
    }
}
