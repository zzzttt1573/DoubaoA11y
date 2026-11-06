package io.github.doubaoa11y.hook

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.RelativeLayout
import android.widget.TextView

/**
 * 编辑面板：豆包没有的全选、复制、粘贴、剪切、移动光标、选择、向后删除。
 * 挂在豆包工具栏的“移动光标”工具上：点它打开这个面板，盖在键盘上，候选栏/工具栏保留。
 * 面板是普通的 Java 按钮，讯飞式下滑动朗读、抬手点击，和其他按钮一样。
 */
object EditPanel {
    private var panel: View? = null
    private var selecting = false
    private var anchor = -1

    val isOpen: Boolean get() = panel?.isShown == true

    private class Btn(val label: String, val spoken: String, val action: () -> Unit)

    private fun ic(): InputConnection? = ViewA11yHooks.service?.currentInputConnection

    /** 打开面板；成功返回 true */
    fun open(): Boolean {
        val s = ViewA11yHooks.service ?: return false
        val root = s.window?.window?.decorView ?: return false
        val res = root.resources
        val pkg = root.context.packageName
        fun find(name: String): View? {
            val id = res.getIdentifier(name, "id", pkg)
            return if (id != 0) root.findViewById(id) else null
        }
        val container = find("keyboard_main_container_area") as? ViewGroup ?: run {
            L.w("编辑面板：找不到键盘区域")
            return false
        }
        close()
        val bar = find("native_candidate_bar")
        // 候选栏下边到键盘区域上边的距离（屏幕坐标），这样不管键盘多高都能盖住第一排字母
        val cl = IntArray(2)
        container.getLocationOnScreen(cl)
        val top = if (bar != null && bar.isShown) {
            val bl = IntArray(2)
            bar.getLocationOnScreen(bl)
            (bl[1] + bar.height - cl[1]).coerceIn(0, container.height)
        } else 0
        selecting = false
        anchor = -1
        val v = build(container.context)
        val lp = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, container.height - top)
        lp.topMargin = top
        container.addView(v, lp)
        panel = v
        v.post {
            try {
                val pl = IntArray(2)
                v.getLocationOnScreen(pl)
                val bl = IntArray(2)
                bar?.getLocationOnScreen(bl)
                L.i("编辑面板位置：面板=[${pl[0]},${pl[1]},${pl[0] + v.width},${pl[1] + v.height}] 键盘区域上边=${cl[1]} 高=${container.height} 候选栏=${if (bar != null) "[${bl[1]},${bl[1] + bar.height}] 显示=${bar.isShown}" else "无"}")
            } catch (_: Throwable) {
            }
        }
        L.i("打开编辑面板")
        Speaker.speak("编辑面板")
        return true
    }

    /** 从菜单里打开：先点菜单的返回键把菜单关掉，再打开面板 */
    fun closeToolboxThenOpen() {
        val root = ViewA11yHooks.service?.window?.window?.decorView ?: return
        val id = root.resources.getIdentifier("btn_toolbox_back", "id", root.context.packageName)
        val back = if (id != 0) root.findViewById<View>(id) else null
        back?.performClick()
        root.postDelayed({
            try {
                open()
            } catch (t: Throwable) {
                L.hookError("EditPanel.open", t)
            }
        }, 350)
    }

    fun close() {
        val v = panel ?: return
        panel = null
        try {
            (v.parent as? ViewGroup)?.removeView(v)
        } catch (t: Throwable) {
            L.hookError("EditPanel.close", t)
        }
    }

    private fun build(ctx: android.content.Context): View {
        val d = ctx.resources.displayMetrics.density
        val rows = listOf(
            listOf(
                Btn("Tab", "Tab") { key(KeyEvent.KEYCODE_TAB) },
                Btn("复制", "复制") { menu(android.R.id.copy, "已复制") },
                Btn("上移", "上移") { arrow(KeyEvent.KEYCODE_DPAD_UP) },
                Btn("粘贴", "粘贴") { menu(android.R.id.paste, null) },
                Btn("删除", "删除") { key(KeyEvent.KEYCODE_DEL) },
            ),
            listOf(
                Btn("开头", "开头") { jump(true) },
                Btn("左移", "左移") { move(-1) },
                Btn("选择", "选择") { toggleSelect() },
                Btn("右移", "右移") { move(1) },
                Btn("Del", "向后删除") { key(KeyEvent.KEYCODE_FORWARD_DEL) },
            ),
            listOf(
                Btn("末尾", "末尾") { jump(false) },
                Btn("全选", "全选") { menu(android.R.id.selectAll, "已全选") },
                Btn("下移", "下移") { arrow(KeyEvent.KEYCODE_DPAD_DOWN) },
                Btn("剪切", "剪切") { menu(android.R.id.cut, "已剪切") },
                Btn("返回", "返回键盘") { close(); Speaker.speak("已返回键盘") },
            ),
        )
        val grid = GridLayout(ctx)
        grid.rowCount = rows.size
        grid.columnCount = 5
        val pad = (4 * d).toInt()
        grid.setPadding(pad, pad, pad, pad)
        for ((r, row) in rows.withIndex()) {
            for ((c, b) in row.withIndex()) {
                val tv = TextView(ctx)
                tv.text = b.label
                tv.contentDescription = b.spoken
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
                        L.i("编辑面板：${b.spoken}")
                        b.action()
                    } catch (t: Throwable) {
                        L.hookError("EditPanel.${b.spoken}", t)
                    }
                }
                val lp = GridLayout.LayoutParams(
                    GridLayout.spec(r, 1f), GridLayout.spec(c, 1f),
                )
                lp.width = 0
                lp.height = 0
                lp.setMargins(pad, pad, pad, pad)
                grid.addView(tv, lp)
            }
        }
        return FrameLayout(ctx).apply {
            setBackgroundColor(Color.parseColor("#141414"))
            // 挡住下面的键盘
            isClickable = true
            addView(grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    // ---------------- 操作 ----------------

    private fun key(code: Int) {
        val ic = ic() ?: return
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun menu(id: Int, done: String?) {
        val ic = ic() ?: return
        val ok = ic.performContextMenuAction(id)
        if (id != android.R.id.selectAll) selecting = false
        if (done != null) Speaker.speak(if (ok) done else "当前编辑框不支持")
    }

    private fun selection(ic: InputConnection): Triple<Int, Int, Int>? {
        val et = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return null
        val len = et.text?.length ?: return null
        return Triple(et.selectionStart + et.startOffset, et.selectionEnd + et.startOffset, len + et.startOffset)
    }

    private fun toggleSelect() {
        val ic = ic() ?: return
        selecting = !selecting
        if (selecting) {
            val sel = selection(ic)
            anchor = sel?.first ?: -1
            Speaker.speak("开始选择，用左移右移、上移下移、开头末尾扩大选择")
        } else {
            val sel = selection(ic)
            if (sel != null) ic.setSelection(sel.second, sel.second)
            anchor = -1
            Speaker.speak("取消选择")
        }
    }

    /** 左右移一个字；选择中就移动选择的这一头 */
    private fun move(dir: Int) {
        val ic = ic() ?: return
        val sel = selection(ic)
        if (sel == null) {
            arrow(if (dir < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
            return
        }
        val (start, end, len) = sel
        if (selecting && anchor >= 0) {
            val focus = if (start == anchor) end else start
            val nf = (focus + dir).coerceIn(0, len)
            ic.setSelection(anchor, nf)
            speakRange(ic, anchor, nf)
        } else {
            val pos = (if (dir < 0) start else end) + dir
            val p = pos.coerceIn(0, len)
            ic.setSelection(p, p)
            if (p == pos) speakCharAt(ic, dir) else Speaker.speak(if (dir < 0) "已到开头" else "已到末尾")
        }
    }

    /** 上下移一行：交给编辑框（带 Shift 就是扩大选择） */
    private fun arrow(code: Int) {
        val ic = ic() ?: return
        val meta = if (selecting) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
        val t = android.os.SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, meta))
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0, meta))
    }

    private fun jump(toStart: Boolean) {
        val ic = ic() ?: return
        val sel = selection(ic)
        if (sel == null) {
            val meta = if (selecting) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
            val code = if (toStart) KeyEvent.KEYCODE_MOVE_HOME else KeyEvent.KEYCODE_MOVE_END
            val t = android.os.SystemClock.uptimeMillis()
            ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, meta or KeyEvent.META_CTRL_ON))
            ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0, meta or KeyEvent.META_CTRL_ON))
            return
        }
        val p = if (toStart) 0 else sel.third
        if (selecting && anchor >= 0) {
            ic.setSelection(anchor, p)
            speakRange(ic, anchor, p)
        } else {
            ic.setSelection(p, p)
            Speaker.speak(if (toStart) "开头" else "末尾")
        }
    }

    /** 读光标刚越过的那个字 */
    private fun speakCharAt(ic: InputConnection, dir: Int) {
        val t = if (dir < 0) ic.getTextAfterCursor(1, 0) else ic.getTextBeforeCursor(1, 0)
        val c = t?.toString() ?: return
        Speaker.speak(when (c) {
            "" -> if (dir < 0) "开头" else "末尾"
            " " -> "空格"
            "\n" -> "换行"
            else -> Names.spoken(c)
        })
    }

    private fun speakRange(ic: InputConnection, a: Int, b: Int) {
        val n = kotlin.math.abs(a - b)
        val sel = ic.getSelectedText(0)?.toString()
        Speaker.speak(if (sel.isNullOrEmpty()) "已选 $n 个字" else "已选 $sel")
    }
}
