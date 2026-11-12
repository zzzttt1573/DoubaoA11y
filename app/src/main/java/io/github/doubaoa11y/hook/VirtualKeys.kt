package io.github.doubaoa11y.hook

import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import java.util.WeakHashMap

/**
 * 阶段 2：把自绘键盘拆成一个个虚拟按键给读屏。
 * - 每个键是一个虚拟节点（AccessibilityNodeProvider），读屏可以单独聚焦、双击。
 * - 手指摸到键时让读屏聚焦并朗读；抬手（悬停结束且手指还在这个键上）就按下这个键。
 * - 在删除键上停 1.5 秒连续删除，在空格上停 0.8 秒开始语音；抬手松开。
 * 按键靠往 KeyboardView 送合成的触摸事件实现，和手指点击走同一条路，豆包的逻辑不受影响。
 */
object VirtualKeys {
    private const val HOST = View.NO_ID
    private const val KEY_CLASS = "android.inputmethodservice.Keyboard\$Key"

    /** 原生层的长按延时（KeyboardView 内部 Handler） */
    private const val NATIVE_LONG_PRESS_MS = 500L

    private val main = Handler(Looper.getMainLooper())
    private val providers = WeakHashMap<View, Provider>()

    /** 每个键盘视图当前的按键列表（虚拟节点 id = 下标） */
    private class State {
        var keys: List<Key> = emptyList()
        var focused = HOST
        var hovered = HOST
        var signature = ""
    }

    private val states = WeakHashMap<View, State>()

    private fun state(v: View) = states.getOrPut(v) { State() }

    val enabled: Boolean get() = (A11yState.active || A11yState.directTouch) && Config.b("virtualKeys")

    /** 讯飞式的“摸、抬手”处理：触摸浏览开着时由设置决定，关着（直接触摸）时总是 */
    private val explorer: Boolean get() = A11yState.directTouch || keepFocus

    /**
     * 手指触摸直接到键盘：触摸浏览关着，或者天坦读屏已设了触摸穿透。
     * 天坦穿透没生效时收到的仍是悬停，照旧按触摸浏览处理，不会摸不到键。
     */
    val touchMode: Boolean get() = A11yState.directTouch || (A11yState.active && TatansBridge.requested)

    /** 模块自己送的触摸，不能再被当成手指拦下来 */
    @Volatile
    var injecting = false
        private set

    // ---------------- 按键列表 ----------------

    private fun refreshKeys(v: View): List<Key> {
        val s = state(v)
        val t = KeyboardViewHooks.tableOf(v) ?: return s.keys.also { s.keys = emptyList() }
        val ks = t.keys().filter { it.rect.width() > 0 && it.rect.height() > 0 && it.speakable().isNotBlank() }
        s.keys = ks
        return ks
    }

    /** 绘制后调用：按键变了就通知读屏刷新 */
    fun onTableChanged(v: View) {
        if (!enabled) return
        main.removeCallbacksAndMessages(v)
        main.postAtTime({
            try {
                val s = state(v)
                val old = s.signature
                val ks = refreshKeys(v)
                KeyboardState.onKeys(ks)
                val sig = ks.joinToString("|") { "${it.rect.flattenToString()}=${it.speakable()}" }
                s.signature = sig
                if (sig != old) {
                    val e = obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
                    e.contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
                    e.setSource(v)
                    send(v, e)
                }
            } catch (t: Throwable) {
                L.hookError("VirtualKeys.onTableChanged", t)
            }
        }, v, SystemClock.uptimeMillis() + 150)
    }

    // ---------------- 无障碍节点 ----------------

    fun providerFor(v: View): AccessibilityNodeProvider? {
        if (!enabled) return null
        return providers.getOrPut(v) { Provider(v) }
    }

    private class Provider(val host: View) : AccessibilityNodeProvider() {
        override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? = try {
            if (virtualViewId == HOST) hostNode() else keyNode(virtualViewId)
        } catch (t: Throwable) {
            L.hookError("VirtualKeys.createNode", t)
            null
        }

        private fun hostNode(): AccessibilityNodeInfo {
            val info = AccessibilityNodeInfo.obtain(host)
            host.onInitializeAccessibilityNodeInfo(info)
            val ks = refreshKeys(host)
            if (liftFor(host)) {
                // 讯飞式：键盘对读屏不可见，焦点留在编辑框（工具栏上方的翻译栏除外）
                info.contentDescription = null
                info.isVisibleToUser = false
                info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
            } else {
                for (i in ks.indices) info.addChild(host, i)
            }
            info.className = "android.inputmethodservice.KeyboardView"
            info.contentDescription = "豆包键盘"
            info.isFocusable = false
            info.isClickable = false
            info.isLongClickable = false
            info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
            info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK)
            return info
        }

