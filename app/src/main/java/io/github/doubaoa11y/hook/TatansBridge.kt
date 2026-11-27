package io.github.doubaoa11y.hook

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Rect
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityManager
import io.github.doubaoa11y.ConfigSpec

/**
 * 天坦读屏（com.android.tback）的输入法接口：把键盘区域设为“触摸穿透”，
 * 这块区域的触摸不走触摸浏览，直接交给输入法，模块按“触摸浏览关闭”的方式处理（按下滑动朗读、抬手输入），
 * 快速打字不会被读屏当成双击。打开菜单、剪贴板、定制工具栏这些用读屏焦点的界面时撤掉穿透。
 *
 * 和其他读屏的兼容：
 * - 只在天坦读屏是正在运行的读屏时连接；换成别的读屏就撤掉穿透、断开。
 * - 模块不靠“已经设了穿透”来判断怎么处理触摸，而是看实际收到什么：
 *   收到手指触摸就按触摸浏览关闭处理，收到悬停就按原来的触摸浏览处理。穿透没生效也不会摸不到键。
 *
 * 接口是按天坦读屏对外提供的绑定动作和方法编号调用的，没有用天坦的代码。
 *
 * 解说（com.nirenr.talkman）也支持同样的效果，但接口不同：它对外开放了一个广播，
 * 输入法显示时发 ACTION_INPUTMETHOD_SHOW 带上键盘区域，解说就把这块设为触摸浏览穿透。
 * 收起时不发 ACTION_INPUTMETHOD_HIDE：那样解说会把记着的区域清成“没有”，之后改用它自己的默认穿透区域，
 * 收起键盘后屏幕上按住就成了长按。像天坦输入法那样，收起时也发 SHOW，只是给一个空区域（左边 -1，其余 0），
 * 解说记着这个空区域，每次触摸结束重新设一遍，就不会穿透了。解说接天坦输入法用的是另一套（解说主动绑定 com.tatans.inputmethod），
 * 只认天坦输入法的包名，豆包用不了，所以这里走广播。
 */
object TatansBridge {
    private const val PKG = "com.android.tback"
    private const val ACTION = "tatans.intent.action.IME_SERVICE"
    private const val DESCRIPTOR = "net.tatans.soundback.ITatansImeService"
    private const val CONN_DESCRIPTOR = "net.tatans.soundback.ITatansImeConnection"
    private const val TX_ON_INPUT_MODE_CHANGED = 2
    private const val TX_SET_PASS_THROUGH = 3
    private const val TX_PERFORM_ACTION = 9
    private const val TX_SET_IME_CONNECTION = 10
    private const val ACTION_IME_EXPANDED = 10000
    private const val ACTION_IME_COLLAPSED = 10001
    private const val CONN_TX_GET_CLIP = 4
    private const val CONN_TX_IS_SHOWN = 5

    private const val TALKMAN = "com.nirenr.talkman"
    private const val TALKMAN_SHOW = "com.nirenr.talkman.ACTION_INPUTMETHOD_SHOW"
    private const val TALKMAN_RECT = "com.nirenr.talkman.EXTRA_RECT_DATA"

    private val main = Handler(Looper.getMainLooper())
    private val proxyUri: Uri = Uri.parse("content://${ConfigSpec.SETTINGS_AUTHORITY}/config")
    private val clientToken = Binder()
    @Volatile
    private var proxyHandler: Handler? = null
    private var remote: IBinder? = null
    private var ctx: Context? = null
    private var am: AccessibilityManager? = null
    private var binding = false
    private var bound = false
    @Volatile
    private var viaProxy = false
    @Volatile
    private var proxyConnecting = false
    private var retryAfter = 0L
    private val current = Rect()
    /** 当前已向天坦读屏请求的事件屏蔽状态 */
    private var currentSuppressed = false
    /** 天坦读屏已生效全屏事件屏蔽（16/8192 由读屏静默丢弃，改由模块接管光标/选字/打字回声） */
    @Volatile
    var readerEventsSuppressed: Boolean = false
        private set
    /** 已经发给解说的穿透区域 */
    private val talkmanCurrent = Rect()
    /** 键盘窗口显示着（收起动画期间也算收起） */
    private var visible = false

