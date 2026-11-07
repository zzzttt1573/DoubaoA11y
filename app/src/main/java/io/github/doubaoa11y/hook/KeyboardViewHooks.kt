package io.github.doubaoa11y.hook

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Field
import java.util.Collections
import java.util.WeakHashMap

/**
 * KeyboardView（C++ 自绘键盘）的 hook：
 * 记录原生层回调 Java 的每一次绘制，建立键位表；记录触摸和悬停。
 */
object KeyboardViewHooks {
    const val CLS = "com.bytedance.android.input.keyboard.KeyboardView"

    var kbClass: Class<*>? = null
        private set
    private var fDirty: Field? = null
    private var fWindowName: Field? = null
    private var fNativeId: Field? = null

    /** KeyboardView 实例 → 绘制项表。只在主线程读写。 */
    private val tables = WeakHashMap<Any, DrawTable>()
    private val typefaceNames = Collections.synchronizedMap(WeakHashMap<Typeface, String>())
    private val drawTextDepth = object : ThreadLocal<IntArray>() {
        override fun initialValue() = IntArray(1)
    }
    private val main = Handler(Looper.getMainLooper())
    private var dumpSeq = 0
    private val dumpRunnable = Runnable { dumpChanged() }

    private var lastHoverKey: Key? = null
    private var lastHoverLabel: String? = null

    private val I = Int::class.javaPrimitiveType!!
    private val J = Long::class.javaPrimitiveType!!
    private val Z = Boolean::class.javaPrimitiveType!!
    private val F = Float::class.javaPrimitiveType!!

