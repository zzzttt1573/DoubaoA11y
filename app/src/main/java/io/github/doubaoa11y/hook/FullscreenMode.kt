package io.github.doubaoa11y.hook

import android.app.Dialog
import android.graphics.Rect
import android.graphics.Region
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout

/**
 * 上半屏操作模式。
 *
 * 关键点（1.1.7 修正）：豆包输入法自己的窗口本来就是满屏的——
 * ImeService.onConfigureWindow 里它自己调了 setLayout(MATCH_PARENT, MATCH_PARENT)，
 * 键盘只是画在窗口底部，上半屏靠 onComputeInsets 报一个“只有键盘那块能摸”的触摸区域，
 * 所以原来那样去改窗口高度，什么都不会变，这就是上一版全屏和半屏没区别的原因。
 *
 * 现在的做法：
 * 1. 在窗口根视图上叠一层手势层，高度 = 键盘顶边（含工具栏、候选栏），贴顶；
 * 2. 挂 ImeService.onComputeInsets，把窗口的触摸区域改成整个窗口
 *    （touchableInsets = TOUCHABLE_INSETS_REGION，区域 = 整个窗口），
 *    这样上半屏的触摸/悬停才会送到输入法窗口，落到手势层上，而不是被应用窗口接走；
 * 3. contentTopInsets 仍然用豆包自己算的键盘顶边，应用该让位还是让位，和半屏一样；
 * 4. 窗口万一没满屏（个别机型/横屏），再补一次窗口高度兜底。
 *
 * 任何一步失败都会自动退回半屏（activ=false），并在状态里写明原因。
 */
object FullscreenMode {
    private val main = Handler(Looper.getMainLooper())

    private const val TOUCHABLE_REGION = 3

    /** 全屏真正生效（手势层已经挂上、触摸区域已经改好） */
    @Volatile
    var active = false
        private set

    /** 最近一次失败原因，设置页和日志读它 */
    @Volatile
    var failedReason = ""
        private set

    private var layer: GestureLayer? = null
    private var decor: ViewGroup? = null
    private var dialog: Dialog? = null
    private var listener: ViewTreeObserver.OnGlobalLayoutListener? = null

    /** 键盘顶边在窗口里的 y */
    private var kbTop = 0
    /** 窗口高度 */
    private var winH = 0
    private var winW = 0

    private var tries = 0
    /** 自己把窗口撑满时记下原值，退出还原 */
    private var forcedHeight = Int.MIN_VALUE
    private var forcedGravity = Int.MIN_VALUE
    /** 上一次改 insets 的结果，用来写日志，避免刷屏 */
    private var lastInsetsLog = ""
    /** 上一次应用的布局（宽/高/键盘顶边），没变就不重复请求布局 */
    private var lastLayoutKey = ""

    /** 该不该全屏：输入方式选了上半屏操作，并且模块总开关开着，并且没被“暂时关闭全屏” */
    val wanted: Boolean get() = Config.fullscreen && A11yState.moduleOn && A11yState.recording && !tempSuspended

    /**
     * 用户执行“暂时关闭全屏模式”后的标记：true 期间退回半屏（键盘不收），
     * 键盘下次收起再展开时自动清掉，恢复全屏。
     */
    @Volatile
    private var tempSuspended = false

    /** 暂时关闭全屏模式：不收起键盘，只把输入法切回半屏；下次键盘再展开自动恢复全屏 */
    fun suspendTemporarily(): Boolean {
        if (!active) {
            // 本来就不在全屏，没得关
            Speaker.speak("当前不是全屏模式")
            return false
        }
        tempSuspended = true
        detach("暂时关闭全屏")
        Speaker.speak("已暂时关闭全屏模式，下次展开自动恢复")
        return true
    }

