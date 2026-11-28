package io.github.doubaoa11y.hook

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import io.github.doubaoa11y.ConfigSpec

/**
 * 全屏手势里跟“键盘”有关的动作：上屏候选、清空候选、发送、收起键盘、
 * 切键盘、剪贴板、常用语、菜单、编辑面板、语音输入、智能整理。
 *
 * 这些不走编辑框，走的是豆包键盘自己的按键和工具栏按钮，所以和 EditActions 分开两份。
 */
object KeyboardOps {
    private val main = Handler(Looper.getMainLooper())
    private var organizeToken = 0L
    private var organizeWatchUntil = 0L
    private var organizeBeforeText: String? = null
    private var organizeStartHintRunner: Runnable? = null
    private var organizePollRunner: Runnable? = null
    private var organizeDoneRunner: Runnable? = null
    private var lastOrganizeTip = ""
    private var lastOrganizeTipAt = 0L
    private fun root(): View? = try {
        ViewA11yHooks.service?.window?.window?.decorView
    } catch (t: Throwable) {
        null
    }

    private fun service() = try {
        ViewA11yHooks.service
    } catch (t: Throwable) {
        null
    }

    private fun ic() = try {
        ViewA11yHooks.service?.currentInputConnection
    } catch (t: Throwable) {
        null
    }

    /** 上屏：打拼音时点空格，把选中的候选写进编辑框 */
    fun commit(): Boolean {
        if (!Candidates.composing) {
            Speaker.speak("现在没有候选")
            return false
        }
        if (VirtualKeys.tapKeyNamed("空格")) {
            L.i("全屏手势：上屏候选")
            return true
        }
        Speaker.speak("没找到空格键")
        return false
    }

    /**
     * 清空候选：把豆包原生引擎的拼音/组字缓冲和候选栏彻底清掉，不上屏，也不删除已上屏的正文。
     *
     * 实测 1.5.6 仅调 finishPreeditNative(false) 只清了编辑框组字，C++ 引擎里仍留着旧拼音。
     * 按以下顺序逐层清理并核验引擎组字状态（isTypingInput）：
     * 1) 引擎仍在组字时，循环调用 KeyboardJni.handleJavaBackspaceClickEvent() 逐字退掉拼音
     *    （每次调用前都核对 isTypingInput() == true，绝不误删已上屏文字）；
     * 2) 若仍未清空且引擎仍在组字，调用 KeyboardJni.doUpClearAction(1)（对应原生上滑清空输入 SwipeUpClearInput）；
     * 3) 若仍未清空，模拟豆包原生“在删除键上向左滑动”清空拼音手势；
     * 4) 手写/非拼音组字或以上都不可用时，降级调 finishPreeditNative(false) + InputConnection 清理。
     */
    fun clearCandidate(): Boolean {
        val hasCand = Candidates.composing || Candidates.engineTyping() == true
        if (!hasCand) {
            Speaker.speak("现在没有候选")
            return false
        }
        // 清理过程中屏蔽中间候选朗读和删字回显，免得打断“候选栏已清空”
        Candidates.suppressSpeakFor(350)
        TypingEcho.suppressDeleteFor(350)
        var how = clearEngineComposing()
        if (how == null) {
            // 仅手写候选或找不到原生方法：降级清理 preedit 与编辑框
            val preeditOk = finishPreeditOnly()
            val ic = ic()
            if (!preeditOk && ic == null) {
                Speaker.speak("现在没有候选")
                return false
            }
            if (!preeditOk && ic != null) {
                try {
                    ic.setComposingText("", 1)
                    ic.finishComposingText()
                } catch (t: Throwable) {
                    L.hookError("KeyboardOps.clearCandidate.fallback", t)
                    return false
                }
            }
            how = if (preeditOk) "finishPreeditNative降级" else "InputConnection降级"
        }
        Candidates.reset("清空候选")
        L.i("全屏手势：清空候选（$how，剩余组字=${Candidates.engineTyping()}）")
        // 用户反馈：上滑清空候选栏之后一点声音都没有，不知道到底清没清掉
        Speaker.speak("候选栏已清空")
        return true
    }

