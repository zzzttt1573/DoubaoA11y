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

    val active: Boolean get() = sysActive || Config.b("forceEnable")

    /**
     * 记录绘制和回调：只要读屏开着就记录（不管触摸探索）。
     * 用户手写、打字、语音时常会临时关掉触摸探索，键位表和日志不能因此断掉。
     */
    val recording: Boolean get() = sysEnabled || active

    /**
     * 读屏开着、触摸浏览关着：手指的触摸直接到输入法。
     * 打开“触摸浏览关闭时仍用读屏操作”后，模块把触摸当成摸和抬手来处理（按下、移动朗读，抬起输入）。
     */
    val directTouch: Boolean get() = sysEnabled && !sysActive && !Config.b("forceEnable") && Config.b("directTouch")

    fun init(ctx: Context) {
        if (am != null) return
        try {
            val m = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            am = m
            refresh("初始化")
            val main = Handler(Looper.getMainLooper())
            m.addAccessibilityStateChangeListener({ refresh("读屏开关变化") }, main)
            m.addTouchExplorationStateChangeListener({ refresh("触摸探索变化") }, main)
        } catch (t: Throwable) {
            L.e("初始化无障碍状态失败", t)
        }
    }

    fun onChange(l: (Boolean) -> Unit) {
        listeners.add(l)
    }

    fun refresh(why: String) {
        val m = am ?: return
        val was = active
        sysEnabled = try {
            m.isEnabled
        } catch (t: Throwable) {
            false
        }
        sysActive = try {
            sysEnabled && m.isTouchExplorationEnabled
        } catch (t: Throwable) {
            false
        }
        L.i("无障碍状态（$why）：读屏=${m.isEnabled} 触摸探索=${m.isTouchExplorationEnabled} 强制=${Config.b("forceEnable")} → 模块${if (active) "启用" else "停用"}")
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

    fun describeShort(): String {
        val m = am ?: return "无障碍状态未知"
        return "读屏${if (m.isEnabled) "已开" else "已关"}，触摸浏览${if (m.isTouchExplorationEnabled) "已开" else "已关"}"
    }

    fun describe(): String {
        val m = am
        return if (m == null) "无障碍服务未初始化" else
            "读屏=${if (m.isEnabled) "开" else "关"}，触摸探索=${if (m.isTouchExplorationEnabled) "开" else "关"}，模块${if (active) "已启用" else "未启用"}"
    }
}
