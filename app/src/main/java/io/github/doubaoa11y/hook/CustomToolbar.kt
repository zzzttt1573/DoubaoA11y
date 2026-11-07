package io.github.doubaoa11y.hook

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.PopupWindow
import android.widget.TextView

/**
 * 定制工具栏（菜单 → 定制工具栏）：豆包用一个弹出窗口显示可选工具，两页，每页 8 个。
 * 交给读屏焦点：每个工具一个焦点，读“名字，已添加/未添加”，双击添加或移除；
 * 底部小圆点做成“下一页”/“上一页”（由 ToolboxPager 处理）。
 * 顶部已添加的工具在候选栏上（CandidateToolbarEditItemLayout），读成“名字，已添加”。
 */
object CustomToolbar {
    private val main = Handler(Looper.getMainLooper())
    private var current: java.lang.ref.WeakReference<View>? = null
    private const val ROW_TAG = "doubaoa11y_top_row"

    /** 定制工具栏开着（顶部工具栏处于编辑状态） */
    fun editing(): Boolean = current?.get()?.isShown == true

    private fun id(v: View, name: String): Int = v.resources.getIdentifier(name, "id", v.context.packageName)

    private fun idName(v: View): String? {
        if (v.id == View.NO_ID || v.id ushr 24 == 0) return null
        return try {
            v.resources.getResourceEntryName(v.id)
        } catch (_: Throwable) {
            null
        }
    }

    /** 弹出窗口里的内容根视图（有“靠左吸附”和翻页） */
    fun isPanel(v: View): Boolean {
        if (v !is ViewGroup) return false
        if (v.parent?.javaClass?.simpleName != "PopupDecorView") return false
        return isContent(v)
    }

    private fun isContent(v: View): Boolean {
        if (v !is ViewGroup) return false
        val tab = id(v, "tabLeft")
        val vp = id(v, "viewPager")
        return tab != 0 && vp != 0 && v.findViewById<View>(tab) != null && v.findViewById<View>(vp) != null
    }

    fun panelOf(v: View): View? {
        var p: Any? = v
        while (p is View) {
            if (isPanel(p)) return p
            p = p.parent
        }
        return null
    }

    /** 一个可选工具的可点外框（OptionItemView 里的 id=root） */
    fun isOption(v: View): Boolean = v.parent?.javaClass?.simpleName == "OptionItemView" && idName(v) == "root"

    private fun optionOf(v: View): View? {
        var p: Any? = v
        var depth = 0
        while (p is View && depth < 6) {
            if (p.javaClass.simpleName == "OptionItemView") return p
            p = p.parent
            depth++
        }
        return null
    }

    /** OptionItemView 里唯一的非静态 boolean 字段就是“已添加” */
    private fun added(opt: View): Boolean? = try {
        val f = opt.javaClass.declaredFields.firstOrNull {
            it.type == Boolean::class.javaPrimitiveType && !java.lang.reflect.Modifier.isStatic(it.modifiers)
        }
        f?.isAccessible = true
        f?.getBoolean(opt)
    } catch (t: Throwable) {
        L.hookError("CustomToolbar.added", t)
        null
    }

    private fun labelOf(opt: View): String {
        // 单手模式已改成编辑面板，和工具栏上的读法一致
        if (idName(opt) == "item_one_hand") return "编辑面板"
        val t = (opt.findViewById<View>(id(opt, "labelText")) as? TextView)?.text?.toString()?.trim()
        if (!t.isNullOrEmpty()) return t.replace("(大)", "，大").replace("(小)", "，小")
        return ViewA11yHooks.mappedName(opt) ?: "工具"
    }

    private fun stateName(opt: View): String {
        val name = labelOf(opt)
        return when (added(opt)) {
            true -> "$name，已添加"
            false -> "$name，未添加"
            null -> name
        }
    }

    /** 节点处理；返回 true 表示已处理 */
    fun label(v: View, info: AccessibilityNodeInfo): Boolean {
        if (panelOf(v) == null) return false
        if (isOption(v)) {
            val opt = v.parent as View
            val t = stateName(opt)
            info.text = t
            info.contentDescription = t
            info.className = "android.widget.Button"
            info.isClickable = true
            info.isFocusable = true
            return true
        }
        // 工具里的图标、名字不再单独占焦点
        if (optionOf(v) != null) {
            Panels.hide(info)
            return true
        }
        val entry = idName(v)
        if (entry == "tabLeft" || entry == "tabRight") {
            val name = (v as? TextView)?.text?.toString() ?: return false
            val t = if (v.background != null) "$name，已选中" else name
            info.contentDescription = t
            return true
        }
        return false
    }

    /** 读屏双击一个工具：点图标切换添加/移除，然后读出结果 */
    fun click(v: View): Boolean {
        if (!isOption(v) || panelOf(v) == null) return false
        val opt = v.parent as View
        val icon = opt.findViewById<View>(id(opt, "iconImage")) ?: return false
        icon.performClick()
        main.postDelayed({
            try {
                val name = labelOf(opt)
                val msg = when (added(opt)) {
                    true -> "已添加$name"
                    false -> "已移除$name"
                    null -> name
                }
                L.i("定制工具栏：$msg")
                Speaker.speak(msg)
                panelOf(v)?.let { refreshRowLater(it) }
                v.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            } catch (t: Throwable) {
                L.hookError("CustomToolbar.click", t)
            }
        }, 120)
        return true
    }