    fun install(cl: ClassLoader) {
        val c = Hooker.findClass(CLS, cl) ?: return
        kbClass = c
        fDirty = Hooker.field(c, "mDirty")
        fWindowName = Hooker.field(c, "mWindowName")
        fNativeId = Hooker.field(c, "mNativeViewId")

        // ---- 生命周期 ----
        Hooker.method(c, "CreateView", arrayOf(String::class.java, J, I, I), SafeHook("CreateView", after = { p ->
            L.i("CreateView 窗口=${p.args[0]} nativeId=${p.args[1]} 宽=${p.args[2]} 高=${p.args[3]}")
        }))
        Hooker.method(c, "SetNativeId", arrayOf(J, String::class.java), SafeHook("SetNativeId", after = { p ->
            L.i("SetNativeId 窗口=${p.args[1]} nativeId=${p.args[0]} 视图=${p.thisObject.hashCode()}")
            synchronized(tables) { tables[p.thisObject]?.windowName = p.args[1] as? String ?: "?" }
        }))
        Hooker.method(c, "showView", arrayOf(Z), SafeHook("showView", before = { p ->
            L.i("showView 窗口=${windowName(p.thisObject)} 显示=${p.args[0]}")
        }))
        Hooker.method(c, "onSizeChanged", arrayOf(I, I, I, I), SafeHook("onSizeChanged", after = { p ->
            L.i("onSizeChanged 窗口=${windowName(p.thisObject)} ${p.args[2]}x${p.args[3]} → ${p.args[0]}x${p.args[1]}")
        }))
        Hooker.method(c, "setAsrLongPressView", arrayOf(Hooker.findClassQuiet("com.bytedance.android.input.speech.view.AsrLongPressView", cl) ?: Any::class.java),
            SafeHook("setAsrLongPressView", after = { p ->
                L.i("setAsrLongPressView 窗口=${windowName(p.thisObject)} view=${p.args[0]}")
            }))

        // ---- 绘制 ----
        Hooker.method(c, "onDraw", arrayOf(Canvas::class.java), SafeHook("onDraw",
            before = { p -> onDrawBefore(p.thisObject) },
            after = { p -> onDrawAfter(p.thisObject) }))

        // 公开的 drawText(…color…) 和 drawLinerGradientText 最终都调用私有的 9 参数 drawText。
        // 按名称 hook 全部 drawText，用嵌套深度只记录最外层一次，参数按类型取，版本变动也能用。
        Hooker.byName(c, "drawText", SafeHook("drawText",
            before = { p ->
                val d = drawTextDepth.get()!!
                d[0]++
                if (d[0] == 1) recordText(p.thisObject, p.args, "drawText")
            },
            after = { _ -> drawTextDepth.get()!![0]-- }))
        Hooker.byName(c, "drawLinerGradientText", SafeHook("drawLinerGradientText",
            before = { p ->
                val d = drawTextDepth.get()!!
                d[0]++
                if (d[0] == 1) recordText(p.thisObject, p.args, "drawLinerGradientText")
            },
            after = { _ -> drawTextDepth.get()!![0]-- }))
        Hooker.byName(c, "fillRect", SafeHook("fillRect", before = { p -> recordRect(p.thisObject, p.args) }))
        Hooker.byName(c, "drawImage", SafeHook("drawImage", before = { p -> recordImage(p.thisObject, p.args, "图片") }))
        Hooker.byName(c, "drawGif", SafeHook("drawGif", before = { p -> recordImage(p.thisObject, p.args, "动图") }))
        // 预览气泡：之后画的大字属于气泡，不能算进下面的键
        Hooker.byName(c, "drawTouchTipBubble", SafeHook("drawTouchTipBubble", before = { p ->
            if (!A11yState.recording) return@SafeHook
            table(p.thisObject).inPopup = true
            if (Config.b("logOtherDraw")) L.v("drawTouchTipBubble 窗口=${windowName(p.thisObject)} ${Fmt.args(p.args)}")
        }))
        for (name in listOf("drawRect", "drawCircle", "drawLine", "drawShadow",
            "playLottieAnimation", "stopLottieAnimation", "invalidateRect")) {
            Hooker.byName(c, name, SafeHook(name, before = { p ->
                if (A11yState.recording && Config.b("logOtherDraw")) {
                    L.v("$name 窗口=${windowName(p.thisObject)} ${Fmt.args(p.args)}")
                }
            }))
        }

        // 字体：Typeface 本身没有名字，在创建时记下名字
        Hooker.method(c, "createTypeFace", arrayOf(String::class.java, Z, I), SafeHook("createTypeFace", after = { p ->
            val tf = p.result as? Typeface ?: return@SafeHook
            val name = "${p.args[0]}/w${p.args[2]}${if (p.args[1] == true) "斜" else ""}"
            typefaceNames[tf] = name
            L.i("createTypeFace $name")
        }))

        // ---- 触摸 ----
        Hooker.method(c, "onTouchEvent", arrayOf(MotionEvent::class.java), SafeHook("onTouchEvent", before = { p ->
            if (!A11yState.recording || !Config.b("logTouch")) return@SafeHook
            val ev = p.args[0] as MotionEvent
            val msg = "触摸 窗口=${windowName(p.thisObject)} ${Fmt.action(ev.action)} x=${ev.x.toInt()} y=${ev.y.toInt()} 键=「${keyLabelAt(p.thisObject, ev.x, ev.y)}」"
            if (ev.actionMasked == MotionEvent.ACTION_MOVE) L.v(msg) else L.i(msg)
        }))

        // 悬停：KeyboardView 没有重写 dispatchHoverEvent，hook ViewGroup 的再按类型过滤
        Hooker.method(ViewGroup::class.java, "dispatchHoverEvent", arrayOf(MotionEvent::class.java),
            SafeHook("dispatchHoverEvent", before = { p ->
                val v = p.thisObject
                if (!A11yState.active) return@SafeHook
                val ev = p.args[0] as MotionEvent
                if ((v as View).parent !is View) {
                    if (Config.b("logTouch") && ev.actionMasked != MotionEvent.ACTION_HOVER_MOVE)
                        L.i("根悬停 ${Fmt.action(ev.action)} x=${ev.x.toInt()} y=${ev.y.toInt()} t=${ev.eventTime}")
                    // 键盘窗口根视图：长按删除或语音时由它接管（语音界面会盖住键盘）
                    if (VirtualKeys.onRootHover(v, ev)) p.result = true
                    return@SafeHook
                }
                if (c.isInstance(v)) {
                    onHover(v, ev)
                    // 阶段 2：虚拟按键接管悬停，豆包原来的处理（把整个键盘当一个控件）不再执行
                    VirtualKeys.onHover(v as View, ev)?.let { p.result = it }
                }
            }))

        // 触摸浏览关着：手指触摸直接到键盘窗口，由模块当成摸和抬手处理
        Hooker.method(ViewGroup::class.java, "dispatchTouchEvent", arrayOf(MotionEvent::class.java),
            SafeHook("dispatchTouchEvent", before = { p ->
                if (Config.b("logTouch") && (p.thisObject as View).parent !is View) {
                    val e = p.args[0] as MotionEvent
                    if (e.actionMasked != MotionEvent.ACTION_MOVE)
                        L.i("根触摸 ${Fmt.action(e.action)} x=${e.x.toInt()} y=${e.y.toInt()} t=${e.eventTime} 走触摸=${VirtualKeys.touchMode} 天坦=${TatansBridge.requested}")
                }
                if (!VirtualKeys.touchMode || VirtualKeys.injecting) return@SafeHook
                val v = p.thisObject as View
                if (v.parent is View) return@SafeHook
                val imeRoot = try {
                    ViewA11yHooks.service?.window?.window?.decorView
                } catch (_: Throwable) {
                    null
                }
                if (v !== imeRoot) return@SafeHook
                if (VirtualKeys.onRootTouch(v, p.args[0] as MotionEvent)) p.result = true
            }))

        // 虚拟按键节点
        Hooker.method(View::class.java, "getAccessibilityNodeProvider", arrayOf(),
            SafeHook("getAccessibilityNodeProvider", after = { p ->
                val v = p.thisObject
                if (c.isInstance(v)) VirtualKeys.providerFor(v as View)?.let { p.result = it }
            }))

        A11yState.onChange { on -> if (on && isEmpty()) requestFullRedraw("模块启用") }
    }

