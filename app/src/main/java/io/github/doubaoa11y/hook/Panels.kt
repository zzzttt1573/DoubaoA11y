package io.github.doubaoa11y.hook

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import java.lang.ref.WeakReference

/**
 * 交给读屏处理的界面（豆包菜单、剪贴板、常用语、表情键盘）：用读屏焦点、左右滑动浏览。
 * 这些界面打开时，被它盖住的工具栏、候选栏对读屏隐藏，免得焦点落到看不见的控件上。
 */
object Panels {
    private val names = setOf(
        "ToolboxKeyboardView",
        "ClipboardCommonPhraseContainer",
        "ClipboardHistoryView",
        "ClipboardHistoryListView",
        "ClipboardHistoryClearView",
        "ClipboardHistorySettingsView",
        "ClipboardHistoryEmptyView",
        "CommonPhraseListView",
        "EmojiKeyboardView",
        "EmojiPanelView",
    )
    private val panelIds = setOf(
        "emoji_layout",
    )
    private val main = Handler(Looper.getMainLooper())
    private var last: WeakReference<View>? = null
    private var lastWasShown = false
    private var pendingFocusGen = 0L
    private var pendingLayoutView: WeakReference<View>? = null
    private var pendingLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var pendingTimeoutRunner: Runnable? = null
    private var pendingConfirmRunner: Runnable? = null
    private var autoFocusing = false

    private val excludedContentIds = setOf(
        "btn_delete_clipboard", "clipboard_clear_icon", "clipboard_history_delete_btn",
        "common_phrase_delete_btn", "btn_close_clipboard_clear_view", "btn_close_clipboard_common_phrase",
        "btn_close_clipboard_history", "btn_close_phrase_empty_view", "btn_add_common_phrase",
        "btn_backspace", "btn_close", "btn_toolbox_back", "iv_back", "close_btn",
        "toolbar_clipboard_close", "toolbar_clipboard_smart_organize", "toolbar_clipboard_word_segmentation"
    )

    private fun entryName(v: View): String? {
        val id = v.id
        if (id == View.NO_ID || id ushr 24 == 0) return null
        return try {
            v.resources.getResourceEntryName(id)
        } catch (_: Throwable) {
            null
        }
    }

    fun isPanel(v: View): Boolean =
        v.javaClass.simpleName in names ||
            entryName(v) in panelIds ||
            CustomToolbar.isPanel(v)

    fun note(v: View, triggerFocus: Boolean = true) {
        val prev = last?.get()
        val shown = v.isShown
        val newlyShown = prev !== v || (!lastWasShown && shown)
        last = WeakReference(v)
        lastWasShown = shown
        if (triggerFocus && newlyShown && shown && !CustomToolbar.isPanel(v)) {
            schedulePanelFocus(v)
        }
    }

    /** 用户自己把读屏焦点移到了面板内其他控件：立即取消待决的自动首焦，避免抢焦 */
    fun onUserAccessibilityFocus(v: View) {
        if (autoFocusing) return
        val panel = active() ?: return
        if (isDescendant(v, panel)) {
            cancelPendingFocus()
        }
    }

    private fun cancelPendingFocus() {
        pendingFocusGen++
        pendingTimeoutRunner?.let { main.removeCallbacks(it) }
        pendingTimeoutRunner = null
        pendingConfirmRunner?.let { main.removeCallbacks(it) }
        pendingConfirmRunner = null
        val lv = pendingLayoutView?.get()
        val li = pendingLayoutListener
        pendingLayoutView = null
        pendingLayoutListener = null
        if (lv != null && li != null) {
            try {
                if (lv.viewTreeObserver.isAlive) lv.viewTreeObserver.removeOnGlobalLayoutListener(li)
            } catch (_: Throwable) {
            }
        }
    }

    fun active(): View? {
        val v = last?.get() ?: return null
        if (v.isShown && v.width > 0) return v
        lastWasShown = false
        return null
    }

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
        if (CustomToolbar.editing()) {
            val imeRoot = try {
                ViewA11yHooks.service?.window?.window?.decorView
            } catch (_: Throwable) {
                null
            }
            if (imeRoot != null && v.rootView === imeRoot) return true
        }
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

