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

    /**
     * 全屏输入时，触摸浏览是不是已经不在中间挡着了（被模块关掉、或者本来就没开）。
     * 全屏模式下键盘区一律由模块自己摸键读键，不看这个值，只有“是不是该让路给读屏面板”才用它。
     */
    private val fullscreenExplore: Boolean get() = keepFocus && Config.fullscreen && FullscreenMode.active && A11yState.recording

    /**
     * 模块要不要接管键盘。
     *
     * 1.2.1 起多了一项：全屏输入生效时，即使读屏被模块关掉触摸浏览、甚至读屏整个关着，
     * 也要接管——全屏是模块自建的触摸浏览层（见 FullscreenTouch），不再依赖读屏的状态。
     */
    val enabled: Boolean get() =
        (A11yState.active || A11yState.directTouch || fullscreenExplore || readerPauseTouch) &&
            Config.b("virtualKeys")

    /** 讯飞式的“摸、抬手”处理：触摸浏览开着时由设置决定，关着（直接触摸）时总是；全屏时总是 */
    private val explorer: Boolean get() = A11yState.directTouch || keepFocus || fullscreenExplore

    /**
     * 手指触摸直接到键盘：触摸浏览关着，或者天坦读屏已设了触摸穿透。
     * 天坦穿透没生效时收到的仍是悬停，照旧按触摸浏览处理，不会摸不到键。
     *
     * 保益/点明这类读屏被模块用广播/服务整块关掉触摸浏览后，系统 isTouchExplorationEnabled
     * 不一定立刻变 false，所以这里也认模块仍持有的暂停请求，
     * 让键盘区域走“摸键读键抬手输入”，保益/点明关了触摸浏览后下半屏照样能朗读、能打字。
     */
    val touchMode: Boolean get() =
        A11yState.directTouch || (A11yState.active && TatansBridge.requested) || readerPauseTouch

    private val readerPauseTouch: Boolean get() = A11yState.recording && ReaderControl.requested

    /** 模块自己送的触摸，不能再被当成手指拦下来 */
    @Volatile
    var injecting = false
        private set

    // ---------------- 按键列表 ----------------

    private fun refreshKeys(v: View): List<Key> {
        val s = state(v)
        val t = KeyboardViewHooks.tableOf(v)
        if (t == null) {
            s.keys = emptyList()
            return s.keys
        }
        val ks = t.keys().filter { it.rect.width() > 0 && it.rect.height() > 0 && it.speakable().isNotBlank() }
        s.keys = ks
        return ks
    }

    /** 绘制后调用：按键变了就通知读屏刷新 */
    fun onTableChanged(v: View): Boolean {
        if (!enabled) return false
        main.removeCallbacksAndMessages(v)
        return main.postAtTime({
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
        }, v, SystemClock.uptimeMillis() + 48)
    }

    // ---------------- 无障碍节点 ----------------

    fun providerFor(v: View): AccessibilityNodeProvider? {
        if (!enabled) return null
        return providers.getOrPut(v) { Provider(v) }
    }

    private class Provider(host: View) : AccessibilityNodeProvider() {
        private val hostRef = java.lang.ref.WeakReference(host)
        override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? = try {
            hostRef.get()?.let { if (virtualViewId == HOST) hostNode(it) else keyNode(it, virtualViewId) }
        } catch (t: Throwable) {
            L.hookError("VirtualKeys.createNode", t)
            null
        }

        private fun hostNode(host: View): AccessibilityNodeInfo {
            val info = AccessibilityNodeInfo.obtain(host)
            host.onInitializeAccessibilityNodeInfo(info)
            val ks = refreshKeys(host)
            if (liftFor(host) || CustomToolbar.editing() || EditPanel.isOpen) {
                // 讯飞式或被定制工具栏/编辑面板盖住：键盘对读屏不可见，焦点不落进底层按键
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

        private fun keyNode(host: View, id: Int): AccessibilityNodeInfo? {
            val s = state(host)
            val k = s.keys.getOrNull(id) ?: return null
            val info = AccessibilityNodeInfo.obtain(host, id)
            info.packageName = host.context.packageName
            info.className = KEY_CLASS
            info.setParent(host)
            info.contentDescription = Candidates.navName(k, s.keys) ?: k.speakable()
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
            hostRef.get()?.let { host ->
                if (virtualViewId == HOST) host.performAccessibilityAction(action, arguments)
                else keyAction(host, virtualViewId, action)
            } ?: false
        } catch (t: Throwable) {
            L.hookError("VirtualKeys.performAction", t)
            false
        }

        private fun keyAction(host: View, id: Int, action: Int): Boolean {
            val s = state(host)
            val k = s.keys.getOrNull(id) ?: return false
            return when (action) {
                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                    if (s.focused == id) return false
                    Sound.play("key")
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
        e.contentDescription = Candidates.navName(k, state(host).keys) ?: k.speakable()
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
    private var barRef: java.lang.ref.WeakReference<View>? = null
    private var imeInputViewId = 0
    private var imeInputViewRef: java.lang.ref.WeakReference<View>? = null
    private val multiLettersRegex = Regex("[A-Za-z]{2,4}")
    private val nonTypingKeys = setOf("重写", "返回", "回车", "发送", "收起键盘")

    /** 工具栏上边在屏幕上的纵坐标；工具栏不在就返回 null */
    fun toolbarTop(root: View): Int? {
        val cached = barRef?.get()?.takeIf { it.isAttachedToWindow && it.rootView === root }
        val bar = cached ?: run {
            if (barId == 0) barId = root.resources.getIdentifier("native_candidate_bar", "id", root.context.packageName)
            (if (barId != 0) root.findViewById<View>(barId) else null)?.also { barRef = java.lang.ref.WeakReference(it) }
        } ?: return null
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
    /** 空格长按在语音启动前取消后，短暂拦截原生层迟到的 StartAsrOnSpaceLPress */
    @Volatile
    private var spaceCancelUntil = 0L

    /** 当前是否应拦截原生层迟到的空格长按语音触发 */
    fun shouldBlockSpaceAsr(): Boolean {
        if (holding != null && holdIsVoice) return false
        val until = spaceCancelUntil
        if (until <= 0L) return false
        if (SystemClock.uptimeMillis() < until) return true
        spaceCancelUntil = 0L
        return false
    }

    private val REPEAT_SILENT_KEYS = setOf("删除", "向后删除", "左移", "右移", "上移", "下移")
    private const val REPEAT_SILENT_WINDOW_MS = 5000L
    private var lastSpokenKeyName = ""
    private var lastSpokenKeyAt = 0L

    /** 朗读按键/目标名称：开启“重复按键不朗读键名”时，连续按下删除、左移、右移等键只读第一次 */
    private fun speakTargetName(name: String) {
        val now = SystemClock.uptimeMillis()
        val suppress = Config.b("suppressRepeatKeySpeak") &&
            name in REPEAT_SILENT_KEYS &&
            name == lastSpokenKeyName &&
            now - lastSpokenKeyAt in 0L..REPEAT_SILENT_WINDOW_MS
        lastSpokenKeyName = name
        lastSpokenKeyAt = now
        if (!suppress) Speaker.speak(name)
    }

    private fun speakKey(v: View, s: State, id: Int) {
        val k = s.keys.getOrNull(id) ?: return
        if (liftFor(v)) speakTargetName(Candidates.navName(k, s.keys) ?: k.speakable())
        else sendKeyEvent(v, id, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)
    }

    /** 键盘视图上的悬停。返回 true 表示已处理，豆包原来的悬停逻辑不再执行 */
    fun onHover(v: View, ev: MotionEvent): Boolean? {
        if (!enabled || CustomToolbar.editing()) return null
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
                        Sound.play("key")
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
                if (scrollingSymbolBar && ev.actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
                    val tol = 24f * root.resources.displayMetrics.density
                    if (kotlin.math.hypot(rx - scrollRx, ry - scrollRy) <= tol) return true
                    cancelScroll()
                }
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
                lastAnnounce = SystemClock.uptimeMillis()
                if (EditPanel.isOpen) L.i("编辑面板打开时摸到「${t.name}」${if (t.key != null) "（键盘键）" else t.view?.javaClass?.simpleName ?: ""}")
                Haptic.tick()
                Sound.play(if (t.view?.javaClass?.simpleName?.contains("Candidate") == true) "scroll" else "key")
                speakTargetName(t.name)
                if (t.key != null && t.kb != null) {
                    scheduleHold(t.kb, t.key)
                    scheduleSymbolScroll(root, t.kb, t.key, rx, ry)
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
                        L.i("抬手点击「${c.name}」 ${c.view.javaClass.simpleName}")
                        tapView(root, c.view)
                        Panels.focusIfOpened(root)
                    }
                }
            }
        }
        return true
    }


    // ---------------- 符号区域停留翻页 ----------------

    private class SymbolBar(
        val barRect: Rect,
        val vertical: Boolean,
        val keys: List<Key>,
    )

    private class BarScrollState(
        var startSnapshot: List<String>? = null,
        var blockedBackSnapshot: List<String>? = null,
        var blockedForwardSnapshot: List<String>? = null,
    )

    private var barBoardName: String = ""
    private val barStates = HashMap<String, BarScrollState>()
    private var scrollRunnable: Runnable? = null
    private var afterScrollRunnable: Runnable? = null
    private var scrollingSymbolBar = false
    private var scrollRx = 0f
    private var scrollRy = 0f

    private fun cancelScroll() {
        scrollRunnable?.let { main.removeCallbacks(it) }
        scrollRunnable = null
        afterScrollRunnable?.let { main.removeCallbacks(it) }
        afterScrollRunnable = null
        scrollingSymbolBar = false
    }

    private fun findSymbolBottomRow(table: DrawTable): SymbolBar? {
        if (KeyboardState.boardName != "符号键盘" || table.width <= 0 || table.height <= 0) return null
        val all = table.keys()
        val ret = all.firstOrNull { it.mainName() == "返回" && it.rect.top >= table.height * 2 / 3 }
        val rowTop = ret?.rect?.top ?: (table.height * 3 / 4)
        val barLeft = ret?.rect?.right ?: 0
        val barRight = table.width
        val row = all.filter { k ->
            k !== ret && k.mainName() != "返回" &&
                k.rect.centerY() >= rowTop && k.rect.bottom <= table.height + 16 &&
                k.rect.right > barLeft + 20 && k.rect.left < barRight - 20
        }.sortedBy { it.rect.left }
        if (row.size < 2) return null
        val top = row.minOf { it.rect.top }
        val bottom = row.maxOf { it.rect.bottom }
        return SymbolBar(Rect(barLeft, top, barRight, bottom), false, row)
    }

    private fun barKeysIn(table: DrawTable, barRect: Rect, vertical: Boolean): List<Key> {
        val all = table.keys()
        return if (vertical) {
            all.filter { it.inColumn && it.bg?.rect == barRect }.sortedBy { it.rect.top }
        } else {
            findSymbolBottomRow(table)?.keys ?: all.filter {
                (it.inRow && it.bg?.rect == barRect) ||
                    (barRect.contains(it.rect.centerX(), it.rect.centerY()) && it.rect != barRect)
            }.sortedBy { it.rect.left }
        }
    }

    private fun findSymbolBar(table: DrawTable, k: Key): SymbolBar? {
        if (k.inColumn) {
            val bg = k.bg ?: return null
            val col = barKeysIn(table, bg.rect, true)
            return if (col.size >= 2) SymbolBar(Rect(bg.rect), true, col) else null
        }
        findSymbolBottomRow(table)?.let { rowBar ->
            if (rowBar.keys.any { it.rect == k.rect }) return rowBar
        }
        val barRect = when {
            k.inRow -> k.bg?.rect
            else -> table.keys().firstOrNull {
                it.inRow && it.bg?.rect?.contains(k.rect.centerX(), k.rect.centerY()) == true
            }?.bg?.rect
        } ?: return null
        val row = barKeysIn(table, barRect, false)
        return if (row.size >= 2) SymbolBar(Rect(barRect), false, row) else null
    }

    private fun barStateFor(bar: SymbolBar, table: DrawTable, snapshot: List<String>): BarScrollState {
        val board = KeyboardState.boardName
        if (board != barBoardName) {
            barStates.clear()
            barBoardName = board
        }
        val key = "$board:${if (bar.vertical) "V" else "H"}"
        return barStates.getOrPut(key) {
            // 首次进入该键盘的符号区且首端无被裁切项时，记录为第一页，避免在第一页首项停留时误尝试向上/左翻页
            val clippedBefore = table.hasClippedEdge(bar.barRect, bar.vertical, before = true)
            BarScrollState(startSnapshot = if (clippedBefore) null else snapshot)
        }
    }

    /**
     * 数字/九键左侧符号栏（竖向）与符号键盘底部分类栏（横向）：
     * 手指停在首尾按键超过设定时间后自动翻页，翻完读出手指下面的新项；
     * 已在第一页首项或已到达尾页边界时不尝试翻页，避免重复朗读死循环。
     */
    private fun scheduleSymbolScroll(root: View, kb: View, k: Key, rx: Float, ry: Float) {
        if (!Config.b("symbolScroll")) return
        // 热路径提前挡掉：符号栏只存在于「符号键盘」里，以及竖长/横长的滚动格里。
        // 普通字母键盘上这一整段（findSymbolBar → barKeysIn → 遍历全表）纯属白跑，
        // 实测占每次摸键约 220us。判据只是三个字段读，不会漏掉真正的符号栏。
        if (KeyboardState.boardName != "符号键盘" && !k.inColumn && !k.inRow) return
        val table = KeyboardViewHooks.tableOf(kb) ?: return
        val bar = findSymbolBar(table, k) ?: return
        val first = bar.keys.first()
        val last = bar.keys.last()
        val dir = when {
            k.rect == last.rect -> -1   // 末项：向后翻（竖栏上拖看下面，横栏左拖看右边）
            k.rect == first.rect -> 1   // 首项：向前翻（竖栏下拖看上面，横栏右拖看左边）
            else -> return
        }
        val snapshot = bar.keys.map { it.speakable() }
        val st = barStateFor(bar, table, snapshot)
        if (dir > 0) {
            val clippedBefore = table.hasClippedEdge(bar.barRect, bar.vertical, before = true)
            if (!clippedBefore && (snapshot == st.startSnapshot || snapshot == st.blockedBackSnapshot)) {
                L.i("符号栏已在第一页，不尝试向${if (bar.vertical) "上" else "左"}翻页")
                return
            }
        } else {
            val clippedAfter = table.hasClippedEdge(bar.barRect, bar.vertical, before = false)
            if (!clippedAfter && snapshot == st.blockedForwardSnapshot) {
                L.i("符号栏已在最后一页，不尝试向${if (bar.vertical) "下" else "右"}翻页")
                return
            }
        }
        val holdMs = Config.i("symbolScrollMs").toLong().coerceIn(500, 5000)
        val r = Runnable {
            scrollRunnable = null
            try {
                val curTable = KeyboardViewHooks.tableOf(kb) ?: return@Runnable
                val curBar = findSymbolBar(curTable, k) ?: bar
                val beforeNames = curBar.keys.map { it.speakable() }
                scrollingSymbolBar = true
                scrollRx = rx
                scrollRy = ry
                dragBar(kb, curBar.barRect, curBar.vertical, dir)
                val after = Runnable {
                    afterScrollRunnable = null
                    scrollingSymbolBar = false
                    try {
                        val newTable = KeyboardViewHooks.tableOf(kb)
                        val afterNames = if (newTable != null) {
                            barKeysIn(newTable, curBar.barRect, curBar.vertical).map { it.speakable() }
                        } else emptyList()
                        if (afterNames.isEmpty() || afterNames == beforeNames) {
                            if (dir > 0) st.blockedBackSnapshot = beforeNames
                            else st.blockedForwardSnapshot = beforeNames
                            val dirLabel = if (curBar.vertical) (if (dir < 0) "向下" else "向上") else (if (dir < 0) "向右" else "向左")
                            L.i("符号栏${dirLabel}翻页未变化，已到达边界")
                            return@Runnable
                        }
                        if (dir > 0) st.blockedForwardSnapshot = null else st.blockedBackSnapshot = null
                        // 重新看手指下面是什么，读出来
                        current = null
                        val t = targetAt(root, rx, ry) ?: return@Runnable
                        current = t
                        lastAnnounce = SystemClock.uptimeMillis()
                        Haptic.tick()
                        Sound.play("scroll")
                        Speaker.speak(t.name)
                        if (t.key != null && t.kb != null) scheduleSymbolScroll(root, t.kb, t.key, rx, ry)
                    } catch (e: Throwable) {
                        L.hookError("VirtualKeys.afterScroll", e)
                    }
                }
                afterScrollRunnable = after
                main.postDelayed(after, 450)
            } catch (e: Throwable) {
                scrollingSymbolBar = false
                L.hookError("VirtualKeys.symbolScroll", e)
            }
        }
        scrollRunnable = r
        main.postDelayed(r, holdMs)
    }

    /** 在符号栏上模拟一次拖动，距离是半栏高（竖栏）或半栏宽（横栏） */
    private fun dragBar(kb: View, bar: Rect, vertical: Boolean, dir: Int) {
        val vl = IntArray(2)
        kb.getLocationOnScreen(vl)
        val down = SystemClock.uptimeMillis()
        val steps = 8
        if (vertical) {
            val x = vl[0] + bar.exactCenterX()
            val dist = bar.height() * 0.5f
            val y0 = vl[1] + bar.exactCenterY() - dir * dist / 2
            L.i("符号栏${if (dir < 0) "向下" else "向上"}翻页")
            inject(kb, down, MotionEvent.ACTION_DOWN, x, y0)
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
        } else {
            val y = vl[1] + bar.exactCenterY()
            val dist = bar.width() * 0.5f
            val x0 = vl[0] + bar.exactCenterX() - dir * dist / 2
            L.i("符号栏${if (dir < 0) "向右" else "向左"}翻页")
            inject(kb, down, MotionEvent.ACTION_DOWN, x0, y)
            for (i in 1..steps) {
                val x = x0 + dir * dist * i / steps
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
        // 编辑面板按钮之间的缝：落在底板上就取最近的按钮，不读整个面板
        if (EditPanel.isBackdrop(v)) v = EditPanel.nearestButton(rx, ry) ?: return null
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
        if (EditPanel.isOpen || CustomToolbar.editing()) return null
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
                val table = KeyboardViewHooks.tableOf(v) ?: return
                val hit = table.nearest(rx - loc[0], ry - loc[1], radius) ?: return
                if (hit.second < bestD) {
                    val name = Candidates.navName(hit.first, table.keys()) ?: hit.first.speakable()
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
        // 只在根节点做一次 getLocationOnScreen，子树用父坐标 + child.left 递推。
        // 原来每个节点都调一次 getLocationOnScreen（内部要沿父链累加偏移），
        // 一次遍历 50 个节点就是 50 次跨语言调用 —— 实测这是查找耗时的主要来源。
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return findAt(v, rx, ry, loc[0].toFloat(), loc[1].toFloat())
    }

    /**
     * v 的屏幕左上角已由调用方给出（px, py）。
     * 子节点坐标用它加上 child.left/top、translationX/Y 再减滚动量推导，不再调 JNI。
     * 有旋转/缩放的节点退回 getLocationOnScreen，保证语义一致。
     */
    private fun findAt(v: View, rx: Float, ry: Float, px: Float, py: Float): Any? {
        if (v.visibility != View.VISIBLE || v.alpha <= 0f) return null
        var x0 = px
        var y0 = py
        if (v.rotation != 0f || v.scaleX != 1f || v.scaleY != 1f) {
            val l2 = IntArray(2)
            v.getLocationOnScreen(l2)
            x0 = l2[0].toFloat()
            y0 = l2[1].toFloat()
        }
        if (!(rx >= x0 && ry >= y0 && rx < x0 + v.width && ry < y0 + v.height)) return null
        if (KeyboardViewHooks.kbClass?.isInstance(v) == true) {
            if (!v.isShown || hwSeen || EditPanel.isOpen || CustomToolbar.editing()) return null
            val slop = 12f * v.resources.displayMetrics.density
            val table = KeyboardViewHooks.tableOf(v) ?: return null
            val key = table.keyAt(rx - x0, ry - y0, slop) ?: return null
            val name = Candidates.navName(key, table.keys()) ?: key.speakable()
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
                val c = v.getChildAt(i)
                // 子节点屏幕坐标 = 父节点屏幕坐标 + child.left/top + 平移 - 父节点滚动量
                val cx = x0 + c.left + c.translationX - v.scrollX
                val cy = y0 + c.top + c.translationY - v.scrollY
                val r = findAt(c, rx, ry, cx, cy) ?: continue
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
        val cached = imeInputViewRef?.get()?.takeIf { it.isAttachedToWindow && it.rootView === root }
        val area = cached ?: run {
            if (imeInputViewId == 0) imeInputViewId = root.resources.getIdentifier("ime_inputview", "id", root.context.packageName)
            (if (imeInputViewId != 0) root.findViewById<View>(imeInputViewId) else null)?.also { imeInputViewRef = java.lang.ref.WeakReference(it) }
        } ?: return false
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

    /**
     * 点是否落在控件内（hasChildAt 等路径在主线程调用）。
     *
     * 原实现每次 new IntArray(2) 并调 getLocationOnScreen。
     * 这里复用同一个数组，避免重复分配。语义完全不变。
     */
    private val locBuf = IntArray(2)

    private fun contains(v: View, rx: Float, ry: Float): Boolean {
        val loc = locBuf
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
            (n == "回车" || n == "发送") && explorer -> Config.i("enterHoldMs").toLong().coerceIn(500, 5000)
            else -> null
        }
    }

    /** 手指停在删除、空格或回车上：提前按下，让原生层长按在设定时间触发 */
    private fun scheduleHold(v: View, k: Key) {
        val total = holdMsFor(k) ?: return
        if (!Config.b("liftToType")) return
        // 有候选时空格是上屏用的，停留不开始语音；被映射为左移/右移的键（如回车并入右移）也不触发停留长按
        if (k.mainName() == "空格" && Candidates.composing) return
        if (Candidates.navName(k, KeyboardViewHooks.tableOf(v)?.keys().orEmpty()) != null) return
        val r = Runnable {
            holdRunnable = null
            try {
                holding = k
                holdView = v
                holdIsVoice = k.mainName() == "空格"
                if (holdIsVoice) spaceCancelUntil = 0L
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
        cancelScroll()
        val k = holding ?: return
        val v = holdView ?: run { holding = null; return }
        if (holdIsVoice && !voiceShown(v)) {
            // 语音还没弹出时手指离开空格：
            // 1) 发 ACTION_CANCEL（不能发 ACTION_MOVE + ACTION_UP，否则豆包会把短按空格+上滑抬手当成点按，误上屏一个空格）；
            // 2) 短暂拦截迟到的 StartAsrOnSpaceLPress，若录音刚启动则主动停掉，避免语音卡住；
            // 3) 兜底保留 adoptVoiceLater，万一后续仍弹出语音面板，由模块接回控制。
            val down = holdDownTime
            val now = SystemClock.uptimeMillis()
            spaceCancelUntil = now + NATIVE_LONG_PRESS_MS + 300
            holding = null
            holdView = null
            holdIsVoice = false
            lastHoldEnd = now
            L.i("取消「${k.speakable()}」，语音还没开始，发取消事件，按住 ${now - down} 毫秒")
            inject(v, down, MotionEvent.ACTION_CANCEL, lastRawX, lastRawY)
            if (Voice.isRecording()) stopCancelledSpaceAsr("取消时已在录音")
            main.postDelayed({
                try {
                    if (holding == null && SystemClock.uptimeMillis() < spaceCancelUntil &&
                        (Voice.isRecording() || voiceShown(v))) {
                        stopCancelledSpaceAsr("取消后延迟检查")
                    }
                } catch (t: Throwable) {
                    L.hookError("VirtualKeys.cancelVoiceCheck", t)
                }
            }, 120)
            adoptVoiceLater(v, k, down)
            return
        }
        // 手指滑到别的键上：取消，不输入
        release(v, k, MotionEvent.ACTION_CANCEL, lastRawX, lastRawY)
    }

    private fun stopCancelledSpaceAsr(why: String) {
        try {
            Voice.discard()
        } catch (t: Throwable) {
            L.hookError("VirtualKeys.stopCancelledSpaceAsr.discard", t)
        }
        val c = JniHooks.jniClass ?: return
        try {
            val stop = de.robv.android.xposed.XposedHelpers.findMethodExactIfExists(
                c, "AsrStopCommand", Boolean::class.javaPrimitiveType!!, String::class.java
            )
            if (stop != null) {
                stop.invoke(null, true, "cancel")
                L.i("取消空格长按语音：调用 AsrStopCommand(true, cancel)（$why）")
                return
            }
            val fn = de.robv.android.xposed.XposedHelpers.findMethodExactIfExists(
                c, "DoFunctionKey", Int::class.javaPrimitiveType!!
            )
            if (fn != null) {
                L.w("取消空格长按语音：AsrStopCommand 未找到，降级调用 DoFunctionKey(7)（$why）")
                fn.invoke(null, 7)
            }
        } catch (t: Throwable) {
            L.hookError("VirtualKeys.stopCancelledSpaceAsr", t)
        }
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
        val cancelVoice = holdIsVoice && action == MotionEvent.ACTION_UP && isVoiceCancelZone(voiceZone)
        // 按住删除连续删完松手：多选里这一串删除已经把选中的文本删掉了，退出选择态
        if (!holdIsVoice && k.mainName() == "删除" && action == MotionEvent.ACTION_UP) {
            EditActions.onKeyDelete()
        }
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

    private fun isVoiceCancelZone(zone: String): Boolean = VoiceZoneReader.isCancelZone(zone)

    /** 语音长按界面：手指滑进左右两个区域时朗读（底层反射与多版本字段映射委托给 VoiceZoneReader） */
    private fun checkVoiceZone(kb: View) {
        val reading = VoiceZoneReader.read(kb) ?: return
        val zone = reading.zone
        if (zone != voiceZone) {
            val first = voiceZone.isEmpty()
            voiceZone = zone
            if (!first && Config.b("voiceZoneHint")) Speaker.speak(zone)
            L.i(reading.debugInfo)
        }
    }

    /** 虚拟按键式：读屏抬手点击时，刚松开长按的不再重复输入 */
    fun justReleased(): Boolean = SystemClock.uptimeMillis() - lastHoldEnd < 400

    // ---------------- 合成触摸 ----------------

    private fun tap(v: View, k: Key) {
        KeyboardOps.cancelOrganizeWatch()
        val name = k.mainName()
        // 全屏：键盘区一打字，上半屏的编辑选项轮盘就回到“双击跳转行首行尾”那一项；
        // 删除键顺带看看多选是不是该退出了（选中的文本被删掉，编辑框里就没有选区了）
        if (fullscreenExplore) {
            if (name == "删除") EditActions.onKeyDelete() else EditOptions.keyTyped()
        }
        // 有候选时底行空格两侧按键切换候选，空格上屏选中的候选
        if (Candidates.onKey(k, KeyboardViewHooks.tableOf(v)?.keys().orEmpty())) {
            Sound.play("double")
            return
        }
        // 删除键：候选栏要变了，旧的候选状态先作废，免得左右滑还能读到删掉的候选
        if (name == "删除") {
            Candidates.onDelete()
            Sound.play("key")
            press(v, k, 40)
            return
        }
        // 打字：候选栏里接下来弹的候选归“打字”，全屏上半屏的左右滑才去动候选栏。
        // 移动光标之后弹的联想候选不算（见 Candidates.fromTyping），免得编辑文字时左右滑被吃掉。
        if ((name.length == 1 && name[0].isLetterOrDigit()) || multiLettersRegex.matches(name)) Candidates.markTyping()
        else if (name in nonTypingKeys) Candidates.reset("非输入键")
        // 按键音在手指摸到这个键的时候已经响过了（key_pressed，和参考实现一致），
        // 抬手输入时不再补一声，不然一次按键响两下
        press(v, k, 40)
    }

    /** 最近一次输入按键、最近一次摸到新目标并朗读的时间。输入后又摸到新键，就不再读上一个键带出来的候选 */
    @Volatile
    var lastKeyTap = 0L
        private set
    @Volatile
    var lastAnnounce = 0L
        private set

    /** 上一个键输入之后，手指已经摸到别的键了 */
    fun movedOnSinceTap(): Boolean = lastAnnounce > lastKeyTap

    private fun press(v: View, k: Key, ms: Long) {
        lastKeyTap = SystemClock.uptimeMillis()
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
        return handleRawTouch(root, ev)
    }

    /**
     * 全屏输入：键盘区的触摸由模块自己接管，不看读屏的触摸浏览是开是关。
     *
     * 这是 1.2.1 全屏重做的核心——全屏模式下模块自己就是一层触摸浏览，
     * 读屏被暂停、被关掉、甚至根本没装读屏，键盘区照样“摸到读键、抬手输入”。
     * 细节（停留删除、长按空格语音、手写区/豆包面板让路）和半屏那条路完全一致，走同一份代码。
     */
    fun onFullscreenTouch(root: View, ev: MotionEvent): Boolean {
        if (injecting) return false
        if (!Config.b("virtualKeys")) return false
        if (!A11yState.recording) return false
        return handleRawTouch(root, ev)
    }

    private fun handleRawTouch(root: View, ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            passThrough = false
            nativeVoiceKb = null
            val loc = IntArray(2)
            root.getLocationOnScreen(loc)
            downX = ev.x
            downY = ev.y
            downTime = ev.downTime
            downRoot = root
            val rx = ev.x + loc[0]
            val ry = ev.y + loc[1]
            val t = targetAt(root, rx, ry)
            downInScroller = if (inHandwrite) inHorizontalScroller(root, rx, ry) else false
            if (inHandwrite && !downInScroller) {
                Candidates.markHandwriting()
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
            // 空格、删除、回车默认由模块处理；
            // 设置里改成豆包原生操作时，整串触摸交给豆包。用逗号句号选了别的候选时，空格仍由模块上屏那个候选
            val n = t?.key?.mainName()
            val keyNav = t?.key != null && t.kb != null &&
                Candidates.navName(t.key, KeyboardViewHooks.tableOf(t.kb)?.keys().orEmpty()) != null
            val native = (n == "空格" && Config.b("nativeSpace") && Candidates.sel <= 0) ||
                (n == "删除" && Config.b("nativeDelete")) ||
                ((n == "回车" || n == "发送") && Config.b("nativeEnter") && !keyNav)
            if (native) {
                passThrough = true
                cancelHold()
                current = null
                lastKeyTap = SystemClock.uptimeMillis()
                if (n == "空格") {
                    nativeVoiceKb = t?.kb
                    voiceZone = ""
                }
                // 删除交给豆包原生处理时，多选也该跟着退出：等原生删完再核对选区
                if (n == "删除") EditActions.onKeyDelete()
                speakTargetName(n)
                L.i("直接触摸：$n 交给豆包原来的处理")
                return false
            }
        }
        if (passThrough) {
            nativeVoiceKb?.let { kb ->
                // 空格交给豆包时也跟着语音区域：豆包撤回了，模块留着的中间结果也要丢掉，不然 3 秒后会上屏
                if (ev.actionMasked == MotionEvent.ACTION_MOVE) checkVoiceZone(kb)
                if (ev.actionMasked == MotionEvent.ACTION_UP) {
                    checkVoiceZone(kb)
                    if (isVoiceCancelZone(voiceZone)) {
                        L.i("原生空格：$voiceZone")
                        Voice.discard()
                    }
                }
            }
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (ev.actionMasked == MotionEvent.ACTION_UP) lastKeyTap = SystemClock.uptimeMillis()
                passThrough = false
                nativeVoiceKb = null
                voiceZone = ""
            }
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
    /** 空格交给豆包原生处理时的键盘视图，用来跟踪语音长按界面的区域 */
    private var nativeVoiceKb: View? = null

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

    // ---------------- 全屏手势用：按名字点键、按住空格说话 ----------------

    /** 找到读法（或主名字）是 name 的键，连同它所在的键盘视图 */
    private fun findKey(name: String): Pair<View, Key>? {
        val root = try {
            ViewA11yHooks.service?.window?.window?.decorView
        } catch (_: Throwable) {
            null
        } ?: return null
        val c = KeyboardViewHooks.kbClass ?: return null
        var found: Pair<View, Key>? = null
        fun walk(v: View) {
            if (found != null || v.visibility != View.VISIBLE) return
            if (c.isInstance(v)) {
                if (!v.isShown) return
                val t = KeyboardViewHooks.tableOf(v) ?: return
                val k = t.keys().firstOrNull { it.speakable() == name || it.mainName() == name }
                if (k != null) found = v to k
                return
            }
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return found
    }

    /** 全屏手势：点键盘上某个名字的键（空格上屏、中英切换） */
    fun tapKeyNamed(name: String): Boolean {
        val (v, k) = findKey(name) ?: return false
        L.i("全屏手势：点击「${k.speakable()}」")
        tap(v, k)
        return true
    }

    /** 模拟豆包原生“在删除键上向左滑”清空候选栏 */
    fun swipeLeftDelete(): Boolean {
        val (v, k) = findKey("删除") ?: return false
        return try {
            val vl = IntArray(2)
            v.getLocationOnScreen(vl)
            val x0 = vl[0] + k.rect.exactCenterX()
            val y0 = vl[1] + k.rect.exactCenterY()
            val density = v.resources.displayMetrics.density
            val dist = maxOf(160f * density, k.rect.width() * 2.2f).coerceAtMost((x0 - vl[0] - 8f).coerceAtLeast(1f))
            val down = SystemClock.uptimeMillis()
            L.i("全屏手势：模拟左滑删除键（起点=${x0.toInt()},${y0.toInt()} 左滑=${dist.toInt()}px）")
            inject(v, down, MotionEvent.ACTION_DOWN, x0, y0)
            for (step in 1..3) {
                val x = x0 - dist * step / 3f
                main.postDelayed({
                    try {
                        inject(v, down, MotionEvent.ACTION_MOVE, x, y0)
                    } catch (t: Throwable) {
                        L.hookError("VirtualKeys.swipeLeftDelete.move", t)
                    }
                }, step * 10L)
            }
            main.postDelayed({
                try {
                    inject(v, down, MotionEvent.ACTION_UP, x0 - dist, y0)
                } catch (t: Throwable) {
                    L.hookError("VirtualKeys.swipeLeftDelete.up", t)
                }
            }, 40L)
            true
        } catch (t: Throwable) {
            L.hookError("VirtualKeys.swipeLeftDelete", t)
            false
        }
    }

    /** 全屏手势：点键盘窗口里的一个控件（菜单按钮这类） */
    fun tapViewOnScreen(root: View, v: View) = tapView(root, v)

    /**
     * 全屏手势的语音输入：第一次执行按下空格并一直按着（豆包开始录音），
     * 第二次执行松开（结束录音）。说话期间手指不用按着屏幕。
     */
    fun toggleVoice(): Boolean {
        val hk = holding
        val hv = holdView
        if (hk != null && hv != null && holdIsVoice) {
            L.i("全屏手势：松开空格结束语音")
            release(hv, hk, MotionEvent.ACTION_UP, lastRawX, lastRawY)
            return false
        }
        if (hk != null) cancelHold()
        val (v, k) = findKey("空格") ?: run {
            Speaker.speak("没找到空格键")
            return false
        }
        val vl = IntArray(2)
        v.getLocationOnScreen(vl)
        holding = k
        holdView = v
        holdIsVoice = true
        spaceCancelUntil = 0L
        voiceZone = ""
        holdDownTime = SystemClock.uptimeMillis()
        lastRawX = vl[0] + k.rect.exactCenterX()
        lastRawY = vl[1] + k.rect.exactCenterY()
        L.i("全屏手势：按下空格开始语音")
        inject(v, holdDownTime, MotionEvent.ACTION_DOWN, lastRawX, lastRawY)
        return true
    }

    /** 正在按住空格说话（全屏模式退出、键盘收起时要松开） */
    fun voiceHeld(): Boolean = holding != null && holdIsVoice

    /** 松开按住的空格（兜底） */
    fun releaseVoice() {
        val hv = holdView ?: return
        val hk = holding ?: return
        if (!holdIsVoice) return
        release(hv, hk, MotionEvent.ACTION_UP, lastRawX, lastRawY)
    }

    /**
     * 撤掉当前这一次“摸键”：取消停留计时、松开已经按住的长按、忘掉摸到的键。
     * 全屏触摸层要用——第一根手指已经摸到键并开始朗读了，紧跟着又落下第二根手指（双指手势），
     * 这时要把第一根手指的事全撤掉，不能让它在抬手时把那个键打出去。
     */
    fun cancelExplore() {
        cancelHold()
        current = null
        passThrough = false
        nativeVoiceKb = null
    }

    fun reset() {
        holdRunnable?.let { main.removeCallbacks(it) }
        holdRunnable = null
        cancelScroll()
        barStates.clear()
        barBoardName = ""
        holding = null
        holdView = null
        holdIsVoice = false
        spaceCancelUntil = 0L
        lastSpokenKeyName = ""
        lastSpokenKeyAt = 0L
        for (s in states.values) {
            s.hovered = HOST
            s.focused = HOST
        }
    }
}