    // ---------------- 绘制记录 ----------------

    fun windowName(v: Any): String = try {
        fWindowName?.get(v) as? String ?: "?"
    } catch (_: Throwable) {
        "?"
    }

    private fun table(v: Any): DrawTable {
        val t = synchronized(tables) { tables.getOrPut(v) { DrawTable(windowName(v)) } }
        val view = v as View
        t.width = view.width
        t.height = view.height
        return t
    }

    private fun onDrawBefore(v: Any) {
        if (!A11yState.recording) return
        val t = table(v)
        val d = fDirty?.get(v) as? Rect ?: return
        if (d.isEmpty) return
        t.beginFrame(Rect(d))
        if (Config.b("logOtherDraw")) L.v("onDraw 开始 窗口=${t.windowName} 脏区=${Fmt.rect(d)}")
        inOnDraw = true
    }

    private var inOnDraw = false

    private fun onDrawAfter(v: Any) {
        inOnDraw = false
        if (!A11yState.recording) return
        val t = table(v)
        if (Config.b("logOtherDraw")) L.v("onDraw 结束 窗口=${t.windowName} 绘制项=${t.items.size}")
        if (t.changed) VirtualKeys.onTableChanged(v as View)
        if (t.changed && Config.b("dumpAfterDraw")) {
            // 等绘制停下来 300 毫秒再输出，避免动画期间刷屏
            main.removeCallbacks(dumpRunnable)
            main.postDelayed(dumpRunnable, 300)
        }
    }

    private fun noteOutside(t: DrawTable, what: String) {
        if (!inOnDraw) {
            t.drawsOutsideOnDraw++
            if (t.drawsOutsideOnDraw <= 20 || t.drawsOutsideOnDraw % 500 == 0L) {
                L.i("注意：$what 发生在 onDraw 之外（第 ${t.drawsOutsideOnDraw} 次） 窗口=${t.windowName}")
            }
        }
    }

    private fun recordText(v: Any, args: Array<Any?>, src: String) {
        if (!A11yState.recording) return
        val text = args.firstOrNull { it is String } as? String ?: return
        val tf = args.firstOrNull { it is Typeface } as? Typeface
        val rect = args.lastOrNull { it is Rect } as? Rect ?: return
        val ints = args.filterIsInstance<Int>()
        val size = ints.getOrNull(0) ?: 0
        val flags = ints.getOrNull(1) ?: 0
        // 公开的 drawText 有颜色参数（第 3 个 int）；渐变文字和私有方法没有
        val color = if (src == "drawText" && ints.size >= 3) ints[2] else 0
        val font = if (tf == null) "默认" else (typefaceNames[tf] ?: "未知字体@${Integer.toHexString(System.identityHashCode(tf))}")
        val t = table(v)
        noteOutside(t, src)
        val item = DrawItem(DrawItem.Kind.TEXT, Rect(rect), text, font, size, flags, color)
        t.add(item)
        if (Config.b("logDraw")) L.v("$src 窗口=${t.windowName} ${item.describe()}")
    }

    private fun recordRect(v: Any, args: Array<Any?>) {
        if (!A11yState.recording) return
        val ints = args.filterIsInstance<Int>()
        if (ints.size < 5) return
        val r = Rect(ints[0], ints[1], ints[2], ints[3])
        val radius = args.firstOrNull { it is Float } as? Float ?: 0f
        val t = table(v)
        noteOutside(t, "fillRect")
        val item = DrawItem(DrawItem.Kind.RECT, r, color = ints[4], radius = radius)
        t.add(item)
        if (Config.b("logRect")) L.v("fillRect 窗口=${t.windowName} ${item.describe()} 参数=${Fmt.args(args)}")
    }

    private fun recordImage(v: Any, args: Array<Any?>, what: String) {
        if (!A11yState.recording) return
        val dst = args.lastOrNull { it is Rect } as? Rect ?: return
        val bmp = args.firstOrNull { it is Bitmap } as? Bitmap
        val extra = if (bmp != null) "$what ${bmp.width}x${bmp.height} id=${Integer.toHexString(System.identityHashCode(bmp))}" else what
        val t = table(v)
        noteOutside(t, what)
        val item = DrawItem(DrawItem.Kind.IMAGE, Rect(dst), extra = extra)
        t.add(item)
        if (Config.b("logOtherDraw")) L.v("$what 窗口=${t.windowName} ${item.describe()}")
    }

