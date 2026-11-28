package io.github.doubaoa11y

/**
 * 模块设置项的定义。设置界面（模块自己的进程）和 hook 代码（豆包进程）共用这一份。
 * 设置界面通过广播把值发给豆包进程，豆包进程存进自己的 SharedPreferences。
 *
 * 注意：这个文件只能依赖 Android 平台类，不能引用 hook 包里的东西——
 * 设置页进程没有 Xposed 环境，引用了会在打开设置页时崩溃。
 */
object ConfigSpec {
    /** 模块自己的版本（build.gradle.kts 从这里读） */
    const val MODULE_VERSION_CODE = 53
    const val MODULE_VERSION_NAME = "3.0.1"

    const val TARGET_PACKAGE = "com.bytedance.android.doubaoime"
    /** 已适配的豆包版本：版本号 -> 版本名 */
    val SUPPORTED_VERSIONS = mapOf(
        100504010L to "1.5.4",
        100505010L to "1.5.5",
        100506012L to "1.5.6",
        100507010L to "1.5.7",
    )
    val SUPPORTED_NAMES: String get() = SUPPORTED_VERSIONS.values.joinToString("、")

    /**
     * 设置页给豆包进程读配置的 ContentProvider authority。
     * 此 authority 属于主线模块，与包名保持一致，
     * 调用权限由 SettingsProvider 验证真实 UID。
     */
    const val SETTINGS_AUTHORITY = "io.github.doubaoa11y.settings"

    /** 设置广播。extras：各开关的 boolean 值；可选 "cmd" = status / dump / reset。 */
    const val ACTION_CONFIG = "io.github.doubaoa11y.CONFIG"
    const val EXTRA_CMD = "cmd"
    const val CMD_STATUS = "status"
    const val CMD_DUMP = "dump"
    const val CMD_RESET = "reset"
    const val CMD_EDIT_ADD = "editAdd"
    const val CMD_EDIT_REMOVE = "editRemove"
    /** 问题反馈：豆包把日志复制到剪贴板、存到“下载”文件夹、清空 */
    const val CMD_LOG_COPY = "logCopy"
    const val CMD_LOG_SAVE = "logSave"
    const val CMD_LOG_CLEAR = "logClear"
    /** 豆包原生设置离线备份/恢复 */
    const val CMD_NATIVE_SCAN = "nativeScan"
    const val CMD_NATIVE_BACKUP = "nativeBackup"
    const val CMD_NATIVE_RESTORE = "nativeRestore"
    const val CMD_NATIVE_RESTORE_STATUS = "nativeRestoreStatus"
    const val EXTRA_NATIVE_TOKEN = "nativeToken"
    const val EXTRA_NATIVE_URI = "nativeUri"
    const val EXTRA_NATIVE_INCLUDE_CLIPBOARD = "nativeIncludeClipboard"
    /** 自定义解释词：每行“字<TAB>词”，读成“字，词的字” */
    const val EXTRA_CUSTOM_WORDS = "customWords"


    /** 豆包进程里保存设置用的 SharedPreferences 文件名（hook 侧存一份） */
    const val PREFS_NAME = "doubao_a11y_module"

    /**
     * 模块设置页自己存的 SharedPreferences 文件名。
     *
     * 设置页、SettingsProvider、hook 侧三处必须用同一个名字：
     * 之前 Provider 用的是 PREFS_NAME，设置页用的是 "settings"，Provider 读到的永远是空的，
     * 于是每次键盘弹出“同步配置”都把全屏、语音库这些覆盖回默认值——
     * 表现就是全屏再也不触发、第三方语音库怎么选都不生效。
     */
    const val SETTINGS_PREFS = "settings"

    /** 设置页里的分组；FIXED 不显示，永远用默认值 */
    enum class Group { MAIN, LOG, INPUT, HOLD, FAST, CANDIDATE, STATE, VIBRATE, VOICE, GESTURE, INTERNAL, DEBUG, FIXED }

    class Item(val key: String, val label: String, val default: Boolean, val group: Group, val hint: String = "")

    /** 模块在豆包定制工具栏中注册的自定义开关 key（统一供 Config / SettingsProvider / ServiceHooks 同步） */
    val TOOLBAR_TOGGLE_KEYS: List<String> = listOf(
        "screenToolbar",
        "editToolbar",
        "clearToolbar",
        "undoToolbar",
        "deleteRightToolbar",
        "readEditorToolbar",
        "pasteToolbar",
    )

