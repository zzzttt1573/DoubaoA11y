package io.github.doubaoa11y.hook

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import io.github.doubaoa11y.ConfigSpec
import java.text.BreakIterator
import java.util.Locale

/**
 * 全屏手势用到的文本编辑动作。全部用当前输入框的 InputConnection 做，和编辑面板（EditPanel）互相独立，
 * 只在全屏手势里用；编辑面板照旧。
 *
 * 每个动作都自己朗读结果：移动就读越过的那个字，到边界读“已到开头”“已到末尾”。
 *
 * “选择文本（多选）”进入选择模式后，单指左右滑动（上一个/下一个字符）改成左右扩展选择，
 * 删除则删掉选中的一段，直到“取消选择”、上屏或者换输入框。
 */
object EditActions {
    private val main = Handler(Looper.getMainLooper())

    /** 是否在选择模式（多选）里：进入后方向动作变成扩展选择 */
    @Volatile
    private var selecting = false
    private var selectionGeneration = 0L

    /** 选择模式的起点（绝对偏移），-1 表示还没开始选 */
    private var anchor = -1

    /**
     * 选择模式的**活动端**（绝对偏移）：真正跟着手指跑的那一头。
     *
     * 以前这里图省事，每次都用“选区里较大的那个偏移”当活动端，于是：
     * 光标停在文本末尾进入选择态、往左滑，选区是 [末尾-1, 末尾]，较大端永远是末尾，
     * 活动端看起来一直没动 ⇒ 怎么滑都选不出反方向（用户反馈的核心 bug）。
     * Android 的选区本来就分“固定端 selectionStart / 活动端 selectionEnd”，
     * 编辑框也可能把方向吃掉（有的实现 setSelection(lo,hi) 后 end 恒等于大的一端），
     * 所以活动端必须我们自己记：手指往哪边跑，它就是哪边。
     */
    private var activeEnd = -1

    /** 动作已自行播报过结果后的防重标记，避免后续 onUpdateSelection 重复触发 */
    private val speechGate = SelectionSpeechGate()

    /** 主动发起选择/全选/扩选后的保护截止时间，避免期间瞬时零长度回调误清选择态 */
    private var selectGuardUntil = 0L

    private data class PendingSpeech(val token: Long, val afterSelMs: Long, val deliver: () -> kotlin.Unit)
    private var speechToken = 0L
    private var pendingSpeech: PendingSpeech? = null
    private var pendingSpeechRunner: Runnable? = null

    private data class PendingExtend(
        val token: Long,
        val how: String,
        val unit: Unit,
        val dir: Int,
        val prevText: String,
        val prevOffset: Int,
        val prevLo: Int,
        val prevHi: Int,
        val allowFallback: Boolean,
    )
    private var extendToken = 0L
    private var pendingExtend: PendingExtend? = null
    private var pendingExtendRunner: Runnable? = null

    private fun cancelPendingSpeech() {
        speechToken++
        pendingSpeech = null
        pendingSpeechRunner?.let { main.removeCallbacks(it) }
        pendingSpeechRunner = null
    }

    private fun cancelPendingExtend() {
        extendToken++
        pendingExtend = null
        pendingExtendRunner?.let { main.removeCallbacks(it) }
        pendingExtendRunner = null
    }

    private fun selectionDelayMs(): Long = SelectionOverrideTiming.selectionDelayMs(A11yState.readerPackage)
    private fun cancelSelectionDelayMs(): Long = SelectionOverrideTiming.cancelSelectionDelayMs(A11yState.readerPackage)
    private fun textChangeDelayMs(): Long = SelectionOverrideTiming.textChangeDelayMs(A11yState.readerPackage)

    private fun postDelayedSpeech(delayMs: Long, text: String, isEcho: Boolean = false) {
        if (text.isEmpty()) return
        cancelPendingSpeech()
        if (delayMs <= 0L) {
            if (isEcho) Speaker.echo(text) else Speaker.speak(text)
            return
        }
        val tok = ++speechToken
        val runner = Runnable {
            if (speechToken != tok) return@Runnable
            pendingSpeechRunner = null
            if (isEcho) Speaker.echo(text) else Speaker.speak(text)
        }
        pendingSpeechRunner = runner
        main.postDelayed(runner, delayMs)
    }

    /**
     * 把动作结果播报对齐到目标编辑框的 onUpdateSelection 之后再发声：
     * - 天坦全屏屏蔽已生效或独立 TTS 模式：不依赖读屏事件顺序，立即播报；
     * - 其他读屏（解说/保益/点明等）的读屏播报模式：等待 onUpdateSelection 到达后再延迟
     *   [afterSelMs] 毫秒发 HOVER_ENTER，在读屏刚开口念 8192/16 的瞬间将其打断覆盖；
     *   若目标 App 不回调则由 [fallbackMs] 兜底。
     */
    private fun scheduleAfterSelection(
        text: String,
        isEcho: Boolean = false,
        afterSelMs: Long = selectionDelayMs(),
        fallbackMs: Long = SelectionOverrideTiming.fallbackMs(afterSelMs),
    ) {
        if (text.isEmpty()) return
        if (!Speaker.readerWillSpeak()) {
            cancelPendingSpeech()
            if (isEcho) Speaker.echo(text) else Speaker.speak(text)
            return
        }
        scheduleAfterSelectionAction(afterSelMs, fallbackMs) {
            if (isEcho) Speaker.echo(text) else Speaker.speak(text)
        }
    }

    private fun scheduleAfterSelectionAction(
        afterSelMs: Long = selectionDelayMs(),
        fallbackMs: Long = SelectionOverrideTiming.fallbackMs(afterSelMs),
        deliver: () -> kotlin.Unit,
    ) {
        cancelPendingSpeech()
        val tok = ++speechToken
        val ps = PendingSpeech(tok, afterSelMs, deliver)
        pendingSpeech = ps
        val runner = Runnable {
            if (pendingSpeech?.token != tok) return@Runnable
            pendingSpeech = null
            pendingSpeechRunner = null
            deliver()
        }
        pendingSpeechRunner = runner
        main.postDelayed(runner, fallbackMs)
    }

    /**
     * 接收 InputMethodService.onUpdateSelection 回调：
     * 1. 驱动扩选/缩选结果确认与增量差分朗读；
     * 2. 把已排队的动作播报从兜底超时切换为短落地缓冲，避开与 8192/16 竞态打断；
     * 3. 发现选区被外部清空时及时退出选择态。
     */
    fun onUpdateSelection(oldStart: Int, oldEnd: Int, newStart: Int, newEnd: Int) {
        if (!FullscreenMode.active && !selecting && pendingSpeech == null && pendingExtend == null) return
        try {
            val now = SystemClock.uptimeMillis()
            val ext = pendingExtend
            if (ext != null && newStart >= 0 && newEnd >= 0 && newStart != newEnd) {
                cancelPendingExtend()
                finishExtendSelection(ext, newStart, newEnd)
            }
            val ps = pendingSpeech
            if (ps != null) {
                val tok = ps.token
                pendingSpeech = null
                pendingSpeechRunner?.let { main.removeCallbacks(it) }
                pendingSpeechRunner = null
                if (ps.afterSelMs <= 0L) {
                    ps.deliver()
                } else {
                    val aligned = Runnable {
                        if (speechToken != tok) return@Runnable
                        pendingSpeechRunner = null
                        ps.deliver()
                    }
                    pendingSpeechRunner = aligned
                    main.postDelayed(aligned, ps.afterSelMs)
                }
            }
            if (selecting && pendingExtend == null && newStart >= 0 && newStart == newEnd && now >= selectGuardUntil) {
                if (newStart != anchor || oldStart != oldEnd) {
                    clearSelectionState("编辑框选区已清除")
                }
            }
        } catch (t: Throwable) {
            L.hookError("EditActions.onUpdateSelection", t)
        }
    }

    /** 现在是不是在选择模式（多选）里：选择模式下左右滑动是扩选，不能拿去切候选 */
    val isSelecting: Boolean get() = selecting

    /**
     * 给手势层用的选择态自检：真的还在选才返回 true。
     *
     * 直接看 [isSelecting] 这个标志位是不够的——一旦它有残留（键盘收起没走到、编辑框自己取消了选区），
     * 手势层会一直按“扩选”处理，候选优先、左右滑切候选全都进不去，用户看到的就是“动作没了”。
     * 这里拿编辑框的真实选区核对一次，对不上就顺手清掉。
     */
    fun stillSelectingNow(): Boolean {
        if (!selecting) return false
        val c = ic() ?: return false
        val s = snapshot(c) ?: return false
        return stillSelecting(s)
    }

    /**
     * 键盘上直接按了删除键（豆包原生的 deleteBackward / deleteSurroundingText，不是走我们的手势）。
     * 选择态下删掉选中文本后，编辑框就没有选中了，这时要自动退出选择态（天坦也是这么做的：
     * 文本一变就 setSelectingMode(false)）。这里延迟一下，等删除真正生效、选区更新后再判断。
     */
    fun onExternalDelete() {
        if (!selecting) return
        checkSelectionAfterDelete(120, clearWithoutConnection = false, reason = "删除键删掉选中文本")
    }

    /**
     * 键盘区（下半屏）按了删除键——全屏时摸到“删除”抬手、或者按住连续删除。
     *
     * 用户拿这个删掉选中的文本之后，编辑框里已经没有选区了，可选择态还挂着：
     * 左右滑还在扩选、编辑菜单轮盘还停在“剪切/复制/删除文本/取消选择”上，
     * 看着就像“没退出多选”（用户反馈）。这里等原生删除真的做完再核对一次。
     */
    fun onKeyDelete() {
        if (!selecting) {
            // 不在选择态：打字用的删除，编辑菜单轮盘照样回到“双击跳转行首行尾”
            EditOptions.keyTyped()
            return
        }
        checkSelectionAfterDelete(150, clearWithoutConnection = true, reason = "键盘删除键删掉选中文本")
    }

    private fun checkSelectionAfterDelete(delayMs: Long, clearWithoutConnection: Boolean, reason: String) {
        val generation = selectionGeneration
        main.postDelayed({
            if (generation != selectionGeneration || !selecting) return@postDelayed
            val connection = ic()
            if (connection == null && !clearWithoutConnection) return@postDelayed
            if (connection?.let { snapshot(it) }?.hasSelection != true) clearSelectionState(reason)
        }, delayMs)
    }

    /**
     * 键盘上打了字：选择态已经没意义了（打出来的字会替换掉选中的那段），静默退出。
     * 不播报、不动光标——用户正在打字，别插话。
     */
    fun leaveSelectionQuiet() {
        if (!selecting) return
        clearSelectionState("键盘打字，静默退出")
    }

