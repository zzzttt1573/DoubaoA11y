package io.github.doubaoa11y.hook

import android.util.Log
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.ConcurrentHashMap

/**
 * 日志：logcat 标签 DoubaoA11y，同时按设置写入 LSPosed 日志。
 * 运行日志会记下按了哪个键、语音识别出的文字等输入内容，所以默认不记，
 * 只有设置页“调试选项”里打开“记录运行日志”才记。警告和错误不含输入内容，总是记。
 */
object L {
    const val TAG = "DoubaoA11y"
    private const val CHUNK = 3000

    /** 手动“输出键位表和控件树”时临时放行几秒 */
    @Volatile
    var forceUntil = 0L

    private val on: Boolean
        get() = Config.b("runLog") || android.os.SystemClock.uptimeMillis() < forceUntil

    /** 运行事件：logcat + LSPosed 日志 */
    fun i(msg: String) {
        if (on) out(Log.INFO, msg, Config.b("xposedLog"))
    }

    /** 绘制细节等高频日志：只有打开“绘制细节也写入 LSPosed 日志”才进 LSPosed */
    fun v(msg: String) {
        if (on) out(Log.DEBUG, msg, Config.b("xposedLog") && Config.b("xposedVerbose"))
    }

    fun w(msg: String) = out(Log.WARN, msg, true)

    fun e(msg: String, t: Throwable? = null) {
        out(Log.ERROR, if (t == null) msg else msg + "\n" + Log.getStackTraceString(t), true)
    }

    private val errorCounts = ConcurrentHashMap<String, Int>()

    /** hook 回调里的异常：同一处只记前 5 次，之后每 200 次记一次，防止刷屏 */
    fun hookError(where: String, t: Throwable) {
        val n = (errorCounts[where] ?: 0) + 1
        errorCounts[where] = n
        if (n <= 5 || n % 200 == 0) e("回调出错（第 $n 次）：$where", t)
    }

    private fun out(level: Int, msg: String, toXposed: Boolean) {
        var start = 0
        while (start < msg.length) {
            val end = minOf(msg.length, start + CHUNK)
            val part = msg.substring(start, end)
            try {
                Log.println(level, TAG, part)
            } catch (_: Throwable) {
            }
            if (toXposed) {
                try {
                    XposedBridge.log("[$TAG] $part")
                } catch (_: Throwable) {
                }
            }
            start = end
        }
    }
}
