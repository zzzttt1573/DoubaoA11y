package io.github.doubaoa11y.hook

import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * 豆包“调整键盘”界面（StretchView）只能拖动边缘和“移动”把手，读屏用户没法拖。
 * 这个界面出现时，模块在上面盖一层按钮：增高、降低、上移、下移、加宽、变窄、左移、右移、重置、完成。
 * 每个按钮往豆包的把手送一次合成拖动（和手指拖一下一样），再读出新的大小。
 */
object StretchPanel {
    private val main = Handler(Looper.getMainLooper())
    private var panel: View? = null
    private var watched: View? = null
    private var busy = false

    val isOpen: Boolean get() = panel?.isShown == true

    private fun find(root: View, name: String): View? {
        val id = root.resources.getIdentifier(name, "id", root.context.packageName)
        return if (id != 0) root.findViewById(id) else null
    }

    /** 键盘显示后调用：盯着布局变化，调整界面出现就盖上按钮，消失就拿掉 */
    fun watch(root: View) {
        if (watched === root) return
        watched = root
        try {
            root.viewTreeObserver.addOnGlobalLayoutListener(ViewTreeObserver.OnGlobalLayoutListener {
                try {
                    check(root)
                } catch (t: Throwable) {
                    L.hookError("StretchPanel.check", t)
                }
            })
        } catch (t: Throwable) {
            L.hookError("StretchPanel.watch", t)
        }
    }

    private fun check(root: View) {
        if (!VirtualKeys.enabled) return
        val mask = find(root, "keyboard_stretch_mask")
        val shown = mask != null && mask.isShown
        if (shown && panel == null) open(root, mask!!)
        else if (!shown && panel != null) close()
    }

    private fun open(root: View, mask: View) {
        val sv = mask.parent as? View ?: return
        val container = sv.parent as? ViewGroup ?: return
        val v = build(root, container.context)
        container.addView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        panel = v
        L.i("打开调整键盘按钮")
        Speaker.speak("调整键盘，${sizeText(root)}")
    }

    fun close() {
        val v = panel ?: return
        panel = null
        try {
            (v.parent as? ViewGroup)?.removeView(v)
        } catch (t: Throwable) {
            L.hookError("StretchPanel.close", t)
        }
        L.i("关闭调整键盘按钮")
    }

    private class Btn(val label: String, val action: () -> Unit)

