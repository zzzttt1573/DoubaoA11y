package io.github.doubaoa11y.hook

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import io.github.doubaoa11y.ConfigSpec

/**
 * 全屏输入的自建触摸层（1.2.1 重做）。
 *
 * 目的：全屏模式下**不再依赖屏幕阅读器的触摸浏览**。手指怎么走，全由模块自己判：
 *
 * - 上半屏（键盘顶边以上）：手势区。单击、双击、上下左右滑、双指双击、拖动，
 *   槽位和天坦输入法一一对应，见 [GestureLayer]。
 * - 键盘区（键盘顶边以下）：模块自己的一套触摸浏览。手指落在哪个键/按钮上就读哪个，
 *   抬手就输入那个键，停在删除/空格上就连续删除、开始语音。见 [VirtualKeys.onFullscreenTouch]。
 *
 * 和以前最大的区别：
 * 1. 以前键盘区要不要自己处理，看读屏的触摸浏览是不是被关掉了（`VirtualKeys.touchMode`），
 *    读屏没关、或者读屏状态没来得及变，键盘区就落回豆包原生触摸——没有朗读，读屏一暂停还会漏键。
 *    现在全屏一律自己处理，读屏暂停、读屏关掉、甚至根本没装读屏，都能摸到键、听到朗读、抬手打字。
 * 2. 一次手势从头到尾只归一个区域：按下在哪边，抬手也在哪边结束，中途划过边界不会半路换手，
 *    免得“上半屏滑到一半掉进键盘”把键盘点了一下。
 * 3. 键盘区的双指手势在这里识别（双指双击＝语音输入那类），单指的摸键/输入交给 VirtualKeys。
 */
object FullscreenTouch {
    private const val NONE = 0
    private const val UPPER = 1
    private const val KEYBOARD = 2

    /** 这次手势按下时落在哪个区域（整个手势期间不变） */
    private var region = NONE

    private val density get() = android.content.res.Resources.getSystem().displayMetrics.density

    private val main = Handler(Looper.getMainLooper())

    /** 判“有没有滑动”的距离：跟手势灵敏度一起缩放（和 GestureLayer 保持同一套手感） */
    private val slop: Float
        get() = maxOf(
            18f * density * when (Config.i("gestureSensitivity").coerceIn(0, 2)) {
                0 -> 1.45f
                2 -> 0.55f
                else -> 1f
            },
            18f * density,
        )

    private val twoDoubleGapMs: Long
        get() = TwoFingerTapPolicy.doubleGapMs(Config.i("gestureSensitivity").coerceIn(0, 2))

    // 键盘区双指手势
    private var twoTapPending = false
    private var lastTwoTapUpAt = 0L
    private var lastTwoTapX = 0f
    private var lastTwoTapY = 0f
    private var twoDown = false
    private var firstDownAt = 0L
    private var twoAt = 0L
    private var twoX = 0f
    private var twoY = 0f
    private var twoUpX = 0f
    private var twoUpY = 0f
    private var twoFired = false

    private val twoSingleRunner: Runnable = object : Runnable {
        override fun run() {
            if (!twoTapPending) return
            if (region == KEYBOARD && !twoDown && SystemClock.uptimeMillis() - firstDownAt in 0L..120L) {
                main.postDelayed(this, 80L)
                return
            }
            twoTapPending = false
            lastTwoTapUpAt = 0L
            GestureLayer.fireSlot(ConfigSpec.G_DOUBLE_SINGLE_TAP, "全屏双指单击")
        }
    }

    private val twoExpireRunner: Runnable = object : Runnable {
        override fun run() {
            if (!twoTapPending) return
            if (region == KEYBOARD && !twoDown && SystemClock.uptimeMillis() - firstDownAt in 0L..120L) {
                main.postDelayed(this, 80L)
                return
            }
            twoTapPending = false
            lastTwoTapUpAt = 0L
        }
    }

    private fun cancelTwoTap() {
        twoTapPending = false
        lastTwoTapUpAt = 0L
        main.removeCallbacks(twoSingleRunner)
        main.removeCallbacks(twoExpireRunner)
    }

