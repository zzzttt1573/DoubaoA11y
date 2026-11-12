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

    /** 正在打拼音、候选栏里有候选 */
    val composing: Boolean get() = pinyin.isNotBlank() && current.any { it.isNotBlank() }

    private val navOn: Boolean get() = VirtualKeys.enabled && Config.b("candidateNav") && composing

    /**
     * 有候选时哪些键用来左移、右移候选：逗号、句号；九键拼音上空格左边的 123 键和右边的中英切换键。
     * 返回 -1 左移，1 右移，0 不是这些键。
     */
    private fun navDir(k: Key): Int {
        if (!navOn) return 0
        val m = k.mainName()
        when (m) {
            "逗号" -> return -1
            "句号" -> return 1
        }
        if (KeyboardState.boardName == "九键拼音") {
            if (m == "123") return -1
            if (m == "中" || k.speakable() == "英文键盘") return 1
        }
        return 0
    }

    /** 有候选时左移、右移键的读法；不是这些键返回 null */
    fun navName(k: Key): String? = when (navDir(k)) {
        -1 -> "左移"
        1 -> "右移"
        else -> null
    }

    /** 抬手（或双击）输入一个键之前问这里；返回 true 表示模块已处理，不再把键交给豆包 */
    fun onKey(k: Key): Boolean {
        if (!navOn) return false
        val d = navDir(k)
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
            Speaker.speak("已到头，" + CharWords.candidate(list[now]))
            return
        }
        sel = n
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
    private fun afterLoad(old: List<String>, list: List<String>) {
        loadingFrom = null
        main.removeCallbacksAndMessages(loadToken)
        // 一般是在原来的后面接上；不是的话，从第一个不在原来里面的开始
        val n = if (list.size > old.size && list.subList(0, old.size) == old) old.size
        else list.indexOfFirst { it !in old }
        if (n < 0 || n >= list.size) {
            L.i("加载更多后没有新候选（${list.size} 个）")
            Speaker.speak("已到头，" + CharWords.candidate(old[old.size - 1]))
            sel = (list.size - 1).coerceAtLeast(0)
            return
        }
        sel = n
        L.i("加载更多后切换到第${n + 1}个「${list[n]}」，共 ${list.size} 个")
        Speaker.speak(CharWords.candidate(list[n]))
    }

    /** 空格：选了别的候选就上屏那个；还是第一个就照常按空格 */
    private fun commitSelected(): Boolean {
        val i = sel
        if (i <= 0 || i >= current.size) return false
        val j = JniHooks.jni() ?: return false
        return try {
            val e = engineIdx.getOrElse(i) { i }
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
        InputHooks.onCommit { _, _ -> reset("上屏") }
    }

    fun reset(why: String) {
        if (pinyin.isEmpty() && current.isEmpty()) return
        L.i("候选状态清空（$why）")
        current = emptyList()
        engineIdx = emptyList()
        pinyin = ""
        sel = 0
        listKey = ""
        last = ""
        loadingFrom = null
        main.removeCallbacksAndMessages(loadToken)
    }

    fun onSnapshot(args: Array<Any?>) {
        @Suppress("UNCHECKED_CAST")
        val raw = (args.firstOrNull { it is Array<*> && it.isArrayOf<String>() } as? Array<String>)?.toList() ?: return
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
        (args.getOrNull(0) as? Long)?.let { snapId = it }
        val oldPy = pinyin
        current = list
        engineIdx = eng
        pinyin = py
        val lk = py + "|" + list.joinToString("|")
        val waiting = loadingFrom
        if (lk != listKey) {
            listKey = lk
            sel = 0
            if (waiting != null) {
                if (py == oldPy && py.isNotBlank()) afterLoad(waiting, list) else loadingFrom = null
            }
        }
        if (waiting != null && py == oldPy) return
        if (!VirtualKeys.enabled || !Config.b("speakCandidate")) return
        val first = list.firstOrNull { it.isNotBlank() } ?: return
        if (py.isBlank()) {
            last = ""
            return
        }
        val key = "$py|$first"
        if (key == last) return
        last = key
        val text = CharWords.candidate(first)
        // 稍等一下，让读屏先读完编辑框里拼音的变化，再读候选
        main.removeCallbacksAndMessages(token)
        main.postAtTime({ Speaker.speak(text) }, token, android.os.SystemClock.uptimeMillis() + 120)
    }
}