    private fun clearEngineComposing(): String? {
        if (Candidates.engineTyping() != true) return null
        val c = JniHooks.jniClass
        val j = JniHooks.jni()

        if (c != null && j != null) {
            // 1) 优先在确认引擎组字中调用 doUpClearAction(1)（对应 SwipeUpClearInput，仅清组字不动已上屏正文）
            val intType = Int::class.javaPrimitiveType!!
            val upClear = de.robv.android.xposed.XposedHelpers.findMethodExactIfExists(c, "doUpClearAction", intType)
            if (upClear != null) {
                try {
                    upClear.invoke(j, 1)
                    if (Candidates.engineTyping() != true) return "原生doUpClearAction(1)"
                    L.w("清空候选：doUpClearAction(1) 后引擎仍在组字，继续尝试退格清理")
                } catch (t: Throwable) {
                    L.hookError("KeyboardOps.clearCandidate.upClear", t)
                }
            }

            // 2) 降级逐次退掉原生拼音缓冲：每次前检查 isTypingInput()，不可能误删已上屏文字
            if (Candidates.engineTyping() == true) {
                val bs = de.robv.android.xposed.XposedHelpers.findMethodExactIfExists(c, "handleJavaBackspaceClickEvent")
                if (bs != null) {
                    try {
                        var steps = 0
                        var stagnant = 0
                        var prevPy = Candidates.pinyin
                        var prevFirst = Candidates.current.firstOrNull().orEmpty()
                        while (steps < 64 && Candidates.engineTyping() == true) {
                            bs.invoke(j)
                            steps++
                            if (Candidates.engineTyping() != true) break
                            val nowPy = Candidates.pinyin
                            val nowFirst = Candidates.current.firstOrNull().orEmpty()
                            if (nowPy == prevPy && nowFirst == prevFirst) {
                                stagnant++
                                if (stagnant >= 2) break
                            } else {
                                stagnant = 0
                                prevPy = nowPy
                                prevFirst = nowFirst
                            }
                        }
                        if (Candidates.engineTyping() != true) return "原生退格${steps}次"
                        L.w("清空候选：handleJavaBackspaceClickEvent 执行 $steps 次后引擎仍在组字，降级模拟左滑删除键")
                    } catch (t: Throwable) {
                        L.hookError("KeyboardOps.clearCandidate.backspace", t)
                    }
                }
            }
        }

        // 3) 兜底：模拟豆包原生左滑删除键清空候选栏
        if (Candidates.engineTyping() == true && VirtualKeys.swipeLeftDelete()) {
            return "模拟左滑删除键"
        }
        return null
    }

    private fun finishPreeditOnly(): Boolean {
        val c = JniHooks.jniClass ?: return false
        val j = JniHooks.jni() ?: return false
        val z = Boolean::class.javaPrimitiveType!!
        return try {
            val m1 = de.robv.android.xposed.XposedHelpers.findMethodExactIfExists(c, "finishPreeditNative", z)
            if (m1 != null) {
                m1.invoke(j, false)
                return true
            }
            val m2 = de.robv.android.xposed.XposedHelpers.findMethodExactIfExists(c, "finishPreeditNative", z, z)
            if (m2 != null) {
                m2.invoke(j, false, false)
                return true
            }
            false
        } catch (t: Throwable) {
            L.hookError("KeyboardOps.finishPreeditOnly", t)
            false
        }
    }

