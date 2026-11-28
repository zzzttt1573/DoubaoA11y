package io.github.doubaoa11y.hook

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import java.util.WeakHashMap

/** 统一文本回声（独立 TTS，以及天坦读屏屏蔽 16/8192 期间）。只读操作成功后确认的变化，密码框和无法查询的编辑器保守不补读。 */
object TypingEcho {
    private val main = Handler(Looper.getMainLooper())
    private val composing = WeakHashMap<InputConnection, Composing>()
    private var session = 0L
    private var deletion: PendingDelete? = null
    private var suppressDeleteUntil = 0L
    private var suppressInputUntil = 0L
    private var suppressInputSyncDepth = 0
    data class Snapshot(val text: String, val selectionStart: Int = -1, val selectionEnd: Int = -1)
    private data class Composing(val start: Int, val inserted: String, val confirmedBody: String) {
        fun validIn(body: String): Boolean = body == confirmedBody && start >= 0 &&
            start + inserted.length <= body.length && body.regionMatches(start, inserted, 0, inserted.length)
    }
    private data class PendingDelete(val session: Long, val connection: InputConnection, val before: Snapshot)
    private val suppressedByReader: Boolean get() = TatansBridge.readerEventsSuppressed
    private val enabled: Boolean
        get() = A11yState.recording && (suppressedByReader || !Config.b("speakByReader")) &&
            ViewA11yHooks.service?.currentInputEditorInfo != null
    private val inputEchoOn: Boolean get() = suppressedByReader || (!Config.b("speakByReader") && Config.b("ttsInputEcho"))
    private val deleteEchoOn: Boolean get() = suppressedByReader || (!Config.b("speakByReader") && Config.b("ttsDeleteEcho"))
    private val voiceEchoOn: Boolean get() = suppressedByReader || (!Config.b("speakByReader") && Config.b("ttsVoiceEcho"))
    fun observing(): Boolean = enabled && (inputEchoOn || deleteEchoOn || voiceEchoOn)
    private fun inputSuppressed(): Boolean = suppressInputSyncDepth > 0 || SystemClock.uptimeMillis() < suppressInputUntil
    private fun deleteSuppressed(): Boolean = SystemClock.uptimeMillis() < suppressDeleteUntil
    fun suppressDeleteFor(ms: Long = 220L) {
        suppressDeleteUntil = SystemClock.uptimeMillis() + ms
        deletion = null
        main.removeCallbacks(readDeletion)
    }
    fun suppressInputFor(ms: Long = 300L) {
        suppressInputUntil = SystemClock.uptimeMillis() + ms
    }
    inline fun suppressingInput(block: () -> Unit) {
        beginSuppressInputSync()
        try {
            block()
        } finally {
            endSuppressInputSync()
        }
    }
    @PublishedApi internal fun beginSuppressInputSync() { suppressInputSyncDepth++ }
    @PublishedApi internal fun endSuppressInputSync() { suppressInputSyncDepth = (suppressInputSyncDepth - 1).coerceAtLeast(0) }
    fun snapshotForInput(ic: InputConnection): Snapshot? {
        if (!enabled || inputSuppressed() || !InputPrivacy.mayRead()) return null
        if (!inputEchoOn && !(voiceEchoOn && Voice.needsEchoSnapshot())) return null
        return readSnapshot(ic)
    }
    fun snapshotForDelete(ic: InputConnection): Snapshot? {
        if (!enabled || !deleteEchoOn || deleteSuppressed() || !InputPrivacy.mayRead()) return null
        return readSnapshot(ic)
    }
    fun snapshot(ic: InputConnection): Snapshot? {
        if (!observing() || !InputPrivacy.mayRead()) return null
        return readSnapshot(ic)
    }
    private fun readSnapshot(ic: InputConnection): Snapshot? {
        return try {
            val request = ExtractedTextRequest().apply { hintMaxChars = 8192; hintMaxLines = 100 }
            val value = ic.getExtractedText(request, 0) ?: return null
            if (value.startOffset != 0 || value.partialStartOffset >= 0) return null
            val text = value.text?.toString() ?: return null
            if (text.length > 8192) null else Snapshot(text, value.selectionStart, value.selectionEnd)
        } catch (_: Throwable) { null }
    }
    fun reset() { session++; composing.clear(); deletion = null; suppressDeleteUntil = 0L; suppressInputUntil = 0L; main.removeCallbacks(readDeletion) }
    fun successful(ic: InputConnection, name: String, argument: String, before: Snapshot?) {
        if (!enabled || !InputPrivacy.mayRead()) return
        if (inputSuppressed()) return
        if (name == "finishComposingText") {
            val value = composing.remove(ic) ?: return
            if (snapshot(ic)?.text?.let { value.validIn(it) } != true) return
            if (!Voice.acceptSuccessfulWrite(value.inserted, true) && inputEchoOn) Speaker.echo(value.inserted)
            return
        }
        val previous = if (name == "commitText") composing.remove(ic) else composing[ic]
        val old = before ?: return
        val after = snapshot(ic) ?: return
        val change = EchoTextChange.between(old.text, after.text)
        if (change.inserted.isEmpty()) {
            // 正文不变的 commit 只有匹配此前确认的同一组字段才可补读。
            // 先交给语音事务处理，避免语音组字+确认又被普通输入路径重复朗读。
            if (name == "commitText" && old.text == after.text && previous?.validIn(old.text) == true &&
                argument == previous.inserted && argument.isNotEmpty()) {
                if (!Voice.acceptSuccessfulWrite(previous.inserted, true) && inputEchoOn)
                    Speaker.echo(previous.inserted)
            } else if (name == "commitText") Voice.acceptConfirmedCommit(argument)
            return
        }
        // 用确切替换位置与完整正文验证累计组字，不能靠 contains 命中别处旧文字。
        val record = confirmedComposition(old, after, argument, previous, change.inserted)
        val confirmed = record.inserted
        if (name == "setComposingText") composing[ic] = record
        if (Voice.acceptSuccessfulWrite(confirmed, name == "commitText")) return
        if (name == "commitText" && inputEchoOn) Speaker.echo(confirmed)
    }
    private fun matchesReplaced(before: String, start: Int, end: Int, replacement: String, after: String): Boolean {
        if (start < 0 || end < start || end > before.length) return false
        val expectedLen = before.length - (end - start) + replacement.length
        if (after.length != expectedLen) return false
        if (start > 0 && !before.regionMatches(0, after, 0, start)) return false
        if (replacement.isNotEmpty() && !after.regionMatches(start, replacement, 0, replacement.length)) return false
        val tailLen = before.length - end
        return tailLen <= 0 || before.regionMatches(end, after, start + replacement.length, tailLen)
    }

