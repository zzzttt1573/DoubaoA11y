package io.github.doubaoa11y.hook

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import de.robv.android.xposed.XposedHelpers
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Modifier
import java.util.LinkedHashMap

/**
 * 底层工具栏原生管线注入：
 * 1. 定制工具栏弹窗（ToolbarPagerAdapter）：在原生页之后动态追加模块专属页，直接实例化豆包原生 OptionItemView；
 *    底部圆点指示器、ViewPager2 翻页、横竖屏布局、键盘缩放均由豆包原生逻辑自动适配，不受官方新增工具影响。
 * 2. 配置与编辑事务（KeyboardJni native 接口）：拦截 getToolbarConfig / customizeToolbarButton /
 *    reorderToolbarButtons / enter|confirm|exitCustomizeToolbar，将模块自定义工具融入原生 JSON 配置与排序。
 * 3. 顶部工具栏渲染与点击（CandidateIdleView）：向原生按钮图标静态表注册自定义 key，并在按钮绑定处
 *    替换自定义图标与点击事件，不借壳单手模式，也不依赖易变的点击分发方法签名。
 */
object ToolbarInject {
    const val KEY_UPPER_GESTURE = "btn_a11y_upper_gesture"
    const val KEY_EDIT_MODE = "btn_a11y_edit_mode"
    const val KEY_CLEAR_TEXT = "btn_a11y_clear_text"
    const val KEY_UNDO = "btn_a11y_undo"
    const val KEY_DELETE_RIGHT = "btn_a11y_delete_right"
    const val KEY_READ_EDITOR = "btn_a11y_read_editor"
    const val KEY_PASTE = "btn_a11y_paste"

    private const val MODULE_PAGE_VIEW_TYPE = 0x5A110001
    private const val CLS_ADAPTER = "com.bytedance.android.input.keyboard.CustomToolbar.ToolbarPagerAdapter"
    private const val CLS_OPTION_ITEM = "com.bytedance.android.input.OptionItemView"
    private const val CLS_IDLE_VIEW = "com.bytedance.common_biz.tool_bar.view.views.CandidateIdleView"
    private const val TAG_OPT_PREFIX = "doubaoa11y_mod_opt_"
    private const val READ_EDITOR_DOUBLE_CLICK_MS = 750L

    private val main = Handler(Looper.getMainLooper())
    private var lastReadEditorClickAt = 0L

    data class CustomTool(
        val key: String,
        val label: String,
        val configKey: String,
        val onClick: (Context) -> Unit,
    )

