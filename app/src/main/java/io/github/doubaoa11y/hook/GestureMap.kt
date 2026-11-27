package io.github.doubaoa11y.hook

import io.github.doubaoa11y.ConfigSpec

/**
 * 全屏手势的映射表：手势槽位 -> 动作，以及动作的执行分发。
 *
 * 槽位和天坦输入法一样：单指、双指各有单击、双击、上下左右滑动，另有八个拖动手势。
 * 方向和手指数量决定槽位，槽位里配的动作由用户自己改。
 */
object GestureMap {

    /** 这个槽位当前绑的动作 */
    fun actionOf(slot: Int): Int = Config.i(ConfigSpec.gestureKey(slot))

    fun slotName(slot: Int): String = ConfigSpec.GESTURE_SLOTS.firstOrNull { it.first == slot }?.second ?: "手势"

    fun actionName(action: Int): String = ConfigSpec.actionName(action)

    const val DIR_NONE = 0
    const val DIR_LEFT = 1
    const val DIR_RIGHT = 2
    const val DIR_UP = 3
    const val DIR_DOWN = 4

    /** 往左上左下这些斜向按主轴算：左右差得多就是左右，上下差得多就是上下 */
    private fun dominant(dx: Float, dy: Float): Boolean = kotlin.math.abs(dx) >= kotlin.math.abs(dy)

    /** 判断位移对应的主方向，不够距离返回 DIR_NONE */
    fun directionOf(dx: Float, dy: Float, minDist: Float): Int {
        if (kotlin.math.abs(dx) < minDist && kotlin.math.abs(dy) < minDist) return DIR_NONE
        return if (dominant(dx, dy)) {
            if (dx > 0) DIR_RIGHT else DIR_LEFT
        } else {
            if (dy > 0) DIR_DOWN else DIR_UP
        }
    }

    /** 单指方向对应的折线/往返槽位 */
    fun compoundSlot(first: Int, second: Int): Int? = when (first) {
        DIR_LEFT -> when (second) {
            DIR_RIGHT -> ConfigSpec.G_LEFT_RIGHT
            DIR_UP -> ConfigSpec.G_LEFT_UP
            DIR_DOWN -> ConfigSpec.G_LEFT_DOWN
            else -> null
        }
        DIR_RIGHT -> when (second) {
            DIR_LEFT -> ConfigSpec.G_RIGHT_LEFT
            DIR_UP -> ConfigSpec.G_RIGHT_UP
            DIR_DOWN -> ConfigSpec.G_RIGHT_DOWN
            else -> null
        }
        DIR_UP -> when (second) {
            DIR_DOWN -> ConfigSpec.G_UP_DOWN
            DIR_LEFT -> ConfigSpec.G_UP_LEFT
            DIR_RIGHT -> ConfigSpec.G_UP_RIGHT
            else -> null
        }
        DIR_DOWN -> when (second) {
            DIR_UP -> ConfigSpec.G_DOWN_UP
            DIR_LEFT -> ConfigSpec.G_DOWN_LEFT
            DIR_RIGHT -> ConfigSpec.G_DOWN_RIGHT
            else -> null
        }
        else -> null
    }

    /** 某个起始方向是否绑定了任意往返/折线手势（未绑定则单指滑动零延迟直接触发） */
    fun hasCompoundStartingWith(first: Int): Boolean {
        val slots = when (first) {
            DIR_LEFT -> intArrayOf(ConfigSpec.G_LEFT_RIGHT, ConfigSpec.G_LEFT_UP, ConfigSpec.G_LEFT_DOWN)
            DIR_RIGHT -> intArrayOf(ConfigSpec.G_RIGHT_LEFT, ConfigSpec.G_RIGHT_UP, ConfigSpec.G_RIGHT_DOWN)
            DIR_UP -> intArrayOf(ConfigSpec.G_UP_DOWN, ConfigSpec.G_UP_LEFT, ConfigSpec.G_UP_RIGHT)
            DIR_DOWN -> intArrayOf(ConfigSpec.G_DOWN_UP, ConfigSpec.G_DOWN_LEFT, ConfigSpec.G_DOWN_RIGHT)
            else -> return false
        }
        return slots.any { actionOf(it) != ConfigSpec.ACT_NONE }
    }

    /** 单指滑动槽位对应的方向 */
    fun singleSlideDir(slot: Int): Int = when (slot) {
        ConfigSpec.G_SINGLE_LEFT -> DIR_LEFT
        ConfigSpec.G_SINGLE_RIGHT -> DIR_RIGHT
        ConfigSpec.G_SINGLE_UP -> DIR_UP
        ConfigSpec.G_SINGLE_DOWN -> DIR_DOWN
        else -> DIR_NONE
    }

    /** 判断两个方向是否相反（用于区分“往返”和“折线”日志） */
    fun isOppositeDir(a: Int, b: Int): Boolean =
        (a == DIR_LEFT && b == DIR_RIGHT) || (a == DIR_RIGHT && b == DIR_LEFT) ||
            (a == DIR_UP && b == DIR_DOWN) || (a == DIR_DOWN && b == DIR_UP)

