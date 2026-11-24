package io.github.doubaoa11y.hook

import android.os.Handler
import android.os.Looper

/**
 * 候选栏：打拼音时朗读第一个候选。
 * 数据来自 KeyboardJni.notifyCandidateBarSnapshot：第一个 String[] 是候选词，第一个 String 是拼音串，
 * 第 9 个参数（int[]）是引擎序号，-1 表示不是候选（如“手写找字”入口）。
 */
object Candidates {
    private val main = Handler(Looper.getMainLooper())
    private var last = ""
    private val token = Any()

    @Volatile
    var current: List<String> = emptyList()
        private set

    /** current 里每个候选在豆包引擎里的序号（selectCand 用） */
    @Volatile
    private var engineIdx: List<Int> = emptyList()

    @Volatile
    var pinyin: String = ""
        private set

    /** 逗号、句号选到的候选（下标，0 是第一个） */
    @Volatile
    var sel = 0
        private set
    private var listKey = ""

    private val origin = CandidateOriginState()
    @Volatile
    private var lastTypingAt = 0L
    @Volatile
    private var suppressSpeakUntil = 0L
    /** 必须属于当前输入会话；无拼音串仅凭手写/实际组字或按键+引擎证据兜底。 */
    val composing: Boolean get() = origin.isOpen() && current.any { it.isNotBlank() }
    fun hasCandidates(): Boolean = composing
    fun suppressSpeakFor(ms: Long) { suppressSpeakUntil = android.os.SystemClock.uptimeMillis() + ms }
    fun prev(): Boolean { if (!composing) return false; move(-1); return true }
    fun next(): Boolean { if (!composing) return false; move(1); return true }
    fun commit(): Boolean = composing && (commitSelected() || VirtualKeys.tapKeyNamed("空格"))
    fun markTyping() {
        lastTypingAt = android.os.SystemClock.uptimeMillis()
        suppressSpeakUntil = 0L
        Voice.onManualInput()
        origin.input(CandidateOriginState.Input.KEY)
    }
    fun markHandwriting() {
        lastTypingAt = android.os.SystemClock.uptimeMillis()
        suppressSpeakUntil = 0L
        Voice.onManualInput()
        origin.input(CandidateOriginState.Input.HANDWRITING)
    }
    fun onCursorMove() { origin.cursorMoved(); clearView("光标移动") }
    fun onDelete() { clearSession("删除候选") }
    private fun isEnglishBoard(): Boolean = JniHooks.isEnglish || KeyboardState.boardName == "英文键盘"
    fun onExternalSelection(hasComposingRegion: Boolean) {
        // 刚抬手输入按键时，合成的 ACTION_UP（40ms 后）和引擎快照还在路上；
        // 此时到达的异步 onUpdateSelection 多半来自上一笔删除/上屏，不能误判为移光标而挡掉新按键的候选。
        if (android.os.SystemClock.uptimeMillis() - lastTypingAt < 250L) return
        // 英文键盘下把光标移到已有单词中间时，豆包会自动给该单词加上 composingRegion 并返回 engineTyping=true，
        // 若直接放行会把单词联想误当成打字候选；只要最近没真实按过键，仍按移光标清理候选态。
        if (!isEnglishBoard() && (hasComposingRegion || engineTyping() == true)) return
        onCursorMove()
    }
    private var queryFailureLogged = false
    fun engineTyping(): Boolean? = try {
        val j = JniHooks.jni()
        if (j == null) null else de.robv.android.xposed.XposedHelpers.callMethod(j, "isTypingInput") as? Boolean
    } catch (t: Throwable) {
        if (!queryFailureLogged) { queryFailureLogged = true; L.hookError("Candidates.isTypingInput", t) }
        null
    }
    fun onPinyinCleared() {
        // 手写本来没有拼音串，空拼音通知不能当成取消手写候选。
        if (pinyin.isBlank() && origin.fromHandwriting()) return
        if (engineTyping() == false) clearSession("拼音栏已空")
    }

    private val navOn: Boolean get() = VirtualKeys.enabled && Config.b("candidateNav") && composing

    private val ENTER_NAMES = setOf("回车", "换行", "发送", "搜索", "完成", "下一项", "前往")