    /** 点了按钮之后如果打开了这类界面，事件驱动把读屏焦点直接放到第一条实际内容（不再固定死等 450 ms） */
    fun focusIfOpened(root: View) {
        cancelPendingFocus()
        val gen = pendingFocusGen
        fun tryRoot(): Boolean {
            if (gen != pendingFocusGen) return true
            val panel = find(root) ?: return false
            if (!panel.isShown) return false
            note(panel, triggerFocus = false)
            TatansBridge.update("打开读屏面板")
            L.i("打开了 ${panel.javaClass.simpleName}，交给读屏")
            schedulePanelFocus(panel)
            return true
        }
        root.post {
            try {
                if (tryRoot()) return@post
                // 宿主若尚未把面板挂上视图树，用 OnGlobalLayoutListener 监听并在 800ms 后自动注销兜底
                val li = ViewTreeObserver.OnGlobalLayoutListener {
                    try {
                        if (gen != pendingFocusGen || tryRoot()) {
                            // tryRoot 内部调用 schedulePanelFocus 会推进 pendingFocusGen 并清理监听
                        }
                    } catch (t: Throwable) {
                        L.hookError("Panels.focusIfOpened.layout", t)
                        cancelPendingFocus()
                    }
                }
                pendingLayoutView = WeakReference(root)
                pendingLayoutListener = li
                if (root.viewTreeObserver.isAlive) root.viewTreeObserver.addOnGlobalLayoutListener(li)
                val timeout = Runnable {
                    if (gen == pendingFocusGen) cancelPendingFocus()
                }
                pendingTimeoutRunner = timeout
                main.postDelayed(timeout, 800L)
            } catch (t: Throwable) {
                L.hookError("Panels.focusIfOpened", t)
            }
        }
    }

    /** 对已可见的面板执行事件驱动首焦：有内容立即落焦，未绑定则等布局/首项 attach（最多 1200ms 自动注销） */
    private fun schedulePanelFocus(panel: View) {
        cancelPendingFocus()
        val gen = pendingFocusGen
        fun performFocus(target: View): Boolean {
            autoFocusing = true
            return try {
                target.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
            } finally {
                autoFocusing = false
            }
        }
        fun attempt(): Boolean {
            if (gen != pendingFocusGen || !panel.isShown) return true
            if (panel.javaClass.simpleName == "ToolboxKeyboardView") {
                ToolboxPager.focusFirst(panel)
                return true
            }
            val target = findFirstContentItem(panel) ?: return false
            val ok = performFocus(target)
            if (ok) {
                L.i("面板首焦命中：${panel.javaClass.simpleName} -> ${target.javaClass.simpleName}/${entryName(target) ?: ""}")
                // 防止刚点击工具栏按钮后读屏尾随事件把焦点拉回工具栏：120ms 后若未保持聚焦且用户未移焦，再补一次
                val confirm = Runnable {
                    if (gen != pendingFocusGen || !panel.isShown) return@Runnable
                    pendingConfirmRunner = null
                    try {
                        val currentTarget = findFirstContentItem(panel) ?: return@Runnable
                        if (!currentTarget.isAccessibilityFocused) {
                            performFocus(currentTarget)
                            L.i("面板首焦补落：${panel.javaClass.simpleName} -> ${currentTarget.javaClass.simpleName}/${entryName(currentTarget) ?: ""}")
                        }
                    } catch (_: Throwable) {
                    }
                }
                pendingConfirmRunner = confirm
                main.postDelayed(confirm, 120L)
            }
            return ok
        }
        panel.post {
            try {
                if (attempt()) return@post
                val li = ViewTreeObserver.OnGlobalLayoutListener {
                    try {
                        if (gen != pendingFocusGen || attempt()) {
                            if (gen == pendingFocusGen) cancelPendingFocus()
                        }
                    } catch (t: Throwable) {
                        L.hookError("Panels.scheduleFocus.layout", t)
                        cancelPendingFocus()
                    }
                }
                pendingLayoutView = WeakReference(panel)
                pendingLayoutListener = li
                if (panel.viewTreeObserver.isAlive) panel.viewTreeObserver.addOnGlobalLayoutListener(li)
                val timeout = Runnable {
                    if (gen != pendingFocusGen) return@Runnable
                    try {
                        // 超时兜底：若特定列表仍为空，回退到常规首项（如空态提示）
                        if (panel.isShown) focusFirstFallback(panel)
                    } catch (_: Throwable) {
                    } finally {
                        cancelPendingFocus()
                    }
                }
                pendingTimeoutRunner = timeout
                main.postDelayed(timeout, 1200L)
            } catch (t: Throwable) {
                L.hookError("Panels.schedulePanelFocus", t)
            }
        }
    }

    /** 按豆包 1.5.7 面板结构定位第一条实际内容（剪贴板/常用语/表情/图片表情），排除返回键、分类栏与删除按钮 */
    private fun findFirstContentItem(panel: View): View? {
        // 1. 剪贴板历史列表
        findVisibleById(panel, "clipboard_history_recycler_view")?.let { rv ->
            firstListContent(rv, "clipboard_history_item_text")?.let { return it }
            return null // 列表已挂载但数据尚未绑定，继续等待布局事件而不是误落顶部按钮
        }
        // 2. 常用语列表
        findVisibleById(panel, "common_phrases_recycler_view")?.let { rv ->
            firstListContent(rv, "common_phrase_item_text")?.let { return it }
            return null
        }
        // 3. 图片表情 / GIF 列表
        findVisibleById(panel, "emoticons_recycler_view")?.let { rv ->
            firstGridItem(rv)?.let { return it }
            return null
        }
        // 4. 普通表情 / 颜文字网格（在当前可见页的 EmojiGridRecyclerView 中取第一项）
        findVisibleEmojiGrid(panel)?.let { grid ->
            firstGridItem(grid)?.let { return it }
            return null
        }
        // 5. 其他读屏面板回退到阅读顺序首项
        return findFirstFallback(panel)
    }

