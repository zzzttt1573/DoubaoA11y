package io.github.doubaoa11y.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.doubaoa11y.ConfigSpec

/**
 * 第一次打开设置页（模块更新后也算第一次）时全屏显示使用须知。
 * 点“同意”记下这个模块版本，以后不再显示；点“不同意并退出”直接关掉设置页，下次打开再显示。
 */
object Disclaimer {
    private const val PREFS = "settings"
    private const val KEY = "disclaimerAgreed"

    fun agreed(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, 0) == ConfigSpec.MODULE_VERSION_CODE

    /** 没同意过就显示；点了“同意”后调用 onAgree */
    fun showIfNeeded(act: Activity, onAgree: () -> Unit) {
        if (agreed(act)) return
        val d = act.resources.displayMetrics.density
        val pad = (20 * d).toInt()

        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            setBackgroundColor(Color.WHITE)
        }
        fun text(s: String, size: Float, bold: Boolean = false, color: Int = Color.BLACK) = TextView(act).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, pad / 2, 0, pad / 2)
            setLineSpacing(0f, 1.2f)
        }
        box.addView(text("豆包输入法无障碍模块 使用须知", 22f, bold = true))
        box.addView(text(
            "1. 本模块是个人为视障用户开发的无障碍辅助工具，与北京字节跳动科技有限公司及豆包输入法官方没有任何关系，也没有得到其授权。\n" +
                "2. 本模块免费提供，不收集、不上传你输入的任何内容和个人信息，不得用于商业或违法用途。\n" +
                "3. 使用本模块需要用 LSPatch 修补豆包输入法，可能出现词库或登录丢失、账号异常、功能失效等情况，风险由你自行承担，开发者不承担责任。", 17f))
        box.addView(text(
            "如果你是直接下载的直装版 APK，这不是原作者制作的，直装版可能有安全风险，原作者不对直装版的问题和风险负责！！！",
            18f, bold = true, color = Color.rgb(0xB0, 0x00, 0x20)))
        box.addView(text("完整声明见整合包里的“使用说明.txt”。", 15f, color = Color.DKGRAY))

        val dlg = Dialog(act, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        fun button(label: String, onClick: () -> Unit) = Button(act).apply {
            text = label
            textSize = 18f
            isAllCaps = false
            setOnClickListener { onClick() }
        }
        val agree = button("同意") {
            act.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, ConfigSpec.MODULE_VERSION_CODE).apply()
            dlg.dismiss()
            onAgree()
        }
        val refuse = button("不同意并退出") {
            dlg.dismiss()
            act.finishAffinity()
        }
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, pad, 0, 0)
            addView(agree, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(refuse, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        box.addView(row)

        val scroll = ScrollView(act).apply {
            setBackgroundColor(Color.WHITE)
            isFillViewport = true
            addView(box)
        }
        dlg.setContentView(scroll)
        // 返回键等同“不同意并退出”
        dlg.setCancelable(true)
        dlg.setCanceledOnTouchOutside(false)
        dlg.setOnCancelListener { act.finishAffinity() }
        dlg.window?.let { w ->
            w.setBackgroundDrawableResource(android.R.color.white)
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.attributes = w.attributes.apply { title = "使用须知" }
        }
        dlg.show()
    }
}