    private fun build(root: View, ctx: android.content.Context): View {
        val d = ctx.resources.displayMetrics.density
        val dm = ctx.resources.displayMetrics
        val stepY = (dm.heightPixels * 0.04f)
        val stepX = (dm.widthPixels * 0.05f)
        val rows = listOf(
            listOf(
                Btn("增高") { drag(root, "stretch_up", 0f, -stepY, "已增高") },
                Btn("降低") { drag(root, "stretch_up", 0f, stepY, "已降低") },
                Btn("上移") { drag(root, "btn_stretch_move", 0f, -stepY, "已上移") },
                Btn("下移") { drag(root, "btn_stretch_move", 0f, stepY, "已下移") },
            ),
            listOf(
                Btn("加宽") { widen(root, stepX, "已加宽") },
                Btn("变窄") { widen(root, -stepX, "已变窄") },
                Btn("左移") { drag(root, "btn_stretch_move", -stepX, 0f, "已左移") },
                Btn("右移") { drag(root, "btn_stretch_move", stepX, 0f, "已右移") },
            ),
            listOf(
                Btn("重置") { click(root, "btn_stretch_reset", "已重置") },
                Btn("读大小") { Speaker.speak(sizeText(root)) },
                Btn("完成") { click(root, "btn_stretch_complete", "已完成调整") },
            ),
        )
        val grid = GridLayout(ctx)
        grid.rowCount = rows.size
        grid.columnCount = 4
        val pad = (4 * d).toInt()
        grid.setPadding(pad, pad, pad, pad)
        for ((r, row) in rows.withIndex()) {
            for ((c, b) in row.withIndex()) {
                val tv = TextView(ctx)
                tv.text = b.label
                tv.contentDescription = b.label
                tv.gravity = Gravity.CENTER
                tv.textSize = 20f
                tv.setTextColor(Color.WHITE)
                tv.isClickable = true
                tv.isFocusable = true
                tv.background = GradientDrawable().apply {
                    cornerRadius = 8 * d
                    setColor(Color.parseColor("#2A2A2A"))
                }
                tv.setOnClickListener {
                    try {
                        if (busy) return@setOnClickListener
                        L.i("调整键盘：${b.label}")
                        b.action()
                    } catch (t: Throwable) {
                        L.hookError("StretchPanel.${b.label}", t)
                    }
                }
                // 最后一排三个按钮，“完成”占两格
                val span = if (r == 2 && c == 2) 2 else 1
                val lp = GridLayout.LayoutParams(GridLayout.spec(r, 1f), GridLayout.spec(c, span, 1f))
                lp.width = 0
                lp.height = 0
                lp.setMargins(pad, pad, pad, pad)
                grid.addView(tv, lp)
            }
        }
        return FrameLayout(ctx).apply {
            setBackgroundColor(Color.parseColor("#C0141414"))
            isClickable = true
            addView(grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    // ---------------- 操作 ----------------

    private fun stretchView(root: View): View? = find(root, "keyboard_stretch_mask")?.parent as? View

    private fun screenRect(v: View): Rect {
        val l = IntArray(2)
        v.getLocationOnScreen(l)
        return Rect(l[0], l[1], l[0] + v.width, l[1] + v.height)
    }

    /** 键盘占屏幕的高度和宽度 */
    private fun sizeText(root: View): String {
        val sv = stretchView(root) ?: return ""
        val dm = root.resources.displayMetrics
        val r = screenRect(sv)
        val h = (r.height() * 100f / dm.heightPixels).roundToInt()
        val w = (r.width() * 100f / dm.widthPixels).roundToInt()
        return "高度占屏幕百分之$h，宽度百分之$w"
    }

    private fun click(root: View, name: String, done: String) {
        val b = find(root, name) ?: run { Speaker.speak("找不到按钮"); return }
        b.performClick()
        main.postDelayed({ Speaker.speak("$done，${sizeText(root)}") }, 400)
    }

    /** 加宽、变窄：左右两边各拖一半 */
    private fun widen(root: View, dx: Float, done: String) {
        val half = dx / 2
        dragOnly(root, "stretch_left", -half, 0f) {
            main.postDelayed({ drag(root, "stretch_right", half, 0f, done) }, 80)
        }
    }

    private fun drag(root: View, name: String, dx: Float, dy: Float, done: String) {
        val before = stretchView(root)?.let { screenRect(it) }
        dragOnly(root, name, dx, dy) {
            main.postDelayed({
                try {
                    val after = stretchView(root)?.let { screenRect(it) }
                    val msg = if (before != null && after == before) "已到头" else "$done，${sizeText(root)}"
                    Speaker.speak(msg)
                } catch (t: Throwable) {
                    L.hookError("StretchPanel.after", t)
                }
            }, 300)
        }
    }

    /** 从把手中心拖动 (dx, dy)，事件直接送给调整界面（不经过盖在上面的按钮层） */
    private fun dragOnly(root: View, name: String, dx: Float, dy: Float, then: () -> Unit) {
        val sv = stretchView(root) ?: run { Speaker.speak("找不到调整界面"); return }
        val h = find(root, name) ?: run { Speaker.speak("找不到把手"); return }
        val sl = screenRect(sv)
        val hl = screenRect(h)
        val x0 = hl.exactCenterX() - sl.left
        val y0 = hl.exactCenterY() - sl.top
        val down = SystemClock.uptimeMillis()
        busy = true
        send(sv, down, MotionEvent.ACTION_DOWN, x0, y0)
        val steps = 10
        for (i in 1..steps) {
            main.postDelayed({
                try {
                    // 把手跟着移动，坐标按开始时的界面位置算，送前换算成现在的界面坐标
                    val now = screenRect(sv)
                    val x = x0 + dx * i / steps + (sl.left - now.left)
                    val y = y0 + dy * i / steps + (sl.top - now.top)
                    send(sv, down, MotionEvent.ACTION_MOVE, x, y)
                    if (i == steps) {
                        send(sv, down, MotionEvent.ACTION_UP, x, y)
                        busy = false
                        then()
                    }
                } catch (t: Throwable) {
                    busy = false
                    L.hookError("StretchPanel.drag", t)
                }
            }, (i * 20).toLong())
        }
    }

    private fun send(v: View, downTime: Long, action: Int, x: Float, y: Float) {
        val ev = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            v.dispatchTouchEvent(ev)
        } finally {
            ev.recycle()
        }
    }
}
