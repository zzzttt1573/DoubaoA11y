package io.github.doubaoa11y.hook

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.doubaoa11y.ConfigSpec

/**
 * 全屏手势里的“编辑菜单”。
 *
 * 单指上滑 = 上一个选项，单指下滑 = 下一个选项，单指双击 = 执行当前这一项。
 *
 * 选项是一个完整列表（用户可在设置里自由增删排序，出厂默认是“默认/全选文本/选择文本/粘贴/清空文本/收起键盘”）：
 * 1. 基础列表：用户配的那串（Config 里的 editOptions）；
 * 2. 选择态：执行“全选文本”或“选择文本”之后，上下滑只浏览“取消选择”这一项，
 *    剪切、复制、跳行首行尾、收起键盘这些都不出现；执行剪切/复制/删除/取消选择后退出选择态。
 *
 * 切换时只读当前项名字，不报“第几项共几项”。
 */
object EditOptions {

    private val main = Handler(Looper.getMainLooper())

    private var selectedId: Int? = null

    /** 最近一次切换选项的时间：刚切完（2 秒内）双击就是在执行它，不去抢候选 */
    private var navAt = 0L

    /** 动态项（剪切/复制/取消选择）现在有没有展开在列表里 */
    private var dynamicOpen = false

    private var state = EditorMenuState()

    /** 键盘显示中：只有这时候才值得盯着编辑框 */
    @Volatile
    private var keyboardUp = false

    /** 兜底轮询开着没有 */
    private var watching = false

    /** 空着的时候查得勤一点：要尽快从“粘贴/收起键盘”切回完整列表 */
    private const val WATCH_EMPTY_MS = 300L

    /** 有字的时候只需要发现“被清空了”，慢一点省电省跨进程 */
    private const val WATCH_FULL_MS = 1200L

    private val ids: List<Int>
        get() = if (dynamicOpen) {
            ConfigSpec.EDIT_OPTIONS_DYNAMIC.map { it.second }.filter {
                state.selected != false || it == ConfigSpec.ACT_DESELECT
            }
        } else state.available(ConfigSpec.parseEditOptions(Config.s(ConfigSpec.EDIT_OPTIONS_KEY)))

    val size: Int get() = ids.size
    val editorEmpty: Boolean get() = state.empty == true

    /**
     * 重新问一次编辑框有没有内容（关键节点调用：换输入框、键盘显示、上屏、删除、粘贴…）。
     *
     * 真正的判断收敛在 [apply]，这里只负责跨进程问一次。
     */
    fun refresh(why: String) {
        if (!FullscreenMode.wanted || !FullscreenMode.active) return
        apply(EditActions.editorEmpty(), why)
    }

    /**
     * 键盘显示：开始盯编辑框。
     *
     * 首查故意晚一点点（[ServiceHooks] 那边已经延迟过），因为弹键盘的瞬间
     * `getCurrentInputConnection` 可能还没接上，太早问会一律判成空。
     */
    fun onKeyboardShown() {
        keyboardUp = true
        refresh("键盘显示")
        startWatch()
    }

    /** 键盘收起：停掉轮询，不留下一条空转的延迟任务 */
    fun onKeyboardHidden() {
        keyboardUp = false
        stopWatch()
    }

    private fun startWatch() {
        if (watching) return
        watching = true
        main.removeCallbacks(tick)
        main.postDelayed(tick, WATCH_EMPTY_MS)
        L.i("编辑菜单开始盯着编辑框（空=$editorEmpty）")
    }

    private fun stopWatch() {
        if (!watching) return
        watching = false
        main.removeCallbacks(tick)
        L.i("编辑菜单停止盯着编辑框")
    }

    /**
     * 兜底轮询：不管文字是打字、语音、长按粘贴、自动纠错还是别的应用写进来的，
     * 这里都能发现，然后走和 [refresh] 同一套切换逻辑。
     */
    private val tick = object : Runnable {
        override fun run() {
            if (!watching) return
            if (!keyboardUp || !FullscreenMode.wanted || !FullscreenMode.active) {
                stopWatch()
                return
            }
            try {
                apply(EditActions.editorEmpty(), "轮询")
            } catch (t: Throwable) {
                L.hookError("EditOptions 轮询", t)
            }
            if (watching) main.postDelayed(this, if (state.empty != false) WATCH_EMPTY_MS else WATCH_FULL_MS)
        }
    }

    /** 切换列表：变空停在“粘贴”，有字停在“默认”，顺带清掉选择态和“刚切过”的标记 */
    private fun apply(now: EditorMenuState, why: String) {
        val changed = now.empty != state.empty
        state = now
        if (changed) {
            if (now.empty == true) dynamicOpen = false
            selectedId = null
            navAt = 0L
            L.i("编辑菜单刷新（$why），空框=${now.empty}")
        }
        selectedId = currentAction
    }