    /** 单指滑动：返回槽位，位移不够返回 null */
    fun singleSlide(dx: Float, dy: Float, minDist: Float): Int? {
        if (kotlin.math.abs(dx) < minDist && kotlin.math.abs(dy) < minDist) return null
        return if (dominant(dx, dy)) {
            if (dx > 0) ConfigSpec.G_SINGLE_RIGHT else ConfigSpec.G_SINGLE_LEFT
        } else {
            if (dy > 0) ConfigSpec.G_SINGLE_DOWN else ConfigSpec.G_SINGLE_UP
        }
    }

    /** 双指滑动 */
    fun doubleSlide(dx: Float, dy: Float, minDist: Float): Int? {
        if (kotlin.math.abs(dx) < minDist && kotlin.math.abs(dy) < minDist) return null
        return if (dominant(dx, dy)) {
            if (dx > 0) ConfigSpec.G_DOUBLE_RIGHT else ConfigSpec.G_DOUBLE_LEFT
        } else {
            if (dy > 0) ConfigSpec.G_DOUBLE_DOWN else ConfigSpec.G_DOUBLE_UP
        }
    }

    /** 拖动槽位 */
    fun dragSlot(fingers: Int, dx: Float, dy: Float): Int {
        val right = dx > 0
        val down = dy > 0
        return if (fingers >= 2) {
            when {
                dominant(dx, dy) -> if (right) ConfigSpec.G_DRAG2_RIGHT else ConfigSpec.G_DRAG2_LEFT
                down -> ConfigSpec.G_DRAG2_DOWN
                else -> ConfigSpec.G_DRAG2_UP
            }
        } else {
            when {
                dominant(dx, dy) -> if (right) ConfigSpec.G_DRAG_RIGHT else ConfigSpec.G_DRAG_LEFT
                down -> ConfigSpec.G_DRAG_DOWN
                else -> ConfigSpec.G_DRAG_UP
            }
        }
    }

    /** 执行一个动作，返回是否真的做了事情（做不了要震两下提示） */
    fun run(action: Int): Boolean {
        if (action == ConfigSpec.ACT_NONE) return false
        if (action != ConfigSpec.ACT_SMART_ORGANIZE) KeyboardOps.cancelOrganizeWatch()
        // 天坦输入法那套编辑选项轮盘：上下滑切换，双击执行
        if (action == ConfigSpec.ACT_PREV_OPTION) return EditOptions.prev()
        if (action == ConfigSpec.ACT_NEXT_OPTION) return EditOptions.next()
        if (action == ConfigSpec.ACT_RUN_OPTION) return EditOptions.run()
        // 切换剪贴板历史（带序号全文朗读；紧接着单指双击可直接粘贴到编辑框）
        if (action == ConfigSpec.ACT_PREV_CLIPBOARD) return ClipboardNav.prev()
        if (action == ConfigSpec.ACT_NEXT_CLIPBOARD) return ClipboardNav.next()
        // “默认”项：双击跳行首/行尾（在行尾就跳行首，否则跳行尾）
        if (action == ConfigSpec.ACT_DEFAULT) return EditActions.toggleEnd()
        // “暂时关闭全屏模式”：不收起键盘，切回半屏，下次展开自动恢复全屏
        if (action == ConfigSpec.ACT_TOGGLE_FULLSCREEN) return FullscreenMode.suspendTemporarily()
        // “上一个/下一个字符或候选词”和天坦的动作名一个意思：有候选就切候选，没候选才移光标。
        // 选择模式下不能切候选，要留给左右扩选；英文键盘下也不切候选，直接移动光标
        if (!EditActions.isSelecting && !Candidates.isEnglishBoard()) {
            if (action == ConfigSpec.ACT_PREV_CHAR && Candidates.prev()) return true
            if (action == ConfigSpec.ACT_NEXT_CHAR && Candidates.next()) return true
        }
        if (EditActions.handles(action)) {
            val ok = EditActions.run(action)
            // 选择态下直接执行了剪切/复制/删除/取消选择（不是走编辑选项轮盘），也要退出选择态
            if (ok && EditOptions.isSelecting && action in SELECTION_END_ACTIONS) {
                EditOptions.selectionEnded()
            }
            return ok
        }
        if (KeyboardOps.handles(action)) return KeyboardOps.run(action)
        return false
    }

    /** 执行了这些动作就算退出选择态（剪切/复制/删除/取消选择） */
    private val SELECTION_END_ACTIONS: Set<Int> = setOf(
        ConfigSpec.ACT_CUT, ConfigSpec.ACT_COPY, ConfigSpec.ACT_DELETE_LEFT, ConfigSpec.ACT_DESELECT,
    )