    val items = listOf(
        Item("upperOperation", "上半屏手势", false, Group.GESTURE),
        Item("screenToolbar", "工具栏显示上半屏手势", false, Group.INTERNAL),
        Item("editToolbar", "工具栏显示编辑模式", false, Group.INTERNAL),
        Item("clearToolbar", "工具栏显示清空编辑框", false, Group.INTERNAL),
        Item("undoToolbar", "工具栏显示撤销", false, Group.INTERNAL),
        Item("deleteRightToolbar", "工具栏显示向后删除", false, Group.INTERNAL),
        Item("readEditorToolbar", "工具栏显示朗读编辑框", false, Group.INTERNAL),
        Item("pasteToolbar", "工具栏显示粘贴", false, Group.INTERNAL),
        Item("soundOn", "启用音效", false, Group.STATE),
        // 首页的总开关：关掉后模块什么都不做，豆包和没装模块一样
        Item("moduleOn", "模块总开关", true, Group.MAIN,
            "提示：这是整个模块的总开关，关闭后豆包输入法恢复官方原版方式，请谨慎关闭！"),
        // 问题反馈
        Item("runLog", "记录日志", false, Group.LOG,
            "提示：打开 1 小时后自动关闭"),
        Item("logText", "日志包含输入的文字", false, Group.LOG,
            "提示：默认把打的字和语音识别的文字换成星号。排查语音、候选的问题时，作者可能会请你打开"),
        // 延迟调整
        Item("symbolScroll", "符号区域停留翻页", true, Group.HOLD),
        // 快速输入（触摸直接到键盘时）
        Item("tatansFast", "快速输入", true, Group.FAST,
            "提示：键盘响应更快，但是读屏手势无法在键盘区域生效"),
        Item("nativeSpace", "空格键改为豆包原生操作", false, Group.FAST,
            "提示：豆包原生操作为：短按语音输入，空格上左右滑动可移动光标"),
        Item("nativeDelete", "删除键改为豆包原生操作", false, Group.FAST,
            "提示：豆包原生操作为：短按即可快速删除，按住左滑可以清空候选字"),
        Item("nativeEnter", "回车键改为豆包原生操作", false, Group.FIXED,
            "提示：仅在快速输入或触摸浏览关闭、触摸直接到键盘时生效。打开后回车、发送键交给豆包处理，" +
                "短按、长按和滑动行为以豆包设置为准；模块的回车键停留时间不再生效"),
        // 候选词解释
        Item("charExplain", "字词解释", true, Group.CANDIDATE),
        Item("candidateNav", "按键切换候选", true, Group.CANDIDATE,
            "提示：有候选字时，底行空格左侧按键变为左移、右侧按键变为右移，空格上屏"),
        Item("candidateNavEnter", "回车键并入右移候选", false, Group.CANDIDATE,
            "提示：开启后有候选字时，回车/发送键也变为右移；关闭时保留回车/发送原功能"),
        // 键盘状态提示
        Item("announceMode", "切换键盘类型、大小写状态时提示", true, Group.STATE),
        Item("announceOnShow", "键盘弹出时朗读键盘类型", true, Group.STATE),
        Item("announceHide", "键盘关闭时提示", true, Group.STATE),
        Item("voiceZoneHint", "语音输入操作提示", true, Group.STATE,
            "提示：长按空格时左上滑动取消输入，右上滑直接发送。"),
        Item("suppressRepeatKeySpeak", "重复按键不朗读键名", true, Group.STATE,
            "提示：连续按下删除、左移、右移等按键时只读第一次，避免打断候选词或删除朗读"),
        // 震动
        Item("touchVibrate", "触摸震动", true, Group.VIBRATE),
        // 播报方式：和原来的“将所有播报内容交给屏幕阅读器”“开启语音”合并成一个开关
        Item("speakByReader", "使用屏幕阅读器播报", true, Group.VOICE,
            "提示：关闭后改用下面选择的语音库播报，走独立TTS"),
        Item("ttsInputEcho", "独立语音库朗读上屏文字", false, Group.VOICE),
        Item("ttsDeleteEcho", "独立语音库朗读删除结果", false, Group.VOICE),
        Item("ttsVoiceEcho", "语音输入后朗读上屏文字", false, Group.VOICE),
        Item("ttsPunctuationNames", "独立语音库逐项朗读标点名称", false, Group.VOICE,
            "提示：把整段提示中的标点转成中文名称，例如逗号、省略号；读屏自身的标点设置不受影响"),
        Item("ttsA11yStream", "语音库和音效使用无障碍音量通道", false, Group.VOICE, "提示：打开后语音走无障碍音量，关闭走媒体音量"),
        // 全屏手势（这些只影响全屏输入，半屏输入、标准输入一条都不读）
        Item("fsHaptic", "执行手势时反馈", true, Group.FIXED,
            "提示：每做一个手势震一下，动作做不了时震两下并说明原因"),
        Item("fsDrag", "启用拖动手势", false, Group.GESTURE,
            "提示：按住不放往一个方向拖动，按设定的间隔反复执行这个方向的动作，适合连续移动光标、连续删字"),
        Item("fsSpeakAction", "执行手势后朗读结果", true, Group.FIXED,
            "提示：关掉后手势照做，只是不读“已删除”“行尾”这类结果提示"),
        Item("fsSpeakChar", "移动光标时朗读字词", true, Group.FIXED,
            "提示：开启后移动光标会朗读字词，如遇到与读屏重复朗读时请关闭"),
        Item("fsCursorExplainWord", "移动光标时解释字词", true, Group.GESTURE),
        Item("fsCursorExplainLetter", "移动光标时解释英文字母", false, Group.GESTURE),
        Item("fsCandidateFirst", "有候选时优先操作候选", true, Group.FIXED,
            "提示：正在打拼音、候选栏里有候选时，单击或双击上屏候选，左右滑动切换候选；关掉后按下面每个手势配的动作执行"),
        // 调试
        Item("logDraw", "记录文字绘制", false, Group.DEBUG),
        Item("logRect", "记录按键背景", false, Group.DEBUG),
        Item("logOtherDraw", "记录图片和其他绘制", false, Group.DEBUG),
        Item("logJni", "记录键盘回调", false, Group.DEBUG),
        Item("logTouch", "记录触摸和悬停", false, Group.DEBUG),
        Item("dumpAfterDraw", "每次重绘后输出键位表", false, Group.DEBUG),
        Item("xposedLog", "同时写入 LSPosed 日志", false, Group.DEBUG),
        Item("xposedVerbose", "绘制细节也写入 LSPosed 日志（量很大）", false, Group.DEBUG),
        Item("traceAllJni", "跟踪全部键盘回调（重启输入法后生效，量大）", false, Group.DEBUG),
        Item("forceEnable", "强制启用（不检查读屏和触摸浏览是否开启）", false, Group.DEBUG),
        Item("debugSpeak", "调试朗读（只在关掉接管键盘时用）", false, Group.DEBUG),
        // 不在设置页显示，固定开
        Item("virtualKeys", "接管键盘", true, Group.FIXED),
        Item("keepFocus", "讯飞式操作", true, Group.FIXED),
        Item("directTouch", "触摸浏览关闭时仍用读屏操作", true, Group.FIXED),
        Item("liftToType", "删除键、空格键停留功能", true, Group.FIXED),
        Item("speakCandidate", "朗读第一个候选", true, Group.FIXED),
        Item("editPanel", "编辑面板", true, Group.FIXED),
        Item("voiceBuffer", "语音说完再上屏", true, Group.FIXED),
        Item("labelViews", "按钮中文名", true, Group.FIXED),
        Item("queryNative", "查询键盘类型", true, Group.FIXED),
        Item("nineDigits", "九键拼音按键读数字（如 abc 键读 2）", true, Group.FIXED),
        // 其他功能由各自独立分支处理
    )

