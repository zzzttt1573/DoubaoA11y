package io.github.doubaoa11y.hook

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
    private var context: Context? = null
    private var am: AccessibilityManager? = null
    private val listeners = mutableListOf<(Boolean) -> Unit>()

    /** 首页的模块总开关 */
    val moduleOn: Boolean get() = Config.b("moduleOn")

    val active: Boolean get() = moduleOn && (sysActive || Config.b("forceEnable"))

    /** 当前识别到的最高优先级读屏包名 */
    @Volatile
    var readerPackage: String = ""
        private set

    /** 已启用的无障碍服务包名集合（合并 AccessibilityManager 与 Secure 设置，供热路径零 IPC 读取） */
    @Volatile
    var enabledPackages: Set<String> = emptySet()
        private set

    /**
     * 记录绘制和回调：只要读屏开着就记录（不管触摸探索）。
     * 用户手写、打字、语音时常会临时关掉触摸探索，键位表和日志不能因此断掉。
     */
    val recording: Boolean get() = moduleOn && (sysEnabled || active)

    /** 触摸浏览关着时沿用主线直接触摸路径，不主动改全局状态。 */
    val directTouch: Boolean get() = moduleOn && sysEnabled && !sysActive && !Config.b("forceEnable") && Config.b("directTouch")
    val explorationOff: Boolean get() = !sysActive

    fun init(ctx: Context) {
        if (am != null) return
        try {
            val m = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            context = ctx.applicationContext ?: ctx
            am = m
            refresh("初始化")
            val main = Handler(Looper.getMainLooper())
            m.addAccessibilityStateChangeListener({ refresh("读屏开关变化") }, main)
            m.addTouchExplorationStateChangeListener({ refresh("触摸探索变化") }, main)
            // 已有别的无障碍服务开着时再开关读屏，上面两个不一定触发
            if (Build.VERSION.SDK_INT >= 33) {
                m.addAccessibilityServicesStateChangeListener(ctx.mainExecutor) { refresh("无障碍服务变化") }
            }
            if (Build.VERSION.SDK_INT < 33) {
                context?.contentResolver?.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                    false,
                    object : ContentObserver(main) {
                        override fun onChange(selfChange: Boolean) = refresh("已启用无障碍服务变化")
                    },
                )
            }
        } catch (t: Throwable) {
            L.e("初始化无障碍状态失败", t)
        }
    }

    fun onChange(l: (Boolean) -> Unit) {
        listeners.add(l)
    }

    fun refresh(why: String) {
        refreshState(why)
        FullscreenMode.refresh("无障碍状态变化")
    }

    // 显示键盘时先同步状态，全屏由 onWindowShown 按窗口生命周期激活。
    fun refreshState(why: String) {
        val m = am ?: return
        val enabled = try {
            m.isEnabled
        } catch (t: Throwable) {
            L.hookError("A11yState.isEnabled", t)
            false
        }
        val touch = enabled && try {
            m.isTouchExplorationEnabled
        } catch (t: Throwable) {
            L.hookError("A11yState.isTouchExplorationEnabled", t)
            false
        }
        val services = if (enabled) enabledServices(m) else emptyList()
        val setting = if (enabled) enabledServicesSetting() else null
        val pkgs = HashSet<String>()
        for (s in services) if (s.pkg.isNotEmpty()) pkgs.add(s.pkg)
        if (!setting.isNullOrEmpty()) {
            for (part in setting.split(':')) {
                val slash = part.indexOf('/')
                if (slash > 0) pkgs.add(part.substring(0, slash))
            }
        }
        enabledPackages = pkgs
        val state = ReaderDetection.detect(enabled, touch, services, setting)
        readerPackage = state.readerPackage
        sysEnabled = state.readerOn
        sysActive = state.touchExploration

        val nowActive = active
        L.i("无障碍状态（$why）：读屏=$sysEnabled 触摸探索=$sysActive 强制=${Config.b("forceEnable")} 读屏包名=${readerPackage.ifEmpty { "未识别" }} → 模块${if (nowActive) "启用" else "停用"}")
        if (lastActive != nowActive) {
            lastActive = nowActive
            for (l in listeners) {
                try {
                    l(nowActive)
                } catch (t: Throwable) {
                    L.e("状态回调出错", t)
                }
            }
        }
    }

    @Volatile
    private var lastActive = false

    private fun enabledServices(m: AccessibilityManager): List<ReaderDetection.Service> = try {
        m.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).mapNotNull { info ->
            val service = info.resolveInfo?.serviceInfo ?: return@mapNotNull null
            ReaderDetection.Service(
                service.packageName.orEmpty(),
                service.name.orEmpty(),
                (info.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE) != 0,
            )
        }
    } catch (t: Throwable) {
        L.hookError("A11yState.enabledServices", t)
        emptyList()
    }

    private fun enabledServicesSetting(): String? = try {
        context?.contentResolver?.let {
            Settings.Secure.getString(it, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }
    } catch (t: Throwable) {
        L.hookError("A11yState.enabledServicesSetting", t)
        null
    }

    fun describeShort(): String {
        if (am == null) return "无障碍状态未知"
        return "读屏${if (sysEnabled) "已开" else "已关"}，触摸浏览${if (sysActive) "已开" else "已关"}"
    }

    fun describe(): String {
        val m = am
        return if (m == null) "无障碍服务未初始化" else
            "读屏=${if (sysEnabled) "开" else "关"}，触摸探索=${if (sysActive) "开" else "关"}，模块${if (active) "已启用" else "未启用"}"
    }
}
