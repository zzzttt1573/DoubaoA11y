package io.github.doubaoa11y.hook

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityManager

/**
 * 只有读屏开启且触摸探索开启时才工作（或设置里打开“强制启用”）。
 * 监听开关变化，动态启用或关闭。
 */
object A11yState {
    @Volatile
    private var sysActive = false
    @Volatile
    private var sysEnabled = false
    private var am: AccessibilityManager? = null
    private val listeners = mutableListOf<(Boolean) -> Unit>()

    /** 首页的模块总开关 */
    val moduleOn: Boolean get() = Config.b("moduleOn")

    val active: Boolean get() = moduleOn && (sysActive || Config.b("forceEnable"))

    /**
     * 记录绘制和回调：只要读屏开着就记录（不管触摸探索）。
     * 用户手写、打字、语音时常会临时关掉触摸探索，键位表和日志不能因此断掉。
     */
    val recording: Boolean get() = moduleOn && (sysEnabled || active)

    /**
     * 读屏开着、触摸浏览关着：手指的触摸直接到输入法。
     * 打开“触摸浏览关闭时仍用读屏操作”后，模块把触摸当成摸和抬手来处理（按下、移动朗读，抬起输入）。
     */
    val directTouch: Boolean get() = moduleOn && sysEnabled && !sysActive && !Config.b("forceEnable") && Config.b("directTouch")

    fun init(ctx: Context) {
        if (am != null) return
        try {
            val m = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            am = m
            refresh("初始化")
            val main = Handler(Looper.getMainLooper())
            m.addAccessibilityStateChangeListener({ refresh("读屏开关变化") }, main)
            m.addTouchExplorationStateChangeListener({ refresh("触摸探索变化") }, main)
            // 已有别的无障碍服务开着时再开关读屏，上面两个不一定触发
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                m.addAccessibilityServicesStateChangeListener(ctx.mainExecutor) { refresh("无障碍服务变化") }
            }
        } catch (t: Throwable) {
            L.e("初始化无障碍状态失败", t)
        }
    }

    fun onChange(l: (Boolean) -> Unit) {
        listeners.add(l)
    }

    fun refresh(why: String) {
        val m = am ?: return
        val was = lastActive
        val touch = try {
            m.isEnabled && m.isTouchExplorationEnabled
        } catch (t: Throwable) {
            false
        }
        reader = touch || readerOn(m)
        sysEnabled = reader
        sysActive = touch
        L.i("无障碍状态（$why）：读屏=$reader 触摸探索=$touch 强制=${Config.b("forceEnable")} → 模块${if (active) "启用" else "停用"}")
        lastActive = active
        if (was != active) {
            for (l in listeners) {
                try {
                    l(active)
                } catch (t: Throwable) {
                    L.e("状态回调出错", t)
                }
            }
        }
    }

    @Volatile
    private var reader = false
    @Volatile
    private var lastActive = false

    /** 读屏的包名：TalkBack、天坦、解说、点明、保益、三星 TalkBack */
    private val readerPackages = setOf(
        "com.google.android.marvin.talkback", "com.android.tback", "com.nirenr.talkman",
        "com.dianming.phoneapp", "com.bjbyhd.voiceback", "com.bjbyhd.screenreader",
        "com.samsung.android.accessibility.talkback",
    )

    /**
     * 肯定不是读屏的无障碍服务（包名前缀）：自动化工具、跳广告、输入法、手机厂商的辅助功能、游戏和直播助手。
     * 这里没列到的服务一律当成读屏，宁可多认，不漏认；认错了可以用首页的模块总开关关掉。
     */
    private val notReaderPrefixes = listOf(
        "hello.litiaotiao.", "li.songe.gkd", "com.arlosoft.macrodroid", "org.autojs.", "com.stardust.",
        "com.zdanjian.", "com.x7890.", "me.zhanghai.", "bin.mt.",
        "com.miui.", "com.xiaomi.", "com.mipay.", "com.milink.", "com.android.settings", "com.android.systemui",
        "com.google.android.accessibility.switchaccess",
        "com.iflytek.", "com.baidu.input", "com.sohu.inputmethod", "com.tencent.wetype", "com.tatans.inputmethod",
        io.github.doubaoa11y.ConfigSpec.TARGET_PACKAGE,
        "com.ss.android.", "com.xingin.", "tv.danmaku.", "com.netease.uuremote", "com.limelight",
        "com.eureka.nvdapocketremote",
    )
    private val notReaderClasses = listOf("SelectToSpeak", "SwitchAccess", "AccessibilityMenu", "VoiceAccess")

    /**
     * 有没有开着读屏。只看“有无障碍服务开着”不够：李跳跳、GKD、MacroDroid 这类工具也是无障碍服务，还会声明语音反馈。
     * 认得的读屏、申请了触摸浏览的、类名像读屏的直接算；剩下的只要不在“肯定不是读屏”名单里，也算读屏。
     */
    private fun readerOn(m: AccessibilityManager): Boolean = try {
        if (!m.isEnabled) false
        else m.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { info ->
            val si = info.resolveInfo?.serviceInfo
            val pkg = si?.packageName ?: ""
            val cls = si?.name ?: ""
            when {
                pkg.isEmpty() -> false
                (info.flags and android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE) != 0 -> true
                pkg in readerPackages -> !cls.contains("SelectToSpeak")
                cls.endsWith("TalkBackService") || cls.endsWith("SoundBackService") || cls.contains("ScreenReader") -> true
                notReaderClasses.any { cls.contains(it) } -> false
                notReaderPrefixes.any { pkg.startsWith(it) } -> false
                else -> {
                    L.i("不认识的无障碍服务，当成读屏：$pkg/$cls")
                    true
                }
            }
        }
    } catch (t: Throwable) {
        L.e("检查读屏失败", t)
        m.isEnabled
    }

    fun describeShort(): String {
        val m = am ?: return "无障碍状态未知"
        return "读屏${if (reader) "已开" else "已关"}，触摸浏览${if (sysActive) "已开" else "已关"}"
    }

    fun describe(): String {
        val m = am
        return if (m == null) "无障碍服务未初始化" else
            "读屏=${if (reader) "开" else "关"}，触摸探索=${if (sysActive) "开" else "关"}，模块${if (active) "已启用" else "未启用"}"
    }
}