    val defaults: Map<String, Boolean> = items.associate { it.key to it.default }
    val fixed: Set<String> = items.filter { it.group == Group.FIXED }.map { it.key }.toSet()

    /** 多选一的设置项（值是整数），设置页里点一下弹出选择框 */
    class IntItem(val key: String, val label: String, val default: Int, val options: List<Pair<Int, String>>, val group: Group)

    /** 文本设置项（语音库、发音人这类，值是字符串） */
    class StrItem(val key: String, val label: String, val default: String, val group: Group)


    /** 0.5 到 5 秒，0.1 秒一挡；值以毫秒保存 */
    private val holdOptions = (5..50).map { it * 100 to "${it / 10}.${it % 10} 秒" }

    /**
     * 输入方式：
     * 0 半屏输入（原来的“抬手输入”，读屏焦点留在编辑框，手指在键盘上摸着读、抬手输入）
     * 1 标准输入（按键交给读屏焦点，摸到读、双击输入）
     * 2 全屏输入（保留底部键盘，上半屏接收手势；不改变全局触摸浏览开关）
     */
    const val TYPE_LIFT = 0
    const val TYPE_DOUBLE_TAP = 1
    const val TYPE_FULLSCREEN = 2

    // ---------------- 全屏手势 ----------------
    //
    // 单指、双指各有单击/双击/上下左右滑动，外加单指往返/折线手势（12 个）和八个拖动手势。
    // 注意：0..19 的编号与键名保持不变，兼容旧版本已保存的手势设置。

    const val G_SINGLE_TAP = 0
    const val G_SINGLE_DOUBLE_TAP = 1
    const val G_SINGLE_LEFT = 2
    const val G_SINGLE_RIGHT = 3
    const val G_SINGLE_UP = 4
    const val G_SINGLE_DOWN = 5
    const val G_DOUBLE_TAP = 6
    const val G_DOUBLE_LEFT = 7
    const val G_DOUBLE_RIGHT = 8
    const val G_DOUBLE_UP = 9
    const val G_DOUBLE_DOWN = 10
    const val G_DRAG_LEFT = 11
    const val G_DRAG_RIGHT = 12
    const val G_DRAG_UP = 13
    const val G_DRAG_DOWN = 14
    const val G_DRAG2_LEFT = 15
    const val G_DRAG2_RIGHT = 16
    const val G_DRAG2_UP = 17
    const val G_DRAG2_DOWN = 18
    const val G_DOUBLE_SINGLE_TAP = 19

