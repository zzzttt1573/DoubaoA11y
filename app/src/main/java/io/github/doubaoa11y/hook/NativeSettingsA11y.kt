package io.github.doubaoa11y.hook

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.CompoundButton
import android.widget.TextView
import de.robv.android.xposed.XposedHelpers
import io.github.doubaoa11y.ConfigSpec
import java.util.Collections
import java.util.WeakHashMap

/**
 * 豆包自己的设置页（:Setting 进程）补无障碍信息。思路来自社区 fork 0.6.8，按本项目重写。
 *
 * - 开关（SwitchCompat）：有状态没名字，补上行标题，状态读“已开启/已关闭”。
 * - 首页三行开关被整行透明点击层盖着，焦点落在点击层上，在它身上补名字和状态。
 * - 自绘复选框（ImeCheckBox）：状态不在无障碍树里，补“可勾选”和当前状态，单选组读“已选中/未选中”。
 * - 连续滑块（ImeStepSlider 的百分比样式）：报成标准滑块，读屏上下滑一次调 10%。
 *   有档位列表的分档样式归 StepSlider，两边不重叠。
 *
 * 只挂 AccessibilityDelegate、只在方法执行后补信息，不改豆包的点击、保存和界面。
 * 模块总开关关着就什么都不补。
 */
object NativeSettingsA11y {
    private const val WIDGET_PKG = "com.bytedance.common_biz.ui.widget."
    private const val LIST_ITEM = WIDGET_PKG + "ImeListItemView"
    private const val TITLE_IMAGE_ITEM = WIDGET_PKG + "ImeTitleImageItemView"
    private const val TITLE_PROGRESS = WIDGET_PKG + "ImeTitleProgressView"
    private const val CHECKBOX = WIDGET_PKG + "ImeCheckBox"
    private const val CHECKBOX_LIST = WIDGET_PKG + "ImeCheckBoxList"
    private const val SLIDER = WIDGET_PKG + "ImeStepSlider"
    private const val MAIN_FRAGMENT = "com.bytedance.android.input.fragment.settings.MainFragment"
    private const val CARD_ID_NAME = "settings_image_check_box"

    /** 首页被透明点击层整行盖住的三个开关 */
    private val OVERLAY_NAMES = listOf(
        "clickable_view_enable_report_inputdata",
        "clickable_view_enable_cloud",
        "clickable_view_enable_ai_assistant",
    )

    private var rowClasses: List<Class<*>> = emptyList()
    private var checkboxCls: Class<*>? = null
    private var checkboxListCls: Class<*>? = null
    private var sliderCls: Class<*>? = null

    /** 已挂过的控件，防止重复挂；弱引用，页面关了自动释放 */
    private val attached: MutableSet<View> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    /** 模块总开关。设置页进程不走输入法的初始化，自己读一次；读不到按开着算 */
    @Volatile
    private var moduleOn: Boolean? = null

    private fun on(ctx: Context?): Boolean {
        moduleOn?.let { return it }
        if (ctx != null) refreshModuleOn(ctx)
        return moduleOn ?: true
    }

    @Suppress("DEPRECATION")
    private fun refreshModuleOn(ctx: Context) {
        moduleOn = try {
            // 设置是输入法进程写的，这里用多进程模式读，拿到最新值
            ctx.getSharedPreferences(ConfigSpec.PREFS_NAME, Context.MODE_MULTI_PROCESS)
                .getBoolean("moduleOn", true)
        } catch (t: Throwable) {
            L.e("设置页读取模块总开关失败，按开着处理", t)
            true
        }
    }

    fun install(cl: ClassLoader) {
        rowClasses = listOf(LIST_ITEM, TITLE_IMAGE_ITEM, TITLE_PROGRESS)
            .mapNotNull { Hooker.findClassQuiet(it, cl) }
        checkboxCls = Hooker.findClassQuiet(CHECKBOX, cl)
        checkboxListCls = Hooker.findClassQuiet(CHECKBOX_LIST, cl)
        sliderCls = Hooker.findClassQuiet(SLIDER, cl)
        installMainPage(cl)
        installSwitches()
        installCheckBoxes()
        installSliders()
        L.i("豆包设置页无障碍已装：行类 ${rowClasses.size}/3，复选框 ${checkboxCls != null}，滑块 ${sliderCls != null}")
    }

    // ---------------- 开关：有状态，没名字 ----------------