    val tools: List<CustomTool> = listOf(
        CustomTool(
            key = KEY_UPPER_GESTURE,
            label = "上半屏手势",
            configKey = "screenToolbar",
            onClick = { _ ->
                if (A11yState.recording) {
                    FullscreenMode.toggleScreenMode()
                    refreshToolbar()
                }
            },
        ),
        CustomTool(
            key = KEY_EDIT_MODE,
            label = "编辑模式",
            configKey = "editToolbar",
            onClick = { _ ->
                if (A11yState.recording) EditPanel.open()
            },
        ),
        CustomTool(
            key = KEY_CLEAR_TEXT,
            label = "清空编辑框",
            configKey = "clearToolbar",
            onClick = { _ ->
                if (A11yState.recording) EditActions.clearText()
            },
        ),
        CustomTool(
            key = KEY_UNDO,
            label = "撤销",
            configKey = "undoToolbar",
            onClick = { _ ->
                if (A11yState.recording) EditActions.undo()
            },
        ),
        CustomTool(
            key = KEY_DELETE_RIGHT,
            label = "向后删除",
            configKey = "deleteRightToolbar",
            onClick = { _ ->
                if (A11yState.recording) EditActions.deleteRight()
            },
        ),
        CustomTool(
            key = KEY_READ_EDITOR,
            label = "朗读编辑框",
            configKey = "readEditorToolbar",
            onClick = { _ ->
                if (A11yState.recording) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastReadEditorClickAt in 1L..READ_EDITOR_DOUBLE_CLICK_MS) {
                        lastReadEditorClickAt = 0L
                        EditActions.readAllSpelled()
                    } else {
                        lastReadEditorClickAt = now
                        EditActions.readAll()
                    }
                }
            },
        ),
        CustomTool(
            key = KEY_PASTE,
            label = "粘贴",
            configKey = "pasteToolbar",
            onClick = { _ ->
                if (A11yState.recording) EditActions.paste()
            },
        ),
    )

    private val toolMap: Map<String, CustomTool> = tools.associateBy { it.key }

    fun findTool(key: String?): CustomTool? = if (key == null) null else toolMap[key]

    fun isCustomKey(key: String?): Boolean = key != null && toolMap.containsKey(key)

    /** 编辑态草稿（进入定制工具栏后暂存，点完成后持久化，取消时丢弃） */
    @Volatile
    private var inEditSession = false
    @Volatile
    private var selfReordering = false
    @Volatile
    private var selfQueryingConfig = false
    private val draftEnabled = LinkedHashMap<String, Boolean>()
    private var draftOrder: MutableList<String>? = null

    /** 豆包原生页数（由 getItemCount 动态捕获，自动兼容 2 页或未来更多页） */
    @Volatile
    private var origPageCount = 2

    private var optionItemClass: Class<*>? = null
    private var pageViewHolderClass: Class<*>? = null
    private var idleViewClass: Class<*>? = null
    private var idleDispatchMethod: java.lang.reflect.Method? = null
    private var idleDispatchResolved = false
    private var registeredInIconMap = false

    fun isToolEnabled(key: String): Boolean {
        val tool = toolMap[key] ?: return false
        if (inEditSession) {
            draftEnabled[key]?.let { return it }
        }
        return Config.b(tool.configKey)
    }

    private fun modulePageCount(): Int = if (tools.isEmpty()) 0 else (tools.size + 7) / 8

    fun install(cl: ClassLoader) {
        installJniHooks(cl)
        installIdleViewHooks(cl)
        installTooltipHooks(cl)
        installPagerAdapterHooks(cl)
    }

    // ==================== 1. KeyboardJni 原生配置与编辑事务 ====================

    private fun installJniHooks(cl: ClassLoader) {
        val jniCls = Hooker.findClassQuiet(JniHooks.CLS, cl) ?: return

        Hooker.method(jniCls, "enterCustomizeToolbar", emptyArray(),
            SafeHook("ToolbarInject.enterCustomize", before = {
                if (!A11yState.recording) return@SafeHook
                inEditSession = true
                draftEnabled.clear()
                for (t in tools) draftEnabled[t.key] = Config.b(t.configKey)
                draftOrder = buildInitialVisibleOrder()
            }))

        Hooker.method(jniCls, "customizeToolbarButton",
            arrayOf(Boolean::class.javaPrimitiveType!!, String::class.java),
            SafeHook("ToolbarInject.customizeBtn", before = { p ->
                val visible = p.args[0] as? Boolean ?: return@SafeHook
                val name = p.args[1] as? String ?: return@SafeHook
                // 不论豆包原生按钮还是模块自定义按钮，一律按用户点击添加的先后顺序排到末尾；移除时从顺序表删除
                if (A11yState.recording) {
                    val cur = (if (inEditSession) draftOrder else parseOrder(Config.s("toolbarCustomOrder"))?.toMutableList())
                        ?: buildInitialVisibleOrder()
                    cur.remove(name)
                    if (visible) cur.add(name)
                    if (inEditSession) {
                        draftOrder = cur
                    } else {
                        saveOrder(cur)
                    }
                }
                val tool = toolMap[name] ?: return@SafeHook
                p.result = null
                if (inEditSession) {
                    draftEnabled[name] = visible
                } else {
                    Config.setFromIme(ServiceHooks.appContext, tool.configKey, visible)
                }
                L.i("定制工具栏（底层）：${if (visible) "添加" else "移除"} ${tool.label}（编辑态=$inEditSession）")
            }, after = { p ->
                if (!A11yState.recording) return@SafeHook
                val name = p.args[1] as? String ?: return@SafeHook
                if (!isCustomKey(name)) {
                    // 原生按钮添加/移除后按新的点击顺序立即刷新顶部工具栏，避免豆包按出厂固定顺序回弹
                    refreshToolbar()
                }
            }))

        Hooker.method(jniCls, "reorderToolbarButtons", arrayOf(Array<String>::class.java),
            SafeHook("ToolbarInject.reorder", before = { p ->
                if (selfReordering) return@SafeHook
                @Suppress("UNCHECKED_CAST")
                val arr = p.args[0] as? Array<String> ?: return@SafeHook
                if (arr.none { isCustomKey(it) }) {
                    if (inEditSession) draftOrder = arr.toMutableList()
                    return@SafeHook
                }
                if (inEditSession) {
                    draftOrder = arr.toMutableList()
                } else {
                    saveOrder(arr.toList())
                }
                val filtered = ToolbarOrderPolicy.nativeOnly(arr.toList(), toolMap.keys)
                p.args[0] = filtered
            }))

        Hooker.method(jniCls, "confirmCustomizeToolbar", emptyArray(),
            SafeHook("ToolbarInject.confirm", before = {
                if (!inEditSession) return@SafeHook
                val order = draftOrder ?: return@SafeHook
                val nativeOnly = ToolbarOrderPolicy.nativeOnly(order, toolMap.keys)
                if (nativeOnly.isNotEmpty()) {
                    try {
                        val jni = JniHooks.jni()
                        if (jni != null) {
                            selfReordering = true
                            try {
                                val ok = XposedHelpers.callMethod(jni, "reorderToolbarButtons", nativeOnly)
                                if (ok == false) L.w("ToolbarInject：豆包 reorderToolbarButtons 返回 false")
                            } finally {
                                selfReordering = false
                            }
                        }
                    } catch (t: Throwable) {
                        L.hookError("ToolbarInject.confirmReorder", t)
                    }
                }
            }, after = {
                if (!inEditSession) return@SafeHook
                val ctx = ServiceHooks.appContext
                for (t in tools) {
                    val v = draftEnabled[t.key] ?: continue
                    Config.setFromIme(ctx, t.configKey, v)
                }
                draftOrder?.let { saveOrder(it) }
                inEditSession = false
                draftEnabled.clear()
                draftOrder = null
                refreshToolbar()
                L.i("定制工具栏（底层）：已保存自定义工具配置与点击顺序")
            }))

        Hooker.method(jniCls, "exitCustomizeToolbar", emptyArray(),
            SafeHook("ToolbarInject.exit", after = {
                if (inEditSession) {
                    inEditSession = false
                    draftEnabled.clear()
                    draftOrder = null
                    L.i("定制工具栏（底层）：已取消未保存的自定义工具草稿")
                }
            }))

        Hooker.method(jniCls, "getToolbarConfig", emptyArray(),
            SafeHook("ToolbarInject.getToolbarConfig", after = { p ->
                if (!A11yState.recording || selfQueryingConfig) return@SafeHook
                val raw = p.result as? String ?: return@SafeHook
                if (raw.isBlank()) return@SafeHook
                val merged = mergeToolbarConfig(raw)
                if (merged != null) p.result = merged
            }))
    }

    /** 进入定制工具栏时构造当前已添加按钮的有序列表，作为后续按点击先后追加的基准 */
    private fun buildInitialVisibleOrder(): MutableList<String> {
        val nativeVisible = ArrayList<Pair<String, Int>>()
        try {
            val jni = JniHooks.jni()
            if (jni != null) {
                selfQueryingConfig = true
                val raw = try {
                    XposedHelpers.callMethod(jni, "getToolbarConfig") as? String
                } finally {
                    selfQueryingConfig = false
                }
                if (!raw.isNullOrBlank()) {
                    val buttons = JSONObject(raw).optJSONArray("buttons")
                    if (buttons != null) {
                        for (i in 0 until buttons.length()) {
                            val o = buttons.optJSONObject(i) ?: continue
                            val name = o.optString("name", "")
                            if (name.isNotEmpty() && !isCustomKey(name) && o.optBoolean("visible", false)) {
                                nativeVisible.add(name to o.optInt("order", i))
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            L.hookError("ToolbarInject.buildInitialVisibleOrder", t)
        }
        val enabledCustom = tools.filter { isToolEnabled(it.key) }.map { it.key }
        val saved = parseOrder(Config.s("toolbarCustomOrder"))
        return ToolbarOrderPolicy.mergeInitialOrder(nativeVisible, enabledCustom, saved)
    }

    private fun mergeToolbarConfig(raw: String): String? = try {
        val root = JSONObject(raw)
        val buttons = root.optJSONArray("buttons") ?: return null
        val existing = LinkedHashMap<String, JSONObject>()
        val nativeList = ArrayList<JSONObject>()
        for (i in 0 until buttons.length()) {
            val obj = buttons.optJSONObject(i) ?: continue
            val name = obj.optString("name", "")
            if (name.isNotEmpty() && !isCustomKey(name)) {
                existing[name] = obj
                nativeList.add(obj)
            }
        }
        val customObjs = LinkedHashMap<String, JSONObject>()
        for (t in tools) {
            val vis = isToolEnabled(t.key)
            customObjs[t.key] = JSONObject().apply {
                put("name", t.key)
                put("visible", vis)
                put("order", 10000)
            }
        }

        val savedOrder = if (inEditSession) draftOrder else parseOrder(Config.s("toolbarCustomOrder"))
        val ordered = ArrayList<JSONObject>(nativeList.size + customObjs.size)
        val addedKeys = HashSet<String>()
        val nativeOrdered = nativeList.sortedBy { it.optInt("order", Int.MAX_VALUE) }

        if (!savedOrder.isNullOrEmpty()) {
            // 严格按照用户点击添加/调整后的先后顺序排列（原生与模块按钮统一混排）
            for (k in savedOrder) {
                if (!addedKeys.add(k)) continue
                val obj = existing[k] ?: customObjs[k] ?: continue
                ordered.add(obj)
            }
        }

        // 未在 savedOrder 中的剩余原生与模块按钮按原有顺序补在后面
        for (o in nativeOrdered) {
            val k = o.optString("name")
            if (addedKeys.add(k)) ordered.add(o)
        }
        for (t in tools) {
            if (addedKeys.add(t.key)) customObjs[t.key]?.let { ordered.add(it) }
        }

        val outArr = JSONArray()
        for (i in ordered.indices) {
            val o = ordered[i]
            o.put("order", i)
            outArr.put(o)
        }
        root.put("buttons", outArr)
        root.toString()
    } catch (t: Throwable) {
        L.hookError("ToolbarInject.mergeToolbarConfig", t)
        null
    }

    private fun parseOrder(s: String): List<String>? {
        if (s.isBlank()) return null
        val list = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return list.ifEmpty { null }
    }

    private fun saveOrder(order: List<String>) {
        val encoded = order.joinToString(",")
        Config.setStrFromIme(ServiceHooks.appContext, "toolbarCustomOrder", encoded)
    }

    // ==================== 2. CandidateIdleView 图标注册与点击接管 ====================

    private fun installIdleViewHooks(cl: ClassLoader) {
        val idleCls = Hooker.findClassQuiet(CLS_IDLE_VIEW, cl) ?: run {
            L.w("ToolbarInject：找不到 CandidateIdleView")
            return
        }
        idleViewClass = idleCls
        registerInNativeIconMap(idleCls)

        // 按钮绑定方法 r(ImageView, jl.a, boolean, boolean, boolean)
        val bindMethods = idleCls.declaredMethods.filter { m ->
            if (m.returnType != Void.TYPE) return@filter false
            val ps = m.parameterTypes
            ps.size == 5 &&
                ps[0] == ImageView::class.java &&
                !ps[1].isPrimitive &&
                ps[2] == Boolean::class.javaPrimitiveType &&
                ps[3] == Boolean::class.javaPrimitiveType &&
                ps[4] == Boolean::class.javaPrimitiveType
        }
        if (bindMethods.isNotEmpty()) {
            for (m in bindMethods) {
                L.i("ToolbarInject：找到顶部按钮绑定方法 ${m.name}")
                Hooker.hookMember(m, SafeHook("ToolbarInject.bindButton", after = { p ->
                    val iv = p.args[0] as? ImageView ?: return@SafeHook
                    applyCustomToolbarButton(iv)
                }))
            }
        } else {
            L.w("ToolbarInject：未找到 5 参按钮绑定方法，降级到创建方法 L")
            // 降级：hook 返回 ImageView 且参数不少于 2 个的创建方法
            val createMethods = idleCls.declaredMethods.filter { m ->
                m.returnType == ImageView::class.java && m.parameterTypes.size >= 2 && !m.parameterTypes[0].isPrimitive
            }
            for (m in createMethods) {
                Hooker.hookMember(m, SafeHook("ToolbarInject.createButton", after = { p ->
                    val iv = p.result as? ImageView ?: return@SafeHook
                    applyCustomToolbarButton(iv)
                }))
            }
        }
    }

    /**
     * 通过 CandidateIdleView 字段类型动态发现按钮图标静态 Map（包含 "btn_toolbar_emoji"），
     * 向其中注册模块自定义按钮 key，完全不依赖混淆包名/类名。
     */
    @Suppress("UNCHECKED_CAST")
    private fun registerInNativeIconMap(idleCls: Class<*>) {
        if (registeredInIconMap) return
        val visited = HashSet<Class<*>>()
        for (f in idleCls.declaredFields) {
            val c = f.type
            if (c.isPrimitive || c.isArray) continue
            val name = c.name
            if (name.startsWith("android.") || name.startsWith("java.") || name.startsWith("kotlin.")) continue
            if (!visited.add(c)) continue
            try {
                for (sf in c.declaredFields) {
                    if (!Modifier.isStatic(sf.modifiers)) continue
                    if (!Map::class.java.isAssignableFrom(sf.type)) continue
                    sf.isAccessible = true
                    val map = sf.get(null) as? Map<*, *> ?: continue
                    if (!map.containsKey("btn_toolbar_emoji")) continue
                    val placeholder = (map["btn_toolbar_one_hand"] as? Int)
                        ?: (map["btn_toolbar_emoji"] as? Int)
                        ?: continue
                    if (map is MutableMap<*, *>) {
                        val m = map as MutableMap<String, Int>
                        for (t in tools) m[t.key] = placeholder
                        registeredInIconMap = true
                        L.i("ToolbarInject：已向原生图标表 ${c.name}.${sf.name} 注册 ${tools.size} 个自定义工具")
                        return
                    }
                }
            } catch (t: Throwable) {
                L.hookError("ToolbarInject.registerIconMap", t)
            }
        }
        L.w("ToolbarInject：未找到原生按钮图标静态表")
    }

    private fun applyCustomToolbarButton(iv: ImageView) {
        if (!A11yState.recording) return
        val key = iv.contentDescription?.toString() ?: return
        val tool = toolMap[key] ?: return
        val dark = isDarkTheme(iv.context)
        val active = (key == KEY_UPPER_GESTURE && Config.fullscreen)
        val fgColor = when {
            active -> if (dark) 0xFF64B5F6.toInt() else 0xFF1976D2.toInt()
            dark -> 0xFFEAEAEA.toInt()
            else -> 0xFF222222.toInt()
        }
        iv.setImageDrawable(ToolGlyphDrawable(key, fgColor, iv.resources.displayMetrics.density, inOptionCard = false))
        if (iv.isClickable) {
            iv.setOnClickListener {
                try {
                    tool.onClick(iv.context)
                } catch (t: Throwable) {
                    L.hookError("ToolbarInject.btnClick", t)
                }
            }
        }
    }

    /** 刷新顶部工具栏（通过 CandidateIdleView 未混淆的公开方法 setMode(mode, true) 重新加载配置并重绘） */
    fun refreshToolbar() {
        main.post {
            try {
                val root = ViewA11yHooks.service?.window?.window?.decorView ?: return@post
                val idle = findIdleView(root) ?: return@post
                val mode = if (CustomToolbar.editing()) 1 else 0
                XposedHelpers.callMethod(idle, "setMode", mode, true)
            } catch (t: Throwable) {
                L.hookError("ToolbarInject.refreshToolbar", t)
            }
        }
    }

    /**
     * 直接触发豆包工具栏内置功能（不依赖该按钮是否已添加到工具栏，也不需要展开菜单或点击控件）。
     * 通过 CandidateIdleView 上唯一的非静态 (String, boolean) -> void 分发方法直连底层处理器
     * （如 "btn_toolbar_text_organize" → SmartOrganize 引擎）。
     */
    fun triggerToolbarAction(buttonKey: String): Boolean {
        if (CustomToolbar.editing()) return false
        return try {
            val root = ViewA11yHooks.service?.window?.window?.decorView ?: return false
            val idle = findIdleView(root) ?: return false
            val m = resolveIdleDispatch(idle.javaClass) ?: return false
            m.invoke(idle, buttonKey, false)
            true
        } catch (t: Throwable) {
            L.hookError("ToolbarInject.triggerToolbarAction($buttonKey)", t)
            false
        }
    }

    private fun resolveIdleDispatch(cls: Class<*>): java.lang.reflect.Method? {
        if (idleDispatchResolved) return idleDispatchMethod
        idleDispatchResolved = true
        val boolType = Boolean::class.javaPrimitiveType!!
        val candidates = cls.declaredMethods.filter { m ->
            !Modifier.isStatic(m.modifiers) &&
                m.returnType == Void.TYPE &&
                m.parameterTypes.size == 2 &&
                m.parameterTypes[0] == String::class.java &&
                m.parameterTypes[1] == boolType
        }
        if (candidates.size == 1) {
            idleDispatchMethod = candidates[0].apply { isAccessible = true }
            L.i("ToolbarInject：找到工具栏功能直连分发方法 ${candidates[0].name}(String, boolean)")
        } else {
            L.w("ToolbarInject：未唯一定位工具栏分发方法，候选 ${candidates.size} 个（${candidates.joinToString { it.name }}）")
        }
        return idleDispatchMethod
    }

    /**
     * 拦截工具栏提示条（ImeTooltipView）：智能整理等手势触发后，自动抓取豆包在工具栏上显示的提示文案
     * （如“请先输入内容再整理”“表达清晰，无需整理”“整理失败”等）并交给 KeyboardOps 朗读。
     * 按未混淆类名 ImeTooltipView / ImeTooltipSubmitResult / ImeTooltipStyle 定位，不依赖混淆方法名或字段名。
     */
    private fun installTooltipHooks(cl: ClassLoader) {
        val tooltipCls = Hooker.findClassQuiet("com.bytedance.common_biz.ui.tooltip.ImeTooltipView", cl) ?: return
        val submitResCls = Hooker.findClassQuiet("com.bytedance.common_biz.ui.tooltip.ImeTooltipSubmitResult", cl) ?: return
        val styleCls = Hooker.findClassQuiet("com.bytedance.common_biz.ui.tooltip.ImeTooltipStyle", cl)
        val sourceCls = Hooker.findClassQuiet("com.bytedance.android.input.keyboard.tooltip.ImeTooltipSource", cl)
        val prefix = tooltipCls.name + "$"
        val methods = tooltipCls.declaredMethods.filter { m ->
            !Modifier.isStatic(m.modifiers) &&
                m.returnType == submitResCls &&
                m.parameterTypes.size == 1 &&
                !m.parameterTypes[0].name.startsWith(prefix)
        }
        if (methods.size != 1) {
            L.w("ToolbarInject：未唯一定位工具栏提示方法，候选 ${methods.size} 个")
            return
        }
        val m = methods[0]
        L.i("ToolbarInject：找到工具栏提示方法 ${m.name}(${m.parameterTypes[0].simpleName})")
        Hooker.hookMember(m, SafeHook("ToolbarInject.tooltip", after = { p ->
            if (!A11yState.recording) return@SafeHook
            val resName = (p.result as? Enum<*>)?.name
            if (resName != "ACCEPTED" && resName != "REPLACED") return@SafeHook
            val req = p.args.getOrNull(0)
            val srcName = extractTooltipSource(req, sourceCls)
            val fromVoice = isVoiceTooltipSource(srcName)
            val text = extractTooltipText(req, styleCls)
                .ifBlank { collectViewText(p.thisObject as? View) }
                .trim()
            if (text.isNotEmpty()) {
                L.i("工具栏提示：「$text」（结果=$resName，来源=${srcName.ifEmpty { "未知" }}）")
                KeyboardOps.onToolbarTip(text, fromVoice = fromVoice)
            }
        }))
    }

    private fun extractTooltipSource(req: Any?, sourceCls: Class<*>?): String {
        if (req == null) return ""
        return try {
            for (f in req.javaClass.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                f.isAccessible = true
                val v = f.get(req) ?: continue
                if (sourceCls != null && sourceCls.isInstance(v)) {
                    return (v as? Enum<*>)?.name ?: v.toString()
                }
                if (v is Enum<*> && v.javaClass.simpleName.contains("TooltipSource", ignoreCase = true)) {
                    return v.name
                }
            }
            ""
        } catch (_: Throwable) {
            ""
        }
    }

    private fun isVoiceTooltipSource(sourceName: String): Boolean {
        if (sourceName.isEmpty()) return false
        val upper = sourceName.uppercase()
        return upper.contains("VOICE") || upper.contains("ASR") || upper.contains("SPEECH")
    }

    private fun extractTooltipText(req: Any?, styleCls: Class<*>?): String {
        if (req == null) return ""
        return try {
            // ImeTooltipRenderRequest 的 content 字段内部带有 ImeTooltipStyle 枚举与主文案 CharSequence
            for (f in req.javaClass.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                f.isAccessible = true
                val child = f.get(req) ?: continue
                if (child is CharSequence) continue
                val fields = child.javaClass.declaredFields
                val hasStyle = styleCls == null || fields.any { !Modifier.isStatic(it.modifiers) && it.type == styleCls }
                if (!hasStyle) continue
                for (cf in fields) {
                    if (Modifier.isStatic(cf.modifiers)) continue
                    if (!CharSequence::class.java.isAssignableFrom(cf.type)) continue
                    cf.isAccessible = true
                    val v = (cf.get(child) as? CharSequence)?.toString()?.trim()
                    if (!v.isNullOrEmpty()) return v
                }
            }
            ""
        } catch (t: Throwable) {
            L.hookError("ToolbarInject.extractTooltipText", t)
            ""
        }
    }

    private fun collectViewText(v: View?): String {
        if (v == null) return ""
        val sb = StringBuilder()
        fun walk(cur: View) {
            if (cur is TextView) {
                val t = cur.text?.toString()?.trim()
                if (!t.isNullOrEmpty()) {
                    if (sb.isNotEmpty()) sb.append('，')
                    sb.append(t)
                }
            } else if (cur is ViewGroup) {
                for (i in 0 until cur.childCount) walk(cur.getChildAt(i))
            }
        }
        walk(v)
        return sb.toString()
    }

    private fun findIdleView(v: View): View? {
        val cls = idleViewClass
        if (cls != null && cls.isInstance(v)) return v
        if (v.javaClass.simpleName == "CandidateIdleView") return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                findIdleView(v.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    // ==================== 3. ToolbarPagerAdapter 扩展模块专属页 ====================

    private fun installPagerAdapterHooks(cl: ClassLoader) {
        val adapterCls = Hooker.findClassQuiet(CLS_ADAPTER, cl) ?: run {
            L.w("ToolbarInject：找不到 ToolbarPagerAdapter")
            return
        }
        optionItemClass = Hooker.findClassQuiet(CLS_OPTION_ITEM, cl)
        pageViewHolderClass = Hooker.findClassQuiet("$CLS_ADAPTER\$PageViewHolder", cl)
        if (optionItemClass == null || pageViewHolderClass == null) {
            L.w("ToolbarInject：找不到 OptionItemView 或 PageViewHolder")
            return
        }

        Hooker.method(adapterCls, "getItemCount", emptyArray(),
            SafeHook("ToolbarInject.getItemCount", after = { p ->
                val orig = p.result as? Int ?: return@SafeHook
                origPageCount = orig
                if (!A11yState.recording) return@SafeHook
                val extra = modulePageCount()
                if (extra > 0) p.result = orig + extra
            }))

        Hooker.method(adapterCls, "getItemViewType", arrayOf(Int::class.javaPrimitiveType!!),
            SafeHook("ToolbarInject.getItemViewType", before = { p ->
                if (!A11yState.recording) return@SafeHook
                val pos = p.args[0] as? Int ?: return@SafeHook
                if (pos >= origPageCount) {
                    p.result = MODULE_PAGE_VIEW_TYPE
                }
            }))

        Hooker.method(adapterCls, "onCreateViewHolder",
            arrayOf(ViewGroup::class.java, Int::class.javaPrimitiveType!!),
            SafeHook("ToolbarInject.onCreateViewHolder", before = { p ->
                val viewType = p.args[1] as? Int ?: return@SafeHook
                if (viewType != MODULE_PAGE_VIEW_TYPE) return@SafeHook
                val parent = p.args[0] as? ViewGroup ?: return@SafeHook
                val pageView = createModulePageView(parent.context) ?: return@SafeHook
                val vhCtor = pageViewHolderClass?.getDeclaredConstructor(View::class.java) ?: return@SafeHook
                vhCtor.isAccessible = true
                p.result = vhCtor.newInstance(pageView)
            }))

        Hooker.byName(adapterCls, "onBindViewHolder",
            SafeHook("ToolbarInject.onBindViewHolder", before = { p ->
                if (p.args.size < 2) return@SafeHook
                val pos = p.args[1] as? Int ?: return@SafeHook
                if (pos < origPageCount) return@SafeHook
                p.result = null
                val holder = p.args[0] ?: return@SafeHook
                val itemView = XposedHelpers.getObjectField(holder, "itemView") as? View ?: return@SafeHook
                bindModulePage(p.thisObject, itemView, pos - origPageCount)
            }))

        L.i("ToolbarInject：已安装 ToolbarPagerAdapter 扩展页钩子")
    }

    private fun createModulePageView(ctx: Context): View? = try {
        val optCls = optionItemClass ?: return null
        val optCtor = optCls.getConstructor(Context::class.java)
        val root = FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val grid = GridLayout(ctx).apply {
            columnCount = 4
            rowCount = 2
            useDefaultMargins = false
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        for (i in 0 until 8) {
            val opt = optCtor.newInstance(ctx) as View
            opt.tag = "$TAG_OPT_PREFIX$i"
            val lp = GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED)
            }
            grid.addView(opt, lp)
        }
        root.addView(grid)
        root
    } catch (t: Throwable) {
        L.hookError("ToolbarInject.createModulePageView", t)
        null
    }

    private fun bindModulePage(adapter: Any, itemView: View, modulePageIdx: Int) {
        try {
            val root = itemView as? ViewGroup ?: return
            val grid = (0 until root.childCount).map { root.getChildAt(it) }.firstOrNull { it is GridLayout } as? GridLayout ?: return
            val ctx = itemView.context
            val density = ctx.resources.displayMetrics.density
            val scale = readAdapterScale(adapter)
            val isLand = isAdapterLandscape(adapter, ctx)

            grid.useDefaultMargins = false
            val row2TopMargin: Int
            if (isLand) {
                val s = scale.coerceAtMost(1f)
                grid.columnCount = 8
                grid.rowCount = 1
                val padH = (14.5f * density).toInt()
                val padTop = (24f * density * s).toInt()
                grid.setPadding(padH, padTop, padH, 0)
                row2TopMargin = 0
            } else {
                grid.columnCount = 4
                grid.rowCount = 2
                val padH = ((22.5f * density).toInt() * scale).toInt()
                val padV = ((12f * density).toInt() * scale).toInt()
                grid.setPadding(padH, padV, padH, padV)
                row2TopMargin = ((16f * density).toInt() * scale).toInt()
            }

            val start = modulePageIdx * 8
            for (i in 0 until 8) {
                val opt = grid.findViewWithTag<View>("$TAG_OPT_PREFIX$i") ?: continue
                val lp = opt.layoutParams as? ViewGroup.MarginLayoutParams
                if (lp != null) {
                    val tm = if (!isLand && i >= 4) row2TopMargin else 0
                    if (lp.topMargin != tm) {
                        lp.topMargin = tm
                        opt.layoutParams = lp
                    }
                }

                val tool = tools.getOrNull(start + i)
                if (tool == null) {
                    opt.visibility = View.INVISIBLE
                    opt.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    continue
                }

                opt.visibility = View.VISIBLE
                opt.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                val added = isToolEnabled(tool.key)
                XposedHelpers.callMethod(opt, "setIdKey", tool.key)
                XposedHelpers.callMethod(opt, "setLabel", tool.label)
                XposedHelpers.callMethod(opt, "setAdded", added)
                XposedHelpers.callMethod(opt, "setCompactScale", scale)
                applyOptionIcon(opt, tool.key, added)

                val toggleClick = View.OnClickListener {
                    onOptionClicked(opt, tool)
                }
                val iconId = ctx.resources.getIdentifier("iconImage", "id", ctx.packageName)
                val actionId = ctx.resources.getIdentifier("actionButton", "id", ctx.packageName)
                if (iconId != 0) opt.findViewById<View>(iconId)?.setOnClickListener(toggleClick)
                if (actionId != 0) opt.findViewById<View>(actionId)?.setOnClickListener(toggleClick)
            }
        } catch (t: Throwable) {
            L.hookError("ToolbarInject.bindModulePage", t)
        }
    }

    private fun onOptionClicked(opt: View, tool: CustomTool) {
        try {
            val next = !isToolEnabled(tool.key)
            // 统一通过 KeyboardJni.customizeToolbarButton 写入草稿（由我们的 hook 拦截）
            val jni = JniHooks.jni()
            if (jni != null) {
                XposedHelpers.callMethod(jni, "customizeToolbarButton", next, tool.key)
            } else {
                if (inEditSession) draftEnabled[tool.key] = next
                else Config.setFromIme(opt.context, tool.configKey, next)
            }
            XposedHelpers.callMethod(opt, "setAdded", next)
            applyOptionIcon(opt, tool.key, next)
            refreshToolbar()
        } catch (t: Throwable) {
            L.hookError("ToolbarInject.onOptionClicked", t)
        }
    }

    private fun applyOptionIcon(opt: View, key: String, added: Boolean) {
        val ctx = opt.context
        val iconId = ctx.resources.getIdentifier("iconImage", "id", ctx.packageName)
        val labelId = ctx.resources.getIdentifier("labelText", "id", ctx.packageName)
        val iv = if (iconId != 0) opt.findViewById<ImageView>(iconId) else null
        if (iv == null) return
        val labelTv = if (labelId != 0) opt.findViewById<TextView>(labelId) else null
        val baseColor = labelTv?.currentTextColor ?: if (isDarkTheme(ctx)) 0xFFEAEAEA.toInt() else 0xFF222222.toInt()
        val fg = if (added) {
            baseColor
        } else {
            // 未添加态稍微降低不透明度，贴合豆包原生的选中/未选中双态视觉
            (baseColor and 0x00FFFFFF) or 0x99000000.toInt()
        }
        val drawCard = iv.background == null && (iv.parent as? View)?.background == null
        iv.setImageDrawable(ToolGlyphDrawable(key, fg, ctx.resources.displayMetrics.density, inOptionCard = true, drawCard = drawCard))
    }

    private class AdapterMembers(val type: Class<*>) {
        private fun <T : java.lang.reflect.AccessibleObject> resolve(label: String, find: () -> List<T>): T? =
            try {
                val matches = find()
                if (matches.size != 1) L.w("工具栏 ${type.name} $label 候选数 ${matches.size}")
                matches.firstOrNull()?.apply { isAccessible = true }
            } catch (t: Throwable) {
                L.hookError("ToolbarInject.resolve ${type.name} $label", t)
                null
            }

        val scale = resolve("比例") {
            type.declaredMethods.filter {
                !Modifier.isStatic(it.modifiers) && it.parameterTypes.isEmpty() && it.returnType == Float::class.javaPrimitiveType
            }
        }
        val landscape = resolve("横屏") {
            type.declaredFields.filter {
                !Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers) && it.type == Boolean::class.javaPrimitiveType
            }
        }
        private val warned = HashSet<String>()
        @Synchronized fun warn(label: String, t: Throwable) {
            if (warned.add(label)) L.hookError("ToolbarInject.read ${type.name} $label", t)
        }
    }

    private val members = HashMap<Class<*>, AdapterMembers>()
    private fun members(adapter: Any) = synchronized(members) {
        members.getOrPut(adapter.javaClass) { AdapterMembers(adapter.javaClass) }
    }

    private fun readAdapterScale(adapter: Any): Float {
        val fields = members(adapter)
        return try {
            (fields.scale?.invoke(adapter) as? Float)?.takeIf { it in 0.5f..2.0f } ?: 1f
        } catch (t: Throwable) {
            fields.warn("比例", t)
            1f
        }
    }

    private fun isAdapterLandscape(adapter: Any, ctx: Context): Boolean {
        val fields = members(adapter)
        try {
            fields.landscape?.let { return it.getBoolean(adapter) }
        } catch (t: Throwable) {
            fields.warn("横屏", t)
        }
        return ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    private fun isDarkTheme(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** 供外部（如设置页命令）直接开关某个自定义工具并刷新顶部工具栏 */
    fun setToolEnabled(ctx: Context?, key: String, enabled: Boolean): Boolean {
        val tool = toolMap[key] ?: return false
        Config.setFromIme(ctx ?: ServiceHooks.appContext, tool.configKey, enabled)
        if (inEditSession) draftEnabled[key] = enabled
        refreshToolbar()
        return true
    }

    // ==================== 4. 自定义工具矢量图标绘制 ====================

    private class ToolGlyphDrawable(
        private val key: String,
        private val fgColor: Int,
        private val density: Float,
        private val inOptionCard: Boolean,
        private val drawCard: Boolean = false,
    ) : Drawable() {
        private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x16888888
            style = Paint.Style.FILL
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fgColor
            style = Paint.Style.STROKE
            strokeWidth = (if (inOptionCard) 1.9f else 1.7f) * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fgColor
            style = Paint.Style.FILL
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            val w = b.width().toFloat()
            if (drawCard) {
                val cardRect = RectF(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
                canvas.drawRoundRect(cardRect, w * 0.22f, w * 0.22f, cardPaint)
            }
            val pad = if (inOptionCard) w * 0.26f else w * 0.14f
            val rect = RectF(b.left + pad, b.top + pad, b.right - pad, b.bottom - pad)
            when (key) {
                KEY_UPPER_GESTURE -> drawUpperGesture(canvas, rect)
                KEY_EDIT_MODE -> drawEditMode(canvas, rect)
                KEY_CLEAR_TEXT -> drawClearText(canvas, rect)
                KEY_UNDO -> drawUndo(canvas, rect)
                KEY_DELETE_RIGHT -> drawDeleteRight(canvas, rect)
                KEY_READ_EDITOR -> drawReadEditor(canvas, rect)
                KEY_PASTE -> drawPaste(canvas, rect)
                else -> canvas.drawCircle(rect.centerX(), rect.centerY(), minOf(rect.width(), rect.height()) * 0.35f, strokePaint)
            }
        }

        /** 清空编辑框：简洁垃圾桶轮廓 */
        private fun drawClearText(canvas: Canvas, rect: RectF) {
            val w = rect.width()
            val h = rect.height()
            val lidY = rect.top + h * 0.24f
            canvas.drawLine(rect.left + w * 0.1f, lidY, rect.right - w * 0.1f, lidY, strokePaint)
            canvas.drawLine(rect.left + w * 0.36f, rect.top + h * 0.1f, rect.right - w * 0.36f, rect.top + h * 0.1f, strokePaint)
            val body = RectF(rect.left + w * 0.2f, lidY, rect.right - w * 0.2f, rect.bottom - h * 0.08f)
            canvas.drawRoundRect(body, 2f * density, 2f * density, strokePaint)
            canvas.drawLine(rect.centerX() - w * 0.12f, lidY + h * 0.18f, rect.centerX() - w * 0.12f, body.bottom - h * 0.16f, strokePaint)
            canvas.drawLine(rect.centerX() + w * 0.12f, lidY + h * 0.18f, rect.centerX() + w * 0.12f, body.bottom - h * 0.16f, strokePaint)
        }

        /** 撤销：向左回弯箭头 */
        private fun drawUndo(canvas: Canvas, rect: RectF) {
            val w = rect.width()
            val h = rect.height()
            val arc = RectF(rect.left + w * 0.18f, rect.top + h * 0.32f, rect.right - w * 0.08f, rect.bottom - h * 0.06f)
            canvas.drawArc(arc, -90f, 180f, false, strokePaint)
            val topY = arc.top
            val leftX = rect.left + w * 0.16f
            canvas.drawLine(leftX, topY, arc.centerX(), topY, strokePaint)
            val head = w * 0.2f
            canvas.drawLine(leftX + head, topY - head * 0.85f, leftX, topY, strokePaint)
            canvas.drawLine(leftX + head, topY + head * 0.85f, leftX, topY, strokePaint)
        }

        /** 向后删除：尖头朝右的删除键 + 中间叉号 */
        private fun drawDeleteRight(canvas: Canvas, rect: RectF) {
            val w = rect.width()
            val h = rect.height()
            val topY = rect.top + h * 0.2f
            val botY = rect.bottom - h * 0.2f
            val midY = rect.centerY()
            val tipX = rect.right - w * 0.06f
            val neckX = rect.right - w * 0.32f
            val leftX = rect.left + w * 0.08f
            val path = android.graphics.Path().apply {
                moveTo(leftX, topY)
                lineTo(neckX, topY)
                lineTo(tipX, midY)
                lineTo(neckX, botY)
                lineTo(leftX, botY)
                close()
            }
            canvas.drawPath(path, strokePaint)
            val cx = leftX + (neckX - leftX) * 0.52f
            val arm = minOf(w, h) * 0.14f
            canvas.drawLine(cx - arm, midY - arm, cx + arm, midY + arm, strokePaint)
            canvas.drawLine(cx - arm, midY + arm, cx + arm, midY - arm, strokePaint)
        }

        /** 朗读编辑框：喇叭 + 两条声波弧 */
        private fun drawReadEditor(canvas: Canvas, rect: RectF) {
            val w = rect.width()
            val h = rect.height()
            val midY = rect.centerY()
            val path = android.graphics.Path().apply {
                moveTo(rect.left + w * 0.1f, midY - h * 0.16f)
                lineTo(rect.left + w * 0.28f, midY - h * 0.16f)
                lineTo(rect.left + w * 0.52f, rect.top + h * 0.16f)
                lineTo(rect.left + w * 0.52f, rect.bottom - h * 0.16f)
                lineTo(rect.left + w * 0.28f, midY + h * 0.16f)
                lineTo(rect.left + w * 0.1f, midY + h * 0.16f)
                close()
            }
            canvas.drawPath(path, strokePaint)
            val r1 = w * 0.2f
            val r2 = w * 0.35f
            val cx = rect.left + w * 0.54f
            canvas.drawArc(RectF(cx - r1, midY - r1, cx + r1, midY + r1), -45f, 90f, false, strokePaint)
            canvas.drawArc(RectF(cx - r2, midY - r2, cx + r2, midY + r2), -45f, 90f, false, strokePaint)
        }

        /** 粘贴：剪贴板底板 + 顶部夹子 + 文档横线 */
        private fun drawPaste(canvas: Canvas, rect: RectF) {
            val w = rect.width()
            val h = rect.height()
            val board = RectF(rect.left + w * 0.16f, rect.top + h * 0.18f, rect.right - w * 0.16f, rect.bottom - h * 0.08f)
            canvas.drawRoundRect(board, 2.5f * density, 2.5f * density, strokePaint)
            val clip = RectF(rect.left + w * 0.34f, rect.top + h * 0.06f, rect.right - w * 0.34f, rect.top + h * 0.24f)
            canvas.drawRoundRect(clip, 1.5f * density, 1.5f * density, strokePaint)
            canvas.drawLine(board.left + w * 0.14f, board.top + h * 0.28f, board.right - w * 0.14f, board.top + h * 0.28f, strokePaint)
            canvas.drawLine(board.left + w * 0.14f, board.top + h * 0.48f, board.right - w * 0.22f, board.top + h * 0.48f, strokePaint)
        }

        /** 上半屏手势：圆角屏幕框 + 上下分界线 + 上半屏十字滑动手势 */
        private fun drawUpperGesture(canvas: Canvas, rect: RectF) {
            val r = 3f * density
            canvas.drawRoundRect(rect, r, r, strokePaint)
            val midY = rect.top + rect.height() * 0.58f
            canvas.drawLine(rect.left, midY, rect.right, midY, strokePaint)
            val cx = rect.centerX()
            val cy = (rect.top + midY) * 0.5f
            val arm = minOf(rect.width(), midY - rect.top) * 0.28f
            canvas.drawLine(cx - arm, cy, cx + arm, cy, strokePaint)
            canvas.drawLine(cx, cy - arm, cx, cy + arm, strokePaint)
        }

        /** 编辑模式：四向光标导航 + 中间竖线光标 */
        private fun drawEditMode(canvas: Canvas, rect: RectF) {
            val cx = rect.centerX()
            val cy = rect.centerY()
            val rx = rect.width() * 0.44f
            val ry = rect.height() * 0.44f
            val arrow = minOf(rx, ry) * 0.36f
            // 中间文本光标 I
            val barH = ry * 0.48f
            canvas.drawLine(cx, cy - barH, cx, cy + barH, strokePaint)
            // 左箭头 <
            canvas.drawLine(cx - rx + arrow, cy - arrow, cx - rx, cy, strokePaint)
            canvas.drawLine(cx - rx + arrow, cy + arrow, cx - rx, cy, strokePaint)
            // 右箭头 >
            canvas.drawLine(cx + rx - arrow, cy - arrow, cx + rx, cy, strokePaint)
            canvas.drawLine(cx + rx - arrow, cy + arrow, cx + rx, cy, strokePaint)
            // 上箭头 ^
            canvas.drawLine(cx - arrow, cy - ry + arrow, cx, cy - ry, strokePaint)
            canvas.drawLine(cx + arrow, cy - ry + arrow, cx, cy - ry, strokePaint)
            // 下箭头 v
            canvas.drawLine(cx - arrow, cy + ry - arrow, cx, cy + ry, strokePaint)
            canvas.drawLine(cx + arrow, cy + ry - arrow, cx, cy + ry, strokePaint)
        }

        override fun setAlpha(alpha: Int) {
            strokePaint.alpha = alpha
            fillPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            strokePaint.colorFilter = colorFilter
            fillPaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
