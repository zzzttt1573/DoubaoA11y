package io.github.doubaoa11y.hook

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * 阶段 4：键盘状态变化的提示——切换键盘、中英文，Shift 大小写。
 * 键盘类型来自 KeyboardJni.getCurrentKbdType / getKeyboardType（键盘切换后查询），
 * 大小写来自键位表里 Shift 键的图标。
 */
object KeyboardState {
    private val main = Handler(Looper.getMainLooper())
    private val token = Any()

    /** getCurrentKbdType 的编号（按 1.5.3 实测） */
    private val kbdNames = mapOf(
        0 to "全键拼音",
        1 to "九键拼音",
        2 to "英文键盘",
        3 to "符号键盘",
        5 to "数字键盘",
        6 to "手写键盘",
        8 to "数字符号键盘",
        9 to "数字符号键盘",
        11 to "笔画键盘",
    )

    /** 编号表里没有时按 getKeyboardType 的名字 */
    private val typeNames = mapOf(
        "key_26" to "全键拼音",
        "key_9" to "九键拼音",
        "key_eng" to "英文键盘",
        "handwrite" to "手写键盘",
        "stroke" to "笔画键盘",
        "wubi" to "五笔键盘",
        "shuangpin" to "双拼键盘",
    )

    private var lastBoard = ""
    private var lastShift = ""

    @Volatile
    var boardName = ""
        private set

    private val speakOn: Boolean get() = VirtualKeys.enabled && Config.b("announceMode")

    fun nameOf(type: String?, kbd: Int?): String {
        // 双拼的编号和全键拼音一样是 0，要看类型名（sp_input_xiaohe 等，按方案不同）
        if (type != null && type.startsWith("sp_")) return "双拼键盘"
        return kbd?.let { kbdNames[it] } ?: type?.let { typeNames[it] } ?: ("键盘 " + (type ?: kbd ?: "未知"))
    }

    /** 最近一次的中文键盘（英文键盘上的中英切换键切回去的那个） */
    @Volatile
    var lastChinese = ""
        private set
    private val notChinese = setOf("英文键盘", "符号键盘", "数字键盘", "数字符号键盘")

    /** 键盘切换后查询到类型 */
    fun onBoard(type: String?, kbd: Int?) {
        val name = nameOf(type, kbd)
        boardName = name
        if (name !in notChinese && !name.startsWith("键盘 ")) lastChinese = name
        if (name == lastBoard) return
        val first = lastBoard.isEmpty()
        lastBoard = name
        lastShift = ""
        L.i("当前键盘：$name")
        if (first || !speakOn) return
        say(name, 60)
    }

    /** 键位表变化后检查 Shift 状态 */
    fun onKeys(keys: List<Key>) {
        val shift = keys.firstNotNullOfOrNull { k ->
            when (k.mainName()) {
                "小写", "大写", "锁定大写" -> k.mainName()
                else -> null
            }
        } ?: return
        if (shift == lastShift) return
        val first = lastShift.isEmpty()
        lastShift = shift
        if (first || !speakOn) return
        say(shift, 0)
    }

    /** 键盘弹出时（可选）读当前键盘 */
    fun onShown() {
        hideSaidAt = 0L
        if (!speakOn || !Config.b("announceOnShow") || boardName.isEmpty()) return
        say(boardName, 300)
    }

    private var hideSaidAt = 0L

    /** 键盘收起时（可选）提示；立即读，不等。同一次收起几处都会调到，只读一次 */
    fun onHidden() {
        if (!VirtualKeys.enabled || !Config.b("announceHide")) return
        val now = SystemClock.uptimeMillis()
        if (now - hideSaidAt < 1500) return
        hideSaidAt = now
        main.removeCallbacksAndMessages(token)
        Speaker.speak("键盘已收起")
    }

    private fun say(text: String, delay: Long) {
        // 稍等一下，避免被刚按的那个键的朗读打断
        main.removeCallbacksAndMessages(token)
        main.postAtTime({ Speaker.speak(text) }, token, SystemClock.uptimeMillis() + delay)
    }
}
