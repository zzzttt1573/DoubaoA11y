package io.github.doubaoa11y.hook

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
    private var spoken = false
    private val echoGate = VoiceEchoGate()
    @Volatile private var echoToken = 0L
    private var confirmedWritten = ""
    private var expectedFinalCommit: String? = null


    fun install() {
        Hooker.byName(AudioRecord::class.java, "startRecording", SafeHook("AudioRecord.startRecording", after = { p ->
            if (p.throwable == null && (p.thisObject as? AudioRecord)?.recordingState == AudioRecord.RECORDSTATE_RECORDING) onStart()
        }))
        Hooker.method(AudioRecord::class.java, "stop", arrayOf(), SafeHook("AudioRecord.stop", after = { p ->
            if (p.throwable == null) onStop()
        }))
        InputHooks.onFinishComposing { onFinish() }
    }

    // 说完再上屏
    private var buffering = false
    private var held: String? = null
    private var heldIc: InputConnection? = null
    private var flushing = false
    private val flushToken = Any()
    @Volatile private var dropUntil = 0L

    /**
     * 翻译模式：豆包把原文的中间结果写进编辑框，说完后自己再把原文写一遍、换成译文。
     * 中间结果和框组字区都拦下，拦着的原文一律丢掉、不写进去（写了会留在编辑框里和译文混在一起）。
     */
    private var translateMode = false

    /** 输入连接的每个动作都先问这里；返回 true 表示拦下不执行 */
    fun intercept(name: String, ic: InputConnection, args: Array<Any?>): Boolean {
        // 刚撤回：豆包晚到的中间结果也不写
        if (!flushing && SystemClock.uptimeMillis() < dropUntil &&
            (name == "setComposingText" || name == "setComposingRegion" || name == "setSelection")) {
            L.i("撤回后拦下 $name")
            return true
        }
        if (flushing || !buffering) return false
        if (!Config.b("voiceBuffer")) {
            flush("设置已关")
            return false
        }
        when (name) {
            "setComposingText" -> {
                val t = args[0]?.toString() ?: ""
                if (translateMode && !recording && t.isEmpty()) {
                    L.i("翻译模式：丢掉原文「${held ?: ""}」，等译文")
                    held = null
                    end("译文")
                    return false
                }
                held = t
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
        // 翻译模式：豆包出译文前会自己把原文再写一遍、再换成译文；这里写进去原文就留在编辑框里，和译文混在一起
        if (translateMode) {
            L.i("翻译模式：丢掉原文「$t」，等译文（$why）")
            return
        }
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

    /** 松手撤回输入：留着的中间结果丢掉，不写进编辑框 */
    fun discard() {
        if (held != null) L.i("撤回输入，丢掉「$held」")
        held = null
        confirmedWritten = ""
        expectedFinalCommit = null
        cancelFinalSpeech()
        spoken = true
        dropUntil = if (buffering) SystemClock.uptimeMillis() + 1500 else 0L
        end("撤回")
    }

    /** 键盘收起、换输入框：把留着的写进去 */
    fun onInputEnd() {
        dropUntil = 0L
        if (buffering) { flush("收起"); end("收起") }
        expectedFinalCommit = null
        confirmedWritten = ""
        cancelFinalSpeech()
        sessionUntil = 0L
        TypingEcho.reset()
    }

    /** 新的手动输入结束上一录音的回声事务，避免同一句正常再输入被吞。 */
    fun onManualInput() {
        if (recording) return
        if (buffering) { flush("手动输入"); end("手动输入") }
        expectedFinalCommit = null
        confirmedWritten = ""
        sessionUntil = 0L
    }

    /** 仅供成功回调并确认正文变化的统一回声路径调用。 */
    fun acceptSuccessfulWrite(text: String, commit: Boolean): Boolean {
        val expected = commit && expectedFinalCommit == text
        if (!inSession() && !flushing && !expected) return false
        if (expected) expectedFinalCommit = null
        confirmedWritten = text
        if (!recording) {
            if (!commit) expectedFinalCommit = text
            speakFinal("确认写入")
        }
        return true
    }

    /** 同一语音先组字再 commit 不改变正文，只消费预期收尾，不再补读。 */
    fun acceptConfirmedCommit(text: String) {
        if (expectedFinalCommit == text) expectedFinalCommit = null
    }

    private fun onStart() {
        if (recording) return
        Sound.play("voice")
        recording = true
        synchronized(echoGate) {
            main.removeCallbacksAndMessages(token)
            echoToken = echoGate.begin()
        }
        confirmedWritten = ""
        expectedFinalCommit = null
        dropUntil = 0L
        // 上一次（比如撤回输入）留下的“说完 3 秒写入”定时器要清掉，不然会在这次说话中途触发，提前结束
        main.removeCallbacksAndMessages(flushToken)
        // 上一次没收尾（多半是撤回输入）：留着的中间结果丢掉，不写进编辑框
        if (buffering && held != null) L.i("新一次语音，丢掉上次没上屏的「$held」")
        end("新一次语音")
        held = null
        // 翻译模式下豆包靠组字区域把原文换成译文：原文先拦下，等豆包清空组字时丢掉，让译文写进去
        val translating = KeyboardViewHooks.translateShown || translateViewShown()
        translateMode = translating
        if (Config.b("voiceBuffer") && A11yState.recording) {
            buffering = true
            held = null
            if (translating) L.i("翻译模式：说完再上屏，等译文")
        }
        spoken = false
        sessionUntil = Long.MAX_VALUE
        L.i("语音：开始录音")
    }

    private fun translateViewShown(): Boolean = try {
        val root = ViewA11yHooks.service?.window?.window?.decorView
        val id = root?.resources?.getIdentifier("translate_view", "id", root.context.packageName) ?: 0
        val v = if (id != 0) root?.findViewById<android.view.View>(id) else null
        v != null && v.isShown
    } catch (_: Throwable) {
        false
    }

    private fun onStop() {
        val generation = echoToken
        if (!recording) return
        recording = false
        KeyboardOps.onVoiceStopped()
        stopAt = SystemClock.uptimeMillis()
        sessionUntil = stopAt + 5000
        L.i("语音：结束录音")
        if (buffering) scheduleFlush()
        // 识别结果一般很快上屏；如果只留在组字里，过一会儿也读出来
        scheduleFinal("超时", stopAt + 2500, generation)
    }

    fun isRecording(): Boolean = recording

    /** 语音录音中，或刚结束录音 900ms 内的保护期（防止语音收尾期间工具栏提示被误抓取朗读） */
    fun isVoiceBusy(): Boolean =
        recording || (stopAt > 0L && SystemClock.uptimeMillis() - stopAt < 900L)

    private fun inSession(): Boolean = recording || SystemClock.uptimeMillis() < sessionUntil

    private fun onFinish() {
        val generation = echoToken
        if (!inSession() || recording) return
        scheduleFinal("结束组字", SystemClock.uptimeMillis() + 150, generation)
    }

    private fun cancelFinalSpeech() = synchronized(echoGate) {
        echoGate.cancel()
        echoToken = 0L
        main.removeCallbacksAndMessages(token)
    }

    private fun scheduleFinal(why: String, at: Long, generation: Long) {
        synchronized(echoGate) {
            if (generation == 0L || generation != echoToken) return
            main.removeCallbacksAndMessages(token)
            main.postAtTime({
                try {
                    speakFinal(why, generation)
                } catch (t: Throwable) {
                    L.hookError("Voice.finalSpeech", t)
                }
            }, token, at)
        }
    }

    private fun speakFinal(why: String, generation: Long = echoToken) {
        val t = synchronized(echoGate) {
            if (spoken || generation != echoToken) return
            val text = confirmedWritten.trim()
            if (text.isEmpty() || !echoGate.accept(generation)) return
            spoken = true
            sessionUntil = 0
            main.removeCallbacksAndMessages(token)
            text
        }
        L.i("语音识别结果（$why）：「$t」")
        TypingEcho.voiceResult(t)
    }
}
