package io.github.doubaoa11y.hook

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

/** 摸到一个新的键（或按钮、候选词）时轻震一下，手指滑到另一个键上再震一下 */
object Haptic {
    private var vib: Vibrator? = null

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
    }

    fun tick() {
        if (!Config.b("touchVibrate")) return
        val v = vib ?: return
        try {
            val strength = Config.i("vibrateStrength")
            val effect = when (strength) {
                1 -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                3 -> VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE)
                else -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            }
            // 按“无障碍”类别发震动：系统“触感反馈”关着时，普通触摸类震动会被忽略，无障碍类不受它影响
            if (Build.VERSION.SDK_INT >= 33) {
                v.vibrate(effect, android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_ACCESSIBILITY))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(effect, android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).build())
            }
        } catch (t: Throwable) {
            L.hookError("Haptic.tick", t)
        }
    }
}