    /**
     * 发送（1.2.1 重做）。
     *
     * 老版本先去键盘上找名字叫“发送”的键——豆包只有开了“回车键发送消息”、
     * 并且输入框支持发送时，回车键才会画成“发送”，不然就找不到，发送手势直接哑火（Bug4）；
     * 换一种输入框（搜索框、聊天框）行为又不一样，还得靠用户自己配关键词。
     *
     * 现在按“应用自己的发送”优先，完全不看豆包的设置：
     * 1. 编辑框声明的回车动作（IME_ACTION_SEND/GO/SEARCH/DONE）→ 直接 [InputConnection.performEditorAction]，
     *    这是应用自己实现的“发送”，和豆包怎么画回车键没有关系；
     * 2. 没声明动作（微信、QQ 这类常见）就先试一次 IME_ACTION_SEND，认的应用会照发；
     * 3. 再退到键盘上按关键词找发送键（用户自定义的“发送、发送键”之类）；
     * 4. 最后才是普通回车，并且看编辑框有没有变化来判断到底发出去没有（Bug7：不再依赖回车键发送开关）。
     */
    /**
     * 豆包自己的发送。
     *
     * 反编译 1.5.5 核出来的入口（都在 KeyboardJni 里，PUBLIC STATIC，非 native）：
     * - `doSendAction()`：回车/发送键走的就是它，按 mCurrentEditboxAction（编辑框声明的回车动作）
     *   自己决定是发送、搜索还是换行，B 站那种要拦的也由它拦；
     * - `doToolbarSendAction()`：工具栏“发送”按钮走它，语音面板发送时会直接 performEditorAction(4)。
     *
     * 调用它等于“用户按了豆包的回车/发送键”，不需要“回车键发送消息”这个开关，
     * 也不依赖编辑框有没有画成“发送”两个字。
     */
    /**
     * 调豆包自己的发送 —— 就是工具栏上那个"发送"按钮按下去走的路。
     *
     * 反编译核对（豆包 1.5.5，`KeyboardJni`）：
     * 工具栏按钮的点击处理是 `IdleClickHandler` → `getCurrentEnterType()` →
     * `shouldUseVoicePanelSendAction()` → **`doToolbarSendAction()`**，它内部再按
     * `mCurrentEditboxAction` 走 `performEditorAction` / `sendDownUpKeyEvents`，
     * 还会顺手把工具栏的撤销按钮点亮。所以顺序必须是 doToolbarSendAction 优先。
     *
     * `doSendAction()` 是"编辑框那边的动作"版本（不带工具栏的收尾），作为第二选择。
     * 两个都是公开静态方法，不需要键盘上真有一个发送键，也不需要回车。
     */
    private fun doubaoSend(): Boolean {
        val c = JniHooks.jniClass ?: return false
        // 先定位一个签名匹配的入口；调用后即使抛异常，也不再尝试另一个，避免重复发送。
        val m = arrayOf("doToolbarSendAction", "doSendAction").firstNotNullOfOrNull {
            de.robv.android.xposed.XposedHelpers.findMethodExactIfExists(c, it)
        } ?: return false
        return try { m.invoke(null); true } catch (t: Throwable) { L.hookError("KeyboardOps.send", t); false }
    }

    fun send(announce: Boolean = true): Boolean {
        if (!A11yState.recording) return false
        if (!doubaoSend()) {
            Speaker.speak("当前豆包版本无法执行发送，请使用应用自己的发送按钮")
            return false
        }
        if (announce) Speaker.speak("已发送")
        return true
    }

    /** 显式动作：只调用一次发送入口，然后收起；不声称服务端已收到消息。 */
    fun sendAndHide(): Boolean {
        if (!send(announce = false)) return false
        val hidden = hide(announce = false)
        if (hidden && !Config.b("announceHide")) {
            Speaker.speak("已发送，键盘已收起")
        } else {
            Speaker.speak("已发送")
        }
        return hidden
    }

    /** 返回：先收起豆包自己开着的面板（菜单、剪贴板、常用语），都没有就收起键盘 */
    fun back(): Boolean {
        if (EditPanel.isOpen) {
            EditPanel.close()
            Speaker.speak("已返回键盘")
            return true
        }
        if (CustomToolbar.editing()) {
            EditPanel.closeToolboxThenOpen()
            return true
        }
        if (StretchPanel.isOpen) {
            StretchPanel.close()
            Speaker.speak("已返回键盘")
            return true
        }
        return hide()
    }