    // 单指往返手势（4 个）
    const val G_LEFT_RIGHT = 20
    const val G_RIGHT_LEFT = 21
    const val G_UP_DOWN = 22
    const val G_DOWN_UP = 23

    // 单指直角折线手势（8 个）
    const val G_UP_LEFT = 24
    const val G_UP_RIGHT = 25
    const val G_DOWN_LEFT = 26
    const val G_DOWN_RIGHT = 27
    const val G_LEFT_UP = 28
    const val G_LEFT_DOWN = 29
    const val G_RIGHT_UP = 30
    const val G_RIGHT_DOWN = 31

    /** 手势槽的顺序（设置页按这个顺序列） */
    val GESTURE_SLOTS: List<Pair<Int, String>> = listOf(
        G_SINGLE_TAP to "单指单击",
        G_SINGLE_DOUBLE_TAP to "单指双击",
        G_SINGLE_LEFT to "单指左滑",
        G_SINGLE_RIGHT to "单指右滑",
        G_SINGLE_UP to "单指上滑",
        G_SINGLE_DOWN to "单指下滑",
        G_LEFT_RIGHT to "左滑再右滑",
        G_RIGHT_LEFT to "右滑再左滑",
        G_UP_DOWN to "上滑再下滑",
        G_DOWN_UP to "下滑再上滑",
        G_UP_LEFT to "上滑再左滑",
        G_UP_RIGHT to "上滑再右滑",
        G_DOWN_LEFT to "下滑再左滑",
        G_DOWN_RIGHT to "下滑再右滑",
        G_LEFT_UP to "左滑再上滑",
        G_LEFT_DOWN to "左滑再下滑",
        G_RIGHT_UP to "右滑再上滑",
        G_RIGHT_DOWN to "右滑再下滑",
        G_DOUBLE_SINGLE_TAP to "双指单击",
        G_DOUBLE_TAP to "双指双击",
        G_DOUBLE_LEFT to "双指左滑",
        G_DOUBLE_RIGHT to "双指右滑",
        G_DOUBLE_UP to "双指上滑",
        G_DOUBLE_DOWN to "双指下滑",
        G_DRAG_LEFT to "单指向左拖动",
        G_DRAG_RIGHT to "单指向右拖动",
        G_DRAG_UP to "单指向上拖动",
        G_DRAG_DOWN to "单指向下拖动",
        G_DRAG2_LEFT to "双指向左拖动",
        G_DRAG2_RIGHT to "双指向右拖动",
        G_DRAG2_UP to "双指向上拖动",
        G_DRAG2_DOWN to "双指向下拖动",
    )

    /** 只有“启用拖动手势”打开时才生效的槽位 */
    val DRAG_SLOTS: Set<Int> = setOf(
        G_DRAG_LEFT, G_DRAG_RIGHT, G_DRAG_UP, G_DRAG_DOWN,
        G_DRAG2_LEFT, G_DRAG2_RIGHT, G_DRAG2_UP, G_DRAG2_DOWN,
    )

    private val gestureKeys = listOf(
        "fsSingleTap", "fsSingleDouble", "fsSingleLeft", "fsSingleRight", "fsSingleUp", "fsSingleDown",
        "fsDoubleTap", "fsDoubleLeft", "fsDoubleRight", "fsDoubleUp", "fsDoubleDown",
        "fsDragLeft", "fsDragRight", "fsDragUp", "fsDragDown",
        "fsDrag2Left", "fsDrag2Right", "fsDrag2Up", "fsDrag2Down",
        "fsDoubleSingleTap",
        "fsLeftRight", "fsRightLeft", "fsUpDown", "fsDownUp",
        "fsUpLeft", "fsUpRight", "fsDownLeft", "fsDownRight",
        "fsLeftUp", "fsLeftDown", "fsRightUp", "fsRightDown",
    )

    fun gestureKey(slot: Int): String = gestureKeys.getOrElse(slot) { "fsSingleTap" }