    /** 上屏当前候选：先试选中的那个，不行就按空格上屏 */
    fun commitCandidate(): Boolean = Candidates.commit() || KeyboardOps.commit()

    /**
     * 这个动作做下去就退不回来（删了字、粘了东西、发出去了、候选上屏了）。
     * 单击配成这类动作时，要等一个双击窗口再执行，免得用户其实想双击却被先执行了一次；
     * 其余动作（朗读、移动光标、切编辑选项）抬手就执行，不应有延迟。
     */
    fun destructive(action: Int): Boolean = action in DESTRUCTIVE

    private val DESTRUCTIVE: Set<Int> = setOf(
        ConfigSpec.ACT_DELETE_LEFT, ConfigSpec.ACT_DELETE_RIGHT,
        ConfigSpec.ACT_DELETE_PREV_SENTENCE, ConfigSpec.ACT_DELETE_NEXT_SENTENCE,
        ConfigSpec.ACT_CLEAR_TEXT,
        ConfigSpec.ACT_CUT, ConfigSpec.ACT_PASTE, ConfigSpec.ACT_UNDO, ConfigSpec.ACT_ENTER,
        ConfigSpec.ACT_SELECT_ALL, ConfigSpec.ACT_SELECT_TEXT,
        ConfigSpec.ACT_COMMIT, ConfigSpec.ACT_CLEAR_CANDIDATE, ConfigSpec.ACT_SEND, ConfigSpec.ACT_SEND_AND_HIDE,
        ConfigSpec.ACT_SWITCH_SCREEN,
        ConfigSpec.ACT_HIDE, ConfigSpec.ACT_BACK, ConfigSpec.ACT_VOICE, ConfigSpec.ACT_SMART_ORGANIZE,
        ConfigSpec.ACT_TOGGLE_FULLSCREEN, ConfigSpec.ACT_EDIT_PANEL,
        ConfigSpec.ACT_SYMBOL, ConfigSpec.ACT_ENGLISH, ConfigSpec.ACT_CHINESE, ConfigSpec.ACT_NUMBER,
        ConfigSpec.ACT_CLIPBOARD, ConfigSpec.ACT_PHRASES, ConfigSpec.ACT_MENU,
    )

    /** 这个动作做不了时的提示语 */
    fun failHint(action: Int): String = when (action) {
        ConfigSpec.ACT_COMMIT -> "现在没有候选"
        ConfigSpec.ACT_CLEAR_CANDIDATE -> "现在没有候选"
        ConfigSpec.ACT_PREV_CHAR, ConfigSpec.ACT_NEXT_CHAR,
        ConfigSpec.ACT_PREV_LINE, ConfigSpec.ACT_NEXT_LINE,
        ConfigSpec.ACT_PREV_PARA, ConfigSpec.ACT_NEXT_PARA,
        ConfigSpec.ACT_LINE_START, ConfigSpec.ACT_LINE_END,
        ConfigSpec.ACT_DOC_START, ConfigSpec.ACT_DOC_END,
        ConfigSpec.ACT_PREV_SENTENCE, ConfigSpec.ACT_NEXT_SENTENCE,
        ConfigSpec.ACT_DELETE_LEFT, ConfigSpec.ACT_DELETE_RIGHT,
        ConfigSpec.ACT_DELETE_PREV_SENTENCE, ConfigSpec.ACT_DELETE_NEXT_SENTENCE,
        ConfigSpec.ACT_CLEAR_TEXT, ConfigSpec.ACT_UNDO,
        ConfigSpec.ACT_SELECT_TEXT, ConfigSpec.ACT_DESELECT,
        ConfigSpec.ACT_READ_EDITOR, ConfigSpec.ACT_READ_LINE, ConfigSpec.ACT_READ_ALL,
        ConfigSpec.ACT_READ_SELECTION, ConfigSpec.ACT_CHAR_COUNT, ConfigSpec.ACT_HINT,
        ConfigSpec.ACT_SELECT_ALL, ConfigSpec.ACT_COPY, ConfigSpec.ACT_CUT, ConfigSpec.ACT_PASTE,
        ConfigSpec.ACT_DEFAULT,
        -> "当前编辑框不支持"

        ConfigSpec.ACT_SEND -> "这个输入框没有发送"
        ConfigSpec.ACT_SEND_AND_HIDE -> "这个输入框没有发送"
        ConfigSpec.ACT_SWITCH_SCREEN -> "切换不了输入方式"
        ConfigSpec.ACT_HIDE -> "键盘收不起来"
        ConfigSpec.ACT_SMART_ORGANIZE -> "当前豆包版本无法直接执行智能整理"
        ConfigSpec.ACT_PREV_CLIPBOARD, ConfigSpec.ACT_NEXT_CLIPBOARD -> "剪贴板为空"
        ConfigSpec.ACT_PREV_OPTION, ConfigSpec.ACT_NEXT_OPTION, ConfigSpec.ACT_RUN_OPTION -> "编辑选项没做成"

        else -> "这个动作没做成"
    }
}