    /** 收起键盘 */
    fun hide(announce: Boolean = true): Boolean {
        val svc = service() ?: return false
        return try {
            svc.requestHideSelf(0)
            if (announce && !Config.b("announceHide")) {
                Speaker.speak("键盘已收起")
            }
            true
        } catch (t: Throwable) {
            L.hookError("KeyboardOps.hide", t)
            false
        }
    }

    /** 切到某种键盘：按按键名找，找不到就报没有 */
    fun switchTo(names: List<String>, what: String): Boolean {
        for (name in names) {
            if (VirtualKeys.tapKeyNamed(name)) {
                L.i("全屏手势：切到$what（$name）")
                return true
            }
        }
        Speaker.speak("没找到${what}的按键")
        return false
    }

    /** 打开工具栏上的某个按钮（剪贴板、常用语、菜单） */
    private fun tapToolbar(name: String, what: String): Boolean {
        if (VirtualKeys.tapKeyNamed(name)) return true
        val r = root() ?: return false
        val v = findByName(r, name) ?: run {
            Speaker.speak("没找到" + what + "按钮")
            return false
        }
        L.i("全屏手势：打开$what")
        VirtualKeys.tapViewOnScreen(r, v)
        return true
    }

    fun clipboard(): Boolean = tapToolbar("剪贴板", "剪贴板")

    fun phrases(): Boolean = tapToolbar("常用语", "常用语")

    fun menu(): Boolean = tapToolbar("菜单", "菜单")

    /** 编辑面板：菜单开着时先关菜单 */
    fun editPanel(): Boolean {
        if (EditPanel.isOpen) {
            EditPanel.close()
            Speaker.speak("已返回键盘")
            return true
        }
        if (CustomToolbar.editing()) {
            EditPanel.closeToolboxThenOpen()
            return true
        }
        val ok = EditPanel.open()
        if (!ok) Speaker.speak("打不开编辑面板")
        return ok
    }

    /** 正在用豆包原生语音说话（下一次执行就是结束） */
    @Volatile
    private var voiceOn = false

    /** 键盘收起时清掉语音与智能整理监听状态 */
    fun resetVoice() {
        voiceOn = false
        cancelOrganizeWatch()
    }

    /** 豆包那边语音真的停了（用户自己点麦克风停的、或者豆包自己结束的）：状态对齐 */
    fun onVoiceStopped() {
        voiceOn = false
    }

    /** 当前是否处于语音输入激活态（供手势层判断是否已发起起录，避免普通手势音效覆盖起录提示声） */
    fun isVoiceActive(): Boolean = voiceOn || Voice.isRecording()

    /**
     * 语音输入：调豆包自己的语音输入，不再模拟“长按空格”。
     *
     * 反编译核出来的入口：
     * - 开始：KeyboardJni.DoFunctionKey(KEY_START_SPEECH = 6)
     * - 结束：优先 KeyboardJni.DoFunctionKey(KEY_STOP_SPEECH_WAIT = 36)（对应 AsrManager.a0("keyUp", noWaitResult=false)），
     *   其次 KeyboardJni.AsrStopCommand(false, "tool")。
     *   ★ 不能用 KEY_STOP_SPEECH = 7：7 会走 noWaitResult=true 立刻强杀 ASR 并提前结束组字，导致尾句 2~6 个字丢失；
     *   36 会先停麦并进入 KStoping 等待豆包服务端返回完整尾字后再结束组字，与松开空格键行为一致。
     */
    private fun doubaoVoice(start: Boolean): Boolean {
        val c = JniHooks.jniClass ?: return false
        return try {
            if (start) {
                val startKey = staticIntField(c, "KEY_START_SPEECH") ?: 6
                de.robv.android.xposed.XposedHelpers.callStaticMethod(c, "DoFunctionKey", startKey)
                L.i("语音：调用豆包 DoFunctionKey($startKey)")
                return true
            }
            val stopWaitKey = staticIntField(c, "KEY_STOP_SPEECH_WAIT")
            if (stopWaitKey != null) {
                de.robv.android.xposed.XposedHelpers.callStaticMethod(c, "DoFunctionKey", stopWaitKey)
                L.i("语音：调用豆包 DoFunctionKey($stopWaitKey)（等待尾字识别完成）")
                return true
            }
            // 降级 1：调用非立即强停的 AsrStopCommand(false, "tool")
            try {
                de.robv.android.xposed.XposedHelpers.callStaticMethod(c, "AsrStopCommand", false, "tool")
                L.i("语音：调用豆包 AsrStopCommand(false, tool)（降级等待尾字）")
                return true
            } catch (_: Throwable) {
            }
            // 降级 2：旧版豆包无等待停止入口时退回 KEY_STOP_SPEECH = 7
            val stopKey = staticIntField(c, "KEY_STOP_SPEECH") ?: 7
            de.robv.android.xposed.XposedHelpers.callStaticMethod(c, "DoFunctionKey", stopKey)
            L.i("语音：调用豆包 DoFunctionKey($stopKey)（旧版降级）")
            true
        } catch (t: Throwable) {
            L.w("语音：调用豆包语音入口失败：${t.javaClass.simpleName}")
            false
        }
    }