    /** 已经请求天坦把键盘区域设为穿透（手指触摸可能直接到输入法） */
    val requested: Boolean get() = ((remote != null || viaProxy) && !current.isEmpty) || !talkmanCurrent.isEmpty

    /** 提供给天坦读屏挂 linkToDeath 的回调对象：进程意外退出时天坦读屏自动清零屏蔽状态 */
    private val imeConnBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(CONN_DESCRIPTOR)
                return true
            }
            if (code in 1..CONN_TX_IS_SHOWN) {
                data.enforceInterface(CONN_DESCRIPTOR)
                reply?.writeNoException()
                when (code) {
                    CONN_TX_GET_CLIP -> reply?.writeString("")
                    CONN_TX_IS_SHOWN -> reply?.writeInt(if (readerEventsSuppressed) 1 else 0)
                }
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private fun worker(): Handler {
        proxyHandler?.let { return it }
        synchronized(this) {
            proxyHandler?.let { return it }
            val t = HandlerThread("DoubaoA11yReaderBridge").apply { start() }
            return Handler(t.looper).also { proxyHandler = it }
        }
    }

    private fun canSeePackage(c: Context, pkg: String): Boolean {
        if (Build.SDK < 30) return true
        return try {
            c.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remote = service
            binding = false
            if (service != null) transactSetImeConnection(service)
            L.i("已连接天坦读屏的输入法接口")
            main.post { update("连接后") }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            current.setEmpty()
            currentSuppressed = false
            readerEventsSuppressed = false
            L.i("天坦读屏的输入法接口断开")
        }

        override fun onBindingDied(name: ComponentName?) {
            onServiceDisconnected(name)
            disconnect()
        }
    }

    fun init(c: Context) {
        if (ctx != null) return
        ctx = c
        try {
            val m = c.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            am = m
            // 换读屏、开关触摸浏览时重新判断
            m.addAccessibilityStateChangeListener({ main.postDelayed({ refresh("读屏开关变化") }, 300) }, main)
            m.addTouchExplorationStateChangeListener({ main.postDelayed({ refresh("触摸浏览变化") }, 300) }, main)
            if (Build.SDK >= 33) m.addAccessibilityServicesStateChangeListener({ _ -> main.postDelayed({ refresh("读屏服务变化") }, 300) })
        } catch (t: Throwable) {
            L.hookError("TatansBridge.init", t)
        }
        refresh("初始化")
        // 豆包进程重启前可能给解说设过穿透，还没来得及撤掉：启动时先撤一次
        try {
            if (talkmanWanted()) sendTalkman(Rect(), "初始化")
        } catch (t: Throwable) {
            L.hookError("TatansBridge.initTalkman", t)
        }
    }

    private object Build {
        val SDK = android.os.Build.VERSION.SDK_INT
    }

    /** 这个包的读屏是不是正在运行 */
    private fun running(pkg: String): Boolean {
        try {
            val list = am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            if (list != null && list.any { it.resolveInfo?.serviceInfo?.packageName == pkg }) return true
        } catch (_: Throwable) {
        }
        // 软件包可见性可能让上面查不到，再看系统设置里启用的服务
        return try {
            val s = Settings.Secure.getString(ctx?.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            s.split(':').any { it.startsWith("$pkg/") }
        } catch (_: Throwable) {
            false
        }
    }

    private fun wanted(): Boolean = (Config.b("tatansFast") || FullscreenMode.active) && A11yState.active && running(PKG)

    private fun talkmanWanted(): Boolean = (Config.b("tatansFast") || FullscreenMode.active) && A11yState.active && running(TALKMAN)

    /** 读屏状态、设置变化后：该连就连，不该连就撤掉穿透并断开 */
    fun refresh(why: String) {
        try {
            if (wanted()) {
                connect()
                update(why)
            } else if (bound || binding || viaProxy || proxyConnecting) {
                L.i("天坦读屏不在运行或快速打字已关（$why），撤掉穿透")
                send(Rect(), why)
                disconnect()
            }
            if (talkmanWanted()) {
                update(why)
            } else if (!talkmanCurrent.isEmpty) {
                L.i("解说不在运行或快速打字已关（$why），撤掉穿透")
                sendTalkman(Rect(), why)
            }
        } catch (t: Throwable) {
            L.hookError("TatansBridge.refresh", t)
        }
    }

    private fun connect() {
        val c = ctx ?: return
        if (remote != null || binding || viaProxy || proxyConnecting) return
        val now = SystemClock.uptimeMillis()
        if (now < retryAfter) return
        try {
            binding = c.bindService(Intent(ACTION).setPackage(PKG), conn, Context.BIND_AUTO_CREATE)
            bound = binding
            if (binding) {
                L.i("正在连接天坦读屏的输入法接口")
                return
            }
            try {
                c.unbindService(conn)
            } catch (_: Throwable) {
            }
        } catch (t: Throwable) {
            binding = false
            L.e("连接天坦读屏失败", t)
        }
        // 直连失败（常见于豆包 1.5.6 等高 targetSdk 缺少 <queries> 被 Android 11+ 包可见性拦截）：
        // 自动改由模块进程 SettingsProvider 代理连接天坦读屏输入法接口。
        connectViaProxy(c)
    }

    private fun connectViaProxy(c: Context) {
        if (proxyConnecting || viaProxy) return
        proxyConnecting = true
        val initSuppress = wantTatansSuppress()
        val initRect = if (initSuppress) tatansFullRect(c, computeWant()) else computeWant()
        worker().post {
            val res = try {
                c.contentResolver.call(
                    proxyUri,
                    "tatansPassThrough",
                    null,
                    Bundle().apply {
                        putInt("left", initRect.left)
                        putInt("top", initRect.top)
                        putInt("right", initRect.right)
                        putInt("bottom", initRect.bottom)
                        putBinder("token", clientToken)
                        putBoolean("disconnect", false)
                        putBoolean("suppress", initSuppress)
                    }
                )
            } catch (t: Throwable) {
                L.e("模块代理连接天坦读屏失败", t)
                null
            }
            val okBound = res?.getBoolean("bound", false) == true
            val applied = res?.getBoolean("applied", false) == true
            val suppressed = applied && res?.getBoolean("suppressed", false) == true
            main.post {
                proxyConnecting = false
                if (!wanted()) {
                    readerEventsSuppressed = false
                    currentSuppressed = false
                    if (okBound) disconnectProxy(c)
                    return@post
                }
                if (okBound) {
                    viaProxy = true
                    retryAfter = 0L
                    if (applied) {
                        current.set(initRect)
                        currentSuppressed = suppressed
                        readerEventsSuppressed = suppressed
                    } else {
                        current.setEmpty()
                        currentSuppressed = false
                        readerEventsSuppressed = false
                    }
                    val detail = if (applied && !initRect.isEmpty) "（穿透=${Fmt.rect(initRect)}，屏蔽=${suppressed}）" else ""
                    L.i("已通过模块代理连接天坦读屏的输入法接口$detail")
                    update("代理连接后")
                    if (!applied) main.postDelayed({ if (viaProxy && wanted()) update("代理连接确认") }, 150)
                } else {
                    viaProxy = false
                    currentSuppressed = false
                    readerEventsSuppressed = false
                    retryAfter = SystemClock.uptimeMillis() + 3000L
                    L.i("天坦读屏没有提供输入法接口")
                }
            }
        }
    }

    private fun disconnectProxy(c: Context) {
        worker().post {
            try {
                c.contentResolver.call(
                    proxyUri,
                    "tatansPassThrough",
                    null,
                    Bundle().apply {
                        putBoolean("disconnect", true)
                        putBoolean("suppress", false)
                    }
                )
            } catch (_: Throwable) {
            }
        }
    }

    private fun disconnect() {
        val oldRemote = remote
        val wasSuppressed = currentSuppressed || readerEventsSuppressed
        binding = false
        remote = null
        val wasProxy = viaProxy || proxyConnecting
        viaProxy = false
        proxyConnecting = false
        current.setEmpty()
        currentSuppressed = false
        readerEventsSuppressed = false
        if (oldRemote != null && wasSuppressed) resetTatansSuppression(oldRemote)
        if (wasProxy) ctx?.let { disconnectProxy(it) }
        if (!bound) return
        bound = false
        try {
            ctx?.unbindService(conn)
        } catch (_: Throwable) {
        }
    }

    private var listenedRoot: View? = null
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { update("布局变化") }

    private fun unlisten() {
        val root = listenedRoot ?: return
        listenedRoot = null
        try {
            root.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        } catch (t: Throwable) {
            L.hookError("TatansBridge.unlisten", t)
        }
    }

    /** 键盘显示：按键盘区域设置穿透，布局变化时跟着更新 */
    fun onShown() {
        visible = true
        refresh("键盘显示")
        val root = imeRoot()
        if (listenedRoot !== root) {
            unlisten()
            if (root == null) return
            try {
                root.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
                listenedRoot = root
            } catch (t: Throwable) {
                L.hookError("TatansBridge.listen", t)
            }
        }
        main.postDelayed({ update("键盘显示") }, 150)
    }

    fun onHidden() {
        unlisten()
        visible = false
        readerEventsSuppressed = false
        send(Rect(), "键盘收起", suppress = false)
        if (!talkmanCurrent.isEmpty) sendTalkman(Rect(), "键盘收起")
    }

    private fun imeRoot(): View? = try {
        ViewA11yHooks.service?.window?.window?.decorView
    } catch (_: Throwable) {
        null
    }

    /** 读屏焦点界面开着时不穿透（这些界面要用读屏焦点浏览） */
    private fun readerPanelOpen(): Boolean =
        CustomToolbar.editing() || imeRoot()?.let { Panels.find(it) } != null

    fun panelOpen(): Boolean = try { readerPanelOpen() } catch (_: Throwable) { false }
    fun clearPassThrough(why: String) {
        readerEventsSuppressed = false
        if ((remote != null || viaProxy) && (!current.isEmpty || currentSuppressed)) {
            send(Rect(), why, suppress = false)
        }
    }

    /** 天坦读屏在全屏且整屏穿透时启用 16/8192 事件屏蔽（面板打开、双击键盘或退出全屏立刻解除） */
    private fun wantTatansSuppress(): Boolean =
        visible && FullscreenMode.active && FullscreenMode.wanted && A11yState.recording &&
            VirtualKeys.keepFocus && !readerPanelOpen()

    @Suppress("DEPRECATION")
    private fun tatansFullRect(c: Context?, fallback: Rect): Rect {
        if (c == null) return fallback
        return try {
            val wm = c.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager ?: return fallback
            val p = android.graphics.Point()
            wm.defaultDisplay?.getSize(p)
            if (p.x > 0 && p.y > 0) Rect(0, 0, p.x, p.y) else fallback
        } catch (_: Throwable) {
            fallback
        }
    }

    private fun computeWant(): Rect {
        val want = Rect()
        // 双击输入时按键交给读屏焦点，不穿透
        val full = FullscreenMode.windowRect()
        if (full != null && A11yState.recording && !readerPanelOpen()) {
            want.set(full)
            if (!VirtualKeys.keepFocus) {
                want.bottom = minOf(want.bottom, FullscreenMode.keyboardTopOnScreen())
                if (want.isEmpty) want.setEmpty()
            }
        } else if (visible && Config.b("tatansFast") && A11yState.active && VirtualKeys.enabled && VirtualKeys.keepFocus && !readerPanelOpen()) {
            val root = imeRoot()
            val area = root?.let { r ->
                val id = r.resources.getIdentifier("ime_inputview", "id", r.context.packageName)
                if (id != 0) r.findViewById<View>(id) else null
            }
            if (area != null && area.isShown && area.width > 0 && area.height > 0) {
                val loc = IntArray(2)
                area.getLocationOnScreen(loc)
                want.set(loc[0], loc[1], loc[0] + area.width, loc[1] + area.height)
                // 工具栏上方（翻译栏、超级联想）用读屏焦点，不穿透：穿透从工具栏上边开始
                root?.let { VirtualKeys.toolbarTop(it) }?.let { top ->
                    if (top > want.top && top < want.bottom) want.top = top
                }
            }
        }
        return want
    }

    fun update(why: String) {
        val tatans = remote != null || viaProxy
        val talkman = try {
            talkmanWanted()
        } catch (_: Throwable) {
            false
        }
        if (!tatans && !talkman) return
        try {
            val want = computeWant()
            if (tatans) {
                val suppress = wantTatansSuppress()
                val tatansWant = if (suppress) tatansFullRect(ctx, want) else want
                if (tatansWant != current || suppress != currentSuppressed) {
                    send(tatansWant, why, suppress)
                }
            }
            if (talkman && want != talkmanCurrent) sendTalkman(want, why)
        } catch (t: Throwable) {
            L.hookError("TatansBridge.update", t)
        }
    }

    /** 解说：广播键盘区域（空区域就撤掉穿透，用 SHOW 带空区域，原因见类说明） */
    private fun sendTalkman(r: Rect, why: String) {
        val c = ctx ?: return
        val copy = Rect(r)
        if (!canSeePackage(c, TALKMAN)) {
            talkmanCurrent.set(copy)
            worker().post {
                val ok = try {
                    c.contentResolver.call(
                        proxyUri,
                        "talkmanPassThrough",
                        null,
                        Bundle().apply {
                            putInt("left", copy.left)
                            putInt("top", copy.top)
                            putInt("right", copy.right)
                            putInt("bottom", copy.bottom)
                        }
                    )?.getBoolean("applied", false) == true
                } catch (_: Throwable) {
                    false
                }
                if (ok) {
                    L.i("解说触摸穿透（代理/$why）：${if (copy.isEmpty) "撤掉" else Fmt.rect(copy)}")
                } else {
                    main.post { if (talkmanCurrent == copy) talkmanCurrent.setEmpty() }
                    L.w("通过模块代理设置解说触摸穿透失败（$why）")
                }
            }
            return
        }
        try {
            val area = if (copy.isEmpty) Rect(-1, 0, 0, 0) else Rect(copy)
            // 前台广播：普通广播系统会压 500 毫秒才送到，收起键盘后这段时间还是直接触摸
            c.sendBroadcast(Intent(TALKMAN_SHOW).putExtra(TALKMAN_RECT, area).setPackage(TALKMAN)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND))
            talkmanCurrent.set(copy)
            L.i("解说触摸穿透（$why）：${if (copy.isEmpty) "撤掉" else Fmt.rect(copy)}")
        } catch (t: Throwable) {
            talkmanCurrent.setEmpty()
            L.e("设置解说触摸穿透失败", t)
        }
    }

    private fun send(r: Rect, why: String, suppress: Boolean = false) {
        if (!suppress) readerEventsSuppressed = false
        val b = remote
        if (b == null) {
            if (!viaProxy) return
            val c = ctx ?: return
            val copy = Rect(r)
            current.set(copy)
            currentSuppressed = suppress
            worker().post {
                val res = try {
                    c.contentResolver.call(
                        proxyUri,
                        "tatansPassThrough",
                        null,
                        Bundle().apply {
                            putInt("left", copy.left)
                            putInt("top", copy.top)
                            putInt("right", copy.right)
                            putInt("bottom", copy.bottom)
                            putBinder("token", clientToken)
                            putBoolean("disconnect", false)
                            putBoolean("suppress", suppress)
                        }
                    )
                } catch (t: Throwable) {
                    L.e("模块代理设置天坦读屏触摸穿透失败", t)
                    null
                }
                val applied = res?.getBoolean("applied", false) == true
                val okBound = res?.getBoolean("bound", false) == true
                val suppressed = applied && res?.getBoolean("suppressed", false) == true
                if (applied) {
                    main.post {
                        if (viaProxy && current == copy && currentSuppressed == suppress) {
                            readerEventsSuppressed = suppressed
                        }
                    }
                    val tag = if (suppressed) "，屏蔽读屏文本/选区事件" else ""
                    L.i("天坦读屏触摸穿透（代理/$why）：${if (copy.isEmpty) "撤掉" else Fmt.rect(copy)}$tag")
                } else if (okBound) {
                    main.postDelayed({
                        if (viaProxy && current == copy) {
                            current.setEmpty()
                            currentSuppressed = false
                            readerEventsSuppressed = false
                            update("代理重试")
                        }
                    }, 150)
                } else {
                    main.post {
                        viaProxy = false
                        current.setEmpty()
                        currentSuppressed = false
                        readerEventsSuppressed = false
                        retryAfter = SystemClock.uptimeMillis() + 3000L
                    }
                    L.w("模块代理设置天坦读屏触摸穿透失败（$why）")
                }
            }
            return
        }
        try {
            if (suppress) {
                transactPassThrough(b, r)
                transactPerformAction(b, ACTION_IME_EXPANDED)
                current.set(r)
                currentSuppressed = true
                readerEventsSuppressed = true
                L.i("天坦读屏触摸穿透（$why）：${Fmt.rect(r)}，屏蔽读屏文本/选区事件")
            } else {
                // 面板打开或退出全屏时：先撤穿透，再退全屏抑制态，再补撤一次穿透，防止天坦残留整屏透传导致无法左右滑切焦点
                transactPassThrough(b, r)
                if (currentSuppressed || r.isEmpty) resetTatansSuppression(b)
                if (r.isEmpty) transactPassThrough(b, r)
                current.set(r)
                currentSuppressed = false
                readerEventsSuppressed = false
                L.i("天坦读屏触摸穿透（$why）：${if (r.isEmpty) "撤掉" else Fmt.rect(r)}")
            }
        } catch (t: Throwable) {
            current.setEmpty()
            currentSuppressed = false
            readerEventsSuppressed = false
            L.e("设置天坦读屏触摸穿透失败", t)
        }
    }

    private fun resetTatansSuppression(b: IBinder) {
        try { transactPerformAction(b, ACTION_IME_COLLAPSED) } catch (_: Throwable) { }
        try { transactInputModeChanged(b, false) } catch (_: Throwable) { }
    }

    private fun transactSetImeConnection(b: IBinder) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeStrongBinder(b)
            data.writeStrongBinder(imeConnBinder)
            b.transact(TX_SET_IME_CONNECTION, data, reply, 0)
            reply.readException()
        } catch (t: Throwable) {
            L.hookError("TatansBridge.setImeConnection", t)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun transactPerformAction(b: IBinder, action: Int) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeStrongBinder(b)
            data.writeInt(action)
            data.writeString("")
            b.transact(TX_PERFORM_ACTION, data, reply, 0)
            reply.readException()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun transactInputModeChanged(b: IBinder, fullScreen: Boolean) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeStrongBinder(b)
            data.writeInt(if (fullScreen) 1 else 0)
            b.transact(TX_ON_INPUT_MODE_CHANGED, data, reply, 0)
            reply.readException()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun transactPassThrough(b: IBinder, r: Rect) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            val left = if (r.isEmpty) -1 else r.left
            val top = if (r.isEmpty) 0 else r.top
            val right = if (r.isEmpty) 0 else r.right
            val bottom = if (r.isEmpty) 0 else r.bottom
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeStrongBinder(b)
            data.writeInt(left)
            data.writeInt(top)
            data.writeInt(right)
            data.writeInt(bottom)
            b.transact(TX_SET_PASS_THROUGH, data, reply, 0)
            reply.readException()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
