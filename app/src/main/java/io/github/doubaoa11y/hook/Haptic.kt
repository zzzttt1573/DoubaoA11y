package io.github.doubaoa11y.hook

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * 摸键反馈（震动）。
 *
 * 实测优化依据：每次摸到新键，v.vibrate() 平均阻塞主线程约 1.1ms，
 * 占整次触摸处理（约 600us~1.2ms）的一半以上，比键位查找和朗读都贵。
 *
 * 做了两件事：
 *  1) 效果对象和归属对象在配置变化时预计算，热路径不再分配、不再查配置；
 *  2) 真正的 vibrate 调用丢到单线程池里异步执行 —— 它只是「通知系统震一下」，
 *     不需要等结果，没必要占着输入法主线程。
 */
object Haptic {
    private var vib: Vibrator? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * 震动工作线程。
     * 单线程：保证震动按顺序执行；队列 4 个 + 丢弃最旧：
     * 快速滑动时震动脉冲不会无限堆积，优先保证最新那一下。
     */
    private val worker = java.util.concurrent.ThreadPoolExecutor(
        1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(4),
        java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy(),
    )

    /** 预计算好的震动效果 */
    @Volatile private var effect: VibrationEffect? = null

    /** 预计算好的震动归属（SDK 33+ 是 VibrationAttributes，之前是 AudioAttributes） */
    @Volatile private var attributes: Any? = null

    /** 热路径开关：false 时直接返回 */
    @Volatile private var enabled = false

    fun init(ctx: Context) {
        try {
            vib = if (Build.VERSION.SDK_INT >= 31) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
        } catch (t: Throwable) {
            L.e("初始化震动失败", t)
        }
        refresh()
    }

    /** 配置变化后调用：把效果对象算好，热路径不再查配置、不再分配 */
    fun refresh() {
        enabled = runCatching { Config.b("touchVibrate") }.getOrDefault(false)
        if (!enabled) {
            effect = null
            attributes = null
            return
        }
        val strength = runCatching { Config.i("vibrateStrength") }.getOrDefault(2)
        effect = runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                when (strength) {
                    1 -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                    3 -> VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE)
                    else -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                }
            } else {
                val ms = when (strength) {
                    1 -> 15L
                    3 -> 30L
                    else -> 22L
                }
                VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
            }
        }.getOrNull()
        attributes = if (Build.VERSION.SDK_INT >= 33) {
            runCatching {
                android.os.VibrationAttributes.createForUsage(
                    android.os.VibrationAttributes.USAGE_ACCESSIBILITY
                )
            }.getOrNull()
        } else {
            runCatching {
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).build()
            }.getOrNull()
        }
    }

    fun tick() = tick(1)

    /** 连续震 times 下：一下表示手势认到了，两下表示这个动作做不了 */
    fun tick(times: Int) {
        if (!enabled) return
        val v = vib ?: return
        val e = effect ?: return
        val attr = attributes
        val n = times.coerceIn(1, 3)
        try {
            worker.execute { buzz(v, e, attr) }
            if (n > 1) {
                // 第二、三下也要响，但不能卡主线程：延时投递到同一个工作线程
                for (i in 1 until n) {
                    main.postDelayed({
                        try {
                            worker.execute { buzz(v, e, attr) }
                        } catch (_: Throwable) {
                        }
                    }, 110L * i)
                }
            }
        } catch (t: Throwable) {
            L.hookError("Haptic.tick", t)
        }
    }

    private fun buzz(v: Vibrator, e: VibrationEffect, attr: Any?) {
        try {
            if (Build.VERSION.SDK_INT >= 33 && attr is android.os.VibrationAttributes) {
                v.vibrate(e, attr)
            } else if (attr is android.media.AudioAttributes) {
                @Suppress("DEPRECATION")
                v.vibrate(e, attr)
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(e)
            }
        } catch (_: Throwable) {
        }
    }
}
