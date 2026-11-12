package io.github.doubaoa11y.hook

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Rect
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.provider.Settings
import android.view.View
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityManager

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
 * 输入法显示时发 ACTION_INPUTMETHOD_SHOW 带上键盘区域，解说就把这块设为触摸浏览穿透；
 * 收起时发 ACTION_INPUTMETHOD_HIDE 撤掉。解说接天坦输入法用的是另一套（解说主动绑定 com.tatans.inputmethod），
 * 只认天坦输入法的包名，豆包用不了，所以这里走广播。
 */
object TatansBridge {
    private const val PKG = "com.android.tback"
    private const val ACTION = "tatans.intent.action.IME_SERVICE"
    private const val DESCRIPTOR = "net.tatans.soundback.ITatansImeService"
    private const val TX_SET_PASS_THROUGH = 3

    private const val TALKMAN = "com.nirenr.talkman"
    private const val TALKMAN_SHOW = "com.nirenr.talkman.ACTION_INPUTMETHOD_SHOW"
    private const val TALKMAN_HIDE = "com.nirenr.talkman.ACTION_INPUTMETHOD_HIDE"
    private const val TALKMAN_RECT = "com.nirenr.talkman.EXTRA_RECT_DATA"

    private val main = Handler(Looper.getMainLooper())
    private var remote: IBinder? = null
    private var ctx: Context? = null
    private var am: AccessibilityManager? = null
    private var binding = false
    private var bound = false
    private val current = Rect()
    /** 已经发给解说的穿透区域 */
    private val talkmanCurrent = Rect()
    /** 键盘窗口显示着（收起动画期间也算收起） */
    private var visible = false

    /** 已经请求天坦把键盘区域设为穿透（手指触摸可能直接到输入法） */
    val requested: Boolean get() = (remote != null && !current.isEmpty) || !talkmanCurrent.isEmpty

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remote = service
            binding = false
            L.i("已连接天坦读屏的输入法接口")
            main.post { update("连接后") }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            current.setEmpty()
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

    private fun wanted(): Boolean = Config.b("tatansFast") && A11yState.active && running(PKG)

    private fun talkmanWanted(): Boolean = Config.b("tatansFast") && A11yState.active && running(TALKMAN)

    /** 读屏状态、设置变化后：该连就连，不该连就撤掉穿透并断开 */
    fun refresh(why: String) {
        try {
            if (wanted()) {
                connect()
                update(why)
            } else if (bound || binding) {
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
        if (remote != null || binding) return
        try {
            binding = c.bindService(Intent(ACTION).setPackage(PKG), conn, Context.BIND_AUTO_CREATE)
            bound = binding
            L.i(if (binding) "正在连接天坦读屏的输入法接口" else "天坦读屏没有提供输入法接口")
            if (!binding) try {
                c.unbindService(conn)
            } catch (_: Throwable) {
            }
        } catch (t: Throwable) {
            binding = false
            L.e("连接天坦读屏失败", t)
        }
    }

    private fun disconnect() {
        binding = false
        remote = null
        current.setEmpty()
        if (!bound) return
        bound = false
        try {
            ctx?.unbindService(conn)
        } catch (_: Throwable) {
        }
    }

    private var listenedRoot: View? = null

    /** 键盘显示：按键盘区域设置穿透，布局变化时跟着更新 */
    fun onShown() {
        visible = true
        refresh("键盘显示")
        val root = imeRoot() ?: return
        if (listenedRoot !== root) {
            try {
                root.viewTreeObserver.addOnGlobalLayoutListener(ViewTreeObserver.OnGlobalLayoutListener { update("布局变化") })
                listenedRoot = root
            } catch (t: Throwable) {
                L.hookError("TatansBridge.listen", t)
            }
        }
        main.postDelayed({ update("键盘显示") }, 150)
    }

    fun onHidden() {
        visible = false
        send(Rect(), "键盘收起")
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

    fun update(why: String) {
        val tatans = remote != null
        val talkman = try {
            talkmanWanted()
        } catch (_: Throwable) {
            false
        }
        if (!tatans && !talkman) return
        try {
            val want = Rect()
            // 双击输入时按键交给读屏焦点，不穿透
            if (visible && Config.b("tatansFast") && A11yState.active && VirtualKeys.enabled && VirtualKeys.keepFocus && !readerPanelOpen()) {
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
            if (tatans && want != current) send(want, why)
            if (talkman && want != talkmanCurrent) sendTalkman(want, why)
        } catch (t: Throwable) {
            L.hookError("TatansBridge.update", t)
        }
    }

    /** 解说：广播键盘区域（空区域就撤掉穿透） */
    private fun sendTalkman(r: Rect, why: String) {
        val c = ctx ?: return
        try {
            val i = if (r.isEmpty) Intent(TALKMAN_HIDE)
            else Intent(TALKMAN_SHOW).putExtra(TALKMAN_RECT, Rect(r))
            c.sendBroadcast(i.setPackage(TALKMAN))
            talkmanCurrent.set(r)
            L.i("解说触摸穿透（$why）：${if (r.isEmpty) "撤掉" else Fmt.rect(r)}")
        } catch (t: Throwable) {
            talkmanCurrent.setEmpty()
            L.e("设置解说触摸穿透失败", t)
        }
    }

    private fun send(r: Rect, why: String) {
        val b = remote ?: return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeStrongBinder(b)
            data.writeInt(r.left)
            data.writeInt(r.top)
            data.writeInt(r.right)
            data.writeInt(r.bottom)
            b.transact(TX_SET_PASS_THROUGH, data, reply, 0)
            reply.readException()
            current.set(r)
            L.i("天坦读屏触摸穿透（$why）：${if (r.isEmpty) "撤掉" else Fmt.rect(r)}")
        } catch (t: Throwable) {
            current.setEmpty()
            L.e("设置天坦读屏触摸穿透失败", t)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