    // 全屏手势能做的事：编辑框里的动作交给 EditActions，键盘上的动作交给 KeyboardOps
    const val ACT_NONE = 0
    const val ACT_PREV_CHAR = 1
    const val ACT_NEXT_CHAR = 2
    const val ACT_PREV_LINE = 3
    const val ACT_NEXT_LINE = 4
    const val ACT_LINE_START = 5
    const val ACT_LINE_END = 6
    const val ACT_PREV_SENTENCE = 7
    const val ACT_NEXT_SENTENCE = 8
    const val ACT_DELETE_LEFT = 9
    const val ACT_DELETE_RIGHT = 10
    const val ACT_ENTER = 11
    const val ACT_SEND = 12
    const val ACT_SELECT_ALL = 13
    const val ACT_COPY = 14
    const val ACT_CUT = 15
    const val ACT_PASTE = 16
    const val ACT_READ_EDITOR = 17
    const val ACT_READ_LINE = 18
    const val ACT_READ_ALL = 19
    const val ACT_COMMIT = 20
    const val ACT_CLEAR_CANDIDATE = 21
    const val ACT_CLIPBOARD = 22
    const val ACT_PHRASES = 23
    const val ACT_EDIT_PANEL = 24
    const val ACT_VOICE = 25
    const val ACT_MENU = 26
    const val ACT_SYMBOL = 27
    const val ACT_ENGLISH = 28
    const val ACT_CHINESE = 29
    const val ACT_NUMBER = 30
    const val ACT_HIDE = 31
    const val ACT_UNDO = 32
    const val ACT_CLEAR_TEXT = 33
    const val ACT_CHAR_COUNT = 34
    const val ACT_HINT = 35
    const val ACT_PREV_PARA = 36
    const val ACT_NEXT_PARA = 37
    const val ACT_SELECT_TEXT = 38
    const val ACT_DESELECT = 39
    const val ACT_BACK = 40
    const val ACT_READ_SELECTION = 41
    const val ACT_DOC_START = 42
    const val ACT_DOC_END = 43

    // 编辑菜单：上滑上一个选项、下滑下一个选项、双击执行当前选项
    const val ACT_PREV_OPTION = 44
    const val ACT_NEXT_OPTION = 45
    const val ACT_RUN_OPTION = 46
    // 双击跳行首/行尾（编辑菜单里的“默认”项）
    const val ACT_DEFAULT = 47
    // 暂时关闭全屏模式（不收起键盘，切到半屏；下次输入法展开自动恢复全屏）
    const val ACT_TOGGLE_FULLSCREEN = 48
    // 发送并收起键盘（原来那个“执行发送后收起键盘”开关改成动作）
    const val ACT_SEND_AND_HIDE = 49
    // 工具栏快捷项“切换全屏和半屏”
    const val ACT_SWITCH_SCREEN = 50
    // 菜单内的“智能整理”
    const val ACT_SMART_ORGANIZE = 51
    // 切换剪贴板历史（带序号朗读，紧接着双击可直接粘贴）
    const val ACT_PREV_CLIPBOARD = 52
    const val ACT_NEXT_CLIPBOARD = 53
    // 按句分隔符号删除上一句/下一句
    const val ACT_DELETE_PREV_SENTENCE = 54
    const val ACT_DELETE_NEXT_SENTENCE = 55

    /**
     * 动作表：id 到名字。设置页和豆包进程都用这一份。
     *
     * 只放能在设置里选、能出现在编辑菜单轮盘里的动作。
     * 下面这些动作后端还实现着、但不在列表里（按需求拿掉）：
     * 上屏候选词、清空候选栏、回车换行、返回、输入提示、打开菜单、剪贴板、常用语、编辑面板、
     * 符号键盘、英文键盘、中文键盘、数字键盘；剪切/复制/取消选择是“动态项”，只在全选后出现。
     */
    val ACTIONS: List<Pair<Int, String>> = listOf(
        ACT_NONE to "未指定",
        ACT_PREV_CHAR to "上一个字符或候选词",
        ACT_NEXT_CHAR to "下一个字符或候选词",
        ACT_PREV_LINE to "上一行",
        ACT_NEXT_LINE to "下一行",
        ACT_PREV_SENTENCE to "上一句",
        ACT_NEXT_SENTENCE to "下一句",
        ACT_PREV_PARA to "上一段",
        ACT_NEXT_PARA to "下一段",
        ACT_LINE_START to "光标跳到行首",
        ACT_LINE_END to "光标跳到行尾",
        ACT_DOC_START to "光标跳到开头",
        ACT_DOC_END to "光标跳到结尾",
        ACT_SELECT_TEXT to "选择文本",
        ACT_SELECT_ALL to "全选",
        ACT_DELETE_LEFT to "删除",
        ACT_DELETE_RIGHT to "向后删除",
        ACT_DELETE_PREV_SENTENCE to "删除上一句",
        ACT_DELETE_NEXT_SENTENCE to "删除下一句",
        ACT_CLEAR_TEXT to "清空文本",
        ACT_UNDO to "撤销",
        ACT_CUT to "剪切",
        ACT_PASTE to "粘贴",
        ACT_PREV_CLIPBOARD to "上一个剪贴板（双击上屏）",
        ACT_NEXT_CLIPBOARD to "下一个剪贴板（双击上屏）",
        ACT_SEND to "执行发送",
        ACT_SEND_AND_HIDE to "发送并收起键盘",
        ACT_HIDE to "收起键盘",
        ACT_TOGGLE_FULLSCREEN to "临时关闭上半屏手势",
        ACT_SWITCH_SCREEN to "关闭上半屏手势",
        ACT_READ_EDITOR to "朗读编辑框文字",
        ACT_READ_LINE to "朗读当前行",
        ACT_READ_SELECTION to "朗读选中文字",
        ACT_CHAR_COUNT to "字符统计",
        ACT_VOICE to "语音输入",
        ACT_SMART_ORGANIZE to "智能整理",
        ACT_PREV_OPTION to "上一个编辑菜单",
        ACT_NEXT_OPTION to "下一个编辑菜单",
        ACT_RUN_OPTION to "执行当前编辑菜单",
        ACT_DEFAULT to "跳到开头结尾",
    )

