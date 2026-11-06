package io.github.doubaoa11y.hook

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import de.robv.android.xposed.XposedHelpers

/**
 * 豆包菜单（工具箱）交给读屏：左右滑动逐个浏览。
 * 底部的小圆点（页码指示器）做成一个“下一页”/“上一页”按钮，读屏双击翻页，并聚焦新一页的第一个项目。
 */
object ToolboxPager {
    private val main = Handler(Looper.getMainLooper())

    fun isIndicator(v: View): Boolean {
        val n = idName(v)
        return n == "toolbox_indicator_container" || (n == "indicatorLayout" && CustomToolbar.panelOf(v) != null)
    }

    private fun isItem(v: View): Boolean =
        (v.javaClass.simpleName == "ToolboxItemView" && v.isClickable) || CustomToolbar.isOption(v)

    private fun idName(v: View): String? {
        if (v.id == View.NO_ID || v.id ushr 24 == 0) return null
        return try {
            v.resources.getResourceEntryName(v.id)
        } catch (_: Throwable) {
            null
        }
    }

    fun inPanel(v: View): Boolean = panelOf(v) != null

    /** 读屏自己让菜单翻页（滚动动作）：翻完把焦点放到新一页第一个项目 */
    fun afterScroll(v: View) {
        val panel = panelOf(v) ?: return
        main.postDelayed({ focusFirst(panel) }, 350)
    }

    private fun panelOf(v: View): View? {
        var p: Any? = v
        while (p is View) {
            if (p.javaClass.simpleName == "ToolboxKeyboardView" || CustomToolbar.isPanel(p)) return p
            p = p.parent
        }
        return null
    }

    private fun pager(panel: View): View? {
        for (n in listOf("toolbox_view_pager", "viewPager")) {
            val id = panel.resources.getIdentifier(n, "id", panel.context.packageName)
            if (id != 0) panel.findViewById<View>(id)?.let { return it }
        }
        return null
    }

    private fun pageInfo(panel: View): Pair<Int, Int>? {
        val vp = pager(panel) ?: return null
        return try {
            val cur = XposedHelpers.callMethod(vp, "getCurrentItem") as Int
            val adapter = XposedHelpers.callMethod(vp, "getAdapter") ?: return null
            val count = XposedHelpers.callMethod(adapter, "getItemCount") as Int
            cur to count
        } catch (t: Throwable) {
            L.hookError("ToolboxPager.pageInfo", t)
            null
        }
    }

    /** 要翻到哪一页：不是最后一页往后翻，最后一页往前翻 */
    private fun target(cur: Int, count: Int) = if (cur < count - 1) cur + 1 else cur - 1

    private fun nameFor(cur: Int, count: Int) = if (cur < count - 1) "下一页" else "上一页"

    /** 指示器（底部小圆点）的无障碍节点：一个能点的“下一页”/“上一页”按钮 */
    fun label(v: View, info: AccessibilityNodeInfo) {
        val panel = panelOf(v) ?: return
        val (cur, count) = pageInfo(panel) ?: return
        if (count <= 1) return
        val t = nameFor(cur, count)
        info.isFocusable = true
        info.isVisibleToUser = true
        info.isEnabled = true
        info.isClickable = true
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
        info.className = "android.widget.Button"
        info.text = t
        info.contentDescription = t
    }

    /** 让小圆点进到读屏的焦点列表里：容器本身算一个控件，里面的圆点不单独算 */
    fun prepare(panel: View) {
        val ind = findIndicator(panel) ?: return
        val (cur, count) = pageInfo(panel) ?: return
        if (count <= 1) return
        ind.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        ind.contentDescription = nameFor(cur, count)
        // 有的读屏双击是在控件上模拟一次点按，不走无障碍点击；小圆点本来不能点，补一个点击
        if (!ind.hasOnClickListeners()) ind.setOnClickListener { onClick(it) }
        if (ind is ViewGroup) for (i in 0 until ind.childCount) {
            ind.getChildAt(i).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
    }

    private fun findIndicator(v: View): View? {
        if (isIndicator(v)) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findIndicator(v.getChildAt(i))?.let { return it }
        return null
    }

    /** 读屏双击“下一页”/“上一页”：翻页，然后聚焦新一页的第一个项目。返回 true 表示已处理 */
    fun onClick(v: View): Boolean {
        val panel = panelOf(v) ?: return false
        val (cur, count) = pageInfo(panel) ?: return false
        if (count <= 1) return false
        val next = target(cur, count)
        val vp = pager(panel) ?: return false
        try {
            XposedHelpers.callMethod(vp, "setCurrentItem", next, false)
        } catch (t: Throwable) {
            L.hookError("ToolboxPager.setCurrentItem", t)
            return false
        }
        L.i("工具箱翻到第 ${next + 1} 页")
        main.postDelayed({
            prepare(panel)
            focusFirst(panel)
        }, 250)
        return true
    }

    /** 聚焦当前页的第一个项目 */
    fun focusFirst(panel: View) {
        try {
            prepare(panel)
            val item = firstItem(panel) ?: return
            item.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
        } catch (t: Throwable) {
            L.hookError("ToolboxPager.focusFirst", t)
        }
    }

    private fun firstItem(panel: View): View? {
        val vp = pager(panel) ?: return null
        // 屏幕上看得见的第一个项目
        var best: View? = null
        var bestPos: Pair<Int, Int>? = null
        fun walk(v: View) {
            if (v.visibility != View.VISIBLE) return
            if (isItem(v)) {
                val r = android.graphics.Rect()
                if (v.getGlobalVisibleRect(r) && r.width() > v.width / 2) {
                    val pos = r.top to r.left
                    val b = bestPos
                    if (b == null || pos.first < b.first - 8 || (kotlin.math.abs(pos.first - b.first) <= 8 && pos.second < b.second)) {
                        best = v
                        bestPos = pos
                    }
                }
                return
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(vp)
        return best
    }

    /** 豆包菜单刚打开：把读屏焦点放到第一个项目 */
    fun focusIfOpened(root: View) {
        main.postDelayed({
            try {
                val panel = findPanel(root) ?: return@postDelayed
                if (panel.isShown) focusFirst(panel)
            } catch (t: Throwable) {
                L.hookError("ToolboxPager.focusIfOpened", t)
            }
        }, 450)
    }

    private fun findPanel(v: View): View? {
        if (v.javaClass.simpleName == "ToolboxKeyboardView") return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findPanel(v.getChildAt(i))?.let { return it }
        return null
    }
}
