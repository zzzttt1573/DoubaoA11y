package io.github.doubaoa11y.hook

import android.media.AudioManager
import android.media.AudioRecord
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.inputmethod.InputConnection

/**
 * 阶段 5：语音输入。
 * - 提示音已按用户要求去掉，这里只记录开始、结束和识别结果，方便看日志。
 * - 识别结果由读屏自己读（编辑框文字变化），模块只记日志。
 * 开始/结束靠 hook AudioRecord.startRecording / stop（豆包的语音识别在输入法进程里用 AudioRecord 录音）。
 * - 说完再上屏：豆包边听边用 setComposingText 写中间结果，有的读屏（天坦）会一直读编辑框。
 *   录音期间和录音结束后，把中间结果先留着不写，等豆包结束组字、上屏或做别的编辑时再一次写进去；
 *   停止说话后 3 秒没有新结果也写进去，免得丢字。
 */
object Voice {
    private val main = Handler(Looper.getMainLooper())
    private val token = Any()

    @Volatile
    private var recording = false
    private var stopAt = 0L
    private var sessionUntil = 0L
    private var lastComposing = ""
    private var spoken = false


    fun install() {
        Hooker.byName(AudioRecord::class.java, "startRecording", SafeHook("AudioRecord.startRecording", before = { _ ->
            onStart()
        }))
        Hooker.method(AudioRecord::class.java, "stop", arrayOf(), SafeHook("AudioRecord.stop", after = { _ ->
            onStop()
        }))
        InputHooks.onCommit { text, _ -> onCommit(text) }
        InputHooks.onComposing { text -> onComposing(text) }
        InputHooks.onFinishComposing { onFinish() }
    }

    // 说完再上屏
    private var buffering = false
    private var held: String? = null
    private var heldIc: InputConnection? = null
    private var flushing = false
    private val flushToken = Any()

    /** 输入连接的每个动作都先问这里；返回 true 表示拦下不执行 */
    fun intercept(name: String, ic: InputConnection, args: Array<Any?>): Boolean {
        if (flushing || !buffering) return false
        if (!Config.b("voiceBuffer")) {
            flush("设置已关")
            return false
        }
        when (name) {
            "setComposingText" -> {
                held = args[0]?.toString() ?: ""
                heldIc = ic
                if (!recording) scheduleFlush()
                return true
            }
            // 豆包每写一次中间结果都跟着移一次光标，也先拦下（组字没写进去，光标位置对不上）
            "setSelection" -> if (held != null) return true
            // 豆包按它以为已经写进去的字数框一段组字区域；组字没写进去，这个区域会落到前面的字上，被替换掉
            "setComposingRegion" -> {
                L.i("语音期间拦下组字区域 ${args[0]}-${args[1]}")
                return true
            }
            // 豆包直接上屏最终结果：组字区本来就是空的，丢掉留着的中间结果，不然会重复
            "commitText" -> {
                held = null
                if (!recording) end("上屏")
            }
            // 结束组字：先把留着的写进去，再让豆包结束组字
            "finishComposingText" -> {
                flush("结束组字")
                if (!recording) end("结束组字")
            }
            // 其他编辑（发送、删除、移光标）：先把留着的写进去
            else -> {
                flush(name)
                if (!recording) end(name)
            }
        }
        return false
    }

    private fun scheduleFlush() {
        main.removeCallbacksAndMessages(flushToken)
        main.postAtTime({
            flush("说完 3 秒")
            end("说完 3 秒")
        }, flushToken, SystemClock.uptimeMillis() + 3000)
    }

    /** 把留着的中间结果写进编辑框（仍是组字状态，和豆包原来的样子一样） */
    fun flush(why: String) {
        val t = held ?: return
        val ic = heldIc
        held = null
        if (ic == null) return
        try {
            flushing = true
            ic.setComposingText(t, 1)
            L.i("语音说完再上屏（$why）：「$t」")
        } catch (e: Throwable) {
            L.hookError("Voice.flush", e)
        } finally {
            flushing = false
        }
    }

    private fun end(why: String) {
        if (!buffering) return
        buffering = false
        heldIc = null
        main.removeCallbacksAndMessages(flushToken)
    }

    /** 键盘收起、换输入框：把留着的写进去 */
    fun onInputEnd() {
        if (!buffering) return
        flush("收起")
        end("收起")
    }

    private fun onStart() {
        if (recording) return
        recording = true
        // 上一次（比如撤回输入）留下的“说完 3 秒写入”定时器要清掉，不然会在这次说话中途触发，提前结束
        main.removeCallbacksAndMessages(flushToken)
        // 上一次没收尾（多半是撤回输入）：留着的中间结果丢掉，不写进编辑框
        if (buffering && held != null) L.i("新一次语音，丢掉上次没上屏的「$held」")
        end("新一次语音")
        held = null
        if (Config.b("voiceBuffer") && A11yState.recording) {
            buffering = true
            held = null
        }
        lastComposing = ""
        spoken = false
        sessionUntil = Long.MAX_VALUE
        L.i("语音：开始录音")
    }

    private fun onStop() {
        if (!recording) return
        recording = false
        stopAt = SystemClock.uptimeMillis()
        sessionUntil = stopAt + 5000
        L.i("语音：结束录音")
        if (buffering) scheduleFlush()
        // 识别结果一般很快上屏；如果只留在组字里，过一会儿也读出来
        main.removeCallbacksAndMessages(token)
        main.postAtTime({ speakFinal("超时") }, token, stopAt + 2500)
    }

    private fun inSession(): Boolean = recording || SystemClock.uptimeMillis() < sessionUntil

    private fun onComposing(text: String) {
        if (inSession()) lastComposing = text
    }

    private fun onCommit(text: String) {
        if (!inSession() || recording) return
        if (text.isBlank()) return
        lastComposing = text
        speakFinal("上屏")
    }

    private fun onFinish() {
        if (!inSession() || recording) return
        main.removeCallbacksAndMessages(token)
        main.postAtTime({ speakFinal("结束组字") }, token, SystemClock.uptimeMillis() + 150)
    }

    private fun speakFinal(why: String) {
        if (spoken) return
        val t = lastComposing.trim()
        if (t.isEmpty()) return
        spoken = true
        sessionUntil = 0
        main.removeCallbacksAndMessages(token)
        L.i("语音识别结果（$why）：「$t」")

    }
}