    fun actionName(id: Int): String = ACTIONS.firstOrNull { it.first == id }?.second ?: "未指定"

    /**
     * 编辑菜单的出厂默认（只是初始值，用户可以在设置里自由增删排序）。
     *
     * - 默认（双击跳行首行尾）排第一，刚弹出键盘停在它上面，双击就跳行首/行尾；
     * - 全选文本、选择文本是“父项”：执行之后才冒出剪切、复制、取消选择；
     * - 粘贴、收起键盘；
     * - 其余动作不默认出现，用户在“自定义编辑菜单”里自己加。
     */
    val EDIT_OPTIONS: List<Pair<String, Int>> = listOf(
        "默认" to ACT_DEFAULT,
        "全选文本" to ACT_SELECT_ALL,
        "选择文本" to ACT_SELECT_TEXT,
        "粘贴" to ACT_PASTE,
        "清空文本" to ACT_CLEAR_TEXT,
        "收起键盘" to ACT_HIDE,
    )

    /**
     * 动态项：执行了“全选文本”或“选择文本”之后才插入到列表里的动作
     * （剪切、复制、删除文本、取消选择）。执行完剪切/复制/删除/取消选择就又收起来。
     * 这四个不进设置页列表，也不进“添加一项”的可选项。
     *
     * “删除文本”复用“删除”（ACT_DELETE_LEFT）：选择模式下 deleteLeft 删的就是选中的那段。
     * 所以它不参与 dynamicIds 过滤（“删除”本身是合法的基础动作，用户可以手动加到轮盘里），
     * 但作为动态项出现时名字叫“删除文本”。
     */
    val EDIT_OPTIONS_DYNAMIC: List<Pair<String, Int>> = listOf(
        "剪切" to ACT_CUT,
        "复制" to ACT_COPY,
        "删除文本" to ACT_DELETE_LEFT,
        "取消选择" to ACT_DESELECT,
    )

    /** 只作为动态项、绝不能手动加到基础列表的动作（复制/取消选择）。删除文本与剪切不在此列 */
    private val DYNAMIC_ONLY_IDS: Set<Int> = setOf(ACT_COPY, ACT_DESELECT)

    /** 动态项的名字（剪切/复制/取消选择这些不在 ACTIONS 里，单独查） */
    fun dynamicName(id: Int): String? = EDIT_OPTIONS_DYNAMIC.firstOrNull { it.second == id }?.first

    /** 编辑菜单列表存在这个键里：逗号分开的动作编号，空 = 用出厂默认 */
    const val EDIT_OPTIONS_KEY = "editOptions"

    /** 默认的编辑菜单（只有动作编号） */
    val DEFAULT_EDIT_OPTION_IDS: List<Int> = EDIT_OPTIONS.map { it.second }

    /**
     * 读一份完整的编辑菜单列表：空或写坏了一律回出厂默认。
     * 动态项里“只能动态出现”的（剪切/复制/取消选择）不能手动加，解析时过滤掉；
     * “删除文本”复用“删除”动作，是合法基础项，不过滤。
     */
    fun parseEditOptions(s: String?): List<Int> {
        if (s == "[]") return emptyList()
        if (s.isNullOrBlank()) return DEFAULT_EDIT_OPTION_IDS
        val known = ACTIONS.map { it.first }.toSet()
        val out = s.split(',').mapNotNull { it.trim().toIntOrNull() }
            .filter { it in known && it != ACT_NONE && it !in DYNAMIC_ONLY_IDS }
            .distinct()
        return if (out.isEmpty()) DEFAULT_EDIT_OPTION_IDS else out
    }

    fun encodeEditOptions(ids: List<Int>): String = if (ids.isEmpty()) "[]" else ids.joinToString(",")

    /** “添加一项”能选的动作：去掉“未指定”、轮盘自身动作和只能动态出现的（剪切/复制/取消选择） */
    val EDIT_OPTION_CHOICES: List<Pair<Int, String>> = ACTIONS.filter {
        it.first != ACT_NONE && it.first != ACT_PREV_OPTION &&
            it.first != ACT_NEXT_OPTION && it.first != ACT_RUN_OPTION &&
            it.first !in DYNAMIC_ONLY_IDS
    }