    private fun confirmedComposition(before: Snapshot, after: Snapshot, argument: String,
        previous: Composing?, actualDelta: String): Composing {
        if (previous?.validIn(before.text) == true &&
            matchesReplaced(before.text, previous.start, previous.start + previous.inserted.length, argument, after.text))
            return Composing(previous.start, argument, after.text)
        val start = minOf(before.selectionStart, before.selectionEnd)
        val end = maxOf(before.selectionStart, before.selectionEnd)
        if (start >= 0 && end <= before.text.length &&
            matchesReplaced(before.text, start, end, argument, after.text))
            return Composing(start, argument, after.text)
        // 无可验证的组字或选区位置时，只记实际新增的差异段。
        var at = 0
        while (at < before.text.length && at < after.text.length && before.text[at] == after.text[at]) at++
        if (at > 0 && at < after.text.length && Character.isLowSurrogate(after.text[at]) && Character.isHighSurrogate(after.text[at - 1])) at--
        return Composing(at, actualDelta, after.text)
    }
    fun deleted(ic: InputConnection, before: Snapshot?) {
        if (!enabled || !deleteEchoOn || before == null || SystemClock.uptimeMillis() < suppressDeleteUntil) return
        if (deletion?.session != session || deletion?.connection !== ic) deletion = PendingDelete(session, ic, before)
        main.removeCallbacks(readDeletion)
        main.postDelayed(readDeletion, 55)
    }
    private val readDeletion = Runnable {
        val pending = deletion ?: return@Runnable
        deletion = null
        if (!enabled || !deleteEchoOn || pending.session != session || SystemClock.uptimeMillis() < suppressDeleteUntil) return@Runnable
        val after = snapshot(pending.connection) ?: return@Runnable
        val change = EchoTextChange.between(pending.before.text, after.text)
        if (change.removed.isEmpty() || change.inserted.isNotEmpty()) return@Runnable
        val n = change.removed.codePointCount(0, change.removed.length)
        val removedLabel = if (n == 1 && change.removed.length == 1) {
            when (val ch = change.removed[0]) {
                ' ' -> "空格"
                '\n' -> "换行"
                else -> Names.spoken(ch.toString())
            }
        } else {
            change.removed.replace("\r\n", "\n").replace("\n", "换行")
        }
        Speaker.echo(GestureSpeechFormat.deleted(removedLabel, n))
    }
    fun voiceResult(text: String) {
        if (enabled && InputPrivacy.mayRead() && voiceEchoOn) Speaker.echo(text)
    }
}