    /**
     * 工具栏上的“切换全屏和半屏”按钮：在模块的输入方式里真正切一次，立即全局生效。
     *
     * 和“暂时关闭全屏模式”不同——那个只是这一次收起全屏，下次展开还会回来；
     * 这里改的是设置里的输入方式本身（上半屏操作 ↔ 半屏输入），并且写回设置页那一份，
     * 所以键盘收起再展开、换应用、重启输入法都按新的来。
     */
    fun toggleScreenMode(): Boolean {
        val next = !Config.fullscreen
        Config.setFromIme(imeContext(), "upperOperation", next)
        tempSuspended = false
        if (next) {
            attach("工具栏切到全屏")
            Speaker.speak("已开启上半屏手势")
        } else {
            detach("工具栏切到半屏")
            Speaker.speak("已关闭上半屏手势")
        }
        ModuleEntries.toolbar()
        ToolbarInject.refreshToolbar()
        return true
    }

    private fun imeContext(): android.content.Context? = try {
        ViewA11yHooks.service
    } catch (_: Throwable) {
        null
    }

    // ---------------- 生命周期 ----------------

    fun onWindowShown() {
        if (!wanted) {
            detach("不是上半屏操作")
            return
        }
        attach("键盘显示")
        // 刚显示的一瞬间 isInputViewShown 可能还没翻成 true：稍等再确认一次
        main.postDelayed({ if (wanted && !active) attach("键盘显示后重试") }, 120)
    }

    fun onWindowHidden() {
        // 键盘收起：清掉“暂时关闭全屏”标记，下次展开自动恢复全屏
        tempSuspended = false
        detach("键盘收起")
    }

    /** 设置变化、换输入框、解锁后 */
    fun refresh(why: String) {
        // 只有键盘真的显示着才进全屏。设置页、应用界面里键盘没弹出来，
        // 这里要是照挂手势层，会顺手把触摸浏览关掉——用户看到的就是“打开设置页屏幕就不能摸了”
        if (wanted && keyboardShown()) attach(why) else detach(why)
    }

    fun shutdown() {
        detach("输入法进程退出")
    }

    /**
     * 键盘是不是真的展开着。
     * 两个条件都要：输入法视图显示着（isInputViewShown），窗口也是可见的。
     * 键盘收起、应用切到后台、模块设置页打开这些情况都返回 false。
     */
    internal fun keyboardShown(): Boolean {
        return try {
            val svc = ViewA11yHooks.service ?: return false
            if (!svc.isInputViewShown) return false
            val dv = svc.window?.window?.decorView as? ViewGroup ?: return false
            if (!dv.isShown) return false
            // 天坦的判断比“显示标志”更严一层：键盘本体要真的在屏幕上撑着。
            // 豆包切到某些面板时 isInputViewShown 还是 true，但键盘已经收起来了，
            // 这时候按全屏处理会顺手关掉触摸浏览。拿不到键盘视图时不阻塞（老机型兜底）。
            val v = findInputView(dv)
            if (v != null && (!v.isShown || v.height <= 0)) return false
            true
        } catch (_: Throwable) {
            false
        }
    }

    // ---------------- 进全屏 ----------------