        private fun keyNode(id: Int): AccessibilityNodeInfo? {
            val s = state(host)
            val k = s.keys.getOrNull(id) ?: return null
            val info = AccessibilityNodeInfo.obtain(host, id)
            info.packageName = host.context.packageName
            info.className = KEY_CLASS
            info.setParent(host)
            info.contentDescription = Candidates.navName(k.mainName()) ?: k.speakable()
            info.setBoundsInParent(Rect(k.rect))
            val loc = IntArray(2)
            host.getLocationOnScreen(loc)
            info.setBoundsInScreen(Rect(k.rect).apply { offset(loc[0], loc[1]) })
            info.isVisibleToUser = true
            info.isEnabled = true
            info.isFocusable = true
            info.isClickable = true
            info.isLongClickable = true
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK)
            if (s.focused == id) {
                info.isAccessibilityFocused = true
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
            } else {
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
            }
            return info
        }

        override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean = try {
            if (virtualViewId == HOST) host.performAccessibilityAction(action, arguments)
            else keyAction(virtualViewId, action)
        } catch (t: Throwable) {
            L.hookError("VirtualKeys.performAction", t)
            false
        }

        private fun keyAction(id: Int, action: Int): Boolean {
            val s = state(host)
            val k = s.keys.getOrNull(id) ?: return false
            return when (action) {
                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                    if (s.focused == id) return false
                    val old = s.focused
                    s.focused = id
                    if (old != HOST) sendKeyEvent(host, old, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
                    sendKeyEvent(host, id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
                    true
                }
                AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                    if (s.focused != id) return false
                    s.focused = HOST
                    sendKeyEvent(host, id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
                    true
                }
                AccessibilityNodeInfo.ACTION_CLICK -> {
                    if (justReleased()) {
                        L.i("忽略点击「${k.speakable()}」（刚松开长按）")
                        return true
                    }
                    L.i("读屏点击按键「${k.speakable()}」")
                    tap(host, k)
                    if (isFocusHost(host)) announceAfterClick(host)
                    true
                }
                AccessibilityNodeInfo.ACTION_LONG_CLICK -> {
                    if (k.mainName() == "空格" && Candidates.composing) {
                        L.i("有候选，长按空格不开始语音，按一下空格")
                        tap(host, k)
                        return true
                    }
                    L.i("长按按键「${k.speakable()}」")
                    press(host, k, NATIVE_LONG_PRESS_MS + 300)
                    true
                }
                else -> false
            }
        }
    }

    private fun obtain(type: Int): AccessibilityEvent =
        if (android.os.Build.VERSION.SDK_INT >= 30) AccessibilityEvent(type) else @Suppress("DEPRECATION") AccessibilityEvent.obtain(type)

    private fun send(host: View, e: AccessibilityEvent) {
        val parent = host.parent ?: return
        parent.requestSendAccessibilityEvent(host, e)
    }

    private fun sendKeyEvent(host: View, id: Int, type: Int) {
        val k = state(host).keys.getOrNull(id) ?: return
        val e = obtain(type)
        e.packageName = host.context.packageName
        e.className = KEY_CLASS
        e.contentDescription = Candidates.navName(k.mainName()) ?: k.speakable()
        e.isEnabled = true
        e.setSource(host, id)
        send(host, e)
    }

    // ---------------- 悬停：摸到朗读，抬手输入，停留长按 ----------------

    /** 讯飞式：读屏焦点留在编辑框，模块自己朗读、自己处理抬手 */
    val keepFocus: Boolean get() = Config.b("keepFocus") && Config.i("typeMode") != io.github.doubaoa11y.ConfigSpec.TYPE_DOUBLE_TAP

    // ---------------- 工具栏上方：交给读屏焦点 ----------------
    // 翻译栏、超级联想这类在工具栏上方的界面用读屏焦点操作（摸到读、双击执行），不用摸读抬手。
    // 只在讯飞式、触摸浏览开着时这样做：触摸浏览关着时读屏没有焦点，照旧摸读。

    private val focusAbove: Boolean get() = keepFocus && A11yState.active

    private var barId = 0

    /** 工具栏上边在屏幕上的纵坐标；工具栏不在就返回 null */
    fun toolbarTop(root: View): Int? {
        if (barId == 0) barId = root.resources.getIdentifier("native_candidate_bar", "id", root.context.packageName)
        val bar = (if (barId != 0) root.findViewById<View>(barId) else null) ?: return null
        if (!bar.isShown || bar.height <= 0) return null
        val loc = IntArray(2)
        bar.getLocationOnScreen(loc)
        return loc[1]
    }

    /** 屏幕纵坐标 ry 在工具栏上方，交给读屏 */
    private fun inFocusArea(root: View, ry: Float): Boolean =
        focusAbove && toolbarTop(root)?.let { ry < it } == true

    /** 这个自绘键盘整个在工具栏上方（翻译栏），它的按键报给读屏 */
    private fun isFocusHost(v: View): Boolean {
        if (!focusAbove) return false
        val top = toolbarTop(v.rootView) ?: return false
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return loc[1] + v.height <= top + 2
    }

    /** 这个自绘键盘由模块摸读抬手处理（不是的话按虚拟按键交给读屏） */
    private fun liftFor(v: View): Boolean = keepFocus && !isFocusHost(v)

    /**
     * 工具栏上方双击按钮后，读出变了的按钮。
     * 翻译栏切换语言时按钮本身读法不变，变的是输入框的提示（“输入要翻译的中文”变成“英文”），不读就没有提示。
     */
    private fun announceAfterClick(host: View) {
        val before = state(host).keys.map { it.speakable() }
        main.postDelayed({
            try {
                val changed = refreshKeys(host).map { it.speakable() }.filter { it !in before }
                if (changed.isNotEmpty()) {
                    L.i("双击后按钮变了：${changed.joinToString("，")}")
                    Speaker.speak(changed.joinToString("，"))
                }
            } catch (t: Throwable) {
                L.hookError("VirtualKeys.announceAfterClick", t)
            }
        }, 600)
    }

    private var holdRunnable: Runnable? = null
    private var holding: Key? = null
    private var holdView: View? = null
    private var holdDownTime = 0L
    private var holdIsVoice = false
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var voiceZone = ""
    private var lastHoldEnd = 0L

    private fun speakKey(v: View, s: State, id: Int) {
        val k = s.keys.getOrNull(id) ?: return
        if (liftFor(v)) Speaker.speak(k.speakable())
        else sendKeyEvent(v, id, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)
    }

    /** 键盘视图上的悬停。返回 true 表示已处理，豆包原来的悬停逻辑不再执行 */
    fun onHover(v: View, ev: MotionEvent): Boolean? {
        if (!enabled) return null
        // 讯飞式下只有工具栏上方的翻译栏走这里（报虚拟按键给读屏），其余由根视图的摸读处理
        val focusHost = keepFocus && isFocusHost(v)
        if (keepFocus && !focusHost) return null
        val lift = keepFocus && !focusHost
        val s = state(v)
        if (s.keys.isEmpty()) refreshKeys(v)
        val slop = 12f * v.resources.displayMetrics.density
        val key = KeyboardViewHooks.tableOf(v)?.keyAt(ev.x, ev.y, slop)
        var id = if (key == null) HOST else indexOf(s, key)
        if (key != null && id == HOST) {
            refreshKeys(v)
            id = indexOf(s, key)
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                if (id != s.hovered) {
                    val old = s.hovered
                    s.hovered = id
                    cancelHold()
                    if (old != HOST && !lift) sendKeyEvent(v, old, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
                    if (id != HOST) {
                        Haptic.tick()
                        speakKey(v, s, id)
                        if (!focusHost) scheduleHold(v, s.keys[id])
                    }
                }
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                val old = s.hovered
                s.hovered = HOST
                if (old != HOST && !lift) sendKeyEvent(v, old, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
                cancelHold()
                val inside = ev.x >= 0 && ev.y >= 0 && ev.x < v.width && ev.y < v.height
                val k = s.keys.getOrNull(old)
                // 只有讯飞式由模块抬手输入；虚拟按键式由读屏自己在抬手时点击
                if (lift && inside && k != null && key != null && key.rect == k.rect) {
                    L.i("抬手输入「${k.speakable()}」")
                    tap(v, k)
                }
            }
        }
        // 没摸到键（候选栏区域）就不算处理，让下面的候选栏收到
        return key != null
    }

    /**
     * 键盘窗口根视图上的悬停（比键盘视图先收到）。正在长按删除或语音时由这里接管：
     * 语音时手指的移动原样转给豆包（左上滑撤回、右上滑发送），抬手松开；并吞掉悬停，读屏焦点不乱跳。
     * 返回 true 表示吞掉这个事件。
     */
    fun onRootHover(root: View, ev: MotionEvent): Boolean {
        if (!enabled) return false
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        val rx = ev.x + loc[0]
        val ry = ev.y + loc[1]
        val k = holding
        val v = holdView
        if (k == null || v == null) {
            return if (explorer) explore(root, ev, rx, ry) else false
        }
        lastRawX = rx
        lastRawY = ry
        if (ev.actionMasked == MotionEvent.ACTION_HOVER_EXIT) {
            release(v, k, MotionEvent.ACTION_UP, rx, ry)
            state(v).hovered = HOST
            current = null
            return true
        }
        if (holdIsVoice && !voiceShown(v)) {
            val vl0 = IntArray(2)
            v.getLocationOnScreen(vl0)
            if (!k.rect.contains((rx - vl0[0]).toInt(), (ry - vl0[1]).toInt())) {
                // 语音还没开始就离开了空格：取消，继续正常浏览
                cancelHold()
                return if (explorer) explore(root, ev, rx, ry) else false
            }
            return true
        }
        if (holdIsVoice) {
            inject(v, holdDownTime, MotionEvent.ACTION_MOVE, rx, ry)
            checkVoiceZone(v)
            return true
        }
        // 删除：手指离开删除键就取消
        val vl = IntArray(2)
        v.getLocationOnScreen(vl)
        if (!k.rect.contains((rx - vl[0]).toInt(), (ry - vl[1]).toInt())) {
            cancelHold()
            return if (explorer) explore(root, ev, rx, ry) else false
        }
        return true
    }

    // ---------------- 讯飞式：整个键盘窗口统一接管 ----------------

    /** 手指下面的东西：自绘键盘上的一个键，或者一个 Java 控件（候选词、工具栏按钮） */
    private class Target(val kb: View?, val key: Key?, val view: View?, val name: String) {
        fun same(o: Target?): Boolean = o != null &&
            (if (key != null) o.key != null && o.kb === kb && o.key.rect == key.rect
            else o.key == null && o.name == name && (o.view === view || related(o.view, view)))

        private fun related(a: View?, b: View?): Boolean {
            if (a == null || b == null) return false
            var p: Any? = a.parent
            while (p is View) { if (p === b) return true; p = p.parent }
            p = b.parent
            while (p is View) { if (p === a) return true; p = p.parent }
            return false
        }
    }

    private var current: Target? = null

    private fun explore(root: View, ev: MotionEvent, rx: Float, ry: Float): Boolean {
        if (inFocusArea(root, ry)) {
            // 工具栏上方（翻译栏、超级联想）：交给读屏焦点，模块不读、抬手不输入
            if (current != null) L.i("手指到了工具栏上方，交给读屏")
            cancelHold()
            current = null
            return false
        }
        val t = targetAt(root, rx, ry)
        if (inReaderPanel) {
            // 豆包菜单这类界面交给读屏
            cancelHold()
            current = null
            return false
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                // 手指落在缝里、下面什么都没有：16dp 以内找最近的键或按钮，读它、抬手也输入它
                val t = t ?: if (missedAll) fallbackTarget(root, rx, ry) else null
                if (t == null) {
                    // 手指只是稍微滑出一点（按钮之间的空隙、小图标边上），先不放掉，抬手照样算
                    val c = current
                    if (c != null && !near(c, rx, ry)) {
                        cancelHold()
                        current = null
                    }
                    return true
                }
                if (t.same(current)) return true
                cancelHold()
                current = t
                if (EditPanel.isOpen) L.i("编辑面板打开时摸到「${t.name}」${if (t.key != null) "（键盘键）" else t.view?.javaClass?.simpleName ?: ""}")
                Haptic.tick()
                Speaker.speak(t.name)
                if (t.key != null && t.kb != null) {
                    scheduleHold(t.kb, t.key)
                    scheduleColumnScroll(root, t.kb, t.key, rx, ry)
                }
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                val c = current
                current = null
                cancelHold()
                // 手指往上滑出键盘：系统在键盘上边缘（里面几个像素）发“离开”，不是抬手。
                // 上边缘这一条里只有正好压在目标上才算抬手（候选词占满候选栏高度，照样能点），“附近”不算
                if (c != null && nearTop(root, ry) && !(t != null && c.same(t))) {
                    L.i("手指滑出键盘上方，不输入「${c.name}」 y=${ry.toInt()}")
                    return true
                }
                if (c != null && ((t != null && c.same(t)) || (t == null && near(c, rx, ry)))) {
                    if (c.key != null && c.kb != null) {
                        L.i("抬手输入「${c.name}」")
                        tap(c.kb, c.key)
                    } else if (c.view != null) {
                        val now = SystemClock.uptimeMillis()
                        if (now - lastViewTap < 450) {
                            L.i("忽略重复点击「${c.name}」")
                            return true
                        }
                        lastViewTap = now
                        L.i("抬手点击「${c.name}」 ${c.view.javaClass.simpleName}")
                        tapView(root, c.view)
                        Panels.focusIfOpened(root)
                    }
                }
            }
        }
        return true
    }


    // ---------------- 符号栏自动滚动 ----------------

    private const val COLUMN_SCROLL_MS = 800L
    private var scrollRunnable: Runnable? = null

    /**
     * 九键、数字键盘左边的符号栏要滑动才能看到下面的符号：
     * 手指停在最下面一格 0.8 秒就往上滚，停在最上面一格就往下滚，滚完读手指下面的新符号。
     */
    private fun scheduleColumnScroll(root: View, kb: View, k: Key, rx: Float, ry: Float) {
        if (!k.inColumn) return
        val bg = k.bg ?: return
        val col = KeyboardViewHooks.tableOf(kb)?.keys()?.filter { it.inColumn && it.bg?.rect == bg.rect } ?: return
        if (col.size < 2) return
        val bottom = col.maxByOrNull { it.rect.top }!!
        val top = col.minByOrNull { it.rect.top }!!
        val dir = when {
            k.rect == bottom.rect -> -1   // 手指往上拖，内容往上走，露出下面的
            k.rect == top.rect -> 1
            else -> return
        }
        val r = Runnable {
            scrollRunnable = null
            try {
                dragColumn(kb, bg.rect, dir)
                main.postDelayed({
                    try {
                        // 重新看手指下面是什么，读出来
                        current = null
                        val t = targetAt(root, rx, ry) ?: return@postDelayed
                        current = t
                        Haptic.tick()
                        Speaker.speak(t.name)
                        if (t.key != null && t.kb != null) scheduleColumnScroll(root, t.kb, t.key, rx, ry)
                    } catch (e: Throwable) {
                        L.hookError("VirtualKeys.afterScroll", e)
                    }
                }, 450)
            } catch (e: Throwable) {
                L.hookError("VirtualKeys.columnScroll", e)
            }
        }
        scrollRunnable = r
        main.postDelayed(r, COLUMN_SCROLL_MS)
    }

    /** 在符号栏上模拟一次拖动，距离是半栏高 */
    private fun dragColumn(kb: View, col: Rect, dir: Int) {
        val vl = IntArray(2)
        kb.getLocationOnScreen(vl)
        val x = vl[0] + col.exactCenterX()
        val dist = col.height() * 0.5f
        val y0 = vl[1] + col.exactCenterY() - dir * dist / 2
        val down = SystemClock.uptimeMillis()
        L.i("符号栏${if (dir < 0) "向下" else "向上"}滚动")
        inject(kb, down, MotionEvent.ACTION_DOWN, x, y0)
        val steps = 8
        for (i in 1..steps) {
            val y = y0 + dir * dist * i / steps
            main.postDelayed({
                try {
                    inject(kb, down, MotionEvent.ACTION_MOVE, x, y)
                    if (i == steps) inject(kb, down, MotionEvent.ACTION_UP, x, y)
                } catch (e: Throwable) {
                    L.hookError("VirtualKeys.drag", e)
                }
            }, (i * 16).toLong())
        }
    }

    /** 上一次触摸是不是落在交给读屏的界面里 */
    private var inReaderPanel = false

    /**
     * 按界面层次从上往下找手指下面的东西：盖在上面的先找到。
     * 自绘键盘只在摸到键时才算（它盖着候选栏，候选栏区域没有键）。
     */
    private fun targetAt(root: View, rx: Float, ry: Float): Target? {
        inReaderPanel = false
        inHandwrite = false
        missedAll = false
        val hit = findTop(root, rx, ry)
        if (hit == null) {
            missedAll = true
            return null
        }
        if (hit === PANEL || hit === BLOCK) return null
        if (hit === HANDWRITE) {
            inHandwrite = true
            return Target(null, null, null, "手写区")
        }
        if (hit is Target) return hit
        var v = hit as View
        // 按钮里的文字、图标：统一用可点的父控件，手指在图标和文字之间移动不会重复朗读
        if (!(v.isClickable || v.isLongClickable)) {
            var p = v.parent
            var depth = 0
            val quarter = root(v).let { it.width * it.height } / 4
            while (p is View && depth < 3) {
                if ((p.isClickable || p.isLongClickable) && p.width * p.height <= quarter &&
                    !(p is android.widget.AdapterView<*>) && p.javaClass.simpleName != "RecyclerView") {
                    v = p
                    break
                }
                p = p.parent
                depth++
            }
        }
        // 整条可点的容器（工具栏上的剪贴板条）里只有一个带文字的可点项目：点那个项目，不点容器中间的空白
        if (v is android.view.ViewGroup) {
            onlyClickableChild(v)?.let { v = it }
        }
        val name = ViewA11yHooks.spokenName(v) ?: return null
        return Target(null, null, v, name)
    }

    /**
     * 上一次 targetAt 什么都没找到（落在缝里）。被面板挡住（BLOCK）、交给读屏、
     * 手写区、找到了但没名字的控件都不算，这些情况不兜底，免得读出被盖住的键。
     */
    private var missedAll = false

    private const val FALLBACK_RADIUS_DP = 16f

    /**
     * 落指死区兜底：手指落在键与键、按钮与按钮之间的缝里时，在 16dp 以内找最近的目标。
     * 键用 DrawTable.nearest 跨键盘视图找；按钮只找真正在外面（距离大于 0）的可点有名控件。
     * 16dp 比键行距的一半小，不会跨行配错；更远的地方照旧不出声。
     */
    private fun fallbackTarget(root: View, rx: Float, ry: Float): Target? {
        if (EditPanel.isOpen) return null
        val radius = FALLBACK_RADIUS_DP * root.resources.displayMetrics.density
        val quarter = root.width.toLong() * root.height / 4
        var best: Target? = null
        var bestD = Float.MAX_VALUE
        // 工具栏上方交给读屏的东西不拿来兜底
        val barTop = if (focusAbove) toolbarTop(root) else null
        fun walk(v: View, depth: Int) {
            if (v.visibility != View.VISIBLE || v.alpha <= 0f) return
            if (Panels.isPanel(v) || isHandwrite(v)) return
            if (KeyboardViewHooks.kbClass?.isInstance(v) == true) {
                if (!v.isShown) return
                val loc = IntArray(2)
                v.getLocationOnScreen(loc)
                if (barTop != null && loc[1] + v.height <= barTop + 2) return
                val hit = KeyboardViewHooks.tableOf(v)?.nearest(rx - loc[0], ry - loc[1], radius) ?: return
                if (hit.second < bestD) {
                    val name = Candidates.navName(hit.first.mainName()) ?: hit.first.speakable()
                    if (name.isNotBlank()) {
                        bestD = hit.second
                        best = Target(v, hit.first, null, name)
                    }
                }
                return
            }
            if ((v.isClickable || v.isLongClickable) && v.width.toLong() * v.height <= quarter) {
                val loc = IntArray(2)
                v.getLocationOnScreen(loc)
                if (barTop != null && loc[1] + v.height <= barTop) return
                val dx = maxOf(0f, maxOf(loc[0] - rx, rx - (loc[0] + v.width)))
                val dy = maxOf(0f, maxOf(loc[1] - ry, ry - (loc[1] + v.height)))
                val d = kotlin.math.hypot(dx, dy)
                if (d > 0f && d <= radius && d < bestD) {
                    val name = ViewA11yHooks.spokenName(v)
                    if (name != null) {
                        bestD = d
                        best = Target(null, null, v, name)
                    }
                }
            }
            if (v is android.view.ViewGroup && depth < 10) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
            }
        }
        walk(root, 0)
        val b = best
        // 手指在缝里移动时每一下都会来，只在换了目标时记一行
        if (b?.name != lastFallback) {
            lastFallback = b?.name
            if (b != null) L.i("落在缝里，读最近的「${b.name}」 距 ${bestD.toInt()}px")
        }
        return b
    }

    private var lastFallback: String? = null

    private fun onlyClickableChild(g: android.view.ViewGroup): View? {
        var found: View? = null
        var count = 0
        fun walk(x: View) {
            if (count > 1 || x.visibility != View.VISIBLE) return
            if (x !== g && (x.isClickable || x.isLongClickable)) {
                if (hasText(x)) {
                    count++
                    found = x
                }
                return
            }
            if (x is android.view.ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i))
        }
        walk(g)
        return if (count == 1) found else null
    }

    private fun hasText(x: View): Boolean {
        if (x.visibility != View.VISIBLE) return false
        if (x is android.widget.TextView && !x.text.isNullOrBlank()) return true
        if (x is android.view.ViewGroup) for (i in 0 until x.childCount) if (hasText(x.getChildAt(i))) return true
        return false
    }

    private val PANEL = Any()
    private val BLOCK = Any()
    private val HANDWRITE = Any()

    /** 上一次查找落在手写区（手写键盘、手写找字的书写区域） */
    private var inHandwrite = false

    private fun isHandwrite(v: View) = v.javaClass.simpleName == "HandwriteView"

    /** 这次查找途中已经经过手写区：手写区下面的自绘键盘（被盖住的字母）不算 */
    private var hwSeen = false

    private fun findTop(root: View, rx: Float, ry: Float): Any? {
        hwSeen = false
        return find(root, rx, ry)
    }

    /**
     * 返回 Target（键）、View（控件）、PANEL（交给读屏）、BLOCK（挡住、不读）、HANDWRITE（手写区）或 null。
     * 手写区优先级最低：同一位置有工具栏按钮、候选字时先读它们，什么都没有才算手写区。
     */
    private fun find(v: View, rx: Float, ry: Float): Any? {
        if (v.visibility != View.VISIBLE || v.alpha <= 0f) return null
        if (!contains(v, rx, ry)) return null
        if (KeyboardViewHooks.kbClass?.isInstance(v) == true) {
            if (!v.isShown || hwSeen || EditPanel.isOpen) return null
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            val slop = 12f * v.resources.displayMetrics.density
            val key = KeyboardViewHooks.tableOf(v)?.keyAt(rx - loc[0], ry - loc[1], slop) ?: return null
            val name = Candidates.navName(key.mainName()) ?: key.speakable()
            return if (name.isBlank()) null else Target(v, key, null, name)
        }
        if (Panels.isPanel(v)) {
            Panels.note(v)
            inReaderPanel = true
            return PANEL
        }
        if (isHandwrite(v)) {
            hwSeen = true
            return HANDWRITE
        }
        var weak: Any? = null
        if (v is android.view.ViewGroup) {
            for (i in v.childCount - 1 downTo 0) {
                val r = find(v.getChildAt(i), rx, ry) ?: continue
                if (r === HANDWRITE) {
                    weak = HANDWRITE
                    continue
                }
                return r
            }
        }
        val texty = v is android.widget.TextView && !v.text.isNullOrBlank()
        val clickable = v.isClickable || v.isLongClickable
        // 表里有中文名的控件（手写键盘的删除图标等）即使不可点也算
        if (!texty && !clickable && ViewA11yHooks.mappedName(v) == null) return weak
        if (!texty && v.width * v.height > root(v).let { it.width * it.height } / 4) {
            // 铺满大半个键盘的可点容器：里面有内容（更多候选、菜单这类面板）就挡住下面的键盘，空的就当没有
            if (weak != null) return weak
            return if (v is android.view.ViewGroup && hasChildAt(v, rx, ry)) BLOCK else null
        }
        return v
    }

    private fun hasChildAt(g: android.view.ViewGroup, rx: Float, ry: Float): Boolean {
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            if (c.visibility == View.VISIBLE && contains(c, rx, ry)) return true
        }
        return false
    }

    /** 屏幕坐标 ry 在键盘区域上边往下 12dp 以内，或者更上面 */
    private fun nearTop(root: View, ry: Float): Boolean {
        val id = root.resources.getIdentifier("ime_inputview", "id", root.context.packageName)
        val area = (if (id != 0) root.findViewById<View>(id) else null) ?: return false
        if (!area.isShown || area.height <= 0) return false
        val loc = IntArray(2)
        area.getLocationOnScreen(loc)
        return ry < loc[1] + 12 * root.resources.displayMetrics.density
    }

    /** 手指在上一个目标附近（扩大 16dp） */
    private fun near(c: Target, rx: Float, ry: Float): Boolean {
        val r = Rect()
        val host = c.kb ?: c.view ?: return false
        val loc = IntArray(2)
        host.getLocationOnScreen(loc)
        if (c.key != null) r.set(c.key.rect) else r.set(0, 0, host.width, host.height)
        r.offset(loc[0], loc[1])
        val pad = (16 * host.resources.displayMetrics.density).toInt()
        r.inset(-pad, -pad)
        return r.contains(rx.toInt(), ry.toInt())
    }

    private fun contains(v: View, rx: Float, ry: Float): Boolean {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return rx >= loc[0] && ry >= loc[1] && rx < loc[0] + v.width && ry < loc[1] + v.height
    }

    private fun root(v: View): View {
        var r = v
        while (true) {
            val p = r.parent as? View ?: return r
            r = p
        }
    }

    private fun isAncestor(a: View, b: View): Boolean {
        var p = b.parent
        while (p is View) {
            if (p === a) return true
            p = p.parent
        }
        return false
    }

    /** 模块合成点击后这段时间里，被点的控件发出的点击、获得焦点事件不交给读屏（免得读屏响点击声、把焦点挪过去） */
    @Volatile
    var quietUntil = 0L
        private set

    /** 往键盘窗口根视图送一次点击，和手指点一下一样 */
    private fun tapView(root: View, v: View) {
        val rl = IntArray(2)
        root.getLocationOnScreen(rl)
        val vl = IntArray(2)
        v.getLocationOnScreen(vl)
        val x = (vl[0] - rl[0] + v.width / 2f)
        val y = (vl[1] - rl[1] + v.height / 2f)
        val down = SystemClock.uptimeMillis()
        quietUntil = down + 600
        rootTouch(root, down, MotionEvent.ACTION_DOWN, x, y)
        main.postDelayed({
            try {
                rootTouch(root, down, MotionEvent.ACTION_UP, x, y)
            } catch (t: Throwable) {
                L.hookError("VirtualKeys.tapView", t)
            }
        }, 40)
    }

    private fun rootTouch(root: View, downTime: Long, action: Int, x: Float, y: Float) {
        val ev = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        injecting = true
        try {
            root.dispatchTouchEvent(ev)
        } finally {
            injecting = false
            ev.recycle()
        }
    }


    private fun indexOf(s: State, key: Key): Int {
        val i = s.keys.indexOfFirst { it.rect == key.rect }
        return if (i >= 0) i else HOST
    }

    private fun holdMsFor(k: Key): Long? {
        val n = k.mainName()
        return when {
            n == "删除" -> Config.i("deleteHoldMs").toLong().coerceIn(500, 5000)
            n == "空格" -> Config.i("spaceHoldMs").toLong().coerceIn(500, 5000)
            else -> null
        }
    }

    /** 手指停在删除或空格上：提前按下，让原生层的 500 毫秒长按刚好在设定时间触发 */
    private fun scheduleHold(v: View, k: Key) {
        val total = holdMsFor(k) ?: return
        if (!Config.b("liftToType")) return
        // 有候选时空格是上屏用的，停留不开始语音
        if (k.mainName() == "空格" && Candidates.composing) return
        val r = Runnable {
            holdRunnable = null
            try {
                holding = k
                holdView = v
                holdIsVoice = k.mainName() == "空格"
                voiceZone = ""
                holdDownTime = SystemClock.uptimeMillis()
                val vl = IntArray(2)
                v.getLocationOnScreen(vl)
                lastRawX = vl[0] + k.rect.exactCenterX()
                lastRawY = vl[1] + k.rect.exactCenterY()
                L.i("停留按下「${k.speakable()}」，${NATIVE_LONG_PRESS_MS} 毫秒后原生长按")
                inject(v, holdDownTime, MotionEvent.ACTION_DOWN, lastRawX, lastRawY)
            } catch (t: Throwable) {
                L.hookError("VirtualKeys.hold", t)
            }
        }
        holdRunnable = r
        main.postDelayed(r, (total - NATIVE_LONG_PRESS_MS).coerceAtLeast(0))
    }

    private fun cancelHold() {
        holdRunnable?.let { main.removeCallbacks(it) }
        holdRunnable = null
        scrollRunnable?.let { main.removeCallbacks(it) }
        scrollRunnable = null
        val k = holding ?: return
        val v = holdView ?: run { holding = null; return }
        if (holdIsVoice && !voiceShown(v)) {
            // 语音快开始时手指离开空格：豆包不认取消（照样开始语音），改成滑到键盘上方再松开，不输入
            val down = holdDownTime
            val vl = IntArray(2)
            v.getLocationOnScreen(vl)
            val x = vl[0] + k.rect.exactCenterX()
            val y = vl[1] - 300f
            holding = null
            holdView = null
            holdIsVoice = false
            lastHoldEnd = SystemClock.uptimeMillis()
            L.i("取消「${k.speakable()}」，语音还没开始，滑出键盘松开，按住 ${SystemClock.uptimeMillis() - down} 毫秒")
            inject(v, down, MotionEvent.ACTION_MOVE, x, y)
            inject(v, down, MotionEvent.ACTION_UP, x, y)
            adoptVoiceLater(v, k, down)
            return
        }
        // 手指滑到别的键上：取消，不输入
        release(v, k, MotionEvent.ACTION_CANCEL, lastRawX, lastRawY)
    }

    /**
     * 保险：取消后豆包还是开始了语音，没人按着就结束不了，触摸也全被语音界面占住。
     * 这时把语音接回来，当成手指还按着空格：下一次摸屏幕可以上滑撤回、发送，抬手结束。
     */
    private fun adoptVoiceLater(v: View, k: Key, down: Long) {
        main.postDelayed({
            try {
                if (holding != null || !voiceShown(v)) return@postDelayed
                L.w("取消空格后豆包仍开始了语音，接回语音控制")
                holding = k
                holdView = v
                holdIsVoice = true
                voiceZone = ""
                holdDownTime = down
                Speaker.speak("正在语音输入，摸屏幕再抬手结束")
            } catch (t: Throwable) {
                L.hookError("VirtualKeys.adoptVoice", t)
            }
        }, NATIVE_LONG_PRESS_MS + 600)
    }

    private fun release(v: View, k: Key, action: Int, rawX: Float, rawY: Float) {
        holding = null
        holdView = null
        lastHoldEnd = SystemClock.uptimeMillis()
        // 删除松开在键中心；语音松开在手指位置（左上撤回、右上发送）
        var x = rawX
        var y = rawY
        if (!holdIsVoice && action == MotionEvent.ACTION_UP) {
            val vl = IntArray(2)
            v.getLocationOnScreen(vl)
            x = vl[0] + k.rect.exactCenterX()
            y = vl[1] + k.rect.exactCenterY()
        }
        L.i("${if (action == MotionEvent.ACTION_UP) "松开" else "取消"}「${k.speakable()}」，按住 ${SystemClock.uptimeMillis() - holdDownTime} 毫秒${if (holdIsVoice) "，区域=$voiceZone" else ""}")
        val cancelVoice = holdIsVoice && action == MotionEvent.ACTION_UP && voiceZone == "松手撤回输入"
        inject(v, holdDownTime, action, x, y)
        holdIsVoice = false
        if (cancelVoice) try {
            Voice.discard()
        } catch (t: Throwable) {
            L.hookError("Voice.discard", t)
        }
    }

    private fun voiceShown(kb: View): Boolean {
        val asr = try {
            de.robv.android.xposed.XposedHelpers.getObjectField(kb, "mAsrLongPressView") as? View
        } catch (_: Throwable) {
            null
        } ?: return false
        return asr.visibility == View.VISIBLE && asr.isShown
    }

    /** 语音长按界面：手指滑进左右两个区域时朗读 */
    private fun checkVoiceZone(kb: View) {
        val asr = try {
            de.robv.android.xposed.XposedHelpers.getObjectField(kb, "mAsrLongPressView") as? View
        } catch (_: Throwable) {
            null
        } ?: return
        if (asr.visibility != View.VISIBLE) return
        val id = asr.resources.getIdentifier("asr_action_btn", "id", asr.context.packageName)
        val btn = if (id != 0) asr.findViewById<View>(id) else null
        val left = btn?.let { boolField(it, "r") } ?: false
        val right = btn?.let { boolField(it, "s") } ?: false
        val zone = when {
            left -> "松手撤回输入"
            right -> "松手一键发送"
            else -> "松手结束"
        }
        if (zone != voiceZone) {
            val first = voiceZone.isEmpty()
            voiceZone = zone
            if (!first) Speaker.speak(zone)
            L.i("语音区域：$zone")
        }
    }

    private fun boolField(o: Any, name: String): Boolean? = try {
        de.robv.android.xposed.XposedHelpers.getBooleanField(o, name)
    } catch (_: Throwable) {
        null
    }

    /** 虚拟按键式：读屏抬手点击时，刚松开长按的不再重复输入 */
    fun justReleased(): Boolean = SystemClock.uptimeMillis() - lastHoldEnd < 400

    // ---------------- 合成触摸 ----------------

    private fun tap(v: View, k: Key) {
        // 打拼音时逗号、句号切换候选，空格上屏选中的候选
        if (Candidates.onKey(k.mainName())) return
        press(v, k, 40)
    }

    private fun press(v: View, k: Key, ms: Long) {
        val vl = IntArray(2)
        v.getLocationOnScreen(vl)
        val x = vl[0] + k.rect.exactCenterX()
        val y = vl[1] + k.rect.exactCenterY()
        val down = SystemClock.uptimeMillis()
        inject(v, down, MotionEvent.ACTION_DOWN, x, y)
        main.postDelayed({
            try {
                inject(v, down, MotionEvent.ACTION_UP, x, y)
            } catch (t: Throwable) {
                L.hookError("VirtualKeys.up", t)
            }
        }, ms)
    }

    /** 送一个合成触摸事件。坐标按屏幕坐标给（豆包的语音界面用 rawX/rawY），再换算成键盘视图坐标 */
    private fun inject(v: View, downTime: Long, action: Int, rawX: Float, rawY: Float) {
        val ev = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, rawX, rawY, 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        val vl = IntArray(2)
        v.getLocationOnScreen(vl)
        ev.offsetLocation(-vl[0].toFloat(), -vl[1].toFloat())
        injecting = true
        try {
            v.dispatchTouchEvent(ev)
        } finally {
            injecting = false
            ev.recycle()
        }
    }

    /**
     * 触摸浏览关着时，键盘窗口根视图收到的手指触摸：按下、移动当成摸，抬起当成抬手。
     * 返回 true 表示吞掉（豆包收不到这次触摸，按键由模块合成）。
     */
    fun onRootTouch(root: View, ev: MotionEvent): Boolean {
        if (!enabled || !touchMode || injecting) return false
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            passThrough = false
            val loc = IntArray(2)
            root.getLocationOnScreen(loc)
            downX = ev.x
            downY = ev.y
            downTime = ev.downTime
            downRoot = root
            downInScroller = inHorizontalScroller(root, ev.x + loc[0], ev.y + loc[1])
            val t = targetAt(root, ev.x + loc[0], ev.y + loc[1])
            if (inHandwrite && !downInScroller) {
                passThrough = true
                cancelHold()
                current = null
                return false
            }
            if (inReaderPanel) {
                // 豆包菜单、剪贴板、常用语：触摸浏览关着时完全用豆包原来的触摸
                passThrough = true
                cancelHold()
                current = null
                return false
            }
            // 空格、删除默认由模块处理（摸到读、抬手输入、停留语音或连续删除）；
            // 设置里改成豆包原生操作时，整串触摸交给豆包。用逗号句号选了别的候选时，空格仍由模块上屏那个候选
            val n = t?.key?.mainName()
            val native = (n == "空格" && Config.b("nativeSpace") && Candidates.sel <= 0) ||
                (n == "删除" && Config.b("nativeDelete"))
            if (native) {
                passThrough = true
                cancelHold()
                current = null
                Speaker.speak(n)
                L.i("直接触摸：$n 交给豆包原来的处理")
                return false
            }
        }
        if (passThrough) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) passThrough = false
            return false
        }

        val hoverAction = when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> MotionEvent.ACTION_HOVER_ENTER
            MotionEvent.ACTION_MOVE -> MotionEvent.ACTION_HOVER_MOVE
            MotionEvent.ACTION_UP -> MotionEvent.ACTION_HOVER_EXIT
            MotionEvent.ACTION_CANCEL -> {
                cancelHold()
                current = null
                return true
            }
            else -> return true // 第二根手指等，忽略
        }
        val hv = MotionEvent.obtain(ev.downTime, ev.eventTime, hoverAction, ev.x, ev.y, 0)
        return try {
            onRootHover(root, hv)
            true
        } finally {
            hv.recycle()
        }
    }

    private var passThrough = false
    private var lastViewTap = 0L

    // 触摸浏览关着时的横向滑动（候选栏左右翻）：按下时记下来，滑得快就整串交给豆包
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var downRoot: View? = null
    private var downInScroller = false

    /** 手指下面有能横向滚动的控件（候选栏、手写候选） */
    private fun inHorizontalScroller(root: View, rx: Float, ry: Float): Boolean {
        fun walk(v: View): Boolean {
            if (v.visibility != View.VISIBLE || !contains(v, rx, ry)) return false
            if (v.canScrollHorizontally(1) || v.canScrollHorizontally(-1)) return true
            if (v is android.view.ViewGroup) for (i in v.childCount - 1 downTo 0) if (walk(v.getChildAt(i))) return true
            return false
        }
        return walk(root)
    }

    fun reset() {
        holdRunnable?.let { main.removeCallbacks(it) }
        holdRunnable = null
        holding = null
        holdView = null
        holdIsVoice = false
        for (s in states.values) {
            s.hovered = HOST
            s.focused = HOST
        }
    }
}