    // ---------------- 悬停和查键 ----------------

    private fun slop(v: Any): Float = 12f * ((v as View).resources.displayMetrics.density)

    private fun keyLabelAt(v: Any, x: Float, y: Float): String {
        val t = synchronized(tables) { tables[v] } ?: return "（无键位表）"
        return t.keyAt(x, y, slop(v))?.speakable() ?: "（无）"
    }

    private fun onHover(v: Any, ev: MotionEvent) {
        val t = synchronized(tables) { tables[v] }
        val key = t?.keyAt(ev.x, ev.y, slop(v))
        val label = key?.speakable()
        val changed = key?.rect != lastHoverKey?.rect || label != lastHoverLabel
        if (Config.b("logTouch")) {
            val msg = "悬停 窗口=${windowName(v)} ${Fmt.action(ev.action)} x=${ev.x.toInt()} y=${ev.y.toInt()} 键=「${label ?: "（无）"}」${if (key != null) Fmt.rect(key.rect) else ""}"
            if (ev.actionMasked == MotionEvent.ACTION_HOVER_MOVE && !changed) L.v(msg) else L.i(msg)
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                if (changed) {
                    lastHoverKey = key
                    lastHoverLabel = label
                    if (Config.b("debugSpeak") && !VirtualKeys.enabled && !label.isNullOrBlank()) Speaker.speak(label)
                }
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                lastHoverKey = null
                lastHoverLabel = null
            }
        }
    }

    // ---------------- 输出键位表 ----------------

    private fun dumpChanged() {
        val list = synchronized(tables) { tables.entries.map { it.key to it.value } }
        for ((v, t) in list) {
            if (!t.changed) continue
            t.changed = false
            dumpOne(v, t, "重绘后", skipIfSame = true)
        }
    }

    private fun dumpOne(v: Any, t: DrawTable, reason: String, skipIfSame: Boolean) {
        val view = v as View
        t.width = view.width
        t.height = view.height
        val body = t.buildDump()
        if (skipIfSame && body == t.lastDumpText) return
        t.lastDumpText = body
        dumpSeq++
        L.i("==== 键位表 #$dumpSeq（$reason）可见=${view.isShown} 帧=${t.frames} onDraw外绘制=${t.drawsOutsideOnDraw} ====\n$body==== 键位表 #$dumpSeq 结束 ====")
    }

    /** 立即输出所有键盘视图的键位表（设置页“立即输出键位表”） */
    fun dumpAll(reason: String) {
        main.post {
            try {
                val list = synchronized(tables) { tables.entries.map { it.key to it.value } }
                if (list.isEmpty()) L.i("还没有键位表：请先弹出键盘并确认模块已启用")
                for ((v, t) in list) dumpOne(v, t, reason, skipIfSame = false)
            } catch (t: Throwable) {
                L.e("输出键位表失败", t)
            }
        }
    }

    /** 让原生层把每个键盘视图整个重画一遍，用于模块刚启用时补齐键位表 */
    fun requestFullRedraw(why: String) {
        main.post {
            try {
                val views = LinkedHashSet<Any>()
                val c = kbClass ?: return@post
                (XposedHelpers.getStaticObjectField(c, "sViewManager") as? Map<*, *>)?.values?.filterNotNull()?.let { views.addAll(it) }
                synchronized(tables) { views.addAll(tables.keys) }
                for (v in views) {
                    val view = v as View
                    if (view.width <= 0 || view.height <= 0) continue
                    table(v).clear()
                    XposedHelpers.callMethod(v, "invalidateRect", Rect(0, 0, view.width, view.height))
                }
                L.i("请求整屏重绘（$why），键盘视图 ${views.size} 个")
            } catch (t: Throwable) {
                L.e("请求重绘失败", t)
            }
        }
    }

    fun tableOf(v: Any): DrawTable? = synchronized(tables) { tables[v] }

    fun isEmpty(): Boolean = synchronized(tables) { tables.values.all { it.items.isEmpty() } }

    fun status(): String {
        val list = synchronized(tables) { tables.entries.map { it.key to it.value } }
        return "键盘视图 ${list.size} 个：" + list.joinToString("；") { (v, t) ->
            "${t.windowName} ${t.width}x${t.height} 绘制项${t.items.size} 可见=${(v as View).isShown} id=${try { fNativeId?.get(v) } catch (_: Throwable) { "?" }}"
        }
    }
}