    /**
     * 出厂默认手势：
     *
     * - 单指单击 = 朗读编辑框文字
     * - 单指双击 = 执行当前编辑菜单（默认停在“默认”项，双击就跳行首/行尾）
     * - 单指上滑 = 上一个编辑菜单、下滑 = 下一个编辑菜单
     * - 单指左滑 / 右滑 = 上一个 / 下一个字符或候选词
     * - 双指双击 = 语音输入
     * - 双指上下左右滑、八个拖动 = 未指定（不塞“上一句/下一句”这类提示动作）
     */
    private val gestureDefaults = mapOf(
        G_SINGLE_TAP to ACT_READ_EDITOR,
        G_SINGLE_DOUBLE_TAP to ACT_RUN_OPTION,
        G_SINGLE_UP to ACT_PREV_OPTION,
        G_SINGLE_DOWN to ACT_NEXT_OPTION,
        G_SINGLE_LEFT to ACT_PREV_CHAR,
        G_SINGLE_RIGHT to ACT_NEXT_CHAR,
        G_DOUBLE_SINGLE_TAP to ACT_NONE,
        G_DOUBLE_TAP to ACT_VOICE,
        G_DOUBLE_LEFT to ACT_NONE,
        G_DOUBLE_RIGHT to ACT_NONE,
        G_DOUBLE_UP to ACT_NONE,
        G_DOUBLE_DOWN to ACT_NONE,
        G_DRAG_LEFT to ACT_NONE,
        G_DRAG_RIGHT to ACT_NONE,
        G_DRAG_UP to ACT_NONE,
        G_DRAG_DOWN to ACT_NONE,
        G_DRAG2_LEFT to ACT_NONE,
        G_DRAG2_RIGHT to ACT_NONE,
        G_DRAG2_UP to ACT_NONE,
        G_DRAG2_DOWN to ACT_NONE,
    )

    /** 默认映射改版号：3 = 编辑菜单收敛为“默认+全选/选择/粘贴/收起”，双击跳行首行尾，去掉上一句下一句 */
    const val GESTURE_DEFAULTS_VERSION = 3
    const val GESTURE_DEFAULTS_KEY = "gestureDefaultsVersion"

    /**
     * 编辑菜单改版号：
     * - 2 = 改成“存完整列表”（不再区分固定项和额外项，用户可自由增删排序）；
     * - 3 = 2.0.0 清掉老默认列表里那一串“上一句/下一句/上一段/下一段/光标跳到行首…”。
     *   用户反馈这些选项上下滑时会一直念方向名，属于多余提示，作用也和左右滑、跳过重复，
     *   所以整份列表回到新的 6 项默认（默认 / 全选文本 / 选择文本 / 粘贴 / 清空文本 / 收起键盘）。
     */
    const val EDIT_OPTIONS_VERSION = 3
    const val EDIT_OPTIONS_VERSION_KEY = "editOptionsVersion"

    /** 把某份设置里的手势映射刷成当前出厂默认；设置页和豆包进程各刷一次 */
    fun migrateGestureDefaults(prefs: android.content.SharedPreferences) {
        // 首次引入仅补缺省；已有自定义映射不可因默认值改版而被覆盖。
        val e = prefs.edit()
        for ((slot, _) in GESTURE_SLOTS) if (!prefs.contains(gestureKey(slot))) e.putInt(gestureKey(slot), gestureDefault(slot))
        e.putInt(GESTURE_DEFAULTS_KEY, GESTURE_DEFAULTS_VERSION).apply()
    }

    /**
     * 编辑菜单从“只存额外勾选项”（1.1.13）改成“存完整列表”后，旧串里只有用户额外勾的几项，
     * 直接当完整列表读会丢掉出厂默认项。这里把旧串清掉，回到出厂默认列表，用户再自己增删排序。
     */
    fun migrateEditOptions(prefs: android.content.SharedPreferences) {
        if (!prefs.contains(EDIT_OPTIONS_KEY)) prefs.edit().putString(EDIT_OPTIONS_KEY, encodeEditOptions(DEFAULT_EDIT_OPTION_IDS)).apply()
    }

    fun sentenceList(value: String?): List<String> {
        if (value.isNullOrBlank()) return ".,;?!。，；？！\n".map { it.toString() }
        if (value.startsWith("list:")) return value.removePrefix("list:").map { it.toString() }.distinct()
        return value.split(',', '，', ' ', '\n').filter { it.isNotBlank() }.distinct()
    }

    fun migrateInput(prefs: android.content.SharedPreferences) {
        val editor = prefs.edit()
        if (prefs.getInt("typeMode", TYPE_LIFT) == TYPE_FULLSCREEN) {
            editor.putInt("typeMode", TYPE_LIFT).putBoolean("upperOperation", true)
            if (!prefs.contains("screenToolbar")) editor.putBoolean("screenToolbar", true)
        }
        if (prefs.getInt("fsDragMs", 300) == 150) editor.putInt("fsDragMs", 200)
        editor.apply()
    }