    private fun staticIntField(cls: Class<*>, name: String): Int? = try {
        val f = cls.getDeclaredField(name)
        f.isAccessible = true
        (f.get(null) as? Number)?.toInt()
    } catch (_: Throwable) {
        null
    }

    fun voice(): Boolean {
        // 该开始还是该结束，以“豆包是不是真的在录音”为准：
        // 用户自己点键盘上的麦克风时模块不知道，只看 voiceOn 会出现“第一下反而是停止”
        val start = !Voice.isRecording() && !voiceOn
        if (start) Speaker.stop()
        if (doubaoVoice(start)) {
            voiceOn = start
            // ★ 开始说话时**绝对不能出声**（不发播报、不调语音库）：
            // 播报要过 TTS，TTS 一出声就抢音频焦点，豆包的识别当场就停——
            // 这里由 Voice.onStart() 播放用户设置的“语音开始”音效（SoundPool 通道，不抢音频焦点）。
            // 结束说话也不再朗读“结束语音”，避免与即将上屏的识别结果播报冲突。
            return true
        }
        // 豆包版本对不上时退回老办法（按住空格）
        val on = VirtualKeys.toggleVoice()
        voiceOn = on
        return true
    }

    /**
     * 智能整理：直接对接豆包底层智能整理功能，不展开菜单、不模拟点击按钮。
     *
     * 提示与反馈链路：
     * 1. 触发后立即监听工具栏提示条（ImeTooltipView / showToast），若豆包在 120ms 内直接弹出
     *    “请先输入内容再整理”等即时提示，直接朗读该提示不重复播报；
     * 2. 若 120ms 内未报错（说明已发起异步整理），播报“正在智能整理”；
     * 3. 整理过程中工具栏弹出任意结果提示（如“表达清晰，无需整理”“整理超时”“整理失败”等），
     *    第一时间抓取并朗读原话；
     * 4. 若豆包成功替换了编辑框正文，播报“整理完成，[整理后的文字]”。
     */
    fun smartOrganize(): Boolean {
        if (!A11yState.recording) return false
        cancelOrganizeWatch()
        val before = readEditorText()
        val now = SystemClock.uptimeMillis()
        val tok = ++organizeToken
        organizeWatchUntil = now + 15_000L
        organizeBeforeText = before
        val ok = when {
            ToolbarInject.triggerToolbarAction("btn_toolbar_text_organize") -> {
                L.i("全屏手势：直接触发智能整理（工具栏底层分发）")
                true
            }
            EditHooks.triggerToolboxAction("TEXT_ORGANIZE") -> {
                L.i("全屏手势：直接触发智能整理（工具箱底层分发）")
                true
            }
            else -> false
        }
        if (!ok) {
            cancelOrganizeWatch()
            L.w("全屏手势：当前豆包版本未能直连智能整理入口")
            Speaker.speak("当前豆包版本无法直接执行智能整理")
            return false
        }
        val startHint = Runnable {
            if (tok != organizeToken || SystemClock.uptimeMillis() > organizeWatchUntil) return@Runnable
            organizeStartHintRunner = null
            Speaker.speak("正在智能整理")
        }
        organizeStartHintRunner = startHint
        main.postDelayed(startHint, 80L)
        if (before != null) {
            scheduleOrganizePoll(tok)
        }
        return true
    }

