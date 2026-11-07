package io.github.doubaoa11y.hook

import android.graphics.Rect
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Collections

/**
 * 豆包的 Java 控件（工具栏、候选栏、手写键盘的按钮）给读屏一个中文名。
 * - 工具栏按钮的 contentDescription 是内部标识（btn_xxx），豆包靠它找按钮，所以只改无障碍节点，不改控件本身。
 * - 没有文字也没有描述的控件，按资源 id 名称给名字；表里没有的记一条日志，方便补表。
 */
object ViewA11yHooks {
    private val reported = Collections.synchronizedSet(HashSet<String>())

    fun install() {
        Hooker.method(View::class.java, "createAccessibilityNodeInfo", arrayOf(),
            SafeHook("View.createAccessibilityNodeInfo", after = { p ->
                if (!A11yState.recording || !Config.b("labelViews")) return@SafeHook
                val info = p.result as? AccessibilityNodeInfo ?: return@SafeHook
                val v = p.thisObject as View
                if (Panels.isPanel(v)) Panels.note(v)
                when {
                    ToolboxPager.isIndicator(v) -> ToolboxPager.label(v, info)
                    CustomToolbar.label(v, info) -> {}
                    Panels.covered(v) -> Panels.hide(info)
                    else -> label(v, info)
                }
            }))
        // 模块合成点击时，压住控件自己发出的点击、焦点事件（读屏焦点留在编辑框，不响点击声）
        Hooker.method(View::class.java, "sendAccessibilityEvent", arrayOf(Int::class.javaPrimitiveType!!),
            SafeHook("View.sendAccessibilityEvent", before = { p ->
                if (android.os.SystemClock.uptimeMillis() > VirtualKeys.quietUntil) return@SafeHook
                val type = p.args[0] as Int
                if (type == android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED ||
                    type == android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED ||
                    type == android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SELECTED ||
                    type == android.view.accessibility.AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
                    p.result = null
                }
            }))
        // 工具箱翻页：读屏双击“下一页”/“上一页”时翻页
        Hooker.method(View::class.java, "performAccessibilityAction", arrayOf(Int::class.javaPrimitiveType!!, android.os.Bundle::class.java),
            SafeHook("View.performAccessibilityAction", before = { p ->
                if (!A11yState.recording) return@SafeHook
                val v = p.thisObject as View
                val action = p.args[0] as Int
                if (ToolboxPager.inPanel(v)) {
                    if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                        ToolboxPager.afterScroll(v)
                    }
                    L.i("菜单里读屏操作：动作=${actionName(action)} 控件=${v.javaClass.simpleName} ${idName(v) ?: ""} 「${v.contentDescription ?: (v as? android.widget.TextView)?.text ?: ""}」")
                }
                if (action == AccessibilityNodeInfo.ACTION_CLICK && CustomToolbar.isOption(v)) {
                    if (CustomToolbar.click(v)) p.result = true
                }
                if (action == AccessibilityNodeInfo.ACTION_CLICK && ToolboxPager.isIndicator(v)) {
                    if (ToolboxPager.onClick(v)) p.result = true
                }
            }))
    }

    private fun actionName(a: Int): String = when (a) {
        AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> "聚焦"
        AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> "取消聚焦"
        AccessibilityNodeInfo.ACTION_CLICK -> "点击"
        AccessibilityNodeInfo.ACTION_LONG_CLICK -> "长按"
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "向前滚动"
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> "向后滚动"
        AccessibilityNodeInfo.ACTION_FOCUS -> "输入焦点"
        else -> "0x${Integer.toHexString(a)}"
    }

    private fun idName(v: View): String? {
        val id = v.id
        if (id == View.NO_ID || id ushr 24 == 0) return null
        return try {
            v.resources.getResourceEntryName(id)
        } catch (_: Throwable) {
            null
        }
    }

    /** 这个控件应该读成什么；null 表示不用改 */
    fun nameFor(v: View, info: AccessibilityNodeInfo?): String? {
        // 自绘键盘由键位模型负责
        if (KeyboardViewHooks.kbClass?.isInstance(v) == true) return null
        val cd = v.contentDescription?.toString()
        if (CustomToolbar.editing() && idName(v) == "btn_logo") return "完成"
        // 定制工具栏时顶部已添加的工具
        if (cd != null && v.javaClass.simpleName == "CandidateToolbarEditItemLayout") {
            val base = Names.toolbarKeys[cd] ?: Names.guessFromId(cd) ?: cd
            return "$base，已添加，双击移除"
        }
        if (cd != null) {
            Names.toolbarKeys[cd]?.let { return it }
            if (cd.startsWith("temporary_toolbar_button")) return "恢复原文"
            if (cd.startsWith("btn_") || cd.startsWith("item_")) return Names.guessFromId(cd) ?: cd
            if (cd.isNotBlank()) return null
        }
        val text = info?.text ?: (v as? android.widget.TextView)?.text
        if (!text.isNullOrBlank()) return null
        val entry = idName(v)
        if (entry != null) {
            // 候选栏末尾的按钮：同一个按钮，图标是箭头时展开更多候选，是叉时清空联想
            if (entry == "candidate_ending" || entry == "symbol_ending") return endingName(v)
            Names.viewIds[entry]?.let { return it }
        }
        // 父控件把子控件的文字合在一起读的不用管；只处理能点、能获得焦点的
        if (!(v.isClickable || v.isLongClickable || v.isFocusable)) return null
        if (v is ViewGroup && hasText(v)) return null
        val guess = entry?.let { Names.guessFromId(it) }
        val key = "${v.javaClass.name}#${entry ?: "无id"}"
        if (reported.add(key)) {
            val r = Rect()
            v.getGlobalVisibleRect(r)
            L.i("无标签控件 类=${v.javaClass.name} id=${entry ?: "无"} 位置=${Fmt.rect(r)} 猜测=${guess ?: "无"}")
        }
        return guess
    }

    /** 表里有的中文名（工具栏标识、资源 id、候选栏末尾按钮） */
    fun mappedName(v: View): String? {
        v.contentDescription?.toString()?.let { cd -> Names.toolbarKeys[cd]?.let { return it } }
        val entry = idName(v) ?: return null
        if (entry == "candidate_ending" || entry == "symbol_ending") return endingName(v)
        return Names.viewIds[entry]
    }

    /** 讯飞式朗读一个 Java 控件：中文名、描述、文字，或者里面的文字；单字候选加解释 */
    fun spokenName(v: View): String? {
        nameFor(v, null)?.let { return it }
        v.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        val cand = isCandidate(v)
        if (v is android.widget.TextView) {
            val t = v.text?.toString()?.trim()
            // 图标字体画的按钮（表情面板的删除等）：按图标表读
            if (!t.isNullOrEmpty() && t.any { Fmt.isPua(it) }) return IconNames.speakable(t).takeIf { it.isNotBlank() }
            if (!t.isNullOrEmpty()) return if (cand) CharWords.explainWord(Names.spoken(t)) else Names.spoken(t)
        }
        if (v is ViewGroup) {
            val sb = StringBuilder()
            collectText(v, sb)
            val t = sb.toString().trim()
            if (t.isNotEmpty()) return if (cand) CharWords.explainWord(Names.spoken(t)) else Names.spoken(t)
        }
        return null
    }

    /** 候选栏、更多候选里的候选词（只有它们加逐字解释） */
    private fun isCandidate(v: View): Boolean {
        var p: Any? = v
        var depth = 0
        while (p is View && depth < 4) {
            if (p.javaClass.simpleName.startsWith("CandidateItem")) return true
            val e = idName(p)
            if (e == "candidate_scroll" || e == "more_candidate_word_list") return true
            p = p.parent
            depth++
        }
        return false
    }

    private fun collectText(g: ViewGroup, sb: StringBuilder) {
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            if (c.visibility != View.VISIBLE) continue
            val cd = c.contentDescription
            if (!cd.isNullOrBlank()) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(Names.toolbarKeys[cd.toString()] ?: cd)
            } else if (c is android.widget.TextView && !c.text.isNullOrBlank()) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(c.text)
            } else if (c is ViewGroup) collectText(c, sb)
        }
    }

    private fun hasText(g: ViewGroup): Boolean {
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            if (c.visibility != View.VISIBLE) continue
            if (!c.contentDescription.isNullOrBlank()) return true
            if (c is android.widget.TextView && !c.text.isNullOrBlank()) return true
            if (c is ViewGroup && hasText(c)) return true
        }
        return false
    }

    private fun label(v: View, info: AccessibilityNodeInfo) {
        if (shouldHide(v, info)) {
            // 父控件已经是一个完整的按钮，里面的图标不再单独占一个焦点
            info.isClickable = false
            info.isLongClickable = false
            info.isFocusable = false
            info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
            info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK)
            info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_FOCUS)
            info.isVisibleToUser = false
            return
        }
        val name = nameFor(v, info) ?: return
        info.contentDescription = name
    }

    /** 没有名字、能点的子控件，外层 3 层内有能点的父控件 → 和父控件重复，隐藏 */
    fun shouldHide(v: View, info: AccessibilityNodeInfo?): Boolean {
        if (KeyboardViewHooks.kbClass?.isInstance(v) == true) return false
        if (!(v.isClickable || v.isFocusable)) return false
        if (!v.contentDescription.isNullOrBlank()) return false
        if (v is android.widget.TextView && !v.text.isNullOrBlank()) return false
        val entry = idName(v)
        if (entry != null && (Names.viewIds.containsKey(entry) || entry == "candidate_ending" || entry == "symbol_ending")) return false
        if (v is ViewGroup && hasText(v)) return false
        var p = v.parent
        var depth = 0
        while (p is View && depth < 3) {
            if (p.isClickable || p.isLongClickable) return true
            p = p.parent
            depth++
        }
        return false
    }

    private fun endingName(v: View): String {
        val iv = (v as? android.widget.ImageView) ?: findImage(v) ?: return "更多候选"
        val res = try {
            de.robv.android.xposed.XposedHelpers.getIntField(iv, "mResource")
        } catch (_: Throwable) {
            0
        }
        val name = if (res != 0) try {
            iv.resources.getResourceEntryName(res)
        } catch (_: Throwable) {
            null
        } else null
        return when {
            name == null -> "更多候选"
            name.contains("close") || name.contains("clear") -> "清空联想"
            name.contains("more") || name.contains("open") || name.contains("arrow") -> "更多候选"
            else -> "更多候选（图标 $name）"
        }
    }

    private fun findImage(v: View): android.widget.ImageView? {
        if (v is android.widget.ImageView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findImage(v.getChildAt(i))?.let { return it }
        return null
    }

    // ---------------- 控件树 ----------------

    @Volatile
    var service: InputMethodService? = null

    /** 输出输入法窗口的控件树（设置页“立即输出键位表”时一起输出） */
    private var lastTree: String? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** 键盘显示或切换后自动输出一次（内容没变不重复） */
    fun dumpTreeLater(reason: String) {
        main.removeCallbacksAndMessages(TOKEN)
        main.postAtTime({
            try {
                dumpTree(reason, skipIfSame = true)
            } catch (t: Throwable) {
                L.e("输出控件树失败", t)
            }
        }, TOKEN, android.os.SystemClock.uptimeMillis() + 800)
    }

    private val TOKEN = Any()

    fun dumpTree(reason: String, skipIfSame: Boolean = false) {
        val s = service ?: run { L.i("控件树：输入法服务还没创建"); return }
        val root = try {
            s.window?.window?.decorView
        } catch (_: Throwable) {
            null
        } ?: run { L.i("控件树：拿不到键盘窗口"); return }
        val sb = StringBuilder("==== 控件树（$reason）====\n")
        walk(root, 0, sb)
        // 豆包的弹出窗口（定制工具栏等）是单独的窗口，也一起输出
        if (!skipIfSame) for (r in otherRoots(root)) {
            sb.append("---- 其他窗口 ").append(r.javaClass.simpleName).append(" ----\n")
            walk(r, 0, sb)
        }
        val body = sb.toString()
        val tree = body.substringAfter("====\n")
        if (skipIfSame && tree == lastTree) return
        lastTree = tree
        L.i(body + "==== 控件树结束 ====")
    }

    /** 本进程里除键盘窗口外、正在显示的其他窗口根视图 */
    fun otherRoots(ime: View?): List<View> = try {
        val g = Class.forName("android.view.WindowManagerGlobal")
        val inst = g.getMethod("getInstance").invoke(null)
        val f = g.getDeclaredField("mViews").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val raw = f.get(inst)
        val views = (raw as? Collection<*>)?.filterIsInstance<View>() ?: emptyList()
        views.filter { it !== ime && it.visibility == View.VISIBLE }
    } catch (t: Throwable) {
        L.e("取其他窗口失败", t)
        emptyList()
    }

    private fun walk(v: View, depth: Int, sb: StringBuilder) {
        if (v.visibility != View.VISIBLE || depth > 40) return
        val r = Rect()
        val onScreen = v.getGlobalVisibleRect(r)
        if (!onScreen) {
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            r.set(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
        }
        if (v.width == 0 || v.height == 0) return
        repeat(depth) { sb.append(' ') }
        sb.append(v.javaClass.name.substringAfterLast('.'))
        idName(v)?.let { sb.append(" id=").append(it) }
        sb.append(' ').append(Fmt.rect(r))
        if (v.isClickable) sb.append(" 可点")
        if (v.isLongClickable) sb.append(" 可长按")
        if (!v.isImportantForAccessibility) sb.append(" 读屏忽略")
        v.contentDescription?.let { sb.append(" 描述=「").append(Fmt.esc(it.toString())).append('」') }
        (v as? android.widget.TextView)?.text?.takeIf { it.isNotEmpty() }?.let {
            sb.append(" 文字=「").append(Fmt.esc(it.toString().take(60))).append('」')
        }
        if (v.isFocusable) sb.append(" 可聚焦")
        if (shouldHide(v, null)) sb.append(" 模块隐藏")
        else nameFor(v, null)?.let { sb.append(" 模块读作=「").append(it).append('」') }
        sb.append('\n')
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1, sb)
    }
}
