package io.github.doubaoa11y.hook

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.doubaoa11y.ConfigSpec

/**
 * 第一次使用、模块更新后第一次弹出键盘时，全屏显示免责声明。
 * 点“同意”记下这个模块版本，以后不再显示；点“不同意并退出”收起键盘、切回上一个输入法，下次弹出键盘再显示。
 * 弹窗是挂在键盘窗口上的子窗口（键盘窗口本身是全屏的），弹窗开着时模块不接管触摸，交给读屏正常浏览。
 */
object Disclaimer {
    private const val KEY = "disclaimerAgreed"
    private val main = Handler(Looper.getMainLooper())
    private var dialog: Dialog? = null

    val showing: Boolean get() = dialog?.isShowing == true

    /** 这个视图是弹窗的根视图 */
    fun isRoot(v: View): Boolean = showing && dialog?.window?.decorView === v

    private fun agreed(ctx: Context): Boolean =
        ctx.getSharedPreferences(ConfigSpec.PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY, 0) == ConfigSpec.MODULE_VERSION_CODE

    /** 键盘显示后调用 */
    fun maybeShow(service: InputMethodService) {
        try {
            if (showing || agreed(service)) return
            // 等键盘窗口真正挂上去，才有窗口令牌
            main.postDelayed({
                try {
                    show(service)
                } catch (t: Throwable) {
                    L.hookError("Disclaimer.show", t)
                }
            }, 300)
        } catch (t: Throwable) {
            L.hookError("Disclaimer.maybeShow", t)
        }
    }

    fun onHidden() {
        try {
            dialog?.dismiss()
        } catch (_: Throwable) {
        }
        dialog = null
    }

    private fun show(service: InputMethodService) {
        if (showing || agreed(service)) return
        val decor = service.window?.window?.decorView ?: return
        val token = decor.windowToken ?: return
        val ctx: Context = service
        val d = density(ctx)
        val pad = (20 * d).toInt()

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            setBackgroundColor(Color.WHITE)
        }
        fun text(s: String, size: Float, bold: Boolean = false, color: Int = Color.BLACK) = TextView(ctx).apply {
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

        val dlg = Dialog(ctx, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        fun button(label: String, onClick: () -> Unit) = Button(ctx).apply {
            text = label
            textSize = 18f
            isAllCaps = false
            setOnClickListener {
                try {
                    onClick()
                } catch (t: Throwable) {
                    L.hookError("Disclaimer.click", t)
                }
            }
        }
        val agree = button("同意") {
            service.getSharedPreferences(ConfigSpec.PREFS_NAME, Context.MODE_PRIVATE).edit().putInt(KEY, ConfigSpec.MODULE_VERSION_CODE).apply()
            L.i("用户同意免责声明（模块版本 ${ConfigSpec.MODULE_VERSION_NAME}）")
            dlg.dismiss()
            TatansBridge.update("关闭免责声明")
        }
        val refuse = button("不同意并退出") {
            L.i("用户不同意免责声明，退出键盘")
            dlg.dismiss()
            exitKeyboard(service)
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, pad, 0, 0)
            addView(agree, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(refuse, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        box.addView(row)

        val scroll = ScrollView(ctx).apply {
            setBackgroundColor(Color.WHITE)
            isFillViewport = true
            addView(box)
        }
        dlg.setContentView(scroll)
        dlg.setCancelable(false)
        dlg.setCanceledOnTouchOutside(false)
        val w = dlg.window ?: return
        w.setBackgroundDrawableResource(android.R.color.white)
        val lp = w.attributes
        lp.token = token
        lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT
        lp.gravity = Gravity.FILL
        lp.title = "使用须知"
        w.attributes = lp
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dlg.setOnDismissListener { if (dialog === dlg) dialog = null }
        dialog = dlg
        dlg.show()
        TatansBridge.update("显示免责声明")
        L.i("显示免责声明（模块版本 ${ConfigSpec.MODULE_VERSION_NAME}）")
    }

    /** 不同意：切回上一个输入法；切不了就收起键盘 */
    private fun exitKeyboard(service: InputMethodService) {
        val switched = try {
            if (android.os.Build.VERSION.SDK_INT >= 28) service.switchToPreviousInputMethod() else false
        } catch (_: Throwable) {
            false
        }
        if (!switched) service.requestHideSelf(0)
        Speaker.speak(if (switched) "已切换回上一个输入法" else "键盘已收起")
    }

    private fun density(c: Context) = c.resources.displayMetrics.density
}