    private fun installSwitches() {
        // 开关的初值和点击切换都走 setChecked，在它之后挂上补名字的 Delegate
        Hooker.method(
            CompoundButton::class.java, "setChecked", arrayOf(Boolean::class.javaPrimitiveType!!),
            SafeHook("NativeSettingsA11y.switch", after = { p ->
                val sw = p.thisObject as? CompoundButton ?: return@SafeHook
                if (!on(sw.context)) return@SafeHook
                val row = settingsRowOf(sw) ?: return@SafeHook
                attachSwitchDelegate(sw)
                refreshSwitchFields(sw, row)
            })
        )
    }

    /** 开关所在的设置行；不在设置行里的开关不管 */
    private fun settingsRowOf(v: View): View? {
        var p = v.parent as? View
        var depth = 0
        while (p != null && depth < 10) {
            if (rowClasses.any { it.isInstance(p) }) return p
            p = p.parent as? View
            depth++
        }
        return null
    }

    private fun attachSwitchDelegate(sw: CompoundButton) {
        if (!attached.add(sw)) return
        sw.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                try {
                    val cb = host as? CompoundButton ?: return
                    val row = settingsRowOf(cb) ?: return
                    rowTitle(row, cb)?.let { info.contentDescription = it }
                    if (Build.VERSION.SDK_INT >= 30) info.stateDescription = switchState(cb.isChecked)
                } catch (t: Throwable) {
                    L.hookError("NativeSettingsA11y.switchNode", t)
                }
            }
        }
    }

    /** 控件自己的描述也写一份，读屏不重建节点时也能拿到 */
    private fun refreshSwitchFields(sw: CompoundButton, row: View) {
        try {
            rowTitle(row, sw)?.let { sw.contentDescription = it }
            if (Build.VERSION.SDK_INT >= 30) sw.stateDescription = switchState(sw.isChecked)
        } catch (t: Throwable) {
            L.hookError("NativeSettingsA11y.refreshSwitch", t)
        }
    }

    private fun switchState(checked: Boolean) = if (checked) "已开启" else "已关闭"

    /** 行标题：先按 id 找，找不到用行里第一段文字（跳过开关本身） */
    private fun rowTitle(row: View, exclude: View?): String? {
        for (name in listOf("item_title", "progress_item_title")) {
            val t = (findById(row, name) as? TextView)?.text?.toString()?.trim()
            if (!t.isNullOrEmpty()) return t
        }
        return firstText(row, exclude)
    }

    private fun findById(root: View, name: String): View? = try {
        val id = root.resources.getIdentifier(name, "id", root.context.packageName)
        if (id != 0) root.findViewById(id) else null
    } catch (_: Throwable) {
        null
    }

    private fun firstText(root: View, exclude: View?): String? {
        if (root === exclude || root is CompoundButton) return null
        if (root is TextView) {
            val t = root.text?.toString()?.trim()
            if (!t.isNullOrEmpty()) return t
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) firstText(root.getChildAt(i), exclude)?.let { return it }
        }
        return null
    }

    private fun findSwitch(row: View): CompoundButton? {
        for (name in listOf("item_switch_button", "item_switch", "progress_item_switch")) {
            (findById(row, name) as? CompoundButton)?.let { return it }
        }
        return firstSwitch(row)
    }

    private fun firstSwitch(root: View): CompoundButton? {
        if (root is CompoundButton) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) firstSwitch(root.getChildAt(i))?.let { return it }
        }
        return null
    }

    // ---------------- 首页：透明点击层盖着的开关 ----------------

    private fun installMainPage(cl: ClassLoader) {
        val frag = Hooker.findClassQuiet(MAIN_FRAGMENT, cl) ?: run {
            L.w("豆包设置页无障碍：找不到设置首页，首页点击层跳过")
            return
        }
        Hooker.method(frag, "onViewCreated", arrayOf(View::class.java, Bundle::class.java),
            SafeHook("NativeSettingsA11y.mainPage", after = { p ->
                val root = p.args[0] as? View ?: return@SafeHook
                // 每次进设置首页重读总开关：在模块设置里刚改过的，回到豆包设置就生效
                refreshModuleOn(root.context)
                if (moduleOn == false) return@SafeHook
                for (name in OVERLAY_NAMES) findById(root, name)?.let { attachOverlayDelegate(it) }
            }))
    }

    private fun attachOverlayDelegate(ov: View) {
        if (!attached.add(ov)) return
        try {
            ov.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            ov.isFocusable = true
        } catch (_: Throwable) {
        }
        ov.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                try {
                    val row = overlayRow(host) ?: return
                    rowTitle(row, null)?.let { info.contentDescription = it }
                    val sw = findSwitch(row) ?: return
                    info.className = "android.widget.Switch"
                    info.isCheckable = true
                    info.isChecked = sw.isChecked
                    if (Build.VERSION.SDK_INT >= 30) info.stateDescription = switchState(sw.isChecked)
                } catch (t: Throwable) {
                    L.hookError("NativeSettingsA11y.overlayNode", t)
                }
            }
        }
    }

    /** 点击层盖住的行：布局里点击层紧跟在行后面，同一个父容器里往前找 */
    private fun overlayRow(ov: View): View? {
        val parent = ov.parent as? ViewGroup ?: return null
        for (i in parent.indexOfChild(ov) - 1 downTo 0) {
            val c = parent.getChildAt(i)
            if (rowClasses.any { it.isInstance(c) }) return c
        }
        return null
    }

    // ---------------- 自绘复选框：状态不在无障碍树里 ----------------

    private fun installCheckBoxes() {
        val cls = checkboxCls ?: run {
            L.w("豆包设置页无障碍：找不到 ImeCheckBox，复选框跳过")
            return
        }
        Hooker.method(cls, "setChecked", arrayOf(Boolean::class.javaPrimitiveType!!),
            SafeHook("NativeSettingsA11y.checkSet", after = { p ->
                val cb = p.thisObject as? View ?: return@SafeHook
                if (on(cb.context)) attachCheckBox(cb)
            }))
        // 布局里直接给初值的复选框不走 setChecked，挂到窗口时补
        Hooker.method(View::class.java, "onAttachedToWindow", emptyArray(),
            SafeHook("NativeSettingsA11y.checkAttach", after = { p ->
                val v = p.thisObject as? View ?: return@SafeHook
                if (cls.isInstance(v) && on(v.context)) attachCheckBox(v)
            }))
    }

    private fun attachCheckBox(cb: View) {
        try {
            val target = checkTarget(cb)
            val label = checkLabel(cb, target)
            val radio = isRadioGroup(cb)
            attachCheckDelegate(target, cb, label, radio)
            if (target !== cb) attachCheckDelegate(cb, cb, label, radio)
        } catch (t: Throwable) {
            L.hookError("NativeSettingsA11y.attachCheckBox", t)
        }
    }

    private fun attachCheckDelegate(stop: View, cb: View, label: String?, radio: Boolean) {
        if (!attached.add(stop)) return
        stop.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                try {
                    // 状态每次现读，和图片保持一致
                    val checked = checkState(cb)
                    if (!label.isNullOrEmpty()) info.contentDescription = label
                    info.className = if (radio) "android.widget.RadioButton" else "android.widget.CheckBox"
                    info.isCheckable = true
                    info.isChecked = checked
                    if (Build.VERSION.SDK_INT >= 30) {
                        info.stateDescription = if (radio) (if (checked) "已选中" else "未选中")
                        else (if (checked) "已勾选" else "未勾选")
                    }
                } catch (t: Throwable) {
                    L.hookError("NativeSettingsA11y.checkNode", t)
                }
            }
        }
    }

    /** 读屏焦点停在哪：图片选择卡就是整张卡，其他是复选框的父容器（整行） */
    private fun checkTarget(cb: View): View {
        var p = cb.parent as? View
        var depth = 0
        while (p != null && depth < 6) {
            if (entryName(p) == CARD_ID_NAME) return p
            p = p.parent as? View
            depth++
        }
        return (cb.parent as? View) ?: cb
    }

    private fun checkLabel(cb: View, target: View): String? {
        for (name in listOf("check_box_text", "item_text")) {
            val t = (findById(target, name) as? TextView)?.text?.toString()?.trim()
            if (!t.isNullOrEmpty()) return t
        }
        return firstText(target, cb)
    }

    /** 列表里、选择卡里的是单选；同一页有两个以上的也是单选组；只有一个才是真复选 */
    private fun isRadioGroup(cb: View): Boolean {
        var p = cb.parent as? View
        var depth = 0
        while (p != null && depth < 8) {
            if (checkboxListCls?.isInstance(p) == true) return true
            if (entryName(p) == CARD_ID_NAME) return true
            p = p.parent as? View
            depth++
        }
        val root = cb.rootView ?: return false
        return countCheckBoxes(root) >= 2
    }

    private fun countCheckBoxes(root: View): Int {
        var n = if (checkboxCls?.isInstance(root) == true) 1 else 0
        if (root is ViewGroup) for (i in 0 until root.childCount) n += countCheckBoxes(root.getChildAt(i))
        return n
    }

    /** ImeCheckBox 的选中状态存在布尔字段 a 里；字段名变了就找它唯一的布尔字段 */
    private fun checkState(cb: View): Boolean = try {
        XposedHelpers.getBooleanField(cb, "a")
    } catch (_: Throwable) {
        try {
            val fs = cb.javaClass.declaredFields.filter { it.type == Boolean::class.javaPrimitiveType }
            if (fs.size == 1) fs[0].run { isAccessible = true; getBoolean(cb) } else false
        } catch (_: Throwable) {
            false
        }
    }

    private fun entryName(v: View): String? = try {
        if (v.id != View.NO_ID) v.resources.getResourceEntryName(v.id) else null
    } catch (_: Throwable) {
        null
    }

    // ---------------- 百分比滑块 ----------------

    private fun installSliders() {
        val cls = sliderCls ?: run {
            L.w("豆包设置页无障碍：找不到 ImeStepSlider，滑块跳过")
            return
        }
        Hooker.method(View::class.java, "onInitializeAccessibilityNodeInfo",
            arrayOf(AccessibilityNodeInfo::class.java),
            SafeHook("NativeSettingsA11y.sliderNode", after = { p ->
                val v = p.thisObject as? View ?: return@SafeHook
                if (!cls.isInstance(v) || !isProgressSlider(v) || !on(v.context)) return@SafeHook
                describeProgress(v, p.args[0] as AccessibilityNodeInfo)
            }))
        Hooker.method(View::class.java, "performAccessibilityAction",
            arrayOf(Int::class.javaPrimitiveType!!, Bundle::class.java),
            SafeHook("NativeSettingsA11y.sliderAction", before = { p ->
                val v = p.thisObject as? View ?: return@SafeHook
                if (!cls.isInstance(v) || !isProgressSlider(v) || !on(v.context)) return@SafeHook
                if (actProgress(v, p.args[0] as Int, p.args[1] as? Bundle)) p.result = true
            }))
    }

    /** 百分比样式：样式字段 v 为 1，并且没有档位列表（有档位列表的归 StepSlider） */
    private fun isProgressSlider(v: View): Boolean = try {
        if (XposedHelpers.getIntField(v, "v") != 1) false
        else (try { XposedHelpers.getObjectField(v, "l") as? List<*> } catch (_: Throwable) { null }).isNullOrEmpty()
    } catch (_: Throwable) {
        false
    }

    private fun progressOf(v: View): Int = try {
        (XposedHelpers.callMethod(v, "getProgress") as? Int) ?: 0
    } catch (_: Throwable) {
        0
    }

    private fun sliderTitle(v: View): String? {
        settingsRowOf(v)?.let { row -> rowTitle(row, v)?.let { return it } }
        var p = v.parent as? ViewGroup
        var depth = 0
        while (p != null && depth < 3) {
            firstText(p, v)?.let { return it }
            p = p.parent as? ViewGroup
            depth++
        }
        return null
    }

    private fun describeProgress(v: View, info: AccessibilityNodeInfo) {
        val progress = progressOf(v).coerceIn(0, 100)
        info.className = "android.widget.SeekBar"
        info.contentDescription = sliderTitle(v) ?: "滑块"
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(
            AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 0f, 100f, progress.toFloat()
        )
        if (Build.VERSION.SDK_INT >= 30) info.stateDescription = "$progress%"
        info.isFocusable = true
        info.isScrollable = true
        if (progress < 100) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        if (progress > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
    }

    private fun actProgress(v: View, action: Int, args: Bundle?): Boolean {
        val cur = progressOf(v).coerceIn(0, 100)
        val target = when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> cur + 10
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> cur - 10
            android.R.id.accessibilityActionSetProgress ->
                args?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, cur.toFloat())?.toInt() ?: cur
            else -> return false
        }.coerceIn(0, 100)
        if (target != cur) {
            try {
                XposedHelpers.callMethod(v, "setProgress", target, false)
                // g(true) 是滑块自己的“值变了”通知，和手指拖完松开走同一条路，豆包才会保存
                XposedHelpers.callMethod(v, "g", true)
            } catch (t: Throwable) {
                L.hookError("NativeSettingsA11y.sliderSet", t)
                return false
            }
        }
        try {
            v.announceForAccessibility("$target%")
        } catch (_: Throwable) {
        }
        return true
    }
}
