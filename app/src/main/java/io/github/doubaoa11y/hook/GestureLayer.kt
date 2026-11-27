package io.github.doubaoa11y.hook

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import io.github.doubaoa11y.ConfigSpec

/**
 * 全屏输入时屏幕上半部分的手势层。
 *
 * 手势和天坦输入法的全屏手势一一对应：
 * 单指单击、单指双击、单指上下左右滑、双指双击、双指上下左右滑，外加八个拖动手势。
 * 每个槽位做什么由用户自己设，见设置里的“全屏与手势”。
 *
 * 两种收事件的方式：
 * - 直接触摸：读屏的触摸浏览在这块被暂停（或本来就是触摸穿透），手指事件直接到这里，
 *   按下不动抬手算单击、连着两次算双击、滑动按主轴方向算，双指同理。
 * - 悬停：读屏（比如 TalkBack）的触摸浏览还开着，手指在屏幕上移动发的是悬停事件，
 *   按悬停的位移判断方向，抬手算单击，读屏双击本控件时会收到 ACTION_CLICK，按双击处理。
 *
 * 触摸事件由 FullscreenMode 在键盘窗口根视图上统一路由进来，不靠本控件自己抢焦点。
 */
class GestureLayer(ctx: Context) : View(ctx) {

    /** 尺寸变了要通知外面重新算穿透区域 */
    var onHeightChanged: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var twoTapPending = false
    private var twoTapUpAt = 0L
    private var twoTapX = 0f
    private var twoTapY = 0f