    /**
     * 收到工具栏提示条（ImeTooltipView / showToast）文案：
     * - 语音输入期间（或刚结束语音的保护期、语音来源提示）绝不抓取朗读，避免干扰语音输入；
     * - 其他工具栏功能提示（如翻译、智能整理、剪贴板等）即时朗读，并做短时防重。
     */
    fun onToolbarTip(raw: String, fromVoice: Boolean = false) {
        val text = raw.trim()
        if (text.isEmpty()) return
        if (!A11yState.recording) return
        if (fromVoice || Voice.isVoiceBusy() || isVoiceActive() || VirtualKeys.voiceHeld()) {
            L.i("语音输入期间忽略工具栏提示：「$text」")
            return
        }
        val now = SystemClock.uptimeMillis()
        if (text == lastOrganizeTip && now - lastOrganizeTipAt < 800L) return
        lastOrganizeTip = text
        lastOrganizeTipAt = now
        if (now <= organizeWatchUntil) {
            organizeStartHintRunner?.let { main.removeCallbacks(it) }
            organizeStartHintRunner = null
            organizeDoneRunner?.let { main.removeCallbacks(it) }
            organizeDoneRunner = null
            val inProgress = text.contains("正在") || text.contains("整理中")
            if (!inProgress) {
                organizePollRunner?.let { main.removeCallbacks(it) }
                organizePollRunner = null
                organizeWatchUntil = 0L
            }
            L.i("智能整理工具栏提示：「$text」")
        } else {
            L.i("工具栏功能提示：「$text」")
        }
        Speaker.echo(text)
    }

    /** 收到豆包 1.5.7 原生智能整理失败/降级原因，立即结束 15 秒盲等并播报明确提示 */
    fun onOrganizeFallback(reason: String, message: String?) {
        val now = SystemClock.uptimeMillis()
        if (now > organizeWatchUntil) return
        L.i("智能整理原生回退：reason=$reason -> ${message ?: "静默取消"}")
        cancelOrganizeWatch()
        if (!message.isNullOrBlank()) Speaker.echo(message)
    }

    /** 结束当前智能整理监听（收到其他手势、用户手动按键或收起键盘时调用） */
    fun cancelOrganizeWatch() {
        organizeToken++
        organizeWatchUntil = 0L
        organizeBeforeText = null
        organizeStartHintRunner?.let { main.removeCallbacks(it) }
        organizeStartHintRunner = null
        organizePollRunner?.let { main.removeCallbacks(it) }
        organizePollRunner = null
        organizeDoneRunner?.let { main.removeCallbacks(it) }
        organizeDoneRunner = null
    }

    /** 当前是否正在等待智能整理结果（供 InputHooks / ServiceHooks 零开销快速判断） */
    val isWatchingOrganize: Boolean get() = organizeWatchUntil > 0L

    /** 编辑器发生文本或选区变化时提前唤醒智能整理核验，不等 1000ms 兜底轮询 */
    fun onEditorTextMayHaveChanged() {
        val tok = organizeToken
        if (organizeWatchUntil <= 0L || organizeDoneRunner != null) return
        if (SystemClock.uptimeMillis() > organizeWatchUntil) {
            cancelOrganizeWatch()
            return
        }
        organizePollRunner?.let { main.removeCallbacks(it) }
        val r = Runnable { checkOrganizeNow(tok) }
        organizePollRunner = r
        main.post(r)
    }