    /**
     * 退出选择态的唯一入口：标志位、固定端、活动端、编辑菜单轮盘一起收拾干净。
     *
     * 之前散在各处手写 `selecting = false; anchor = -1`，漏掉过 activeEnd 和
     * EditOptions.selectionEnded()，于是出现“选择态退了、轮盘还停在剪切/复制/取消选择上”
     * 和“活动端留着旧偏移、下次进来直接乱扩选”这两种残留。
     */
    private fun clearSelectionState(reason: String) {
        selectionGeneration++
        cancelPendingExtend()
        selectGuardUntil = 0L
        if (!selecting && anchor < 0 && activeEnd < 0) return
        selecting = false
        anchor = -1
        activeEnd = -1
        EditOptions.selectionEnded()
        L.i("退出选择态（$reason）")
    }

    /**
     * 选择态到底还算不算数：拿编辑框的真实状态核对一遍，别让标志位“卡住”。
     *
     * 用户反馈的场景：点过“全选文本/选择文本”，收起键盘再展开，左右滑想移动光标，
     * 结果模块还认为自己处在选择态，每一次左右滑都被当成扩选，光标一步不动。
     *
     * 返回 true = 这次方向动作应该扩选；返回 false = 选择态已经失效，调用方按普通移动继续。
     */
    private fun stillSelecting(s: Snapshot): Boolean {
        if (!selecting) return false
        val base = s.offset
        val len = s.text.length
        val anchorOk = anchor in base..(base + len)
        val activeOk = activeEnd in base..(base + len)
        // 刚进入选择模式、还没滑过：选区收缩成一点，和固定端重合，这是合法状态
        if (anchorOk && activeOk && activeEnd == anchor) return true
        // 编辑框里确实有选中：老老实实按选择态走（哪怕 anchor 丢了，靠编辑框的选区也能继续扩）
        if (s.hasSelection) return true
        // 没选中、锚点也对不上：这就是残留的选择态。再按扩选处理只会让光标一动不动
        clearSelectionState("选区状态与编辑框对不上")
        return false
    }

    /**
     * 键盘收起（或开始收起）：直接退出选择态，**不跨收起—展开保留**。
     *
     * 以前这里是“收起前记住、展开时恢复”，本意是好的，实际用起来是坑：
     * 用户点过全选/选择文本后收起键盘，再展开想用左右滑移动光标，模块仍处在选择态，
     * 左右滑全被当成扩选，光标动不了（用户反馈）。天坦输入法也是键盘一收就结束选择模式，
     * 这里照做——想接着选，展开后重新执行“选择文本”就行，成本很低。
     */
    fun onWindowHidden() {
        selectionGeneration++
        resetEcho()
        if (!selecting) return
        val ic = ic()
        val s = ic?.let { snapshot(it) }
        val hasSel = s?.hasSelection == true
        val end = activeEnd
        clearSelectionState("键盘收起，收起前有选中=$hasSel")
        // 收起前编辑框还留着选区：顺手收成光标，不然重新展开又是一片蓝色高亮（用户反馈）
        if (hasSel) collapseSelection(ic, end, "键盘收起")
    }

    /**
     * 键盘展开：只做残留清理，绝不主动恢复选择态。
     *
     * 正常路径下 onWindowHidden 已经把选择态清掉了，这里兜的是“没经历收起就换了输入框/窗口重建”
     * 这类极端情况——真是残留，清掉；刚进入选择态的零长度状态由 stillSelecting 放行。
     */
    fun onWindowShown() {
        // 换了输入框、或者收起又展开：上一轮打字的候选会话到此结束，别再拦住左右滑
        Candidates.onCursorMove()
        resetEcho()
        if (!selecting) return
        val s = ic()?.let { snapshot(it) } ?: run { clearSelectionState("展开时拿不到编辑框"); return }
        if (!s.hasSelection && !(activeEnd == anchor && anchor >= 0)) {
            clearSelectionState("展开时发现选择态是残留的")
        }
    }

    // ---------------- 天坦式光标移动 ----------------
    //
    // 移动光标完全照搬天坦输入法的 GranularityIterator 思路：不用方向键（方向键会被豆包编辑框
    // 解释成“跳到行首/行尾”，还会把 InputConnection 焦点搞丢，导致“光标离开输入法”“文字无法上屏”），
    // 而是用 BreakIterator 在文本里自己算出下一个/上一个字符（或句子、段落）的边界，再用
    // setSelection 直接把光标移过去，最后读越过的那段字。这样操作的是 InputConnection 的选区，
    // 与读屏、豆包原生编辑面板各走各的，天然不冲突、不错位。

    /** 默认的“句子结束”标点，和天坦输入法 SENTENCE_SYMBOL_DEFAULT 一致 */
    private fun sentenceSymbols() = ConfigSpec.sentenceList(Config.s("sentenceSymbols")).toSet()

    private fun isSentenceSymbol(c: Char): Boolean = sentenceSymbols().contains(c.toString())

    private fun ic(): InputConnection? = try {
        ViewA11yHooks.service?.currentInputConnection
    } catch (t: Throwable) {
        null
    }

    /**
     * 编辑框里现在一个字都没有。
     *
     * 给编辑菜单用（空框只显示“粘贴”“收起键盘”）。这是跨进程问编辑框，别在每次按键里调，
     * 只在弹键盘、上屏、删除、粘贴这些节点调（见 [EditOptions.refresh]）。
     */
    fun editorEmpty(): Boolean {
        val c = ic() ?: return true
        return try {
            val before = c.getTextBeforeCursor(1, 0)
            val after = c.getTextAfterCursor(1, 0)
            val sel = c.getSelectedText(0)
            before.isNullOrEmpty() && after.isNullOrEmpty() && sel.isNullOrEmpty()
        } catch (t: Throwable) {
            L.hookError("EditActions.editorEmpty", t)
            true
        }
    }

    /** 整段文字、组字区起点、光标 */
    private class Snapshot(val text: String, val offset: Int, val start: Int, val end: Int) {
        val caret: Int get() = if (start == end) start else minOf(start, end)
        val hasSelection: Boolean get() = start != end
        fun indexOf(i: Int): Int = (i - offset).coerceIn(0, text.length)
    }