    init {
        isClickable = true
        // 不要抢焦点：焦点必须留在下面的编辑框上，否则全屏时打字会丢焦点。
        // 读屏要摸到这块靠 importantForAccessibility + clickable，不靠焦点。
        isFocusable = false
        isFocusableInTouchMode = false
        // 触摸浏览开着时要能摸到这块（TalkBack 按无障碍节点来做悬停），所以不能隐藏
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "全屏手势区"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 全屏手势区不再画提示文字和分界线（用户要求去掉，免得盖住应用内容）
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        onHeightChanged?.invoke()
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.view.View"
        info.contentDescription = "全屏手势区"
        info.isClickable = true
        info.isLongClickable = true
        // 让读屏把“上下滑”当成滚动动作发过来，见 performAccessibilityAction
        info.isScrollable = true
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK)
    }

    /**
     * 读屏在本控件上的动作。
     *
     * TalkBack 这一类读屏没有“让输入法暂停触摸浏览”的接口（[TouchGuard] 里说明了原因），
     * 手指的滑动会被读屏自己吃掉、转成“滚动”动作发过来。所以这里把无障碍动作接上：
     * - 双击（ACTION_CLICK）→ 单指双击；
     * - 向下滚（SCROLL_FORWARD）→ 单指向下滑，向上滚 → 单指向上滑；
     * - 长按（ACTION_LONG_CLICK）→ 单指单击（保留一个“点一下”的入口）。
     * 这样即使触摸浏览停不下来，全屏手势区在 TalkBack、解说、天坦这些读屏下也都能用。
     */
    override fun performAccessibilityAction(action: Int, args: android.os.Bundle?): Boolean {
        when (action) {
            AccessibilityNodeInfo.ACTION_CLICK -> {
                fire(ConfigSpec.G_SINGLE_DOUBLE_TAP, "读屏双击")
                return true
            }
            AccessibilityNodeInfo.ACTION_LONG_CLICK -> {
                fire(ConfigSpec.G_SINGLE_TAP, "读屏长按")
                return true
            }
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> {
                fire(ConfigSpec.G_SINGLE_DOWN, "读屏下滑")
                return true
            }
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> {
                fire(ConfigSpec.G_SINGLE_UP, "读屏上滑")
                return true
            }
        }
        return super.performAccessibilityAction(action, args)
    }

    // ---------------- 尺寸与阈值 ----------------

    private val density get() = resources.displayMetrics.density
    /** 手势灵敏度：0 低（防误触）1 中（默认）2 极速 */
    private val sensitivity: Int get() = Config.i("gestureSensitivity").coerceIn(0, 2)

    /** 判“有没有滑动”的距离：灵敏度越高门槛越低，极速时轻轻一划就认 */
    private val slop: Float
        get() = 16f * density * when (sensitivity) {
            0 -> 1.45f
            2 -> 0.55f
            else -> 1f
        }

    /** 按下不动、抬手就算单击：手指按住的时长放得很宽，慢慢点也算，只要没划走 */
    private val tapMaxMs = 1000L

    /**
     * 单击和双击之间的等待时间，取系统的双击超时（一般 300 毫秒）。
     *
     * 1.2.1 起，只有“可能造成不可挽回后果”的单击（删除、清空、粘贴、发送、上屏候选这些）
     * 才真的等这么久；朗读、移动光标、切换编辑菜单这类单击在抬手时立刻执行，
     * 所以“上半屏单指单击有延迟”这个毛病没有了。
     *
     * 1.2.2 补：双击槽位绑了动作时也必须等——不等的话，双击会把单击动作也执行一遍
     * （用户反馈“双击会触发单击的动作”）。窗口取 260ms 而不是系统的 300ms，
     * 把延迟压到最短；想让单击彻底零延迟，把“单指双击”槽位留空即可。
     * 1.2.4 补：这个窗口跟着“手势灵敏度”走——低 320ms（更不容易把两次单击认成双击），
     * 中 260ms，极速 190ms（双击立刻认，单击等得也短）。
     */
    private val doubleGapMs: Long
        get() = when (sensitivity) {
            0 -> 320L
            2 -> 190L
            else -> 260L
        }

    /** 双指滑动阈值下限 18dp，避免双指点按时轻微抖动误判成双指滑动 */
    private val twoSlideSlop: Float get() = maxOf(slop, 18f * density)
    /** 单指折线/往返第二段判定阈值：下限 22dp，防止快速抬手轻微回弹误判成往返 */
    private val turnSlop: Float get() = maxOf(slop * 1.35f, 22f * density)
    /** 双指双击中心点容差（至少 80dp），防止两拍落点略有偏移被拒 */
    private val twoTapRadius: Float get() = maxOf(slop * 4f, 80f * density)
    /** 双指双击允许的空中间隔（第 1 拍抬手到第 2 拍按下） */
    private val twoDoubleGapMs: Long get() = TwoFingerTapPolicy.doubleGapMs(sensitivity)

    // ---------------- 直接触摸 ----------------

    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var twoDownAt = 0L
    private var twoUpX = 0f
    private var twoUpY = 0f
    private var tracking = false
    private var fingers = 1
    private var maxFingers = 1
    private var fired = false

    /** 单指往返/折线跟踪状态：仅当首段方向绑定了折线或往返动作时才启用 */
    private var compoundFirstDir = GestureMap.DIR_NONE
    private var turnX = 0f
    private var turnY = 0f

    /** 拖动/滑动分离状态：有拖动动作绑定时先挂起普通滑动，短划抬手走滑动，按住进入拖动 */
    private var dragging = false
    private var dragX = 0f
    private var dragY = 0f
    private var pendingSlideSlot: Int? = null
    private var pendingSlideHow = ""
    private var pendingDragSlot: Int? = null
    private var currentDragSlot: Int? = null
    private var currentDragHow = ""
    private var dragToken = 0L

    private val dragStartRunner = Runnable {
        val slot = pendingDragSlot ?: return@Runnable
        if (!tracking || dragging) return@Runnable
        val how = if (maxFingers >= 2) "双指拖动" else "单指拖动"
        compoundFirstDir = GestureMap.DIR_NONE
        pendingSlideSlot = null
        pendingDragSlot = null
        dragging = true
        fired = true
        currentDragSlot = slot
        currentDragHow = how
        fire(slot, how)
        scheduleDragRepeat(dragToken)
    }

    private var dragRepeatRunner: Runnable? = null

    private fun scheduleDragRepeat(tok: Long) {
        dragRepeatRunner?.let { main.removeCallbacks(it) }
        val gap = Config.i("fsDragMs").coerceIn(100, 2000).toLong()
        val r = Runnable {
            if (tok != dragToken || !tracking || !dragging) return@Runnable
            val slot = currentDragSlot ?: return@Runnable
            if (GestureMap.actionOf(slot) != ConfigSpec.ACT_NONE) {
                fire(slot, currentDragHow)
            }
            scheduleDragRepeat(tok)
        }
        dragRepeatRunner = r
        main.postDelayed(r, gap)
    }

    private fun stopDragTimers() {
        dragToken++
        main.removeCallbacks(dragStartRunner)
        dragRepeatRunner?.let { main.removeCallbacks(it) }
        dragRepeatRunner = null
        compoundFirstDir = GestureMap.DIR_NONE
        pendingSlideSlot = null
        pendingDragSlot = null
        currentDragSlot = null
    }

    /** 等待判定的单指单击（等看有没有第二次点击） */
    private var tapPending = false

    /** 单击已经执行过了（“抬手立即执行”的那条路），后面再来一次点击就是双击 */
    private var tapFired = false
    private var lastTapAt = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    /** 等待收尾：只是把“还能双击”的窗口关掉，不再用它来执行单击 */
    private val tapRunner = Runnable {
        tapPending = false
        tapFired = false
    }

    /** 等双击窗口过完、确认不是双击之后才执行的那一下单指单击 */
    private val singleRunner = Runnable {
        if (tapPending && !tapFired) {
            tapPending = false
            fire(ConfigSpec.G_SINGLE_TAP, "单指单击")
        }
    }

    /** 等双指双击窗口过完、确认不是双指双击之后才执行的那一下双指单击 */
    private val twoSingleRunner: Runnable = object : Runnable {
        override fun run() {
            if (!twoTapPending) return
            // 若第二拍的第一根手指刚落屏（<120ms），稍等第二根手指落地，避免双指双击被抢跑
            if (tracking && maxFingers == 1 && !fired && SystemClock.uptimeMillis() - downAt in 0L..120L) {
                main.postDelayed(this, 80L)
                return
            }
            twoTapPending = false
            twoTapUpAt = 0L
            fire(ConfigSpec.G_DOUBLE_SINGLE_TAP, "双指单击")
        }
    }

    /** 仅绑了双指双击时，窗口到期后清掉挂起状态 */
    private val twoTapExpireRunner: Runnable = object : Runnable {
        override fun run() {
            if (!twoTapPending) return
            if (tracking && maxFingers == 1 && !fired && SystemClock.uptimeMillis() - downAt in 0L..120L) {
                main.postDelayed(this, 80L)
                return
            }
            twoTapPending = false
            twoTapUpAt = 0L
        }
    }

    private fun cancelTwoTap() {
        twoTapPending = false
        twoTapUpAt = 0L
        main.removeCallbacks(twoSingleRunner)
        main.removeCallbacks(twoTapExpireRunner)
    }

    /** 一次手势作废（手指划出了手势区、或者被别的层接管）：不执行、不留状态 */
    fun cancelTouch() {
        tracking = false
        fired = false
        dragging = false
        stopDragTimers()
        cancelHover()
        cancelTap()
        cancelTwoTap()
    }

    private fun midX(ev: MotionEvent): Float =
        if (ev.pointerCount >= 2) (ev.getX(0) + ev.getX(1)) / 2f else ev.x

    private fun midY(ev: MotionEvent): Float =
        if (ev.pointerCount >= 2) (ev.getY(0) + ev.getY(1)) / 2f else ev.y

    fun handleTouch(ev: MotionEvent): Boolean {
        if (!isShown || height <= 0) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopDragTimers()
                downX = ev.x
                downY = ev.y
                downAt = ev.eventTime
                twoDownAt = ev.eventTime
                twoUpX = ev.x
                twoUpY = ev.y
                fingers = 1
                maxFingers = 1
                fired = false
                dragging = false
                tracking = ev.y <= height
                if (!tracking) return false
                // 手指落到上半屏手势区：什么都不做。
                // 以前这里震一下、fireSlot 里再震一下，用户的感觉就是“上半屏做任何操作都震两次”。
                // 现在震动只在真正执行手势时发一次（见 fireSlot），摸到不震。
                // 音效同理：key_pressed 只属于键盘区的按键，上半屏摸到不出声。
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!tracking) return false
                val prevMax = maxFingers
                fingers = ev.pointerCount
                if (fingers > maxFingers) maxFingers = fingers
                if (prevMax < 2 && ev.pointerCount >= 2) {
                    // 第二根手指刚落屏：以两指中心点重新定锚，避免第一根抬手时 pointerIndex 跳变导致位移炸裂
                    cancelTap()
                    // 若正在等待上一拍双指单击，暂停定时但不清 twoTapPending，等本拍抬手判双指双击
                    main.removeCallbacks(twoSingleRunner)
                    main.removeCallbacks(twoTapExpireRunner)
                    if (!fired && pendingSlideSlot != null) stopDragTimers()
                    val cx = midX(ev)
                    val cy = midY(ev)
                    downX = cx
                    downY = cy
                    twoUpX = cx
                    twoUpY = cy
                    // 两根手指先后落屏通常错开 10~80ms，以第一根落屏时间作为本拍按下起点
                    twoDownAt = if (ev.eventTime - downAt in 0L..120L) downAt else ev.eventTime
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                if (maxFingers >= 2 && ev.pointerCount < 2) {
                    // 双指手势已抬起一根手指，忽略剩余单指的残余位移，防止 pointer0 切换误触发滑动
                    return true
                }
                val curX = if (maxFingers >= 2) midX(ev) else ev.x
                val curY = if (maxFingers >= 2) midY(ev) else ev.y
                if (maxFingers >= 2) {
                    twoUpX = curX
                    twoUpY = curY
                }
                val moveSlop = if (maxFingers >= 2) twoSlideSlop else slop
                val dx = curX - downX
                val dy = curY - downY
                if (!fired && !dragging && pendingSlideSlot == null) {
                    val slideSlot = if (maxFingers >= 2) GestureMap.doubleSlide(dx, dy, moveSlop)
                    else GestureMap.singleSlide(dx, dy, moveSlop)
                    if (slideSlot != null) {
                        cancelTap()
                        cancelTwoTap()
                        val dragSlot = GestureMap.dragSlot(maxFingers, dx, dy)
                        val dragAction = GestureMap.actionOf(dragSlot)
                        val how = if (maxFingers >= 2) "双指滑动" else "单指滑动"
                        val deferForDrag = TwoFingerTapPolicy.shouldDeferSlideForDrag(Config.b("fsDrag"), dragAction)
                        val firstDir = if (maxFingers == 1) GestureMap.singleSlideDir(slideSlot) else GestureMap.DIR_NONE
                        val deferForCompound = firstDir != GestureMap.DIR_NONE && GestureMap.hasCompoundStartingWith(firstDir)
                        if (deferForDrag || deferForCompound) {
                            // 拖动或单指折线/往返：先挂起普通滑动，未进入拖动也未折返则抬手走普通滑动
                            pendingSlideSlot = slideSlot
                            pendingSlideHow = how
                            if (deferForCompound) {
                                compoundFirstDir = firstDir
                                turnX = curX
                                turnY = curY
                            }
                            if (deferForDrag) {
                                pendingDragSlot = dragSlot
                                dragX = curX
                                dragY = curY
                                dragToken++
                                val baseEnterMs = TwoFingerTapPolicy.dragEnterDelayMs(Config.i("fsDragMs"))
                                val enterMs = if (deferForCompound) maxOf(baseEnterMs, 260L) else baseEnterMs
                                main.removeCallbacks(dragStartRunner)
                                main.postDelayed(dragStartRunner, enterMs)
                            }
                        } else {
                            fired = true
                            fire(slideSlot, how)
                        }
                    }
                    return true
                }
                if (pendingSlideSlot != null && !dragging) {
                    if (compoundFirstDir != GestureMap.DIR_NONE && maxFingers == 1) {
                        val tdx = curX - turnX
                        val tdy = curY - turnY
                        val advancing = when (compoundFirstDir) {
                            GestureMap.DIR_LEFT -> curX < turnX && (turnX - curX) >= kotlin.math.abs(tdy)
                            GestureMap.DIR_RIGHT -> curX > turnX && (curX - turnX) >= kotlin.math.abs(tdy)
                            GestureMap.DIR_UP -> curY < turnY && (turnY - curY) >= kotlin.math.abs(tdx)
                            GestureMap.DIR_DOWN -> curY > turnY && (curY - turnY) >= kotlin.math.abs(tdx)
                            else -> false
                        }
                        if (advancing) {
                            turnX = curX
                            turnY = curY
                        } else {
                            // 手指已开始明显拐弯或折返：取消待机拖动，避免转折中途被拖动抢占
                            if (pendingDragSlot != null &&
                                (kotlin.math.abs(tdx) >= slop * 0.7f || kotlin.math.abs(tdy) >= slop * 0.7f)
                            ) {
                                main.removeCallbacks(dragStartRunner)
                                pendingDragSlot = null
                            }
                            val secondDir = GestureMap.directionOf(tdx, tdy, turnSlop)
                            if (secondDir != GestureMap.DIR_NONE && secondDir != compoundFirstDir) {
                                val compSlot = GestureMap.compoundSlot(compoundFirstDir, secondDir)
                                if (compSlot != null && GestureMap.actionOf(compSlot) != ConfigSpec.ACT_NONE) {
                                    val label = if (GestureMap.isOppositeDir(compoundFirstDir, secondDir)) "单指往返" else "单指折线"
                                    stopDragTimers()
                                    fired = true
                                    fire(compSlot, label)
                                    return true
                                }
                            }
                        }
                    }
                    if (pendingDragSlot != null) {
                        val mx = curX - dragX
                        val my = curY - dragY
                        if (kotlin.math.abs(mx) > moveSlop || kotlin.math.abs(my) > moveSlop) {
                            val nextDrag = GestureMap.dragSlot(maxFingers, mx, my)
                            if (GestureMap.actionOf(nextDrag) != ConfigSpec.ACT_NONE) {
                                pendingDragSlot = nextDrag
                            }
                            dragX = curX
                            dragY = curY
                        }
                    }
                    return true
                }
                if (dragging) {
                    val mx = curX - dragX
                    val my = curY - dragY
                    if (kotlin.math.abs(mx) > moveSlop || kotlin.math.abs(my) > moveSlop) {
                        val nextDrag = GestureMap.dragSlot(maxFingers, mx, my)
                        if (GestureMap.actionOf(nextDrag) != ConfigSpec.ACT_NONE) {
                            currentDragSlot = nextDrag
                        }
                        dragX = curX
                        dragY = curY
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (!tracking) return false
                if (maxFingers >= 2 && ev.pointerCount >= 2) {
                    twoUpX = midX(ev)
                    twoUpY = midY(ev)
                }
                fingers = (ev.pointerCount - 1).coerceAtLeast(1)
                if (maxFingers >= 2 && fingers < 2) {
                    if (dragging) {
                        stopDragTimers()
                        dragging = false
                        fired = true
                    } else {
                        val pending = pendingSlideSlot
                        if (pending != null && !fired) {
                            val how = pendingSlideHow
                            stopDragTimers()
                            fired = true
                            fire(pending, how)
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!tracking) return false
                tracking = false
                val pending = pendingSlideSlot
                val pendingHow = pendingSlideHow
                val wasDragging = dragging
                stopDragTimers()
                dragging = false
                if (pending != null && !fired && !wasDragging) {
                    fired = true
                    fire(pending, pendingHow)
                } else if (!fired && !wasDragging) {
                    if (maxFingers >= 2) {
                        cancelTap()
                        val quick = ev.eventTime - twoDownAt <= tapMaxMs
                        if (quick) {
                            onTwoFingerTap(twoUpX, twoUpY, twoDownAt, ev.eventTime)
                        } else {
                            cancelTwoTap()
                        }
                    } else {
                        cancelTwoTap()
                        val dx = ev.x - downX
                        val dy = ev.y - downY
                        val quick = ev.eventTime - downAt <= tapMaxMs
                        val still = kotlin.math.abs(dx) <= slop && kotlin.math.abs(dy) <= slop
                        if (quick && still) {
                            onSingleTap(ev.x, ev.y, ev.eventTime)
                        }
                    }
                }
                fired = false
            }
            MotionEvent.ACTION_CANCEL -> {
                tracking = false
                fired = false
                dragging = false
                stopDragTimers()
            }
            else -> return tracking
        }
        return true
    }

    private fun onTwoFingerTap(x: Float, y: Float, tapDownAt: Long, tapUpAt: Long) {
        val gap = twoDoubleGapMs
        if (TwoFingerTapPolicy.isDoubleTap(
                pending = twoTapPending,
                firstUpAt = twoTapUpAt,
                secondDownAt = tapDownAt,
                dx = x - twoTapX,
                dy = y - twoTapY,
                radius = twoTapRadius,
                gapMs = gap,
            )
        ) {
            cancelTwoTap()
            fire(ConfigSpec.G_DOUBLE_TAP, "双指双击", double = true)
            return
        }
        val hasSingle = GestureMap.actionOf(ConfigSpec.G_DOUBLE_SINGLE_TAP) != ConfigSpec.ACT_NONE
        val hasDouble = GestureMap.actionOf(ConfigSpec.G_DOUBLE_TAP) != ConfigSpec.ACT_NONE
        if (hasSingle && !hasDouble) {
            cancelTwoTap()
            fire(ConfigSpec.G_DOUBLE_SINGLE_TAP, "双指单击")
            return
        }
        twoTapPending = true
        twoTapUpAt = tapUpAt
        twoTapX = x
        twoTapY = y
        main.removeCallbacks(twoSingleRunner)
        main.removeCallbacks(twoTapExpireRunner)
        if (hasSingle) {
            main.postDelayed(twoSingleRunner, gap)
        } else if (hasDouble) {
            main.postDelayed(twoTapExpireRunner, gap)
        } else {
            twoTapPending = false
            twoTapUpAt = 0L
        }
    }

    private fun onSingleTap(x: Float, y: Float, at: Long) {
        // 上一拍还在双击窗口里，位置又差不多：这是一次双击
        if (tapPending && at - lastTapAt <= doubleGapMs &&
            kotlin.math.abs(x - lastTapX) <= slop * 3 && kotlin.math.abs(y - lastTapY) <= slop * 3
        ) {
            val already = tapFired
            cancelTap()
            lastTapAt = 0L
            // 刚才单击已经读过一遍了，双击的播报要把它打断，不然两句话叠在一起
            if (already) Speaker.stop()
            // 双击有自己的音效（double_tap）：做成了才响，做不成还是“做不了”的音（见 fireSlot）
            fire(ConfigSpec.G_SINGLE_DOUBLE_TAP, "单指双击", double = true)
            return
        }
        tapPending = true
        tapFired = false
        lastTapAt = at
        lastTapX = x
        lastTapY = y
        main.removeCallbacks(tapRunner)
        main.removeCallbacks(singleRunner)
        if (tapDelayNeeded()) {
            // 这一单击可能是“删字、粘贴、发送”这类做了就退不回来的事，或者可能是双击的前半：
            // 等一个双击窗口，确认不是双击再执行
            main.postDelayed(singleRunner, doubleGapMs)
        } else {
            // 朗读、移动光标、切编辑菜单：抬手就执行，不等
            tapFired = true
            fire(ConfigSpec.G_SINGLE_TAP, "单指单击")
            main.postDelayed(tapRunner, doubleGapMs)
        }
    }

    /**
     * 这一下单击要不要等双击窗口。
     *
     * 有候选时单击是“上屏候选”，删字、粘贴、发送、清空这些也是一下就改不回来的，
     * 必须先确认用户不是双击；其余（朗读、移动、切换选项）抬手就做。
     */
    private fun tapDelayNeeded(): Boolean {
        if (ClipboardNav.hasPending()) return true
        if (Config.b("fsCandidateFirst") && !EditOptions.isFresh() &&
            Candidates.hasCandidates() && !EditActions.isSelecting
        ) return true
        // 双击槽位绑着动作：这一下可能是双击的前半，不能马上把单击的事做掉，
        // 否则双击会“单击动作 + 双击动作”各来一遍（用户反馈的误触发）。
        // 槽位留空就等于没有歧义，单击可以零延迟。
        if (GestureMap.actionOf(ConfigSpec.G_SINGLE_DOUBLE_TAP) != 0) return true
        return GestureMap.destructive(GestureMap.actionOf(ConfigSpec.G_SINGLE_TAP))
    }

    private fun cancelTap() {
        tapPending = false
        tapFired = false
        main.removeCallbacks(tapRunner)
        main.removeCallbacks(singleRunner)
    }

    // ---------------- 悬停（触摸浏览还开着） ----------------

    private var hoverX = 0f
    private var hoverY = 0f
    private var hoverAt = 0L
    private var hoverFired = false
    private var hoverInside = false

    /** 当前是否正在跟踪上半屏手势区的悬停（只有跟踪中的悬停离开才归手势层收尾，绝不吞掉键盘区的抬手） */
    val isHoverTracking: Boolean get() = hoverInside

    fun cancelHover() {
        hoverInside = false
        hoverFired = false
    }

    fun handleHover(ev: MotionEvent): Boolean {
        if (!isShown || height <= 0) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER -> {
                hoverX = ev.x
                hoverY = ev.y
                hoverAt = ev.eventTime
                hoverFired = false
                hoverInside = true
                // 悬停进入也只在真正执行手势时震一次，这里不再震（否则同样会震两次）
            }
            MotionEvent.ACTION_HOVER_MOVE -> {
                if (!hoverInside) {
                    hoverX = ev.x
                    hoverY = ev.y
                    hoverAt = ev.eventTime
                    hoverInside = true
                }
                // 触摸浏览还开着（读屏没被暂停）时，左右滑会被读屏当成字符/词导航自己处理，
                // 这里再按滑动触发手势就会“滑一下动两个光标、播两遍”。所以悬停模式下不识别滑动，
                // 只识别点按（单击/双击）；滑动交给读屏导航。触摸浏览被暂停后走 handleTouch，手势照常。
                return true
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                if (!hoverInside) return false
                val wasFired = hoverFired
                val at = ev.eventTime
                hoverInside = false
                hoverFired = false
                if (!wasFired && at - hoverAt <= 500L) onSingleTap(hoverX, hoverY, at)
            }
            else -> return false
        }
        return true
    }

    // ---------------- 执行 ----------------

    /** 执行一个手势：先震一下表示认到了，动作没做成再震两下并说明 */
    fun fire(slot: Int, how: String, double: Boolean = false) = fireSlot(slot, how, double)

    /** 手势区现在的矩形（屏幕坐标），给穿透区域用 */
    fun screenRect(): Rect? = try {
        if (!isShown || width <= 0 || height <= 0) null
        else {
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            Rect(loc[0], loc[1], loc[0] + width, loc[1] + height)
        }
    } catch (_: Throwable) {
        null
    }

    companion object {
        /** 最近一次执行手势的时间 */
        @Volatile
        var lastAt = 0L
            private set

        /**
         * 执行一个手势槽位。上半屏由手势层调用，键盘区的双指手势（全屏触摸层）也调这里，
         * 两条路执行的动作、播报、震动完全一致。
         *
         * [double] 是双击（单指双击、双指双击）：做成了放 double_tap 那个音，
         * 其余手势做成放 gesture 那个音，做不成都放“做不了”的 complete 音。
         */
        fun fireSlot(slot: Int, how: String, double: Boolean = false) {
            if (!FullscreenMode.wanted || !FullscreenMode.active) return
            // 刚执行过“上一个/下一个剪贴板”：
            // - 紧接着单指双击 → 直接把刚获取的那条剪贴板粘贴到编辑框；
            // - 紧接着再次切换剪贴板 → 保留待粘贴状态继续切换；
            // - 其他任意手势 → 自动退出待粘贴状态，走正常手势流程。
            if (ClipboardNav.hasPending()) {
                val slotAction = GestureMap.actionOf(slot)
                if (slot == ConfigSpec.G_SINGLE_DOUBLE_TAP) {
                    if (Config.b("fsHaptic")) Haptic.tick()
                    val ok = ClipboardNav.pastePending()
                    if (ok) Sound.play("double") else Sound.play("boundary")
                    lastAt = SystemClock.uptimeMillis()
                    return
                }
                if (slotAction != ConfigSpec.ACT_PREV_CLIPBOARD && slotAction != ConfigSpec.ACT_NEXT_CLIPBOARD) {
                    ClipboardNav.clearPending()
                }
            }
            // 候选优先：候选栏有候选的时候，左右滑切候选、上滑清空、下滑/单击/双击上屏。
            //
            // 前提是这批候选**是打字带出来的**（Candidates.fromTyping）：移动光标（上一句、下一句、
            // 移动一个字）之后豆包也会弹联想候选，那时候左右滑必须还是移动光标，
            // 不然编辑已有文字时每一步都被候选栏吃掉。
            //
            // 另外两个条件放宽过（用户反馈“候选手势没了”）：
            // - 选择态用 stillSelectingNow() 自检，标志位有残留就直接清掉，不再因为卡住把候选整段跳过；
            // - 左右/上下滑不看 EditOptions.isFresh()，只有单击/双击才看（切完编辑菜单马上点，执行的是那个选项）。
            if (Config.b("fsCandidateFirst") &&
                Candidates.hasCandidates() && !EditActions.stillSelectingNow()
            ) {
                val tapSlot = slot == ConfigSpec.G_SINGLE_TAP || slot == ConfigSpec.G_SINGLE_DOUBLE_TAP
                val upDown = slot == ConfigSpec.G_SINGLE_UP || slot == ConfigSpec.G_SINGLE_DOWN
                val skipForEditOption = tapSlot && EditOptions.isFresh()
                if (!skipForEditOption) {
                    val done = when (slot) {
                        // 英文键盘下单指左右滑不用于切换候选，直接放行给常规手势（默认移动光标）
                        ConfigSpec.G_SINGLE_LEFT -> if (Candidates.isEnglishBoard()) false else Candidates.prev()
                        ConfigSpec.G_SINGLE_RIGHT -> if (Candidates.isEnglishBoard()) false else Candidates.next()
                        // 上滑清空候选栏
                        ConfigSpec.G_SINGLE_UP -> KeyboardOps.clearCandidate()
                        // 下滑把当前选中的候选上屏（和单击/双击一样）
                        ConfigSpec.G_SINGLE_DOWN -> GestureMap.commitCandidate()
                        ConfigSpec.G_SINGLE_TAP, ConfigSpec.G_SINGLE_DOUBLE_TAP -> GestureMap.commitCandidate()
                        else -> false
                    }
                    if (done || tapSlot || upDown) {
                        if (Config.b("fsHaptic")) Haptic.tick()
                        // 候选手势归“手势”：做成了放手势音，到头/清空不了放“做不了”的音
                        if (done) {
                            Sound.play("gesture")
                        } else {
                            Sound.play("boundary")
                        }
                        // 上屏没做成也停在这里，不再掉到“执行编辑菜单”上去做别的事
                        if (!done) Speaker.speak(if (upDown && slot == ConfigSpec.G_SINGLE_UP) "清空候选没成功" else "候选没上屏")
                        lastAt = SystemClock.uptimeMillis()
                        return
                    }
                }
            }
            val action = GestureMap.actionOf(slot)
            if (Config.b("fsHaptic")) Haptic.tick()
            if (action == ConfigSpec.ACT_NONE) {
                Sound.play("boundary")
                Speaker.speak("${GestureMap.slotName(slot)}没有设置动作")
                return
            }
            L.i("全屏手势（$how）：${GestureMap.slotName(slot)} → ${GestureMap.actionName(action)}")
            // 关掉“执行手势后朗读结果”：动作自己的播报（“已删除”“行尾”）临时吞掉，
            // 但做不成时下面会取消静音，照样把原因读出来。
            // 切换编辑菜单本身就是“读出来”的动作，关掉也不能把它吞掉，
            // 否则上下滑切选项完全没声，用户不知道切到哪一项了
            val navOption = action == ConfigSpec.ACT_PREV_OPTION ||
                action == ConfigSpec.ACT_NEXT_OPTION || action == ConfigSpec.ACT_RUN_OPTION
            if (!Config.b("fsSpeakAction") && !navOption) Speaker.mute(500)
            val effectiveAction = if (action == ConfigSpec.ACT_RUN_OPTION) EditOptions.currentAction else action
            val ok = try {
                GestureMap.run(action)
            } catch (t: Throwable) {
                L.hookError("GestureLayer.fire", t)
                false
            }
            if (!ok) {
                Sound.play("boundary")
                Speaker.unmute()
                if (Config.b("fsHaptic")) Haptic.tick(2)
                Speaker.speak(GestureMap.failHint(action))
            } else if (effectiveAction == ConfigSpec.ACT_VOICE && KeyboardOps.isVoiceActive()) {
                // 启动语音由 Voice.onStart() 播放用户设置的“语音开始”提示声，不发双击/手势声以免覆盖
            } else if (double) {
                Sound.play("double")
            } else {
                Sound.play("gesture")
            }
            lastAt = SystemClock.uptimeMillis()
        }

        /** 当前手势映射，写日志和设置页状态用 */
        fun describeMap(): String = ConfigSpec.GESTURE_SLOTS.joinToString("，") { (slot, name) ->
            "$name=${GestureMap.actionName(GestureMap.actionOf(slot))}"
        }
    }
}