    fun gestureDefault(slot: Int): Int = gestureDefaults[slot] ?: ACT_NONE

/** 语音库扫描、发音人之后：语速、语调、音量都是 50 到 200，100 是语音库默认 */
    private val percentOptions = (5..20).map { it * 10 to if (it == 10) "语音库默认" else "${it / 10}.${it % 10} 倍" }
    private val volumeOptions = (0..20).map { it * 5 to "${it * 5}%" }

    private val soundChoices = listOf(0 to "按键音", 1 to "边界或失败音", 2 to "手势音", 3 to "双击音", 4 to "翻页音", 5 to "语音开始音")
    val intItems = listOf(
        IntItem("sfxKey", "按键音效", 0, soundChoices, Group.STATE),
        IntItem("sfxFail", "错误音效", 1, soundChoices, Group.STATE),
        IntItem("sfxGesture", "手势音效", 2, soundChoices, Group.STATE),
        IntItem("sfxDouble", "双击音效", 3, soundChoices, Group.STATE),
        IntItem("sfxScroll", "键盘切换音效", 4, soundChoices, Group.STATE),
        IntItem("sfxVoice", "语音输入音效", 5, soundChoices, Group.STATE),
        IntItem("soundVolume", "音效音量", 50, (0..10).map { it * 10 to "${it * 10}%" }, Group.STATE),
        IntItem("typeMode", "输入方式", TYPE_LIFT, listOf(
            TYPE_LIFT to "抬手输入", TYPE_DOUBLE_TAP to "标准输入",
        ), Group.INPUT),
        IntItem("deleteHoldMs", "删除键停留时间", 1500, holdOptions, Group.HOLD),
        IntItem("spaceHoldMs", "空格键停留时间", 1000, holdOptions, Group.HOLD),
        IntItem("enterHoldMs", "回车键停留时间", 1000, holdOptions, Group.HOLD),
        IntItem("symbolScrollMs", "符号翻页停留时间", 800, holdOptions, Group.HOLD),
        IntItem("explainMaxLen", "逐字解释字数", 0, listOf(
            0 to "全部", 1 to "1 个字", 2 to "2 个字", 3 to "3 个字", 4 to "4 个字", 5 to "5 个字",
        ), Group.CANDIDATE),
        IntItem("vibrateStrength", "震动强度", 2, listOf(1 to "轻", 2 to "中", 3 to "强"), Group.VIBRATE),
        // 手势灵敏度：滑动判定距离和双击窗口一起缩放（低=防误触，中=现在的手感，极速=轻轻一划就认）
        IntItem("gestureSensitivity", "手势灵敏度", 1, listOf(
            0 to "1（低）", 1 to "2（中）", 2 to "3（高）",
        ), Group.GESTURE),
        IntItem("fsBoundarySpeakMode", "光标位于开头或结尾时朗读方式", 1, listOf(
            0 to "字数统计+编辑框内容",
            1 to "编辑框内容+字数统计",
            2 to "字数统计",
            3 to "编辑框内容",
        ), Group.GESTURE),
        // 拖动时反复执行的间隔
        IntItem("fsDragMs", "拖动重复间隔", 300, (1..8).map { it * 100 to "0.$it 秒" }, Group.GESTURE),
        // 语音库参数
        IntItem("ttsRate", "语速", 100, percentOptions, Group.VOICE),
        IntItem("ttsPitch", "语调", 100, percentOptions, Group.VOICE),
        IntItem("ttsVolume", "音量", 100, volumeOptions, Group.VOICE),
    ) + GESTURE_SLOTS.map { (slot, name) ->
        IntItem(gestureKey(slot), "$name", gestureDefault(slot), ACTIONS, Group.GESTURE)
    }

    val intDefaults: Map<String, Int> = intItems.associate { it.key to it.default }

    val strItems = listOf(
        // 空字符串 = 跟随系统默认语音库
        StrItem("soundNames", "音效名称", "{}", Group.INTERNAL),
        StrItem("toolbarCustomOrder", "工具栏自定义顺序", "", Group.INTERNAL),
        StrItem("ttsEngine", "语音库", "", Group.VOICE),
        StrItem("ttsVoice", "发音人", "", Group.VOICE),
        // 编辑菜单的完整列表（上下滑切换的顺序），逗号分开的动作编号；空 = 用出厂默认
        StrItem(EDIT_OPTIONS_KEY, "编辑菜单", "", Group.GESTURE),
        // 按句移动的标点（自定义），逗号或换行分开；空 = 用默认（。，；？！.!? 和换行）
        StrItem("sentenceSymbols", "按句移动光标", "", Group.GESTURE),
    ) + intItems.filter { it.key.startsWith("sfx") }.map { StrItem(it.key + "Id", it.label, "", Group.INTERNAL) }

    val strDefaults: Map<String, String> = strItems.associate { it.key to it.default }
}
