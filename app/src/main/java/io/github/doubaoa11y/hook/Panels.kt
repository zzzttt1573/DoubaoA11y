package io.github.doubaoa11y.hook

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import java.lang.ref.WeakReference

/**
 * 交给读屏处理的界面（豆包菜单、剪贴板、常用语）：用读屏焦点、左右滑动浏览。
 * 这些界面打开时，被它盖住的工具栏、候选栏对读屏隐藏，免得焦点落到看不见的控件上。
 */
object Panels {
    private val names = setOf(
        "ToolboxKeyboardView",
        "ClipboardCommonPhraseContainer",
        "ClipboardHistoryListView",
        "CommonPhraseListView",
    )
    private val main = Handler(Looper.getMainLooper())
    private var last: WeakReference<View>? = null

    fun isPanel(v: View): Boolean = v.javaClass.simpleName in names || CustomToolbar.isPanel(v)

    fun note(v: View) {
        if (last?.get() !== v) last = WeakReference(v)
    }

    fun active(): View? = last?.get()?.takeIf { it.isShown && it.width > 0 }

    private fun isDescendant(v: View, ancestor: View): Boolean {
        var p: Any? = v
        while (p is View) {
            if (p === ancestor) return true
            p = p.parent
        }
        return false
    }

    /** 这个控件被打开的界面盖住了（不在界面里面，也不是界面的上层容器） */
    fun covered(v: View): Boolean {
        val panel = active() ?: return false
        if (v.rootView !== panel.rootView) return false
        return !isDescendant(v, panel) && !isDescendant(panel, v)
    }

    fun hide(info: AccessibilityNodeInfo) {
        info.isVisibleToUser = false
        info.isFocusable = false
        info.isClickable = false
        info.isLongClickable = false
        info.contentDescription = null
        info.text = null
        info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
        info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK)
        info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
    }

    fun find(v: View): View? {
        if (v.visibility != View.VISIBLE) return null
        if (isPanel(v)) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
        return null
    }

    /** 点了按钮之后如果打开了这类界面，把读屏焦点放到第一个项目 */
    fun focusIfOpened(root: View) {
        main.postDelayed({
            try {
                val panel = find(root) ?: return@postDelayed
                if (!panel.isShown) return@postDelayed
                note(panel)
                TatansBridge.update("打开读屏面板")
                L.i("打开了 ${panel.javaClass.simpleName}，交给读屏")
                if (panel.javaClass.simpleName == "ToolboxKeyboardView") ToolboxPager.focusFirst(panel)
                else focusFirst(panel)
            } catch (t: Throwable) {
                L.hookError("Panels.focusIfOpened", t)
            }
        }, 450)
    }

    /** 按阅读顺序（从上到下、从左到右）第一个能点或有文字的控件 */
    private fun focusFirst(panel: View) {
        var best: View? = null
        var bt = 0
        var bl = 0
        fun walk(v: View) {
            if (v.visibility != View.VISIBLE) return
            val r = android.graphics.Rect()
            if (!v.getGlobalVisibleRect(r)) return
            val texty = v is android.widget.TextView && !v.text.isNullOrBlank()
            if ((v.isClickable || v.isFocusable || texty) && v !== panel && v.isImportantForAccessibility) {
                if (best == null || r.top < bt - 8 || (kotlin.math.abs(r.top - bt) <= 8 && r.left < bl)) {
                    best = v
                    bt = r.top
                    bl = r.left
                }
                if (v.isClickable) return
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(panel)
        best?.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
    }
}