    private fun findVisibleById(root: View, idName: String): ViewGroup? {
        if (root.visibility != View.VISIBLE || !root.isShown) return null
        if (root is ViewGroup && entryName(root) == idName) {
            val r = Rect()
            if (root.getGlobalVisibleRect(r) && r.width() > 0 && r.height() > 0) return root
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findVisibleById(root.getChildAt(i), idName)?.let { return it }
            }
        }
        return null
    }

    private fun findVisibleEmojiGrid(root: View): ViewGroup? {
        val pager = findVisibleById(root, "emoji_view_pager") ?: findVisibleById(root, "kaomoji_view_pager") ?: root
        val pr = Rect()
        if (!pager.getGlobalVisibleRect(pr) || pr.width() <= 0) return null
        val cx = pr.centerX()
        var best: ViewGroup? = null
        fun walk(v: View) {
            if (v.visibility != View.VISIBLE || !v.isShown) return
            if (v is ViewGroup && v.javaClass.simpleName == "EmojiGridRecyclerView") {
                val r = Rect()
                if (v.getGlobalVisibleRect(r) && r.width() > 0 && r.height() > 0 && cx in r.left..r.right) {
                    best = v
                    return
                }
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    walk(v.getChildAt(i))
                    if (best != null) return
                }
            }
        }
        walk(pager)
        return best
    }

    private fun firstListContent(rv: ViewGroup, preferredTextId: String): View? {
        var best: View? = null
        var bestTop = Int.MAX_VALUE
        val r = Rect()
        for (i in 0 until rv.childCount) {
            val row = rv.getChildAt(i)
            if (row.visibility != View.VISIBLE || !row.isShown || !row.getGlobalVisibleRect(r) || r.height() <= 0) continue
            val candidate = findVisibleByIdAny(row, preferredTextId) ?: firstFocusableContentInRow(row) ?: continue
            if (r.top < bestTop) {
                bestTop = r.top
                best = candidate
            }
        }
        return best
    }

    private fun findVisibleByIdAny(root: View, idName: String): View? {
        if (root.visibility != View.VISIBLE || !root.isShown) return null
        if (entryName(root) == idName && root.isImportantForAccessibility) {
            val r = Rect()
            if (root.getGlobalVisibleRect(r) && r.width() > 0 && r.height() > 0) return root
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findVisibleByIdAny(root.getChildAt(i), idName)?.let { return it }
            }
        }
        return null
    }

    private fun firstFocusableContentInRow(row: View): View? {
        var found: View? = null
        fun walk(v: View) {
            if (found != null || v.visibility != View.VISIBLE || !v.isShown) return
            val name = entryName(v)
            if (name != null && name in excludedContentIds) return
            if (v is android.widget.TextView && !v.text.isNullOrBlank() && v.isImportantForAccessibility) {
                found = v
                return
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(row)
        return found
    }

    private fun firstGridItem(grid: ViewGroup): View? {
        var best: View? = null
        var bt = Int.MAX_VALUE
        var bl = Int.MAX_VALUE
        val r = Rect()
        for (i in 0 until grid.childCount) {
            val child = grid.getChildAt(i)
            if (child.visibility != View.VISIBLE || !child.isShown || !child.getGlobalVisibleRect(r)) continue
            if (r.width() <= 0 || r.height() <= 0) continue
            val name = entryName(child)
            if (name != null && (name in excludedContentIds || name.contains("empty"))) continue
            if (child.isImportantForAccessibility && (child.isClickable || child.isFocusable || child is android.widget.TextView || !child.contentDescription.isNullOrBlank())) {
                if (best == null || r.top < bt - 8 || (kotlin.math.abs(r.top - bt) <= 8 && r.left < bl)) {
                    best = child
                    bt = r.top
                    bl = r.left
                }
            }
        }
        return best
    }

    private fun focusFirstFallback(panel: View) {
        val target = findFirstFallback(panel) ?: return
        autoFocusing = true
        try {
            target.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
        } finally {
            autoFocusing = false
        }
    }

    /** 按阅读顺序（从上到下、从左到右）第一个能点或有文字的控件 */
    private fun findFirstFallback(panel: View): View? {
        var best: View? = null
        var bt = 0
        var bl = 0
        fun walk(v: View) {
            if (v.visibility != View.VISIBLE) return
            val r = Rect()
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
        return best
    }
}
