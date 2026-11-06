package io.github.doubaoa11y.hook

import android.os.Handler
import android.os.Looper

/**
 * 候选栏：打拼音时朗读第一个候选。
 * 数据来自 KeyboardJni.notifyCandidateBarSnapshot：第一个 String[] 是候选词，第一个 String 是拼音串。
 */
object Candidates {
    private val main = Handler(Looper.getMainLooper())
    private var last = ""
    private val token = Any()

    @Volatile
    var current: List<String> = emptyList()
        private set

    @Volatile
    var pinyin: String = ""
        private set

    fun onSnapshot(args: Array<Any?>) {
        @Suppress("UNCHECKED_CAST")
        val list = (args.firstOrNull { it is Array<*> && it.isArrayOf<String>() } as? Array<String>)?.toList() ?: return
        val py = args.firstOrNull { it is String } as? String ?: ""
        current = list
        pinyin = py
        if (!VirtualKeys.enabled || !Config.b("speakCandidate")) return
        val first = list.firstOrNull { it.isNotBlank() } ?: return
        if (py.isBlank()) {
            last = ""
            return
        }
        val key = "$py|$first"
        if (key == last) return
        last = key
        val text = CharWords.explainWord(first)
        // 稍等一下，让读屏先读完编辑框里拼音的变化，再读候选
        main.removeCallbacksAndMessages(token)
        main.postAtTime({ Speaker.speak(text) }, token, android.os.SystemClock.uptimeMillis() + 120)
    }
}