    fun markTyped() {
        state = EditorMenuState(empty = false, selected = false)
        backToDefault()
    }

    /** 换输入框、键盘收起时回到第一项，并把动态项收起来 */
    fun reset() {
        state = EditorMenuState()
        selectedId = null
        navAt = 0L
        dynamicOpen = false
    }

    /** 是不是在选择态（全选/选择文本之后） */
    val isSelecting: Boolean get() = dynamicOpen

    /** 进入选择态：动态项变成“剪切/复制/删除文本/取消选择”，光标停在第一项（剪切）上 */
    fun enterSelection() {
        state = EditActions.editorEmpty()
        dynamicOpen = true
        selectedId = ids.firstOrNull()
    }

    /** 退出选择态：收起动态项，回到基础列表第一项 */
    fun selectionEnded() {
        dynamicOpen = false
        selectedId = ids.firstOrNull()
    }

    /**
     * 键盘区打了字：轮盘回到“双击跳转行首行尾”（编辑菜单里的“默认”项）。
     *
     * 用户打完字再双击，要的是跳行首/行尾，不是上一次上下滑浏览到的“粘贴”“清空文本”；
     * 全屏下打字时手指一直在键盘区，根本看不到轮盘停在哪，所以打完一按就该回到默认那一项。
     * 如果刚才在选择态里，打字会把选区替换掉，选择态跟着静默退出。
     */
    fun keyTyped() {
        val wasDynamic = dynamicOpen
        dynamicOpen = false
        // 打了字编辑框就有内容了：列表切回完整那套，并停在“双击跳转行首行尾”
        state = EditorMenuState(empty = false, selected = false)
        backToDefault()
        // 刚打过字，不算“刚切过编辑菜单”，候选优先重新生效
        navAt = 0L
        if (wasDynamic) EditActions.leaveSelectionQuiet()
    }

    val currentName: String
        get() {
            val id = currentAction ?: return "没有可用的编辑菜单项"
            // 选择态下只有“取消选择”这一项，用动态项名字；普通状态用动作名
            if (dynamicOpen) {
                return ConfigSpec.dynamicName(id) ?: ConfigSpec.actionName(id)
            }
            return ConfigSpec.actionName(id)
        }

    val currentAction: Int?
        get() = state.current(ids, selectedId)

    /** 刚切换过选项（2 秒内） */
    fun isFresh(): Boolean = navAt > 0 && SystemClock.uptimeMillis() - navAt < 2000

    fun next() = navigate(1)
    fun prev() = navigate(-1)

    private fun navigate(direction: Int): Boolean {
        val list = ids
        if (list.isEmpty()) return false
        Sound.play("double")
        val index = list.indexOf(state.current(list, selectedId))
        selectedId = list[(index + direction + list.size) % list.size]
        navAt = SystemClock.uptimeMillis()
        announce()
        return true
    }

    fun run(): Boolean {
        val action = currentAction ?: return false
        navAt = SystemClock.uptimeMillis()
        L.i("执行编辑菜单：${ConfigSpec.dynamicName(action) ?: ConfigSpec.actionName(action)}")
        val ok = GestureMap.run(action)
        if (!ok) return false
        // 全选文本 / 选择文本：执行成功后进入选择态，动态项变成剪切/复制/删除文本/取消选择
        if (action == ConfigSpec.ACT_SELECT_ALL || action == ConfigSpec.ACT_SELECT_TEXT) {
            enterSelection()
            return true
        }
        // 剪切 / 复制 / 删除文本 / 取消选择：执行完退出选择态，回到基础列表第一项
        if (dynamicOpen && action in setOf(
                ConfigSpec.ACT_CUT, ConfigSpec.ACT_COPY,
                ConfigSpec.ACT_DELETE_LEFT, ConfigSpec.ACT_DESELECT,
            )) {
            selectionEnded()
            return true
        }
        // 其余动作都是“做一次就完”的（粘贴、清空文本、收起键盘、朗读…）：
        // 做完回到“默认”那一项（双击跳行首行尾）。
        // 用户反馈：上下滑选了“粘贴”、双击粘贴完，轮盘还停在“粘贴”上，
        // 想跳行首行尾还得再滑回去，很容易误粘第二次。
        backToDefault()
        return true
    }

    /** 回到“默认”项（双击跳行首行尾）；列表里没有默认项就回到第一项 */
    private fun backToDefault() {
        dynamicOpen = false
        selectedId = state.current(ids, null)
        navAt = 0L
    }

    private fun announce() {
        Speaker.speak(currentName)
    }

    /** 给“查询模块状态”用 */
    fun describe(): String = "当前编辑菜单：$currentName"
}