    /** 弹出窗口显示时：把读屏焦点放到第一个工具 */
    fun install() {
        Hooker.byName(PopupWindow::class.java, "showAtLocation", SafeHook("PopupWindow.showAtLocation", after = { p -> onShown(p.thisObject as PopupWindow) }))
        Hooker.byName(PopupWindow::class.java, "showAsDropDown", SafeHook("PopupWindow.showAsDropDown", after = { p -> onShown(p.thisObject as PopupWindow) }))
    }

    private fun onShown(w: PopupWindow) {
        if (!A11yState.recording) return
        val content = w.contentView ?: return
        if (!isContent(content)) return
        L.i("打开了定制工具栏，交给读屏")
        current = java.lang.ref.WeakReference(content)
        TatansBridge.update("打开定制工具栏")
        w.setOnDismissListener(wrapDismiss(w))
        main.postDelayed({
            try {
                refreshRow(content)
            } catch (t: Throwable) {
                L.hookError("CustomToolbar.refreshRow", t)
            }
        }, 200)
        main.postDelayed({
            try {
                val panel = content.parent as? View ?: return@postDelayed
                if (panel.isShown) ToolboxPager.focusFirst(content)
            } catch (t: Throwable) {
                L.hookError("CustomToolbar.onShown", t)
            }
        }, 450)
    }

    /** 定制工具栏关掉后恢复穿透（保留豆包自己的关闭回调） */
    private fun wrapDismiss(w: PopupWindow): PopupWindow.OnDismissListener {
        val old = try {
            val f = PopupWindow::class.java.getDeclaredField("mOnDismissListener")
            f.isAccessible = true
            f.get(w) as? PopupWindow.OnDismissListener
        } catch (_: Throwable) {
            null
        }
        return PopupWindow.OnDismissListener {
            try {
                old?.onDismiss()
            } finally {
                main.postDelayed({ TatansBridge.update("关闭定制工具栏") }, 200)
            }
        }
    }

    // ---- 弹出窗口顶部的一排代理按钮：对应键盘上方工具栏的“完成”和已添加的工具 ----
    // 读屏左右滑动只在弹出窗口里转，到不了键盘窗口里的工具栏，所以在弹出窗口里放一份。

    private fun refreshRowLater(content: View) {
        main.postDelayed({
            try {
                refreshRow(content)
            } catch (t: Throwable) {
                L.hookError("CustomToolbar.refreshRow", t)
            }
        }, 350)
    }

    private fun imeRoot(): View? = try {
        ViewA11yHooks.service?.window?.window?.decorView
    } catch (_: Throwable) {
        null
    }

    /** 键盘上方工具栏现在的样子：(读法, 双击时要点的控件) */
    private fun topItems(): List<Pair<String, View>> {
        val root = imeRoot() ?: return emptyList()
        val out = ArrayList<Pair<String, View>>()
        fun byId(n: String): View? {
            val id = id(root, n)
            return if (id != 0) root.findViewById(id) else null
        }
        byId("btn_logo")?.takeIf { it.isShown }?.let { out.add("完成" to it) }
        val container = byId("middle_container") as? ViewGroup
        if (container != null) for (i in 0 until container.childCount) {
            val c = container.getChildAt(i)
            if (!c.isShown || c.javaClass.simpleName != "CandidateToolbarEditItemLayout") continue
            val cd = c.contentDescription?.toString() ?: continue
            val name = Names.toolbarKeys[cd] ?: Names.guessFromId(cd) ?: cd
            out.add("$name，已添加，双击移除" to c)
        }
        val hide = byId("btn_hide")
        val hideRemove = byId("btn_hide_remove")
        if (hide != null && hide.isShown && hideRemove != null && hideRemove.isShown) {
            out.add("收起键盘，已添加，双击移除" to hideRemove)
        }
        return out
    }

    private fun refreshRow(content: View) {
        val box = content as? android.widget.LinearLayout ?: return
        if (!content.isShown) return
        var row = box.findViewWithTag<android.widget.LinearLayout>(ROW_TAG)
        if (row == null) {
            row = android.widget.LinearLayout(box.context).apply {
                tag = ROW_TAG
                orientation = android.widget.LinearLayout.HORIZONTAL
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            box.addView(row, 0, android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        row.removeAllViews()
        val items = topItems()
        val pad = (6 * box.resources.displayMetrics.density).toInt()
        for ((name, target) in items) {
            val b = TextView(box.context).apply {
                text = name.substringBefore("，")
                contentDescription = name
                textSize = 12f
                setPadding(pad, pad, pad, pad)
                isClickable = true
                isFocusable = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                setOnClickListener {
                    L.i("定制工具栏顶部：$name")
                    target.performClick()
                    val n = name.substringBefore("，")
                    if (n != "完成") {
                        Speaker.speak("已移除$n")
                        refreshRowLater(content)
                    }
                }
            }
            row.addView(b, android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        L.i("定制工具栏顶部代理：${items.joinToString { it.first }}")
    }
}