    /**
     * 有候选时哪些键用来左移、右移候选：
     * - 优先按当前键盘键位表定位空格键，将与空格同一底行的左侧所有按键映射为左移，右侧按键映射为右移；
     * - 排除竖排符号栏（inColumn）与横排分类栏（inRow）；
     * - 回车/发送类按键由 candidateNavEnter 开关决定是否并入右移；
     * - 若无法定位空格键，则降级回退到旧的按名称映射（全键逗号/句号，九键 123/中英）。
     * 返回 -1 左移，1 右移，0 不是这些键。
     */
    private fun navDir(k: Key, keys: List<Key> = emptyList()): Int {
        if (!navOn) return 0
        if (k.inColumn || k.inRow) return 0
        val m = k.mainName()
        if (m == "空格") return 0
        if (m in ENTER_NAMES && !Config.b("candidateNavEnter")) return 0

        if (keys.isNotEmpty()) {
            val space = keys.firstOrNull { it.mainName() == "空格" && !it.inColumn && !it.inRow }
            if (space != null) {
                val overlap = minOf(k.rect.bottom, space.rect.bottom) - maxOf(k.rect.top, space.rect.top)
                val minH = minOf(k.rect.height(), space.rect.height())
                if (minH > 0 && overlap * 2 >= minH) {
                    val cx = k.rect.centerX()
                    if (cx < space.rect.left) return -1
                    if (cx > space.rect.right) return 1
                }
                return 0
            }
        }

        // 兜底：拿不到空格键位置时按原有键名映射
        if (KeyboardState.boardName == "九键拼音") {
            if (m == "123") return -1
            if (m == "中" || k.speakable() == "英文键盘") return 1
            return 0
        }
        when (m) {
            "逗号" -> return -1
            "句号" -> return 1
        }
        return 0
    }

    /** 有候选时左移、右移键的读法；不是这些键返回 null */
    fun navName(k: Key, keys: List<Key> = emptyList()): String? = when (navDir(k, keys)) {
        -1 -> "左移"
        1 -> "右移"
        else -> null
    }

    /** 抬手（或双击）输入一个键之前问这里；返回 true 表示模块已处理，不再把键交给豆包 */
    fun onKey(k: Key, keys: List<Key> = emptyList()): Boolean {
        if (!navOn) return false
        val d = navDir(k, keys)
        if (d != 0) {
            move(d)
            return true
        }
        return k.mainName() == "空格" && commitSelected()
    }

    /** 候选栏快照序号（notifyCandidateBarSnapshot 第 1 个参数），加载更多时交给豆包 */
    @Volatile
    private var snapId = 0L

    /** 正在等豆包加载更多候选：等到的新快照里，选中第一个新候选 */
    private var loadingFrom: List<String>? = null
    private val loadToken = Any()

    private fun move(d: Int) {
        val list = current
        if (list.isEmpty()) return
        val now = sel.coerceIn(0, list.size - 1)
        val n = now + d
        if (d > 0 && n >= list.size && loadMore(list)) return
        if (n < 0 || n >= list.size || list[n].isBlank()) {
            Sound.play("boundary")
            Speaker.speak("已到头，" + CharWords.candidate(list[now]))
            return
        }
        sel = n
        Sound.play("scroll")
        L.i("候选切换到第${n + 1}个「${list[n]}」")
        Speaker.speak(CharWords.candidate(list[n]))
    }

    /** 翻页：让豆包加载更多候选（和手指把候选栏划到最后一样）。返回 false 表示没法加载 */
    private fun loadMore(list: List<String>): Boolean {
        if (loadingFrom != null) return true
        val j = JniHooks.jni() ?: return false
        return try {
            L.i("候选到最后一个，加载更多（快照 $snapId，现有 ${list.size} 个）")
            loadingFrom = list
            de.robv.android.xposed.XposedHelpers.callMethod(j, "onCandidateBarCandidateLoadMore", snapId)
            main.removeCallbacksAndMessages(loadToken)
            main.postAtTime({
                if (loadingFrom != null) {
                    loadingFrom = null
                    L.i("加载更多候选没有结果")
                    Speaker.speak("已到头，" + CharWords.candidate(list[list.size - 1]))
                }
            }, loadToken, android.os.SystemClock.uptimeMillis() + 1500)
            true
        } catch (t: Throwable) {
            loadingFrom = null
            L.hookError("Candidates.loadMore", t)
            false
        }
    }

