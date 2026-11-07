package io.github.doubaoa11y.hook

import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import de.robv.android.xposed.XposedHelpers

/**
 * 豆包设置页里的分档滑块（ImeStepSlider，候选字大小等）是自己画的，读屏看不出是滑块，也拖不动。
 * 这里把它报成标准滑块：读屏上下滑（调节）一次换一档，读出当前档位。
 * 换档调用滑块自己的“选中第几档”方法，和手指拖动后松开一样，会通知豆包保存设置。
 * 设置页不一定在输入法进程里，所以这里不看模块启用状态，只改这个控件的无障碍信息。
 */
object StepSlider {
    private const val CLS = "com.bytedance.common_biz.ui.widget.ImeStepSlider"
    private var cls: Class<*>? = null

    fun install(cl: ClassLoader) {
        val c = Hooker.findClassQuiet(CLS, cl) ?: run {
            L.w("找不到分档滑块 $CLS")
            return
        }
        cls = c
        // 普通 View，不能点也没有文字，系统不把它报给读屏；量好大小后标成重要、可聚焦
        Hooker.method(c, "onSizeChanged", arrayOf(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
            SafeHook("StepSlider.size", after = { p ->
                val v = p.thisObject as View
                v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                v.isFocusable = true
            }))
        Hooker.method(View::class.java, "onInitializeAccessibilityNodeInfo", arrayOf(AccessibilityNodeInfo::class.java),
            SafeHook("StepSlider.node", after = { p ->
                val v = p.thisObject as View
                if (!c.isInstance(v)) return@SafeHook
                describe(v, p.args[0] as AccessibilityNodeInfo)
            }))
        Hooker.method(View::class.java, "performAccessibilityAction", arrayOf(Int::class.javaPrimitiveType!!, Bundle::class.java),
            SafeHook("StepSlider.action", before = { p ->
                val v = p.thisObject as View
                if (!c.isInstance(v)) return@SafeHook
                if (act(v, p.args[0] as Int, p.args[1] as Bundle?)) p.result = true
            }))
    }

    private fun steps(v: View): List<*>? {
        try {
            (XposedHelpers.getObjectField(v, "l") as? List<*>)?.let { return it }
        } catch (_: Throwable) {
        }
        // 字段名混淆后变了：找第一个 List 字段
        var c: Class<*>? = v.javaClass
        while (c != null && c != View::class.java) {
            for (f in c.declaredFields) {
                if (List::class.java.isAssignableFrom(f.type)) {
                    f.isAccessible = true
                    (f.get(v) as? List<*>)?.let { return it }
                }
            }
            c = c.superclass
        }
        return null
    }

    private fun current(v: View): Int = try {
        XposedHelpers.callMethod(v, "getCurrentStep") as Int
    } catch (_: Throwable) {
        0
    }

    private fun label(list: List<*>, i: Int): String {
        val s = list.getOrNull(i)
        return if (s is CharSequence && s.isNotBlank()) s.toString() else "第${i + 1}档"
    }

    /** 滑块旁边的标题文字（同一个父控件里的第一个文字） */
    private fun title(v: View): String? {
        var p = v.parent as? ViewGroup
        var depth = 0
        while (p != null && depth < 3) {
            for (i in 0 until p.childCount) {
                val c = p.getChildAt(i)
                if (c === v) continue
                val t = (c as? TextView)?.text?.toString()?.trim()
                if (!t.isNullOrEmpty()) return t
            }
            p = p.parent as? ViewGroup
            depth++
        }
        return null
    }

    private fun state(v: View, list: List<*>, i: Int): String = "${label(list, i)}，第${i + 1}档，共${list.size}档"

    private fun describe(v: View, info: AccessibilityNodeInfo) {
        val list = steps(v) ?: return
        if (list.isEmpty()) return
        val i = current(v).coerceIn(0, list.size - 1)
        info.className = "android.widget.SeekBar"
        info.contentDescription = title(v) ?: "滑块"
        val st = state(v, list, i)
        if (Build.VERSION.SDK_INT >= 30) info.stateDescription = st else info.text = st
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(
            AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 0f, (list.size - 1).toFloat(), i.toFloat())
        info.isFocusable = true
        info.isScrollable = true
        if (i < list.size - 1) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        if (i > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
    }

    private fun act(v: View, action: Int, args: Bundle?): Boolean {
        val list = steps(v) ?: return false
        if (list.isEmpty()) return false
        val i = current(v).coerceIn(0, list.size - 1)
        val target = when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> i + 1
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> i - 1
            android.R.id.accessibilityActionSetProgress ->
                args?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, i.toFloat())?.toInt() ?: i
            else -> return false
        }.coerceIn(0, list.size - 1)
        if (target != i) select(v, list.size, target)
        val st = state(v, list, current(v).coerceIn(0, list.size - 1))
        L.i("分档滑块「${title(v) ?: ""}」：$st")
        v.announceForAccessibility(if (target == i) "已到头，$st" else st)
        return true
    }

    /** 选中第 n 档：滑块自己的选档方法（会通知豆包保存），再把滑块移到那一档 */
    private fun select(v: View, count: Int, n: Int) {
        try {
            XposedHelpers.callMethod(v, "f", n)
        } catch (t: Throwable) {
            L.e("分档滑块选档失败", t)
            return
        }
        try {
            val progress = if (count > 1) n * 100 / (count - 1) else 0
            XposedHelpers.callMethod(v, "setProgress", progress, false)
        } catch (_: Throwable) {
        }
        v.invalidate()
    }
}