    /** 全屏触摸的统一入口，所有手指事件都从这里进来 */
    fun handleTouch(root: View, ev: MotionEvent): Boolean {
        if (!FullscreenMode.wanted || !FullscreenMode.active) return false
        if (VirtualKeys.injecting) return false
        val action = ev.actionMasked
        val panelOpen = if (action == MotionEvent.ACTION_DOWN) TatansBridge.panelOpen() else TatansBridge.panelOpenFast()
        if (panelOpen) return false
        if (action == MotionEvent.ACTION_DOWN) {
            region = NONE
            twoDown = false
            twoFired = false
            firstDownAt = ev.eventTime
        }
        if (region == NONE) {
            if (!downInKeyboard(root, ev)) {
                // 上半屏：交给手势层
                region = UPPER
                FullscreenMode.onRootTouch(root, ev)
                return true
            }
            region = KEYBOARD
        }

        if (region == UPPER) {
            // 上半屏：整个手势都给手势层。手指划到键盘区去了就把这一下作废（不当作点了键盘），
            // 免得“往上滑到一半滑进键盘”被当成摸了一下某个键
            val handled = FullscreenMode.onRootTouch(root, ev)
            if (!handled && ev.actionMasked == MotionEvent.ACTION_MOVE) {
                FullscreenMode.cancelGesture()
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) region = NONE
            return true
        }

        // ---- 键盘区 ----
        if (Config.typeMode == ConfigSpec.TYPE_DOUBLE_TAP) return false
        if (ev.pointerCount >= 2 || twoDown) return twoFinger(root, ev)

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            region = NONE
        }
        return VirtualKeys.onFullscreenTouch(root, ev)
    }

    /** 手指按下的位置在不在键盘区（键盘顶边以下），坐标按屏幕坐标算 */
    private fun downInKeyboard(root: View, ev: MotionEvent): Boolean {
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        val ry = ev.y + loc[1]
        val top = FullscreenMode.keyboardTopOnScreen()
        // 拿不到键盘顶边时按“键盘区”处理：全屏时手指落在下半屏几乎都是键盘
        if (top <= 0) return ry >= screenH(root) * 0.42f
        return ry >= top
    }

    private fun screenH(v: View): Int = try {
        v.resources.displayMetrics.heightPixels
    } catch (_: Throwable) {
        1
    }

    /**
     * 键盘区的双指手势：双指单击、双指双击（默认语音输入）、双指左右上下滑。
     * 识别期间吞掉事件，不让它漏给豆包或 VirtualKeys。
     */
    private fun twoFinger(root: View, ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_DOWN -> {
                // 第一根手指可能已经摸到一个键并开始朗读了：撤掉，改成双指手势
                VirtualKeys.cancelExplore()
                main.removeCallbacks(twoSingleRunner)
                main.removeCallbacks(twoExpireRunner)
                twoDown = true
                twoFired = false
                twoAt = if (ev.eventTime - firstDownAt in 0L..120L) firstDownAt else ev.eventTime
                twoX = midX(ev)
                twoY = midY(ev)
                twoUpX = twoX
                twoUpY = twoY
            }
            MotionEvent.ACTION_MOVE -> {
                // 已经抬起一根手指了，剩下这一根怎么划都不再算双指手势
                if (!twoDown || twoFired || ev.pointerCount < 2) return true
                val cx = midX(ev)
                val cy = midY(ev)
                twoUpX = cx
                twoUpY = cy
                val dx = cx - twoX
                val dy = cy - twoY
                if (kotlin.math.abs(dx) < slop && kotlin.math.abs(dy) < slop) return true
                twoFired = true
                cancelTwoTap()
                val slot = GestureMap.doubleSlide(dx, dy, slop) ?: return true
                GestureLayer.fireSlot(slot, "全屏双指滑动")
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (ev.pointerCount >= 2) {
                    twoUpX = midX(ev)
                    twoUpY = midY(ev)
                }
            }
            MotionEvent.ACTION_UP -> {
                val quick = ev.eventTime - twoAt <= 600L
                val still = !twoFired
                twoDown = false
                region = NONE
                if (quick && still) {
                    val gap = twoDoubleGapMs
                    val radius = maxOf(slop * 4f, 80f * density)
                    if (TwoFingerTapPolicy.isDoubleTap(
                            pending = twoTapPending,
                            firstUpAt = lastTwoTapUpAt,
                            secondDownAt = twoAt,
                            dx = twoUpX - lastTwoTapX,
                            dy = twoUpY - lastTwoTapY,
                            radius = radius,
                            gapMs = gap,
                        )
                    ) {
                        cancelTwoTap()
                        GestureLayer.fireSlot(ConfigSpec.G_DOUBLE_TAP, "全屏双指双击", double = true)
                    } else {
                        val hasSingle = GestureMap.actionOf(ConfigSpec.G_DOUBLE_SINGLE_TAP) != ConfigSpec.ACT_NONE
                        val hasDouble = GestureMap.actionOf(ConfigSpec.G_DOUBLE_TAP) != ConfigSpec.ACT_NONE
                        if (hasSingle && !hasDouble) {
                            cancelTwoTap()
                            GestureLayer.fireSlot(ConfigSpec.G_DOUBLE_SINGLE_TAP, "全屏双指单击")
                        } else if (hasSingle || hasDouble) {
                            twoTapPending = true
                            lastTwoTapUpAt = ev.eventTime
                            lastTwoTapX = twoUpX
                            lastTwoTapY = twoUpY
                            main.removeCallbacks(twoSingleRunner)
                            main.removeCallbacks(twoExpireRunner)
                            if (hasSingle) main.postDelayed(twoSingleRunner, gap)
                            else main.postDelayed(twoExpireRunner, gap)
                        }
                    }
                } else {
                    cancelTwoTap()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                twoDown = false
                twoFired = false
                region = NONE
                cancelTwoTap()
            }
        }
        return true
    }

    private fun midX(ev: MotionEvent): Float {
        if (ev.pointerCount <= 1) return ev.x
        return (ev.getX(0) + ev.getX(1)) / 2f
    }

    private fun midY(ev: MotionEvent): Float {
        if (ev.pointerCount <= 1) return ev.y
        return (ev.getY(0) + ev.getY(1)) / 2f
    }

    /** 键盘收起、退出全屏时把状态清干净 */
    fun reset() {
        region = NONE
        cancelTwoTap()
        twoDown = false
        twoFired = false
    }

    /** 状态行用 */
    fun describe(): String = when (region) {
        UPPER -> "全屏触摸：上半屏手势"
        KEYBOARD -> "全屏触摸：键盘区自建触摸浏览"
        else -> "全屏触摸：待命"
    }
}
