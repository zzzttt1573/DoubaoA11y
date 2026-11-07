package io.github.doubaoa11y.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import io.github.doubaoa11y.ConfigSpec
import io.github.doubaoa11y.ConfigSpec.Group

/**
 * 模块设置页。改动后立即通过广播发给豆包输入法进程。
 * 输入法进程要运行着（弹出过一次键盘）才能收到。
 */
class SettingsActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var status: TextView
    private var pad = 0
    private var debugShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        pad = (16 * resources.displayMetrics.density).toInt()
        build()
        Disclaimer.showIfNeeded(this) { send(ConfigSpec.CMD_STATUS) }
    }

    override fun onResume() {
        super.onResume()
        // 使用须知还没同意时先不查状态，免得读屏读状态盖过须知
        if (Disclaimer.agreed(this)) send(ConfigSpec.CMD_STATUS)
    }

    private fun build() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(heading("豆包输入法无障碍模块", 22f))

        status = TextView(this).apply {
            text = "状态：正在查询…"
            isFocusable = true
            setPadding(0, pad / 2, 0, pad / 2)
        }
        root.addView(status)
        root.addView(button("查询模块状态") { send(ConfigSpec.CMD_STATUS) })

        root.addView(heading("输入方式"))
        root.addView(note("抬手输入：摸到键读出来，抬手就输入。双击输入：摸到键读出来，双击输入，适合不习惯抬手输入的用户。只在触摸浏览开着时有区别"))
        addGroup(root, Group.INPUT)

        root.addView(heading("延迟调整"))
        root.addView(note("删除键停留可快速删除，空格键停留进行语音输入"))
        addGroup(root, Group.HOLD)

        root.addView(heading("候选词解释"))
        addGroup(root, Group.CANDIDATE)

        root.addView(heading("键盘状态提示"))
        addGroup(root, Group.STATE)

        root.addView(heading("震动"))
        addGroup(root, Group.VIBRATE)

        root.addView(heading("调试选项"))
        val debug = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (debugShown) View.VISIBLE else View.GONE
        }
        val toggle = button(if (debugShown) "隐藏调试选项" else "显示调试选项") {}
        toggle.setOnClickListener {
            debugShown = !debugShown
            debug.visibility = if (debugShown) View.VISIBLE else View.GONE
            toggle.text = if (debugShown) "隐藏调试选项" else "显示调试选项"
        }
        root.addView(toggle)
        debug.addView(note("平时用不到。这些开关只在排查问题时打开。"))
        addGroup(debug, Group.DEBUG)
        debug.addView(button("立即输出键位表和控件树到日志") { send(ConfigSpec.CMD_DUMP) })
        root.addView(debug)

        root.addView(button("全部恢复默认") {
            AlertDialog.Builder(this)
                .setTitle("恢复默认")
                .setMessage("确定要把全部设置恢复默认吗？")
                .setPositiveButton("确定") { _, _ ->
                    prefs.edit().clear().apply()
                    build()
                    send(ConfigSpec.CMD_RESET)
                }
                .setNegativeButton("取消", null)
                .show()
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    /** 把一组的开关和选择项按定义顺序加进去：开关在前，选择项在后（延迟、解释字数、震动强度放在各自开关下面） */
    private fun addGroup(parent: LinearLayout, g: Group) {
        for (item in ConfigSpec.items) if (item.group == g) parent.addView(switch(item), matchWidth())
        for (item in ConfigSpec.intItems) if (item.group == g) parent.addView(choice(item), matchWidth())
    }

    private fun switch(item: ConfigSpec.Item) = Switch(this).apply {
        text = item.label
        isChecked = prefs.getBoolean(item.key, item.default)
        setPadding(0, pad / 2, 0, pad / 2)
        setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(item.key, checked).apply()
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

    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun heading(t: String, size: Float = 18f) = TextView(this).apply {
        text = t
        textSize = size
        isFocusable = true
        setPadding(0, pad, 0, pad / 4)
        if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }

    private fun note(t: String) = TextView(this).apply {
        text = t
        setPadding(0, 0, 0, pad / 4)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    /** 把全部设置值（和可选命令）发给豆包进程；用有序广播拿回执 */
    private fun send(cmd: String?) {
        val intent = Intent(ConfigSpec.ACTION_CONFIG).setPackage(ConfigSpec.TARGET_PACKAGE)
        for (item in ConfigSpec.items) intent.putExtra(item.key, prefs.getBoolean(item.key, item.default))
        for (item in ConfigSpec.intItems) intent.putExtra(item.key, prefs.getInt(item.key, item.default))
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
                        "模块没有回应。请先在任意输入框弹出一次豆包键盘再点“查询模块状态”；仍不行请检查 LSPosed 作用域并强制停止豆包输入法。"
                    }
                    status.text = "状态：$msg"
                    status.announceForAccessibility(msg)
                }
            }, null, RESULT_CANCELED, null, null)
        } catch (t: Throwable) {
            status.text = "状态：发送失败 ${t.message}"
        }
    }
}
