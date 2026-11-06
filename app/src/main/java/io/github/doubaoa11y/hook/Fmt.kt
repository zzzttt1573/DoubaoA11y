package io.github.doubaoa11y.hook

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.MotionEvent

/** 日志格式化工具 */
object Fmt {
    fun isPua(c: Char) = c.code in 0xE000..0xF8FF

    /** 私有区字符输出为 \uXXXX，控制字符转义 */
    fun esc(s: String?): String {
        if (s == null) return "null"
        val sb = StringBuilder()
        for (c in s) {
            when {
                isPua(c) -> sb.append("\\u").append(String.format("%04x", c.code))
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '"' -> sb.append("\\\"")
                c.code < 0x20 -> sb.append("\\u").append(String.format("%04x", c.code))
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    fun rect(r: Rect?): String = if (r == null) "null" else "[${r.left},${r.top},${r.right},${r.bottom}]"

    fun color(c: Int): String = String.format("#%08x", c)

    fun action(a: Int): String = when (a and MotionEvent.ACTION_MASK) {
        MotionEvent.ACTION_DOWN -> "按下"
        MotionEvent.ACTION_UP -> "抬起"
        MotionEvent.ACTION_MOVE -> "移动"
        MotionEvent.ACTION_CANCEL -> "取消"
        MotionEvent.ACTION_POINTER_DOWN -> "多指按下"
        MotionEvent.ACTION_POINTER_UP -> "多指抬起"
        MotionEvent.ACTION_HOVER_ENTER -> "悬停进入"
        MotionEvent.ACTION_HOVER_MOVE -> "悬停移动"
        MotionEvent.ACTION_HOVER_EXIT -> "悬停离开"
        else -> "动作$a"
    } + "(${a and MotionEvent.ACTION_MASK})"

    private const val MAX_ARRAY = 12
    private const val MAX_STR = 300

    fun arg(a: Any?): String = when (a) {
        null -> "null"
        is String -> "\"" + esc(if (a.length > MAX_STR) a.substring(0, MAX_STR) + "…" else a) + "\""
        is CharSequence -> arg(a.toString())
        is Rect -> rect(a)
        is Bitmap -> "Bitmap(${a.width}x${a.height})"
        is Array<*> -> list(a.size, a.asList().take(MAX_ARRAY).map { arg(it) })
        is IntArray -> list(a.size, a.take(MAX_ARRAY).map { it.toString() })
        is LongArray -> list(a.size, a.take(MAX_ARRAY).map { it.toString() })
        is FloatArray -> list(a.size, a.take(MAX_ARRAY).map { it.toString() })
        is BooleanArray -> list(a.size, a.take(MAX_ARRAY).map { it.toString() })
        is ByteArray -> "byte[${a.size}]"
        else -> {
            val s = a.toString()
            if (s.length > MAX_STR) s.substring(0, MAX_STR) + "…" else s
        }
    }

    private fun list(size: Int, items: List<String>): String =
        "[$size]{" + items.joinToString(", ") + (if (size > items.size) ", …" else "") + "}"

    fun args(args: Array<Any?>?): String =
        args?.mapIndexed { i, a -> "#$i=${arg(a)}" }?.joinToString(" ") ?: ""
}