    private fun snapshot(ic: InputConnection): Snapshot? { return try {
        val type = ViewA11yHooks.service?.currentInputEditorInfo?.inputType ?: 0
        val variation = type and android.text.InputType.TYPE_MASK_VARIATION
        val cls = type and android.text.InputType.TYPE_MASK_CLASS
        if ((cls == android.text.InputType.TYPE_CLASS_TEXT && variation in setOf(android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD, android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (cls == android.text.InputType.TYPE_CLASS_NUMBER && variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD)) return null
        val et: ExtractedText = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return null
        val t = et.text?.toString() ?: return null
        Snapshot(t, et.startOffset, et.selectionStart + et.startOffset, et.selectionEnd + et.startOffset)
    } catch (t: Throwable) {
        null
    }

    }

    // ---------------- 字 ----------------

    fun prevChar() = step(-1)
    fun nextChar() = step(1)

    /**
     * 单指左右滑移动光标：照搬天坦输入法的做法——用 BreakIterator 在文本里算出下一个/上一个
     * 字符的边界，再用 setSelection 把光标移过去，读越过的那段字。
     *
     * 不用方向键的原因：方向键发出去后由豆包编辑框自己解释，个别编辑框把 DPAD_LEFT/RIGHT
     * 当成“跳到行首/行尾”，还会把 InputConnection 焦点弄丢，于是“移动几个字后就只会报开头结尾、
     * 光标不动，文字也上不了屏”。setSelection 直接改选区，不碰焦点、不碰按键，稳定可靠。
     */
    private fun step(dir: Int) {
        Candidates.onCursorMove()
        val ic = ic() ?: return
        // 选择态先核对一遍：残留的选择态（比如收起键盘再展开）会让左右滑变成扩选，光标一动不动
        val cached = if (selecting) snapshot(ic) else null
        if (selecting) {
            if (cached == null) {
                // 拿不到文本快照就退回方向键带 Shift 扩选
                arrowShift(if (dir < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
                return
            }
            if (stillSelecting(cached)) {
                extendSelection(ic, cached, dir, Unit.CHAR)
                return
            }
            // 选择态已失效（残留）：往下走普通移动
        }
        val s = cached ?: snapshot(ic) ?: run {
            // 拿不到文本快照（罕见）：退回方向键兜底，至少光标能动
            send(if (dir < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
            return
        }
        val caret = s.indexOf(s.caret)
        val target = boundaryOf(Unit.CHAR, s.text, caret, dir)
        if (target < 0) {
            // 到头了：往右到头只念全文，往左到头念“共 N 个字”+全文（见 speakBoundary
            speakBoundary(dir, s)
            return
        }
        // setSelection 直接移动光标（选区收缩成一点）
        ic.setSelection(s.offset + target, s.offset + target)
        // 光标一动，应用自己会发“选区变了”，读屏立刻把那个字念出来；
        // 走语音库播报时先把读屏压一下，免得同一个字念两遍
        Speaker.hushReader()
        // 始终朗读光标前面的字符，汉字带单字解释（如“好，好坏的好”）；光标移到全文最开头时前面无字，报“已到开头”
        val seg = charBeforeCaret(s.text, target)
        speakCharMove(seg)
    }

    private data class CaretChar(
        val full: String,
        val explainOnly: String?,
        val atStart: Boolean,
    )

    /** 换输入框、收起键盘时清掉，避免上一轮朗读或选区任务串到新输入框 */
    fun resetEcho() {
        rapidMove.reset()
        pendingEcho = ""
        main.removeCallbacks(burstRunner)
        main.removeCallbacks(echoRunner)
        cancelPendingSpeech()
        cancelPendingExtend()
        speechGate.reset()
        selectGuardUntil = 0L
    }

    /**
     * 移动光标到 target 之后，取光标**前面**的一个完整字符并附带单字解释：
     * - 光标在全文最开头（target <= 0）：前面没有字符，读“已到开头”；
     * - 单个汉字且开启单字解释：完整文案“好，好坏的好”，纯解释部分“好坏的好”；
     * - 标点/空白/换行/字母/数字/emoji：读符号名称或原字符。
     */
    private fun charBeforeCaret(text: String, target: Int): CaretChar {
        val len = text.length
        if (len == 0) return CaretChar("编辑框为空", null, atStart = true)
        val p = target.coerceIn(0, len)
        if (p <= 0) {
            return CaretChar("已到开头", null, atStart = true)
        }
        val bi = BreakIterator.getCharacterInstance(Locale.getDefault())
        bi.setText(text)
        val prev = bi.preceding(p).let { if (it == BreakIterator.DONE || it < 0) (p - 1).coerceAtLeast(0) else it }
        val unit = text.substring(prev, p)
        val explain = CharWords.explainOnly(unit)
        val full = if (explain != null) "$unit，$explain" else spokenUnit(unit)
        return CaretChar(full, explain, atStart = false)
    }

    /** 还没读出去的那一个字；快速连滑时被后来的覆盖，只留最后停留的那个 */
    private var pendingEcho = ""

    private val echoRunner = Runnable {
        val t = pendingEcho
        pendingEcho = ""
        if (t.isNotEmpty()) Speaker.speak(t)
    }

    /**
     * 移动光标后的单字朗读：
     * - 独立 TTS / 天坦全屏屏蔽：读屏不念 8192，模块完整念“好，好坏的好”（90ms 防抖合并，无双读）；
     * - 其他读屏（解说/保益/点明/TalkBack）走读屏播报时：
     *   1. 移到全文最开头（atStart）：读屏静默，由模块补报“已到开头”；
     *   2. 极速连滑（<=150ms）：读屏会因去抖/位差 >1 静默，停手 180ms 后由模块完整补念“好，好坏的好”；
     *   3. 正常单步移动：读屏自己会通过 8192 先念字头“好”，模块不再重复字头“好，”，
     *      仅在有汉字解释词时衔接补念“好坏的好”，彻底避免“好好，好坏的好”或非汉字双读。
     */
    private fun speakCharMove(info: CaretChar) {
        if (!Config.b("fsSpeakChar") || info.full.isEmpty()) return
        if (!Speaker.readerWillSpeak()) {
            if (info.atStart) {
                main.removeCallbacks(echoRunner)
                pendingEcho = ""
                Speaker.speak(info.full)
                return
            }
            pendingEcho = info.full
            main.removeCallbacks(echoRunner)
            main.postDelayed(echoRunner, 90)
            return
        }
        main.removeCallbacks(echoRunner)
        pendingEcho = ""
        cancelPendingSpeech()
        if (info.atStart) {
            main.removeCallbacks(burstRunner)
            rapidMove.reset()
            val d = cancelSelectionDelayMs()
            scheduleAfterSelection(info.full, isEcho = true, afterSelMs = d, fallbackMs = SelectionOverrideTiming.fallbackMs(d))
            return
        }
        val now = SystemClock.uptimeMillis()
        main.removeCallbacks(burstRunner)
        if (rapidMove.record(now, info.full)) {
            main.postDelayed(burstRunner, 180L)
            return
        }
        val explain = info.explainOnly ?: return
        val delay = if (A11yState.readerPackage == "com.nirenr.talkman" || A11yState.readerPackage.isEmpty()) 260L else 190L
        scheduleAfterSelection(explain, isEcho = true, afterSelMs = delay, fallbackMs = delay + 90L)
    }

    // ---------------- 极速连滑漏读兜底 ----------------

    private val rapidMove = RapidMoveDetector(maxGapMs = 150L)

    private val burstRunner = Runnable {
        val (n, t) = rapidMove.consumePending()
        if (n >= 2 && t.isNotEmpty()) {
            L.i("极速连滑 $n 格，读屏可能漏念，模块补念最后一格「$t」")
            Speaker.echo(t)
        }
    }


    /**
     * 光标已经到了开头/结尾还继续滑：先完整念一遍正文，再报“共N个字”。
     * 换行读成停顿（`\n` → 逗号），`\r\n` 直接去掉，别让读屏念成“斜杠 n”或者直接吃掉。
     */
    private fun speakBoundary(dir: Int, s: Snapshot) {
        Sound.play("boundary")
        val text = s.text
        val n = text.length
        if (n == 0) {
            Speaker.speak("编辑框为空")
            return
        }
        val whole = text.replace("\r\n", "").replace('\n', '，').trim()
        Speaker.unmute()
        val msg = if (whole.isEmpty()) "共${n}个字" else "${whole}，共${n}个字"
        Speaker.echo(msg)
        L.i("光标到边界：${if (dir < 0) "开头" else "结尾"}（全文+字数），共 $n 个字")
    }

    /** 光标所在的那一句 / 那一段（按用户配置的标点断句） */
    private fun segmentAt(text: String, pos: Int, para: Boolean): String {
        val len = text.length
        if (len == 0) return ""
        val p = pos.coerceIn(0, len)
        if (para) {
            var a = p
            while (a > 0 && text[a - 1] != '\n') a--
            var b = p
            while (b < len && text[b] != '\n') b++
            return text.substring(a, b).trim()
        }
        var a = p
        while (a > 0 && !isSentenceSymbol(text[a - 1])) a--
        var b = p
        while (b < len && !isSentenceSymbol(text[b])) b++
        if (b < len) b++
        return text.substring(a, b).replace('\n', '，').trim()
    }

    /** 方向键带 Shift（选择模式下扩选用） */
    private fun arrowShift(code: Int) {
        val ic = ic() ?: return
        val meta = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        val t = android.os.SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, meta))
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0, meta))
    }

    /** 双击“默认”项：跳到全文末尾；已经在末尾就跳到开头。选择模式下保持锚点扩选到开头/末尾。 */
    fun toggleEnd(): Boolean {
        Candidates.onCursorMove()
        val ic = ic() ?: return false
        val s = snapshot(ic) ?: run {
            if (selecting) {
                val t = SystemClock.uptimeMillis()
                val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
                ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MOVE_END, 0, meta))
                ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MOVE_END, 0, meta))
                return true
            }
            // 拿不到文本快照就发 MOVE_END（跳到结尾），至少光标能动
            send(KeyEvent.KEYCODE_MOVE_END)
            speechGate.markSkipNext(SystemClock.uptimeMillis())
            scheduleAfterSelection("已到结尾")
            return true
        }
        if (selecting && stillSelecting(s)) {
            val base = s.offset
            val len = s.text.length
            val active = if (activeEnd in base..(base + len)) activeEnd else base + s.indexOf(s.caret)
            val atEnd = active >= base + len
            extendToDocBoundary(ic, s, toStart = atEnd)
            return true
        }
        val caret = s.indexOf(s.caret)
        val atEnd = caret >= s.text.length
        // 和豆包编辑面板“开头/末尾”按钮一样发 MOVE_HOME/MOVE_END（带 CTRL 跳到全文）
        val meta = KeyEvent.META_CTRL_ON
        val code = if (atEnd) KeyEvent.KEYCODE_MOVE_HOME else KeyEvent.KEYCODE_MOVE_END
        val t = android.os.SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, meta))
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0, meta))
        speechGate.markSkipNext(t)
        scheduleAfterSelection(if (atEnd) "已到开头" else "已到结尾")
        return true
    }

    // ---------------- 选择模式（多选） ----------------

    /**
     * 进入选择模式并从光标处开始选。已经在选择里再执行一次就是取消选择。
     * 只设选择态和固定端（anchor=光标位置），不立即选字，
     * 之后左右滑才真正扩选/缩选（extendSelection 里活动端从 anchor 开始移动）。
     */
    fun beginSelect() {
        if (selecting) {
            deselect()
            return
        }
        val ic = ic() ?: return
        val s = snapshot(ic) ?: return
        Candidates.onCursorMove()
        val now = SystemClock.uptimeMillis()
        val hadSelection = s.hasSelection
        selectionGeneration++
        selecting = true
        selectGuardUntil = now + 260L
        // 固定端 = 当前光标位置（绝对偏移）
        anchor = s.offset + s.indexOf(s.caret)
        // 活动端一开始和固定端重合；往哪边滑，它就往哪边跑（不预设方向）
        activeEnd = anchor
        // 光标收缩成一点，作为选区起点；左右滑再开始扩选
        ic.setSelection(anchor, anchor)
        if (hadSelection) {
            speechGate.markSkipNext(now)
            val d = cancelSelectionDelayMs()
            scheduleAfterSelection("开始选择", afterSelMs = d, fallbackMs = SelectionOverrideTiming.fallbackMs(d))
        } else {
            speechGate.reset()
            cancelPendingSpeech()
            Speaker.speak("开始选择")
        }
    }

    /** 取消选择：退出选择模式，光标回到选择起点 */
    fun deselect() {
        selectionGeneration++
        Candidates.onCursorMove()
        val a = anchor
        // 先把状态收干净：以前这里 `val ic = ic() ?: return` 会在拿不到输入连接时
        // 直接溜走，标志位留在选择态，用户看到的就是“退不出去”
        cancelPendingExtend()
        selectGuardUntil = 0L
        selecting = false
        anchor = -1
        activeEnd = -1
        EditOptions.selectionEnded()
        val ic = ic()
        if (ic == null) {
            L.w("取消选择时拿不到输入连接，只清了状态")
            cancelPendingSpeech()
            Speaker.speak("退出选择")
            return
        }
        // 编辑框里还留着选区就必须真的收掉：以前只 setSelection 一下就完事，
        // 编辑框不认（或者 snapshot 拿不到）时，退出多选后仍然是一大片蓝色高亮（用户反馈）
        val collapsed = collapseSelection(ic, a, "取消选择")
        speechGate.markSkipNext(SystemClock.uptimeMillis())
        if (collapsed) {
            val d = cancelSelectionDelayMs()
            scheduleAfterSelection("已取消选择", afterSelMs = d, fallbackMs = SelectionOverrideTiming.fallbackMs(d))
        } else {
            cancelPendingSpeech()
            Speaker.speak("退出选择")
        }
    }

    private inline fun <T> batch(ic: InputConnection, action: () -> T): T {
        ic.beginBatchEdit()
        return try {
            action()
        } finally {
            ic.endBatchEdit()
        }
    }

    private fun postEdit(ic: InputConnection, generation: Long, delay: Long, action: () -> kotlin.Unit) {
        main.postDelayed({
            if (generation == selectionGeneration && ic === this.ic()) {
                try { action() } catch (t: Throwable) { L.hookError("EditActions.postEdit", t) }
            }
        }, delay)
    }

    /**
     * 把编辑框里的选区收成一个光标。返回 true 表示真的收掉了（原来有选中）。
     *
     * 退出选择模式必须走这里，不能只管模块自己的标志位——编辑框那边如果还认为
     * “有一段被选中”，屏幕上就是一片蓝色高亮，读屏也会一直报“已选中 N 个字”。
     * 收不掉分两档兜底：先等 90 毫秒核实一次，还在就用不带 Shift 的方向键（Android
     * 编辑框收到方向键会把选区收成光标），再核实一次，实在不行才记日志放手。
     */
    private fun collapseSelection(ic: InputConnection, prefer: Int, why: String): Boolean {
        val s = snapshot(ic) ?: return false
        if (!s.hasSelection) return false
        val base = s.offset
        val lo = base + s.indexOf(minOf(s.start, s.end))
        val hi = base + s.indexOf(maxOf(s.start, s.end))
        val generation = selectionGeneration
        val p = if (prefer in lo..hi) prefer else hi
        try {
            // 套一层 batchEdit：部分编辑框（Flutter、自绘输入框）不套 batch 就不认 setSelection 的选区变更
            batch(ic) { ic.setSelection(p, p) }
        } catch (t: Throwable) {
            L.hookError("collapseSelection.setSelection", t)
        }
        postEdit(ic, generation, 90) {
            val a = snapshot(ic)
            if (a != null && a.start == s.start && a.end == s.end && a.text == s.text) {
                L.w("退出选择态后编辑框还有选区（$why），改用方向键收掉")
                sendArrowCollapse(ic, a)
                postEdit(ic, generation, 130) {
                    if (snapshot(ic)?.hasSelection == true) {
                        L.w("编辑框不给改选区（$why），只能等它自己取消")
                    } else {
                        L.i("方向键收掉了选区（$why）")
                    }
                }
            }
        }
        return true
    }

    /** 不带 Shift 的方向键：编辑框会把选区收成一个光标 */
    private fun sendArrowCollapse(ic: InputConnection, s: Snapshot) {
        val key = if (s.start > 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        try {
            val now = SystemClock.uptimeMillis()
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0))
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0))
        } catch (t: Throwable) {
            L.hookError("sendArrowCollapse", t)
        }
    }

    /**
     * 选择模式下往 dir 方向移动“活动端”，固定端（anchor）不动，可扩选也可缩选：
     * - 固定端 = 进入选择态时的 anchor；
     * - 活动端 = 模块维护的 activeEnd（未选中时等于 anchor），用 BreakIterator / 句段边界步进；
     * - 调用 setSelection(anchor, newActive) 保留选区方向语义，并由 onUpdateSelection 驱动结果确认；
     * - 屏幕阅读器播报模式下不发 HOVER_ENTER("共 N 个字") 打断读屏的 8192 选区朗读；
     * - 独立 TTS 模式下按 SelectionDelta 只播报本次新增/取消的增量片段。
     */
    private fun extendSelection(ic: InputConnection, s: Snapshot, dir: Int, unit: Unit = Unit.CHAR) {
        cancelPendingExtend()
        cancelPendingSpeech()
        speechGate.reset()
        val base = s.offset
        val len = s.text.length
        // 固定端：anchor 有效就用它，否则退回当前选区的起点
        val a = if (anchor in base..(base + len)) anchor else base + s.indexOf(minOf(s.start, s.end))
        // 活动端：用模块自己记的那一头（往左滑就是左边那头，往右滑就是右边那头）。
        // 如果编辑框里的实际选区跟它对不上（用户在外面点了一下、或别人改了选区），
        // 就以编辑框为准重建——取 selectionEnd，因为它才是活动端，而不是“较大的那个偏移”
        var active = if (activeEnd in base..(base + len)) activeEnd else a
        if (s.hasSelection) {
            val realLo = base + s.indexOf(minOf(s.start, s.end))
            val realHi = base + s.indexOf(maxOf(s.start, s.end))
            if (minOf(a, active) != realLo || maxOf(a, active) != realHi) {
                active = s.end.coerceIn(base, base + len)
            }
        }
        val prevLo = minOf(a, active)
        val prevHi = maxOf(a, active)
        val rel = (active - base).coerceIn(0, len)
        val now = SystemClock.uptimeMillis()

        if (unit == Unit.LINE) {
            // 按行扩选只能交给编辑框自己（要有排版信息才能算上一行/下一行）
            selectGuardUntil = now + 260L
            arrowShift(if (dir < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN)
            watchExtendResult("按行扩选", unit, dir, s.text, base, prevLo, prevHi, allowFallback = false, timeoutMs = 110L)
            return
        }

        val next = boundaryOf(unit, s.text, rel, dir)
        if (next < 0) {
            Sound.play("boundary")
            // 活动端到头了（选区未变化，目标 App 不会发 8192）：直接报边界与字数
            val n = kotlin.math.abs(active - a)
            val msg = if (n == 0) {
                if (dir < 0) "已到开头" else "已到结尾"
            } else {
                (if (dir < 0) "已选到开头，共" else "已选到末尾，共") + "${n}个字"
            }
            Speaker.speak(msg)
            return
        }
        val newActive = base + next
        // 活动端正好回到固定端：选区长度变 0，等于把选中取消掉。
        // 保留选择态，手指接着往另一侧划就继续选那一侧（不是“反方向就取消”）
        if (newActive == a) {
            selectGuardUntil = now + 260L
            ic.setSelection(a, a)
            activeEnd = a
            speechGate.markSkipNext(now)
            val removed = if (unit != Unit.CHAR && prevHi > prevLo) {
                val rLo = (prevLo - base).coerceIn(0, len)
                val rHi = (prevHi - base).coerceIn(rLo, len)
                s.text.substring(rLo, rHi)
            } else ""
            val msg = if (removed.isNotEmpty()) GestureSpeechFormat.deselected(formatDeltaText(removed)) else "取消选择"
            val d = cancelSelectionDelayMs()
            scheduleAfterSelection(msg, afterSelMs = d, fallbackMs = SelectionOverrideTiming.fallbackMs(d))
            return
        }
        selectGuardUntil = now + 260L
        // 保留固定端在前、活动端在后的方向语义；即使部分编辑框会规范化成 lo..hi，本地仍以 anchor/activeEnd 为准
        ic.setSelection(a, newActive)
        Speaker.hushReader()
        activeEnd = newActive
        // 优先等待 onUpdateSelection 立即确认；若编辑框不回调或把 setSelection 吃成移动光标，
        // 80ms 后降级为方向键带 Shift 扩选
        watchExtendResult("扩选", unit, dir, s.text, base, prevLo, prevHi, allowFallback = true, timeoutMs = 80L)
    }

    private fun watchExtendResult(
        how: String,
        unit: Unit,
        dir: Int,
        prevText: String,
        prevOffset: Int,
        prevLo: Int,
        prevHi: Int,
        allowFallback: Boolean,
        timeoutMs: Long,
    ) {
        val tok = ++extendToken
        val ext = PendingExtend(tok, how, unit, dir, prevText, prevOffset, prevLo, prevHi, allowFallback)
        pendingExtend = ext
        val runner = Runnable {
            if (pendingExtend?.token != tok) return@Runnable
            pendingExtend = null
            pendingExtendRunner = null
            val ic = ic() ?: return@Runnable
            val after = snapshot(ic)
            if (after != null && after.hasSelection) {
                finishExtendSelection(ext, after.start, after.end, after)
            } else if (ext.allowFallback) {
                L.w("setSelection 扩选没生效（${ext.unit}），改用方向键带 Shift")
                selectGuardUntil = SystemClock.uptimeMillis() + 260L
                shiftFallback(ext.unit, ext.dir)
                watchExtendResult(
                    "方向键扩选", ext.unit, ext.dir,
                    ext.prevText, ext.prevOffset, ext.prevLo, ext.prevHi,
                    allowFallback = false, timeoutMs = 120L,
                )
            } else {
                // setSelection 和方向键都选不上：这个编辑框不给改选区，只能走豆包编辑面板
                Speaker.speak("这个输入框选不中，请用编辑面板选择文本")
                L.w("选择态：两种方式都没能选中（${ext.how}）")
            }
        }
        pendingExtendRunner = runner
        main.postDelayed(runner, timeoutMs)
    }

    /**
     * 方案 A 下的扩选/缩选朗读：
     * - 解说/保益/点明（读屏播报且未静音 8192）：让读屏直接念 8192，模块不发 HOVER_ENTER 抢话，零延迟、零冲突；
     * - 天坦全屏屏蔽生效或独立 TTS：立刻 0ms 播报增量（先念字再念“已选中/已取消”）。
     */
    private fun finishExtendSelection(
        ext: PendingExtend,
        absStart: Int,
        absEnd: Int,
        cachedAfter: Snapshot? = null,
    ) {
        if (speechGate.consumeSkipNext(SystemClock.uptimeMillis())) return
        val after = cachedAfter ?: ic()?.let { snapshot(it) }
        val text: String
        val base: Int
        val newLo: Int
        val newHi: Int
        if (after != null && after.hasSelection) {
            text = after.text
            base = after.offset
            newLo = after.indexOf(minOf(after.start, after.end))
            newHi = after.indexOf(maxOf(after.start, after.end))
            // 按行扩选或 Shift+方向键降级后，把活动端与编辑框实际选区同步
            if (ext.unit == Unit.LINE || !ext.allowFallback) {
                val absLo = base + newLo
                val absHi = base + newHi
                activeEnd = when {
                    anchor == absLo -> absHi
                    anchor == absHi -> absLo
                    else -> after.end.coerceIn(base, base + text.length)
                }
            }
        } else {
            text = ext.prevText
            base = ext.prevOffset
            newLo = (minOf(absStart, absEnd) - base).coerceIn(0, text.length)
            newHi = (maxOf(absStart, absEnd) - base).coerceIn(0, text.length)
        }
        val n = kotlin.math.abs(newHi - newLo)
        val oldLo = (ext.prevLo - base).coerceIn(0, text.length)
        val oldHi = (ext.prevHi - base).coerceIn(0, text.length)
        val delta = SelectionDelta.between(text, oldLo, oldHi, newLo, newHi)
        // 方案 A：仅“按单字扩缩选且本次增量 <= 1 个 UTF-16 码元”让位给读屏直接播报；
        // 按句/按段/按行扩缩选（及代理对 emoji 等多码元增量）读屏因位差 >1 通常静默，由模块统一播报“xxx已选中/已取消”。
        if (Speaker.readerWillSpeak() && ext.unit == Unit.CHAR && delta.text.length <= 1) {
            L.i("选择态（${ext.how}）：选中 $n 个字（交由读屏播报）")
            return
        }
        L.i("选择态（${ext.how}）：选中 $n 个字，粒度=${ext.unit}，增量=${delta.kind}")
        val msg = when (delta.kind) {
            SelectionDelta.Kind.EXPANDED -> GestureSpeechFormat.selected(formatDeltaText(delta.text))
            SelectionDelta.Kind.SHRUNK -> GestureSpeechFormat.deselected(formatDeltaText(delta.text))
            SelectionDelta.Kind.CLEARED -> "取消选择"
            SelectionDelta.Kind.NONE -> ""
        }
        if (msg.isEmpty()) return
        if (!Speaker.readerWillSpeak()) {
            Speaker.speak(msg)
            return
        }
        val delay = if (delta.text.length <= 1) cancelSelectionDelayMs() else selectionDelayMs()
        postDelayedSpeech(delay, msg)
    }

    /** 格式化文本片段：单字符/标点转符号中文名、换行转逗号，供 GestureSpeechFormat 统一拼后缀 */
    private fun plainLabel(raw: String): String {
        if (raw.isEmpty()) return ""
        val normalized = raw.replace("\r\n", "\n")
        if (normalized.length == 1) return spoken(normalized[0])
        val trimmed = normalized.trim()
        if (trimmed.isEmpty()) return spoken(normalized.firstOrNull())
        return trimmed.replace('\n', '，')
    }

    /** 格式化选区增量片段：单字符/标点转符号中文名，全文朗读不截断 */
    private fun formatDeltaText(raw: String): String = plainLabel(raw)

    /** 方向键带 Shift 扩选：按粒度选对应的键 */
    private fun shiftFallback(unit: Unit, dir: Int) {
        val code = when (unit) {
            Unit.SENTENCE -> if (dir < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
            Unit.PARAGRAPH -> if (dir < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
            Unit.LINE -> if (dir < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
            Unit.CHAR -> if (dir < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
            Unit.DOC -> {
                val ic = ic() ?: return
                val key = if (dir < 0) KeyEvent.KEYCODE_MOVE_HOME else KeyEvent.KEYCODE_MOVE_END
                val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
                val t = SystemClock.uptimeMillis()
                ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, key, 0, meta))
                ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, key, 0, meta))
                return
            }
        }
        arrowShift(code)
    }

    /** 扩选用的移动单位 */
    private enum class Unit { CHAR, SENTENCE, PARAGRAPH, LINE, DOC }

    /**
     * 在文本里按单位找下一个边界：成功返回新位置（相对文本开头），到头返回 -1。
     *
     * 字用 BreakIterator（不拆 emoji / 代理对），句用用户配置的句号标点，段用换行——
     * 和天坦输入法的 GranularityIterator 一套思路，光标移动和扩选共用同一个边界函数，
     * 所以“按句移动”和“按句扩选”走的边界完全一致，不会一处能走一处走不动（Bug3）。
     */
    private fun boundaryOf(unit: Unit, text: String, pos: Int, dir: Int): Int {
        val len = text.length
        val p = pos.coerceIn(0, len)
        if (len == 0) return -1
        when (unit) {
            Unit.CHAR -> {
                val bi = BreakIterator.getCharacterInstance(Locale.getDefault())
                bi.setText(text)
                if (dir > 0) {
                    val n = bi.following(p)
                    return if (n == BreakIterator.DONE || n > len) -1 else n
                }
                val n = bi.preceding(p)
                return if (n == BreakIterator.DONE || n < 0) -1 else n
            }
            Unit.SENTENCE -> {
                val symbols = sentenceSymbols()
                fun symAt(i: Int) = i in 0 until len && symbols.contains(text[i].toString())
                fun startBoundary(i: Int): Boolean {
                    if (i < 0 || i >= len) return false
                    if (symAt(i)) return false
                    return i == 0 || symAt(i - 1)
                }
                fun endBoundary(i: Int): Boolean {
                    if (i <= 0 || i > len) return false
                    return symAt(i - 1) || i == len
                }
                return if (dir > 0) {
                    var i = p
                    while (i < len && symAt(i) && !startBoundary(i)) i++
                    if (i >= len) -1 else {
                        var j = i + 1
                        while (j < len && !endBoundary(j)) j++
                        j
                    }
                } else {
                    var i = p
                    while (i > 0 && symAt(i - 1) && !endBoundary(i)) i--
                    if (i <= 0) -1 else {
                        var j = i - 1
                        while (j > 0 && !startBoundary(j)) j--
                        j
                    }
                }
            }
            Unit.PARAGRAPH -> {
                fun nl(i: Int) = i in 0 until len && text[i] == '\n'
                fun startBoundary(i: Int): Boolean {
                    if (i < 0 || i >= len) return false
                    if (nl(i)) return false
                    return i == 0 || nl(i - 1)
                }
                fun endBoundary(i: Int): Boolean {
                    if (i <= 0 || i > len) return false
                    return nl(i - 1) || i == len
                }
                return if (dir > 0) {
                    var i = p
                    while (i < len && nl(i) && !startBoundary(i)) i++
                    if (i >= len) -1 else {
                        var j = i + 1
                        while (j < len && !endBoundary(j)) j++
                        j
                    }
                } else {
                    var i = p
                    while (i > 0 && nl(i - 1) && !endBoundary(i)) i--
                    if (i <= 0) -1 else {
                        var j = i - 1
                        while (j > 0 && !startBoundary(j)) j--
                        j
                    }
                }
            }
            Unit.LINE, Unit.DOC -> return -1
        }
    }

    /** 选择模式下删掉选中的一段（连选带删） */
    private fun deleteSelection(ic: InputConnection, s: Snapshot): Boolean {
        if (!s.hasSelection) return false
        val lo = s.offset + s.indexOf(minOf(s.start, s.end))
        val hi = s.offset + s.indexOf(maxOf(s.start, s.end))
        val text = s.text.substring((lo - s.offset).coerceIn(0, s.text.length), (hi - s.offset).coerceIn(0, s.text.length))
        clearSelectionState("删除选中文本")
        TypingEcho.suppressDeleteFor()
        ic.setSelection(lo, hi)
        ic.commitText("", 1)
        speechGate.markSkipNext(SystemClock.uptimeMillis())
        val d = textChangeDelayMs()
        val count = text.codePointCount(0, text.length)
        val msg = GestureSpeechFormat.deleted(plainLabel(text), count)
        scheduleAfterSelection(msg, afterSelMs = d, fallbackMs = SelectionOverrideTiming.fallbackMs(d))
        return true
    }

    private fun spokenUnit(unit: String) = if (unit.length == 1) spoken(unit[0]) else Names.spoken(unit)

    private fun spoken(c: Char?): String = when (c) {
        null, '\u0000' -> "空"
        ' ' -> "空格"
        '\n' -> "换行"
        else -> Names.spoken(c.toString())
    }

    // ---------------- 行 ----------------

    fun prevLine() = lineStep(KeyEvent.KEYCODE_DPAD_UP)
    fun nextLine() = lineStep(KeyEvent.KEYCODE_DPAD_DOWN)

    private fun lineStep(code: Int) {
        Candidates.onCursorMove()
        val ic = ic() ?: return
        val s = snapshot(ic)
        // 选择态：上下移行变成“往上一行/下一行扩选”
        if (selecting) {
            if (s == null) {
                arrowShift(code)
                return
            }
            if (stillSelecting(s)) {
                extendSelection(ic, s, if (code == KeyEvent.KEYCODE_DPAD_UP) -1 else 1, Unit.LINE)
                return
            }
            // 选择态失效：继续走普通移行
        }
        if (s != null && s.hasSelection) {
            val p = s.indexOf(s.start)
            ic.setSelection(s.offset + p, s.offset + p)
        }
        arrow(code)
        // 上下移一行由编辑框自己算，收到 onUpdateSelection 后在读屏刚开口瞬间覆盖播报新行
        val delay = if (Speaker.readerWillSpeak()) selectionDelayMs() else 10L
        scheduleAfterSelectionAction(
            afterSelMs = delay,
            fallbackMs = SelectionOverrideTiming.fallbackMs(delay),
        ) { readLineAtCaret() }
    }

    /**
     * 移行之后念光标所在的那一行。
     *
     * 以前走 readLine(quiet = true)，读屏模式下直接 return——读屏对“上下移一行”这种
     * 编辑框自己算出来的位移并不会主动念，用户听到的就是“移了一行，什么都没说”。
     */
    private fun readLineAtCaret() {
        val ic = ic() ?: return
        val s = snapshot(ic) ?: return
        val caret = s.indexOf(s.caret)
        var a = caret
        while (a > 0 && s.text[a - 1] != '\n') a--
        var b = caret
        while (b < s.text.length && s.text[b] != '\n') b++
        val line = s.text.substring(a, b).trim()
        if (line.isEmpty()) {
            Speaker.echo("空行")
            return
        }
        Speaker.echo(line)
    }

    fun lineStart() = jumpTo(true)
    fun lineEnd() = jumpTo(false)

    private fun jumpTo(start: Boolean) {
        Candidates.onCursorMove()
        val ic = ic()
        val s = ic?.let { snapshot(it) }
        val code = if (start) KeyEvent.KEYCODE_MOVE_HOME else KeyEvent.KEYCODE_MOVE_END
        if (selecting && ic != null) {
            if (s == null) {
                arrowShift(code)
                return
            }
            if (stillSelecting(s)) {
                cancelPendingExtend()
                cancelPendingSpeech()
                speechGate.reset()
                val base = s.offset
                val len = s.text.length
                val a = if (anchor in base..(base + len)) anchor else base + s.indexOf(minOf(s.start, s.end))
                var active = if (activeEnd in base..(base + len)) activeEnd else a
                if (s.hasSelection) {
                    val realLo = base + s.indexOf(minOf(s.start, s.end))
                    val realHi = base + s.indexOf(maxOf(s.start, s.end))
                    if (minOf(a, active) != realLo || maxOf(a, active) != realHi) {
                        active = s.end.coerceIn(base, base + len)
                    }
                }
                val prevLo = minOf(a, active)
                val prevHi = maxOf(a, active)
                selectGuardUntil = SystemClock.uptimeMillis() + 260L
                arrowShift(code)
                watchExtendResult(
                    if (start) "选到行首" else "选到行尾",
                    Unit.LINE,
                    if (start) -1 else 1,
                    s.text,
                    base,
                    prevLo,
                    prevHi,
                    allowFallback = false,
                    timeoutMs = 110L,
                )
                return
            }
        }
        send(code)
        speechGate.markSkipNext(SystemClock.uptimeMillis())
        scheduleAfterSelection(if (start) "行首" else "行尾")
        main.postDelayed({ readLine(quiet = true) }, 80)
    }

    // ---------------- 句 ----------------

    fun prevSentence() = sentenceStep(-1)
    fun nextSentence() = sentenceStep(1)

    /**
     * 按句移动：句子的边界是用户配置的标点（sentenceSymbols，默认 。，；？！.!? 和换行），
     * 不是按固定字符硬切。这样“3.14”里的点、小数点都不会被当成句号。
     */
    private fun sentenceStep(dir: Int) {
        Candidates.onCursorMove()
        val ic = ic() ?: return
        val s = snapshot(ic) ?: return
        // 选择态：按句扩选（以前只有按字能扩选，按句按段走普通移动，用户以为多选坏了，Bug3）
        if (selecting && stillSelecting(s)) {
            extendSelection(ic, s, dir, Unit.SENTENCE)
            return
        }
        val t = s.text
        val caret = s.indexOf(s.caret)
        val len = t.length
        if (len == 0) {
            Sound.play("boundary")
            Speaker.speak("编辑框为空")
            return
        }
        val p = boundaryOf(Unit.SENTENCE, t, caret, dir)
        if (p < 0) {
            speakBoundary(dir, s)
            return
        }
        ic.setSelection(s.offset + p, s.offset + p)
        // 念“移动到的这一句”本身；走读屏播报时对齐到 onUpdateSelection 之后，防止被 8192 反掐
        Speaker.hushReader()
        val sentence = segmentAt(t, p, para = false)
        // 空句（连续标点、只有换行）不念“上一句/下一句”这种方向名——用户明确说这类提示是多余的，
        // 只会和真正要听的内容抢话。有内容就念完整内容
        if (sentence.isNotEmpty()) {
            val d = if (kotlin.math.abs(p - caret) <= 1) cancelSelectionDelayMs() else selectionDelayMs()
            scheduleAfterSelection(sentence, isEcho = true, afterSelMs = d, fallbackMs = SelectionOverrideTiming.fallbackMs(d))
        }
    }

    // ---------------- 段 ----------------

    fun prevParagraph() = paragraphStep(-1)
    fun nextParagraph() = paragraphStep(1)

    /** 段落按换行分：换行就是段的分界 */
    private fun paragraphStep(dir: Int) {
        Candidates.onCursorMove()
        val ic = ic() ?: return
        val s = snapshot(ic) ?: return
        // 选择态：按段扩选
        if (selecting && stillSelecting(s)) {
            extendSelection(ic, s, dir, Unit.PARAGRAPH)
            return
        }
        val caret = s.indexOf(s.caret)
        val p = boundaryOf(Unit.PARAGRAPH, s.text, caret, dir)
        if (p < 0) {
            speakBoundary(dir, s)
            return
        }
        ic.setSelection(s.offset + p, s.offset + p)
        Speaker.hushReader()
        val para = segmentAt(s.text, p, para = true)
        val msg = if (para.isNotEmpty()) para else "空行"
        val d = if (kotlin.math.abs(p - caret) <= 1) cancelSelectionDelayMs() else selectionDelayMs()
        scheduleAfterSelection(msg, isEcho = true, afterSelMs = d, fallbackMs = SelectionOverrideTiming.fallbackMs(d))
    }

    // ---------------- 整篇的开头、结尾 ----------------

    fun docStart() = jumpDoc(toStart = true)

    fun docEnd() = jumpDoc(toStart = false)

    private fun jumpDoc(toStart: Boolean) {
        Candidates.onCursorMove()
        val ic = ic() ?: return
        val s = snapshot(ic) ?: return
        if (selecting && stillSelecting(s)) {
            extendToDocBoundary(ic, s, toStart)
            return
        }
        val target = if (toStart) s.offset else s.offset + s.text.length
        ic.setSelection(target, target)
        speechGate.markSkipNext(SystemClock.uptimeMillis())
        scheduleAfterSelection(if (toStart) "已到开头" else "已到结尾")
        main.postDelayed({ readLine(quiet = true) }, 80)
    }

    /** 选择模式下跳到整篇开头/末尾：保持固定端 anchor 不动，把中间全部文本选中 */
    private fun extendToDocBoundary(ic: InputConnection, s: Snapshot, toStart: Boolean) {
        cancelPendingExtend()
        cancelPendingSpeech()
        speechGate.reset()
        val base = s.offset
        val len = s.text.length
        val dir = if (toStart) -1 else 1
        val a = if (anchor in base..(base + len)) anchor else base + s.indexOf(minOf(s.start, s.end))
        var active = if (activeEnd in base..(base + len)) activeEnd else a
        if (s.hasSelection) {
            val realLo = base + s.indexOf(minOf(s.start, s.end))
            val realHi = base + s.indexOf(maxOf(s.start, s.end))
            if (minOf(a, active) != realLo || maxOf(a, active) != realHi) {
                active = s.end.coerceIn(base, base + len)
            }
        }
        val prevLo = minOf(a, active)
        val prevHi = maxOf(a, active)
        val newActive = if (toStart) base else base + len
        val now = SystemClock.uptimeMillis()

        if (newActive == active) {
            Sound.play("boundary")
            val n = kotlin.math.abs(active - a)
            val msg = if (n == 0) {
                if (toStart) "已到开头" else "已到结尾"
            } else {
                (if (toStart) "已选到开头，共" else "已选到末尾，共") + "${n}个字"
            }
            Speaker.speak(msg)
            return
        }
        if (newActive == a) {
            selectGuardUntil = now + 260L
            ic.setSelection(a, a)
            activeEnd = a
            speechGate.markSkipNext(now)
            val removed = if (prevHi > prevLo) {
                val rLo = (prevLo - base).coerceIn(0, len)
                val rHi = (prevHi - base).coerceIn(rLo, len)
                s.text.substring(rLo, rHi)
            } else ""
            val msg = if (removed.isNotEmpty()) GestureSpeechFormat.deselected(formatDeltaText(removed)) else "取消选择"
            val d = cancelSelectionDelayMs()
            scheduleAfterSelection(msg, afterSelMs = d, fallbackMs = SelectionOverrideTiming.fallbackMs(d))
            return
        }

        selectGuardUntil = now + 260L
        ic.setSelection(a, newActive)
        Speaker.hushReader()
        activeEnd = newActive
        watchExtendResult(
            if (toStart) "选到开头" else "选到末尾",
            Unit.DOC,
            dir,
            s.text,
            base,
            prevLo,
            prevHi,
            allowFallback = true,
            timeoutMs = 80L,
        )
    }

    // ---------------- 朗读 ----------------

    fun readLine() = readLine(quiet = false)

    private fun readLine(quiet: Boolean) {
        // 自动跟读（跳首尾之后）只在独立 TTS 下追加；屏幕阅读器播报走 HOVER_ENTER 覆盖式，避免 80ms 后掐断“行首/已到开头”
        if (quiet && Config.b("speakByReader")) return
        val ic = ic() ?: return
        val s = snapshot(ic) ?: return
        val caret = s.indexOf(s.caret)
        var a = caret
        while (a > 0 && s.text[a - 1] != '\n') a--
        var b = caret
        while (b < s.text.length && s.text[b] != '\n') b++
        val line = s.text.substring(a, b).trim()
        if (line.isEmpty()) {
            if (!quiet) Speaker.speak("空行")
            return
        }
        Speaker.speak(line)
    }

    fun readAll() {
        val ic = ic() ?: return
        val s = snapshot(ic) ?: return
        val t = s.text.trim()
        if (t.isEmpty()) return Speaker.speak("编辑框为空")
        Speaker.speak(t)
    }

    /** 逐字解释编辑框中的每个字符（供定制工具栏“朗读编辑框”快速双击使用） */
    fun readAllSpelled() {
        val ic = ic() ?: return
        val s = snapshot(ic) ?: return
        val raw = s.text
        if (raw.isEmpty()) {
            Speaker.speak("编辑框为空")
            return
        }
        val bi = BreakIterator.getCharacterInstance(Locale.getDefault())
        bi.setText(raw)
        val parts = ArrayList<String>()
        var start = bi.first()
        var end = bi.next()
        while (end != BreakIterator.DONE) {
            val unit = raw.substring(start, end)
            val explain = CharWords.explainOnly(unit, force = true)
            val piece = if (explain != null) "$unit，$explain" else spokenUnit(unit)
            if (piece.isNotEmpty()) parts.add(piece)
            start = end
            end = bi.next()
        }
        if (parts.isEmpty()) {
            Speaker.speak("编辑框为空")
            return
        }
        Speaker.stop()
        Speaker.unmute()
        Speaker.echo(parts.joinToString("；"))
    }

    // ---------------- 编辑 ----------------

    /** 删除光标左边一个字，先念被删的字再念“已删除”；选择模式下删掉选中的一段 */
    fun deleteLeft() {
        val ic = ic() ?: return
        val s = snapshot(ic)
        if (s != null && deleteSelection(ic, s)) return
        val before = charBefore()
        if (s != null && s.text.isEmpty()) {
            Speaker.speak("编辑框为空")
            return
        }
        if (s != null && before == null) {
            Speaker.speak("已到开头")
            return
        }
        TypingEcho.suppressDeleteFor()
        send(KeyEvent.KEYCODE_DEL)
        speechGate.markSkipNext(SystemClock.uptimeMillis())
        val d = textChangeDelayMs()
        val msg = if (before == null) "已删除" else GestureSpeechFormat.deleted(spoken(before), 1)
        scheduleAfterSelection(msg, afterSelMs = d, fallbackMs = SelectionOverrideTiming.fallbackMs(d))
    }

    /** 删除光标右边一个字，先念被删的字再念“已删除” */
    fun deleteRight() {
        val ic = ic() ?: return
        val s = snapshot(ic)
        val after = charAfter()
        if (s != null && s.text.isEmpty()) {
            Speaker.speak("编辑框为空")
            return
        }
        if (s != null && after == null) {
            Speaker.speak("已到结尾")
            return
        }
        TypingEcho.suppressDeleteFor()
        send(KeyEvent.KEYCODE_FORWARD_DEL)
        speechGate.markSkipNext(SystemClock.uptimeMillis())
        val d = textChangeDelayMs()
        val msg = if (after == null) "已删除" else GestureSpeechFormat.deleted(spoken(after), 1)
        scheduleAfterSelection(msg, afterSelMs = d, fallbackMs = SelectionOverrideTiming.fallbackMs(d))
    }

    /**
     * 删除上一句：删除光标前面的字符，直到按句移动光标配置的断句符号（sentenceSymbols）边界。
     * 光标正好在断句符号后面时（如“第一句。第二句。|”），会把该句连同末尾标点整句删掉；
     * 若编辑框为空或已在开头则提示边界。
     */
    fun deletePrevSentence() {
        val ic = ic() ?: return
        val s = snapshot(ic) ?: return
        clearSelectionState("删除上一句")
        val text = s.text
        if (text.isEmpty()) {
            Sound.play("boundary")
            Speaker.speak("编辑框为空")
            return
        }
        val caret = s.indexOf(minOf(s.start, s.end))
        if (caret <= 0) {
            Sound.play("boundary")
            Speaker.speak("已到开头")
            return
        }
        val target = boundaryOf(Unit.SENTENCE, text, caret, -1)
        val lo = if (target < 0) 0 else target.coerceIn(0, caret)
        if (lo >= caret) {
            Sound.play("boundary")
            Speaker.speak("已到开头")
            return
        }
        deleteSentenceRange(ic, s, lo, caret, beforeCaret = true, reason = "删除上一句")
    }

    /**
     * 删除下一句：删除光标后面的字符，直到删完按句移动光标配置的断句符号（sentenceSymbols）。
     * 若编辑框为空或已在结尾则提示边界。
     */
    fun deleteNextSentence() {
        val ic = ic() ?: return
        val s = snapshot(ic) ?: return
        clearSelectionState("删除下一句")
        val text = s.text
        if (text.isEmpty()) {
            Sound.play("boundary")
            Speaker.speak("编辑框为空")
            return
        }
        val caret = s.indexOf(maxOf(s.start, s.end))
        if (caret >= text.length) {
            Sound.play("boundary")
            Speaker.speak("已到结尾")
            return
        }
        val target = boundaryOf(Unit.SENTENCE, text, caret, 1)
        val hi = if (target < 0) text.length else target.coerceIn(caret, text.length)
        if (hi <= caret) {
            Sound.play("boundary")
            Speaker.speak("已到结尾")
            return
        }
        deleteSentenceRange(ic, s, caret, hi, beforeCaret = false, reason = "删除下一句")
    }

    private fun deleteSentenceRange(
        ic: InputConnection,
        s: Snapshot,
        lo: Int,
        hi: Int,
        beforeCaret: Boolean,
        reason: String,
    ) {
        val removed = s.text.substring(lo, hi)
        if (removed.isEmpty()) return
        Candidates.onCursorMove()
        TypingEcho.suppressDeleteFor()
        val deleted = try {
            if (!s.hasSelection) {
                val len = hi - lo
                if (beforeCaret) ic.deleteSurroundingText(len, 0) else ic.deleteSurroundingText(0, len)
            } else {
                false
            }
        } catch (t: Throwable) {
            L.hookError("EditActions.$reason.deleteSurrounding", t)
            false
        }
        if (!deleted) {
            try {
                val start = s.offset + lo
                val end = s.offset + hi
                val committed = batch(ic) {
                    val selected = ic.setSelection(start, end) || snapshot(ic)?.let {
                        minOf(it.start, it.end) == start && maxOf(it.start, it.end) == end
                    } == true
                    selected && ic.commitText("", 1)
                }
                if (!committed) {
                    L.w("编辑框未确认删除（$reason）")
                    return
                }
            } catch (t: Throwable) {
                L.hookError("EditActions.$reason", t)
                return
            }
        }
        speechGate.markSkipNext(SystemClock.uptimeMillis())
        val d = textChangeDelayMs()
        val count = removed.codePointCount(0, removed.length)
        val msg = GestureSpeechFormat.deleted(plainLabel(removed), count)
        scheduleAfterSelection(msg, afterSelMs = d, fallbackMs = SelectionOverrideTiming.fallbackMs(d))
        main.postDelayed({ EditOptions.refresh(reason) }, 140)
    }

    private fun charBefore(): Char? = try {
        val ic = ic() ?: return null
        val s = snapshot(ic) ?: return null
        val p = s.indexOf(s.caret)
        if (p <= 0) null else s.text.getOrNull(p - 1)
    } catch (_: Throwable) {
        null
    }

    private fun charAfter(): Char? = try {
        val ic = ic() ?: return null
        val s = snapshot(ic) ?: return null
        val p = s.indexOf(s.caret)
        s.text.getOrNull(p)
    } catch (_: Throwable) {
        null
    }

    /** 朗读编辑框里的字（太长只读前面一段） */
    fun readEditor() = readAll()

    /** 朗读选中的文字；没选中就读光标所在行 */
    fun readSelection() {
        val ic = ic() ?: return
        val s = snapshot(ic) ?: return
        if (!s.hasSelection) {
            readLine()
            return
        }
        val lo = (s.indexOf(minOf(s.start, s.end))).coerceIn(0, s.text.length)
        val hi = (s.indexOf(maxOf(s.start, s.end))).coerceIn(0, s.text.length)
        val t = s.text.substring(lo, hi).trim()
        if (t.isEmpty()) Speaker.speak("选中的是空白") else Speaker.speak("${t}，已选中")
    }

    private fun sendCtrlZ(ic: InputConnection) {
        try {
            val t = SystemClock.uptimeMillis()
            val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, 0, meta))
            ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Z, 0, meta))
        } catch (t: Throwable) {
            L.hookError("EditActions.undoCtrlZ", t)
        }
    }

    /** 撤销上一次编辑：优先执行系统 undo，未生效或不支持时自动发送 Ctrl+Z 快捷键兜底 */
    fun undo() {
        val ic = ic() ?: return
        val before = snapshot(ic)?.text
        val ctxOk = try {
            ic.performContextMenuAction(android.R.id.undo)
        } catch (t: Throwable) {
            L.hookError("EditActions.undo", t)
            false
        }
        if (!ctxOk) {
            sendCtrlZ(ic)
        }
        clearSelectionState("撤销")
        val generation = selectionGeneration
        postEdit(ic, generation, 70) {
            val after = snapshot(ic)?.text
            val changed = before != null && after != null && after != before
            if (changed) {
                Speaker.speak("已撤销")
                postEdit(ic, generation, 90) { readLine(quiet = true) }
                postEdit(ic, generation, 140) { EditOptions.refresh("撤销") }
                return@postEdit
            }
            if (ctxOk && before != null) {
                // 部分编辑框 performContextMenuAction(undo) 返回 true 但未实际改动文本，补发一次 Ctrl+Z
                sendCtrlZ(ic)
                postEdit(ic, generation, 70) {
                    val after2 = snapshot(ic)?.text
                    val changed2 = after2 != null && after2 != before
                    if (changed2) {
                        Speaker.speak("已撤销")
                        postEdit(ic, generation, 90) { readLine(quiet = true) }
                        postEdit(ic, generation, 140) { EditOptions.refresh("撤销") }
                    } else {
                        Speaker.speak("没有可撤销的内容")
                    }
                }
            } else if (ctxOk || before == null) {
                Speaker.speak("已撤销")
                postEdit(ic, generation, 90) { readLine(quiet = true) }
                postEdit(ic, generation, 140) { EditOptions.refresh("撤销") }
            } else {
                Speaker.speak("这个输入框不支持撤销")
            }
        }
    }

    /** 清空文本：全选再删，一次清干净 */
    fun clearText() {
        val ic = ic() ?: return
        clearSelectionState("清空文本")
        val s = snapshot(ic)
        if (s == null || s.text.isEmpty()) {
            Speaker.speak("编辑框为空")
            return
        }
        val n = s.text.length
        TypingEcho.suppressDeleteFor()
        ic.performContextMenuAction(android.R.id.selectAll)
        ic.commitText("", 1)
        speechGate.markSkipNext(SystemClock.uptimeMillis())
        val d = textChangeDelayMs()
        scheduleAfterSelection(GestureSpeechFormat.cleared(n), afterSelMs = d, fallbackMs = SelectionOverrideTiming.fallbackMs(d))
        // 清空了：编辑菜单回到“粘贴 / 收起键盘”，默认停在粘贴
        main.postDelayed({ EditOptions.refresh("清空文本") }, 140)
    }

    /** 字符统计 */
    fun charCount() {
        val ic = ic() ?: return
        val s = snapshot(ic) ?: return
        val t = s.text
        if (t.isEmpty()) {
            Speaker.speak("编辑框为空")
            return
        }
        val noSpace = t.count { !it.isWhitespace() }
        if (s.hasSelection) {
            val n = kotlin.math.abs(s.end - s.start)
            Speaker.speak("选中 $n 个字，全文 ${t.length} 个字")
        } else {
            Speaker.speak("共 ${t.length} 个字，不含空格 $noSpace 个")
        }
    }

    /** 输入提示：编辑框自己写的提示语（“请输入手机号”这种） */
    fun hint() {
        val ei = try {
            ViewA11yHooks.service?.currentInputEditorInfo
        } catch (_: Throwable) {
            null
        }
        val h = hintOf(ei)
        Speaker.speak(if (h.isNullOrBlank()) "这个输入框没有提示" else h)
    }

    private fun hintOf(ei: EditorInfo?): String? {
        if (ei == null) return null
        val direct = try {
            ei.hintText?.toString()
        } catch (_: Throwable) {
            null
        }
        if (!direct.isNullOrBlank()) return direct
        return try {
            // hintTextResId 是 @hide 字段，只能反射拿
            val f = EditorInfo::class.java.getField("hintTextResId")
            val id = f.getInt(ei)
            val res = ViewA11yHooks.service?.resources
            if (id != 0 && res != null) res.getText(id)?.toString() else null
        } catch (_: Throwable) {
            null
        }
    }

    fun enter() {
        send(KeyEvent.KEYCODE_ENTER)
        Speaker.speak("换行")
    }

    /** 全选：选中全部文字并进入选择态，之后左右滑可扩选/缩选、剪切复制删除 */
    fun selectAll() {
        val ic = ic() ?: return
        selectionGeneration++
        val before = snapshot(ic)
        val now = SystemClock.uptimeMillis()
        // 先按“选上了”处理：校验要等一下才能做，用户这期间滑一下也是扩选，不会变成移动光标
        selecting = true
        selectGuardUntil = now + 260L
        anchor = before?.offset ?: 0
        // 零长度选择态的合法形态：活动端先跟固定端重合，等核对完选区再挪到末尾
        activeEnd = anchor
        val ok = try {
            ic.performContextMenuAction(android.R.id.selectAll)
        } catch (t: Throwable) {
            L.hookError("EditActions.selectAll", t)
            false
        }
        // 有的编辑框 performContextMenuAction 返回 false 但其实选上了：优先等 onUpdateSelection 落地后核对
        val delay = if (Speaker.readerWillSpeak()) selectionDelayMs() else 10L
        scheduleAfterSelectionAction(
            afterSelMs = delay,
            fallbackMs = SelectionOverrideTiming.fallbackMs(delay),
        ) {
            val s = snapshot(ic)
            val selected = if (s != null && s.hasSelection) kotlin.math.abs(s.end - s.start) else 0
            val total = before?.text?.length ?: (s?.text?.length ?: 0)
            val really = s != null && s.hasSelection && selected > 0
            if (really || ok) {
                selecting = true
                anchor = if (s != null) s.offset + s.indexOf(minOf(s.start, s.end)) else 0
                // 全选后活动端放在末尾：向左滑是从末尾往回缩，向右滑才报“已选到末尾”
                activeEnd = if (s != null) s.offset + s.indexOf(maxOf(s.start, s.end)) else anchor
                speechGate.markSkipNext(SystemClock.uptimeMillis())
                Speaker.speak("全选，共${selected}个字")
                L.i("全选：选中 $selected/$total 个字")
            } else {
                clearSelectionState("全选没生效，编辑框没有选中内容")
                Speaker.speak("当前编辑框不支持全选")
                L.w("全选没生效：编辑框没有选中内容，返回值=$ok")
            }
        }
    }

    fun copy() {
        val ic = ic() ?: return
        val s = snapshot(ic)
        val selected = s?.let {
            if (!it.hasSelection) "" else it.text.substring(
                it.indexOf(minOf(it.start, it.end)).coerceIn(0, it.text.length),
                it.indexOf(maxOf(it.start, it.end)).coerceIn(0, it.text.length),
            )
        } ?: ""
        val ok = try {
            ic.performContextMenuAction(android.R.id.copy)
        } catch (t: Throwable) {
            L.hookError("EditActions.copy", t)
            false
        }
        speechGate.markSkipNext(SystemClock.uptimeMillis())
        main.postDelayed({
            // 复制成功与否不看返回值：有的编辑框返回 false 但已经复制好了，看剪贴板里是不是这段
            val clip = clipboardText()
            val copied = ok || (selected.isNotEmpty() && clip != null && (clip == selected || clip.contains(selected.take(40))))
            if (copied) finishSelection()
            val label = plainLabel(selected)
            Speaker.speak(
                when {
                    copied -> GestureSpeechFormat.copied(label, selected.length)
                    selected.isEmpty() -> "没有选中的文字"
                    else -> "当前编辑框不支持复制"
                }
            )
        }, 100)
        if (ok) finishSelection()
    }

    fun cut() {
        val ic = ic() ?: return
        val s = snapshot(ic)
        if (s != null && s.text.isEmpty()) {
            Speaker.speak("编辑框为空")
            return
        }
        val before = s?.text ?: ""
        val selected = s?.let {
            if (!it.hasSelection) "" else it.text.substring(
                it.indexOf(minOf(it.start, it.end)).coerceIn(0, it.text.length),
                it.indexOf(maxOf(it.start, it.end)).coerceIn(0, it.text.length),
            )
        } ?: ""
        if (s != null && selected.isEmpty()) {
            Speaker.speak("没有选中的文字")
            return
        }
        TypingEcho.suppressDeleteFor()
        val ok = try {
            ic.performContextMenuAction(android.R.id.cut)
        } catch (t: Throwable) {
            L.hookError("EditActions.cut", t)
            false
        }
        speechGate.markSkipNext(SystemClock.uptimeMillis())
        val delay = if (Speaker.readerWillSpeak()) textChangeDelayMs() else 10L
        scheduleAfterSelectionAction(
            afterSelMs = delay,
            fallbackMs = SelectionOverrideTiming.fallbackMs(delay),
        ) {
            val after = snapshot(ic)?.text ?: before
            val cutDone = ok || after != before
            if (cutDone) {
                finishSelection()
                main.postDelayed({ EditOptions.refresh("剪切") }, 140)
            }
            val label = plainLabel(selected)
            Speaker.speak(
                when {
                    cutDone -> GestureSpeechFormat.cut(label, selected.length)
                    selected.isEmpty() -> "没有选中的文字"
                    else -> "当前编辑框不支持剪切"
                }
            )
        }
    }

    /** 复制/剪切/删除/取消选择后退出选择态 */
    private fun finishSelection() {
        clearSelectionState("操作完成")
    }

    fun paste() {
        val ic = ic() ?: return
        val before = snapshot(ic)?.text ?: ""
        val ok = try {
            ic.performContextMenuAction(android.R.id.paste)
        } catch (t: Throwable) {
            L.hookError("EditActions.paste", t)
            false
        }
        // 粘贴到底成没成，看编辑框里的字有没有变——
        // 有的编辑框（豆包的聊天输入框、Flutter 应用）返回 false，其实已经粘进去了，
        // 老版本照着返回值读“当前编辑框不支持”，用户明明看到字进来了（Bug6）。
        checkPaste(ic, before, ok, 0)
    }

    private fun checkPaste(ic: InputConnection, before: String, ok: Boolean, round: Int) {
        main.postDelayed({
            val after = snapshot(ic)
            val now = after?.text ?: before
            val changed = after != null && now != before
            if (changed || ok) {
                val inserted = EchoTextChange.between(before, now).inserted
                val sel = try {
                    ic.getSelectedText(0)?.toString()
                } catch (_: Throwable) {
                    null
                }
                val raw = inserted.ifEmpty { sel.orEmpty() }
                val label = plainLabel(raw)
                // 读屏模式下读屏对“整段文字被替换”这件事经常一声不吭（用户反馈粘贴没播报），
                // 所以粘贴结果一律由模块自己发一条播报（仍然是读屏念，内容由模块给）
                Speaker.unmute()
                Speaker.echo(GestureSpeechFormat.pasted(label, raw.length))
                L.i("粘贴成功：字数 ${before.length} → ${now.length}")
                // 粘进来字以后编辑菜单要换成完整那套（并从“粘贴”回到“双击跳行首行尾”）
                EditOptions.refresh("粘贴成功")
                return@postDelayed
            }
            // 慢一点的编辑框再等一次
            if (round < 2) {
                checkPaste(ic, before, ok, round + 1)
                return@postDelayed
            }
            Speaker.speak("当前编辑框不支持粘贴")
            L.w("粘贴没生效：编辑框文字没变化，返回值=$ok")
        }, if (round == 0) 110L else 260L)
    }

    /** 剪贴板里现在是什么（只用来判断复制有没有成功，读不到就当没有） */
    private fun clipboardText(): String? = try {
        val ctx = ViewA11yHooks.service
        val cm = ctx?.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
    } catch (_: Throwable) {
        null
    }

    private fun menu(id: Int, done: String) {
        val ic = ic() ?: return
        val ok = try {
            ic.performContextMenuAction(id)
        } catch (t: Throwable) {
            L.hookError("EditActions.menu", t)
            false
        }
        Speaker.speak(if (ok) done else "当前编辑框不支持")
    }

    private fun arrow(code: Int) = send(code)

    private fun send(code: Int) {
        val ic = ic() ?: return
        val t = android.os.SystemClock.uptimeMillis()
        try {
            ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
            ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
        } catch (e: Throwable) {
            L.hookError("EditActions.send", e)
        }
    }

    /** 动作 id 是不是编辑类（这些交给本对象执行） */
    fun handles(action: Int): Boolean = when (action) {
        ConfigSpec.ACT_PREV_CHAR, ConfigSpec.ACT_NEXT_CHAR, ConfigSpec.ACT_PREV_LINE, ConfigSpec.ACT_NEXT_LINE,
        ConfigSpec.ACT_LINE_START, ConfigSpec.ACT_LINE_END, ConfigSpec.ACT_PREV_SENTENCE, ConfigSpec.ACT_NEXT_SENTENCE,
        ConfigSpec.ACT_PREV_PARA, ConfigSpec.ACT_NEXT_PARA,
        ConfigSpec.ACT_DOC_START, ConfigSpec.ACT_DOC_END,
        ConfigSpec.ACT_SELECT_TEXT, ConfigSpec.ACT_DESELECT,
        ConfigSpec.ACT_DELETE_LEFT, ConfigSpec.ACT_DELETE_RIGHT,
        ConfigSpec.ACT_DELETE_PREV_SENTENCE, ConfigSpec.ACT_DELETE_NEXT_SENTENCE, ConfigSpec.ACT_ENTER,
        ConfigSpec.ACT_CLEAR_TEXT, ConfigSpec.ACT_UNDO,
        ConfigSpec.ACT_READ_EDITOR, ConfigSpec.ACT_READ_LINE, ConfigSpec.ACT_READ_ALL, ConfigSpec.ACT_READ_SELECTION,
        ConfigSpec.ACT_CHAR_COUNT, ConfigSpec.ACT_HINT,
        ConfigSpec.ACT_SELECT_ALL, ConfigSpec.ACT_COPY, ConfigSpec.ACT_PASTE, ConfigSpec.ACT_CUT,
        -> true
        else -> false
    }

    /** 执行编辑类动作；失败返回 false */
    fun run(action: Int): Boolean {
        when (action) {
            ConfigSpec.ACT_PREV_CHAR -> prevChar()
            ConfigSpec.ACT_NEXT_CHAR -> nextChar()
            ConfigSpec.ACT_PREV_LINE -> prevLine()
            ConfigSpec.ACT_NEXT_LINE -> nextLine()
            ConfigSpec.ACT_PREV_PARA -> prevParagraph()
            ConfigSpec.ACT_NEXT_PARA -> nextParagraph()
            ConfigSpec.ACT_LINE_START -> lineStart()
            ConfigSpec.ACT_LINE_END -> lineEnd()
            ConfigSpec.ACT_DOC_START -> docStart()
            ConfigSpec.ACT_DOC_END -> docEnd()
            ConfigSpec.ACT_PREV_SENTENCE -> prevSentence()
            ConfigSpec.ACT_NEXT_SENTENCE -> nextSentence()
            ConfigSpec.ACT_SELECT_TEXT -> beginSelect()
            ConfigSpec.ACT_DESELECT -> deselect()
            ConfigSpec.ACT_DELETE_LEFT -> deleteLeft()
            ConfigSpec.ACT_DELETE_RIGHT -> deleteRight()
            ConfigSpec.ACT_DELETE_PREV_SENTENCE -> deletePrevSentence()
            ConfigSpec.ACT_DELETE_NEXT_SENTENCE -> deleteNextSentence()
            ConfigSpec.ACT_CLEAR_TEXT -> clearText()
            ConfigSpec.ACT_UNDO -> undo()
            ConfigSpec.ACT_ENTER -> enter()
            ConfigSpec.ACT_READ_EDITOR -> readEditor()
            ConfigSpec.ACT_READ_LINE -> readLine()
            ConfigSpec.ACT_READ_ALL -> readAll()
            ConfigSpec.ACT_READ_SELECTION -> readSelection()
            ConfigSpec.ACT_CHAR_COUNT -> charCount()
            ConfigSpec.ACT_HINT -> hint()
            ConfigSpec.ACT_SELECT_ALL -> selectAll()
            ConfigSpec.ACT_COPY -> copy()
            ConfigSpec.ACT_PASTE -> paste()
            ConfigSpec.ACT_CUT -> cut()
            else -> return false
        }
        return true
    }
}