    /** 新快照到了：如果在等加载更多，选中第一个新候选并朗读 */
    private fun afterLoad(old: List<String>, list: List<String>, firstNew: Int) {
        loadingFrom = null
        main.removeCallbacksAndMessages(loadToken)
        if (firstNew < 0 || firstNew >= list.size) {
            L.i("加载更多后没有新候选（${list.size} 个）")
            if (old.isNotEmpty()) Speaker.speak("已到头，" + CharWords.candidate(old[old.size - 1]))
            sel = (list.size - 1).coerceAtLeast(0)
            return
        }
        sel = firstNew
        Sound.play("scroll")
        L.i("加载更多后切换到第${firstNew + 1}个「${list[firstNew]}」，共 ${list.size} 个（引擎序号 ${engineIdx.firstOrNull()}..${engineIdx.lastOrNull()}）")
        Speaker.speak(CharWords.candidate(list[firstNew]))
    }

    /** 空格：选了别的候选就上屏那个；还是第一个（引擎序号 0）就照常按空格 */
    private fun commitSelected(): Boolean {
        val i = sel
        if (i < 0 || i >= current.size) return false
        val e = engineIdx.getOrElse(i) { i }
        if (i == 0 && e <= 0) return false
        val j = JniHooks.jni() ?: return false
        return try {
            L.i("空格上屏第${i + 1}个候选「${current[i]}」，引擎序号 $e")
            de.robv.android.xposed.XposedHelpers.callMethod(j, "selectCand", e)
            sel = 0
            true
        } catch (t: Throwable) {
            L.hookError("Candidates.selectCand", t)
            false
        }
    }

    /**
     * 上屏、收起键盘、换输入框时清掉候选状态。豆包上屏后不一定再送一份空的候选栏，
     * 不清的话模块一直以为还在打拼音：空格不能语音，逗号句号还在切换候选。
     * 上屏后还剩拼音的（只选了前半截），豆包会紧接着送新的候选栏，状态会再设回来。
     */
    fun install() {
        InputHooks.onCommit { _, _ -> origin.committed(); clearView("上屏") }
        InputHooks.onComposing { text ->
            if (text.isBlank()) clearSession("拼音删空")
            else if (!Voice.isRecording() && !origin.isMoved()) {
                // 英文键盘若近期未发生真实按键输入（光标落入已有单词触发的重新组字/联想），不误开启候选态
                val recentlyTyped = android.os.SystemClock.uptimeMillis() - lastTypingAt < 1500L
                if (!isEnglishBoard() || recentlyTyped) {
                    origin.input(CandidateOriginState.Input.COMPOSING)
                }
            }
        }
        InputHooks.onFinishComposing { origin.committed(); clearView("结束组字") }
        InputHooks.onDelete { onDelete() }
    }

    /** 工具栏恢复闲置。豆包在短信快捷输入等场景可能忽略切换，先核对引擎确实已退出组字。 */
    fun onToolbarIdle() {
        if (pinyin.isEmpty() && current.isEmpty()) return
        val j = JniHooks.jni() ?: return
        try {
            val typing = de.robv.android.xposed.XposedHelpers.callMethod(j, "isTypingInput") as? Boolean ?: return
            L.i("工具栏恢复闲置：引擎组字=$typing")
            if (!typing && !origin.fromHandwriting()) clearSession("工具栏恢复，拼音已清空")
        } catch (t: Throwable) {
            L.hookError("Candidates.toolbarIdle", t)
        }
    }

    /** 清理候选会话但保留“刚移过光标”标记（用于删空/重框旧组字等非键盘输入回调） */
    private fun clearSession(why: String) { origin.clearSession(); clearView(why) }

    fun reset(why: String) { origin.clear(); clearView(why) }