    private fun checkOrganizeNow(tok: Long) {
        if (tok != organizeToken) return
        if (SystemClock.uptimeMillis() > organizeWatchUntil) {
            cancelOrganizeWatch()
            return
        }
        val nowText = readEditorText()
        val before = organizeBeforeText
        if (nowText != null && before != null && nowText != before) {
            organizeStartHintRunner?.let { main.removeCallbacks(it) }
            organizeStartHintRunner = null
            organizePollRunner = null
            val done = Runnable {
                if (tok != organizeToken) return@Runnable
                organizeDoneRunner = null
                organizeWatchUntil = 0L
                val trimmed = nowText.trim()
                val msg = if (trimmed.isEmpty()) "整理完成" else "整理完成，$trimmed"
                L.i("智能整理完成，编辑框字数 ${before.length} → ${nowText.length}")
                Speaker.echo(msg)
            }
            organizeDoneRunner = done
            // 留 90ms 让可能同时到达的工具栏提示或编辑框事件先落地
            main.postDelayed(done, 90L)
            return
        }
        scheduleOrganizePoll(tok)
    }

    private fun scheduleOrganizePoll(tok: Long) {
        val runner = Runnable { checkOrganizeNow(tok) }
        organizePollRunner = runner
        main.postDelayed(runner, 1000L)
    }

    private fun readEditorText(): String? {
        return try {
            val c = ic() ?: return null
            if (!InputPrivacy.mayRead()) return null
            c.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString()
        } catch (_: Throwable) {
            null
        }
    }

    fun handles(a: Int): Boolean = when (a) {
        ConfigSpec.ACT_COMMIT, ConfigSpec.ACT_CLEAR_CANDIDATE, ConfigSpec.ACT_SEND, ConfigSpec.ACT_SEND_AND_HIDE,
        ConfigSpec.ACT_HIDE, ConfigSpec.ACT_SWITCH_SCREEN,
        ConfigSpec.ACT_BACK,
        ConfigSpec.ACT_SYMBOL, ConfigSpec.ACT_ENGLISH, ConfigSpec.ACT_CHINESE, ConfigSpec.ACT_NUMBER,
        ConfigSpec.ACT_CLIPBOARD, ConfigSpec.ACT_PHRASES, ConfigSpec.ACT_MENU,
        ConfigSpec.ACT_EDIT_PANEL, ConfigSpec.ACT_VOICE, ConfigSpec.ACT_SMART_ORGANIZE,
        -> true
        else -> false
    }

    fun run(a: Int): Boolean = try {
        when (a) {
            ConfigSpec.ACT_COMMIT -> commit()
            ConfigSpec.ACT_CLEAR_CANDIDATE -> clearCandidate()
            ConfigSpec.ACT_SEND -> send()
            ConfigSpec.ACT_SEND_AND_HIDE -> sendAndHide()
            ConfigSpec.ACT_SWITCH_SCREEN -> FullscreenMode.toggleScreenMode()
            ConfigSpec.ACT_HIDE -> hide()
            ConfigSpec.ACT_BACK -> back()
            ConfigSpec.ACT_SYMBOL -> switchTo(listOf("符号", "符号键盘"), "符号键盘")
            ConfigSpec.ACT_ENGLISH -> switchTo(listOf("英文输入", "英文", "英文键盘"), "英文键盘")
            ConfigSpec.ACT_CHINESE -> switchTo(listOf("中文输入", "中文", "中文键盘"), "中文键盘")
            ConfigSpec.ACT_NUMBER -> switchTo(listOf("数字", "数字键盘", "123"), "数字键盘")
            ConfigSpec.ACT_CLIPBOARD -> clipboard()
            ConfigSpec.ACT_PHRASES -> phrases()
            ConfigSpec.ACT_MENU -> menu()
            ConfigSpec.ACT_EDIT_PANEL -> editPanel()
            ConfigSpec.ACT_VOICE -> voice()
            ConfigSpec.ACT_SMART_ORGANIZE -> smartOrganize()
            else -> false
        }
    } catch (t: Throwable) {
        L.hookError("KeyboardOps.run($a)", t)
        false
    }

    private fun findByName(v: View, name: String): View? {
        if (v.visibility != View.VISIBLE) return null
        if (ViewA11yHooks.spokenName(v) == name && (v.isClickable || v.isLongClickable)) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) {
            findByName(v.getChildAt(i), name)?.let { return it }
        }
        return null
    }
}