    private fun attach(why: String) {
        if (!keyboardShown()) {
            if (active || layer != null) detach("键盘没显示（$why）")
            return
        }
        val svc = ViewA11yHooks.service ?: return
        val d = try {
            svc.window
        } catch (t: Throwable) {
            null
        }
        if (d == null) {
            fail("拿不到键盘窗口")
            return
        }
        val dv = try {
            d.window?.decorView as? ViewGroup
        } catch (t: Throwable) {
            null
        }
        if (dv == null) {
            fail("键盘窗口根视图不是 ViewGroup")
            return
        }
        if (decor !== dv) {
            if (layer != null) detach("窗口换了")
            dialog = d
            decor = dv
            val l = GestureLayer(dv.context)
            try {
                dv.addView(l, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, Gravity.TOP))
            } catch (t: Throwable) {
                L.e("上半屏操作：手势层挂不上", t)
                decor = null
                dialog = null
                fail("手势层挂不上")
                return
            }
            layer = l
            watch(dv)
        }
        measure(why)
    }

    /** 量键盘位置、摆好手势层、请求重算触摸区域 */
    private fun measure(why: String): Boolean {
        // 每量一次都先确认键盘还在：收起动画、切到别的应用时这里会直接退出，
        // 不会留下“已经不在全屏却还停着触摸浏览”的残留
        if (!keyboardShown()) {
            detach("键盘已收起（$why）")
            return false
        }
        val dv = decor ?: return false
        val l = layer ?: return false
        if (!dv.isAttachedToWindow || dv.height <= 0) {
            // 刚显示时窗口还没挂上去：等一下再看，别急着退出
            if (tries < 10) {
                tries++
                main.postDelayed({ if (wanted && layer != null) measure("等窗口挂上第 $tries 次") }, 100)
            } else {
                fail("窗口一直没挂上")
                detach("窗口一直没挂上（$why）")
            }
            return false
        }
        val h = dv.height
        if (h <= 0) {
            if (tries < 8) {
                tries++
                main.postDelayed({ if (wanted) measure("等窗口尺寸第 $tries 次") }, 120)
            } else {
                fail("等不到窗口尺寸")
                detach("等不到窗口尺寸")
            }
            return false
        }
        tries = 0
        val width = dv.width

        // 窗口没满屏：自己补一次（豆包一般已经满了，这里是兜底）
        if (h < screenHeight(dv) * 0.72f) forceWindowFull()

        val top = keyboardTop(dv)
        val nextTop = when {
            top in 1 until h -> top
            kbTop in 1 until h -> kbTop
            else -> (h * 0.58f).toInt()
        }

        // 布局没变就别再请求布局：不然 onGlobalLayout → measure → requestLayout 会自激，
        // 一遍遍重排很费电
        val key = "$width/$h/$nextTop"
        if (key == lastLayoutKey) return true

        val lp = l.layoutParams as? FrameLayout.LayoutParams
        if (lp == null || lp.height != nextTop) {
            try {
                l.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, nextTop, Gravity.TOP)
            } catch (t: Throwable) {
                L.hookError("FullscreenMode.layoutLayer", t)
                return false
            }
        }

        winW = width
        winH = h
        kbTop = nextTop
        lastLayoutKey = key
        val first = !active
        if (first) {
            active = true
            failedReason = ""
            L.i("上半屏操作生效：窗口 ${winW}x$winH，键盘顶边 $kbTop，手势区高 $kbTop")
            // 区域穿透可用时交给输入法，否则继续保留读屏悬停。
            TouchGuard.pause()
            // 窗口刚展开时区域可能尚未就绪，稍后补发。

            // 重复同步区域是幂等的。
            for (ms in longArrayOf(200L, 600L, 1200L)) {
                main.postDelayed({
                    if (active && !TouchGuard.isPaused) TouchGuard.pause()
                }, ms)
            }
        }
        // 请求重新算一次 insets：改完区域才会把上半屏的触摸交给输入法
        requestInsets(dv)
        main.post { if (active) requestInsets(decor) }
        return true
    }

    private fun screenHeight(v: View): Int = try {
        v.resources.displayMetrics.heightPixels
    } catch (_: Throwable) {
        0
    }

    private fun forceWindowFull() {
        val d = dialog ?: return
        try {
            val w = d.window ?: return
            val lp = w.attributes ?: return
            if (lp.height == ViewGroup.LayoutParams.MATCH_PARENT) return
            if (forcedHeight == Int.MIN_VALUE) {
                forcedHeight = lp.height
                forcedGravity = lp.gravity
            }
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            if (lp.gravity and Gravity.BOTTOM == 0) lp.gravity = lp.gravity or Gravity.BOTTOM
            w.attributes = lp
            L.i("上半屏操作：窗口原本只有 ${forcedHeight} 高，已撑满")
        } catch (t: Throwable) {
            L.hookError("FullscreenMode.forceWindowFull", t)
        }
    }

    /** 键盘顶边（含工具栏、候选栏）在窗口里的 y 坐标 */
    private fun keyboardTop(dv: ViewGroup): Int {
        val v = findInputView(dv) ?: return -1
        if (!v.isShown || v.height <= 0) return if (kbTop > 0) kbTop else -1
        return try {
            val p = IntArray(2)
            v.getLocationInWindow(p)
            p[1]
        } catch (_: Throwable) {
            -1
        }
    }

    private fun findInputView(v: View): View? {
        if (v.javaClass.name == INPUT_VIEW) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) {
            findInputView(v.getChildAt(i))?.let { return it }
        }
        return null
    }

    /** 让系统重新走一遍 onComputeInsets */
    private fun requestInsets(v: ViewGroup?) {
        val dv = v ?: return
        try {
            dv.requestLayout()
        } catch (t: Throwable) {
            L.hookError("FullscreenMode.requestInsets", t)
        }
    }

    private fun watch(dv: ViewGroup) {
        if (listener != null) return
        try {
            var warned = false
            val li = ViewTreeObserver.OnGlobalLayoutListener {
                if (active) {
                    try {
                        if (measure("布局变化")) {
                            TouchGuard.syncPanel()
                            syncPanelVisibility()
                        }
                    } catch (t: Throwable) {
                        if (!warned) {
                            warned = true
                            L.hookError("FullscreenMode.globalLayout", t)
                        }
                    }
                }
            }
            dv.viewTreeObserver.addOnGlobalLayoutListener(li)
            listener = li
        } catch (t: Throwable) {
            L.hookError("FullscreenMode.watch", t)
        }
    }

    private fun unwatch() {
        val li = listener ?: return
        listener = null
        try {
            decor?.viewTreeObserver?.removeOnGlobalLayoutListener(li)
        } catch (_: Throwable) {
        }
    }

    private var lastFailSpoke = 0L

    /** 全屏没做成：写状态、写日志，并读给用户听（10 秒内只读一次，免得刷屏） */
    private fun fail(why: String) {
        failedReason = why
        L.w("上半屏操作没生效：$why")
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastFailSpoke > 10_000) {
            lastFailSpoke = now
            Speaker.speak("上半屏操作没生效，已按半屏输入处理")
        }
    }

    // ---------------- insets：这是全屏能不能用的关键 ----------------

    /**
     * ImeService.onComputeInsets 之后调用。
     * 把触摸区域改成整个窗口：上半屏的触摸和悬停才会进输入法窗口，落到手势层上。
     * 内容区域仍然用豆包自己算的键盘顶边，应用该让位还是让位。
     */
    fun onComputeInsets(insets: Any) {
        if (!active || !wanted || winH <= 0 || kbTop <= 0) return
        // 剪贴板/菜单/定制工具栏等读屏面板打开时，不覆盖整屏触摸区域，交还给豆包与系统读屏焦点导航
        if (layerHidden || TatansBridge.panelOpen()) return
        val value = insets as? android.inputmethodservice.InputMethodService.Insets
        if (value == null) {
            detach("触摸区域类型不匹配"); fail("无法设置全屏触摸区域"); return
        }
        val oldContent = value.contentTopInsets
        val oldVisible = value.visibleTopInsets
        val oldTouchable = value.touchableInsets
        val oldRegion = Region(value.touchableRegion)
        try {
            val top = if (oldContent in 1 until winH) oldContent else kbTop
            value.contentTopInsets = top
            value.visibleTopInsets = top
            value.touchableInsets = TOUCHABLE_REGION
            value.touchableRegion.set(0, 0, winW, winH)
            lastInsetsLog = "内容顶边=$top 触摸区域=0,0,$winW,$winH"
        } catch (t: Throwable) {
            value.contentTopInsets = oldContent
            value.visibleTopInsets = oldVisible
            value.touchableInsets = oldTouchable
            value.touchableRegion.set(oldRegion)
            L.hookError("FullscreenMode.onComputeInsets", t)
            detach("触摸区域设置失败"); fail("无法设置全屏触摸区域")
        }
    }

    // ---------------- 退全屏 ----------------

    private fun detach(why: String) {
        val was = active
        active = false
        EditOptions.onKeyboardHidden()
        // 键盘收起就把编辑选项轮盘复位，下次弹出从“全选”开始
        EditOptions.reset()
        // 全屏触摸层（上半屏手势 + 键盘区自建触摸浏览）的状态一并清掉
        FullscreenTouch.reset()
        VirtualKeys.cancelExplore()
        unwatch()
        val l = layer
        l?.cancelTouch()
        layer = null
        if (l != null) {
            try {
                (l.parent as? ViewGroup)?.removeView(l)
            } catch (t: Throwable) {
                L.hookError("FullscreenMode.removeLayer", t)
            }
        }
        val d = dialog
        if (d != null && forcedHeight != Int.MIN_VALUE) {
            try {
                val w = d.window
                val lp = w?.attributes
                if (lp != null) {
                    lp.height = forcedHeight
                    if (forcedGravity != Int.MIN_VALUE) lp.gravity = forcedGravity
                    w.attributes = lp
                }
            } catch (t: Throwable) {
                L.hookError("FullscreenMode.restoreWindow", t)
            }
        }
        forcedHeight = Int.MIN_VALUE
        forcedGravity = Int.MIN_VALUE
        val dv = decor
        decor = null
        dialog = null
        kbTop = 0
        winH = 0
        winW = 0
        tries = 0
        lastInsetsLog = ""
        lastLayoutKey = ""
        if (dv != null) requestInsets(dv)
        // 只要这次退出时触摸浏览还被模块关着，就一定要打开——不能因为 active 已经提前置 false 就漏掉
        if (was || TouchGuard.isPaused) {
            L.i("退出上半屏操作（$why）")
            // 退出后撤销整屏区域穿透，恢复原有键盘区域。
            TouchGuard.resume("退出上半屏操作（$why）")
        }
    }

    // ---------------- 触摸路由 ----------------

    /** 键盘窗口根视图的触摸：落在手势区就交给手势层 */
    fun onRootTouch(root: View, ev: MotionEvent): Boolean {
        if (!active || !wanted) return false
        val l = layer ?: return false
        if (!l.isShown) return false
        // 手指按下在上半屏后，后续移动/抬手即使向下越界进入键盘区，也应继续交给手势层完成识别，
        // 避免单指下滑、单指向下拖动或下滑折线手势因进入键盘区而被 cancelGesture 吞掉。
        val anywhere = ev.actionMasked != MotionEvent.ACTION_DOWN
        val local = toLayer(root, l, ev, anywhere = anywhere) ?: return false
        return try {
            l.handleTouch(local)
        } finally {
            local.recycle()
        }
    }

    /** 键盘窗口根视图的手势：手指划出了手势区，把这一次作废 */
    fun cancelGesture() {
        try {
            layer?.cancelTouch()
        } catch (t: Throwable) {
            L.hookError("FullscreenMode.cancelGesture", t)
        }
    }

    /** 键盘窗口根视图的悬停：触摸浏览没暂停时走这条路 */
    fun onRootHover(root: View, ev: MotionEvent): Boolean {
        if (!active || !wanted) return false
        val l = layer ?: return false
        // 手指原本在上半屏悬停、滑进了下半屏键盘区：取消上半屏悬停，交回键盘区处理
        if (ev.actionMasked == MotionEvent.ACTION_HOVER_MOVE && l.isHoverTracking) {
            val local = toLayer(root, l, ev)
            if (local == null) {
                l.cancelHover()
                return false
            }
            return try {
                l.handleHover(local)
            } finally {
                local.recycle()
            }
        }
        // 只有原本在上半屏手势区跟踪中的悬停离开，才允许越界收尾；
        // 落在下半屏键盘/工具栏上的 HOVER_EXIT 绝不能被手势层吞掉，否则键盘与工具栏全部无法抬手输入/点击。
        val exit = ev.actionMasked == MotionEvent.ACTION_HOVER_EXIT && l.isHoverTracking
        val local = toLayer(root, l, ev, anywhere = exit) ?: return false
        return try {
            l.handleHover(local)
        } finally {
            local.recycle()
        }
    }

    private fun toLayer(root: View, l: GestureLayer, ev: MotionEvent, anywhere: Boolean = false): MotionEvent? {
        if (!l.isShown || l.height <= 0) return null
        val rl = IntArray(2)
        root.getLocationOnScreen(rl)
        val ll = IntArray(2)
        l.getLocationOnScreen(ll)
        val x = ev.x + rl[0] - ll[0]
        val y = ev.y + rl[1] - ll[1]
        if (!anywhere && (x < 0 || y < 0 || x >= l.width || y >= l.height)) return null
        val e = MotionEvent.obtain(ev)
        e.setLocation(x, y)
        return e
    }

    // ---------------- 对外状态 ----------------

    /**
     * 面板（豆包菜单、剪贴板、常用语、定制工具栏）打开时，把上半屏手势区藏起来。
     *
     * 这些界面在菜单/剪贴板打开后会占住屏幕中间和上半部分，手势层一直盖在上面会有两个问题：
     * 一是读屏/手指摸到的是"全屏手势区"而不是面板里的内容，二是面板自己收不到触摸。
     * 所以面板一开就把手势层 GONE 掉（同时从无障碍树里摘掉），关掉立刻恢复。
     */
    private var layerHidden = false

    fun syncPanelVisibility() {
        val l = layer ?: return
        val hide = try {
            TatansBridge.panelOpen()
        } catch (_: Throwable) {
            false
        }
        if (hide == layerHidden && hide == (l.visibility == View.GONE)) return
        layerHidden = hide
        if (hide) {
            l.cancelTouch()
        }
        try {
            l.visibility = if (hide) View.GONE else View.VISIBLE
            l.importantForAccessibility =
                if (hide) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                else View.IMPORTANT_FOR_ACCESSIBILITY_YES
            // 面板开合时必须重新计算输入法窗口触摸区域：打开面板时交还上半屏给系统与读屏导航，
            // 关闭面板后再恢复全屏手势区触摸范围。
            requestInsets(decor)
            main.post { if (active) requestInsets(decor) }
            L.i("上半屏手势区${if (hide) "已隐藏（面板打开）" else "已恢复"}")
        } catch (t: Throwable) {
            L.hookError("FullscreenMode.syncPanelVisibility", t)
        }
    }

    /** 整个键盘窗口在屏幕上的矩形：全屏时就是整屏，穿透区域用它 */
    fun windowRect(): Rect? {
        if (!active) return null
        val dv = decor ?: return null
        return try {
            val loc = IntArray(2)
            dv.getLocationOnScreen(loc)
            Rect(loc[0], loc[1], loc[0] + dv.width, loc[1] + dv.height)
        } catch (_: Throwable) {
            null
        }
    }

    /** 手势区在屏幕上的矩形 */
    fun gestureBounds(): Rect? = layer?.screenRect()

    /** 键盘顶边在屏幕上的 y（上半屏手势区和下半屏键盘区的分界）；拿不到返回 -1 */
    fun keyboardTopOnScreen(): Int {
        if (!active) return -1
        val dv = decor ?: return -1
        val v = findInputView(dv) ?: return if (kbTop > 0) {
            try {
                val loc = IntArray(2)
                dv.getLocationOnScreen(loc)
                loc[1] + kbTop
            } catch (_: Throwable) {
                -1
            }
        } else -1
        return try {
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            loc[1]
        } catch (_: Throwable) {
            -1
        }
    }

    fun status(): String = when {
        !Config.fullscreen -> "当前不是上半屏操作"
        active -> "上半屏操作已生效，手势区高 $kbTop，窗口 ${winW}x$winH。${FullscreenTouch.describe()}"
        failedReason.isNotEmpty() -> failedReason
        wanted -> "上半屏操作正在准备"
        else -> "上半屏操作未启用"
    }

    /** 给日志用的一行诊断 */
    fun diag(): String {
        val svc = ViewA11yHooks.service
        val dv = try {
            svc?.window?.window?.decorView
        } catch (_: Throwable) {
            null
        }
        val top = if (dv is ViewGroup) keyboardTop(dv) else -1
        return "全屏=${active} 手势层=${layer?.height ?: 0} 键盘顶边=$kbTop 实测顶边=$top 窗口=${dv?.width}x${dv?.height} " +
            "读屏=${A11yState.readerPackage.ifEmpty { "无" }} ${TouchGuard.describe()}"
    }

    const val INPUT_VIEW = "com.bytedance.android.input.keyboard.InputView"
}