    private fun clearView(why: String) {
        if (pinyin.isNotEmpty() || current.isNotEmpty()) L.i("候选状态清空（$why）")
        current = emptyList()
        engineIdx = emptyList()
        pinyin = ""
        sel = 0
        listKey = ""
        last = ""
        loadingFrom = null
        main.removeCallbacksAndMessages(token)
        main.removeCallbacksAndMessages(loadToken)
    }

    fun onSnapshot(args: Array<Any?>) {
        @Suppress("UNCHECKED_CAST")
        val raw = (args.firstOrNull { it is Array<*> && it.isArrayOf<String>() } as? Array<String>)?.toList().orEmpty()
        val py = args.firstOrNull { it is String } as? String ?: ""
        // 第 9 个参数是每项在引擎里的序号；“手写找字”这类入口不是候选，序号是 -1，跳过
        val idx = (args.getOrNull(8) as? IntArray)?.takeIf { it.size == raw.size }
        val list = ArrayList<String>()
        val eng = ArrayList<Int>()
        for (k in raw.indices) {
            val e = idx?.get(k) ?: k
            if (e < 0) continue
            list.add(raw[k])
            eng.add(e)
        }
        // 删空时可能收到空数组或 null；也可能仅剩“手写找字”等非候选入口。
        // 必须同时撤掉翻页等待和延迟朗读，避免旧候选继续影响空格、标点。
        val typingNow = engineTyping()
        if (isEnglishBoard() && origin.isMoved()) {
            if (py.isNotBlank() || list.any { it.isNotBlank() }) {
                L.i("英文键盘光标移动后的联想候选，忽略（py=$py，首项=${list.firstOrNull().orEmpty()}）")
            }
            clearView("英文键盘移光标联想")
            return
        }
        if (!origin.snapshot(py.isNotBlank(), list.any { it.isNotBlank() }, typingNow)) {
            if (py.isNotBlank() || list.any { it.isNotBlank() }) {
                L.i("忽略非当前输入会话候选（py=$py，首项=${list.firstOrNull().orEmpty()}，引擎组字=$typingNow）")
            }
            clearView("无当前输入会话的候选")
            return
        }
        (args.getOrNull(0) as? Long)?.let { snapId = it }
        val oldPy = pinyin
        val prevList = current
        val prevEng = engineIdx
        val lk = py + "|" + eng.joinToString(",") + "|" + list.joinToString("|")
        val waiting = loadingFrom
        if (lk != listKey) {
            listKey = lk
            if (waiting != null && py == oldPy) {
                val merged = CandidatePages.merge(prevList, prevEng, list, eng, hasEngineIdx = idx != null)
                current = merged.items
                engineIdx = merged.engineIdx
                pinyin = py
                if (composing) afterLoad(waiting, merged.items, merged.firstNew) else loadingFrom = null
            } else {
                if (waiting != null) {
                    loadingFrom = null
                    main.removeCallbacksAndMessages(loadToken)
                }
                current = list
                engineIdx = eng
                pinyin = py
                sel = 0
            }
        }
        if (waiting != null && py == oldPy) return
        if (!VirtualKeys.enabled || !Config.b("speakCandidate")) return
        if (android.os.SystemClock.uptimeMillis() < suppressSpeakUntil) return
        val first = list.firstOrNull { it.isNotBlank() } ?: return
        val key = "$py|$first"
        if (key == last) return
        last = key
        val text = CharWords.candidate(first)
        main.removeCallbacksAndMessages(token)
        // 这期间手指已经摸到下一个键：不读了，免得插在下一个键的朗读后面
        if (VirtualKeys.movedOnSinceTap()) {
            L.i("已摸到下一个键，不读候选「$first」")
            return
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            L.i("朗读候选「$first」（py=$py）")
            Speaker.speak(text)
        } else {
            main.postAtTime({
                if (android.os.SystemClock.uptimeMillis() < suppressSpeakUntil) return@postAtTime
                if (VirtualKeys.movedOnSinceTap()) L.i("已摸到下一个键，不读候选「$first」")
                else {
                    L.i("朗读候选「$first」（py=$py）")
                    Speaker.speak(text)
                }
            }, token, android.os.SystemClock.uptimeMillis())
        }
    }
}
