package io.github.doubaoa11y

/**
 * 模块设置项的定义。设置界面（模块自己的进程）和 hook 代码（豆包进程）共用这一份。
 * 设置界面通过广播把值发给豆包进程，豆包进程存进自己的 SharedPreferences。
 */
object ConfigSpec {
    /** 模块自己的版本（build.gradle.kts 从这里读） */
    const val MODULE_VERSION_CODE = 41
    const val MODULE_VERSION_NAME = "1.1.1"

    const val TARGET_PACKAGE = "com.bytedance.android.doubaoime"
    /** 已适配的豆包版本：版本号 -> 版本名 */
    val SUPPORTED_VERSIONS = mapOf(100503008L to "1.5.3", 100504010L to "1.5.4")
    val SUPPORTED_NAMES: String get() = SUPPORTED_VERSIONS.values.joinToString("、")

    /** 设置广播。extras：各开关的 boolean 值；可选 "cmd" = status / dump / reset。 */
    const val ACTION_CONFIG = "io.github.doubaoa11y.CONFIG"
    const val EXTRA_CMD = "cmd"
    const val CMD_STATUS = "status"
    const val CMD_DUMP = "dump"
    const val CMD_RESET = "reset"
    const val CMD_EDIT_ADD = "editAdd"
    const val CMD_EDIT_REMOVE = "editRemove"

    /** 豆包进程里保存设置用的 SharedPreferences 文件名 */
    const val PREFS_NAME = "doubao_a11y_module"

    /** 设置页里的分组；FIXED 不显示，永远用默认值 */
    enum class Group { INPUT, HOLD, CANDIDATE, STATE, VIBRATE, DEBUG, FIXED }

    class Item(val key: String, val label: String, val default: Boolean, val group: Group)

    val items = listOf(
        // 输入方式
        Item("nineDigits", "九键拼音按键读数字（如 abc 键读 2）", false, Group.INPUT),
        // 候选词解释
        Item("charExplain", "单字解释（如：高，提高的高）", true, Group.CANDIDATE),
        // 键盘状态提示
        Item("announceMode", "切换键盘、大小写时提示", true, Group.STATE),
        Item("announceOnShow", "键盘弹出时朗读键盘类型", false, Group.STATE),
        Item("announceHide", "键盘关闭时提示", true, Group.STATE),
        // 震动
        Item("touchVibrate", "触摸震动", true, Group.VIBRATE),
        // 调试
        Item("runLog", "记录运行日志（会记下按键和语音文字，排查问题时才打开）", false, Group.DEBUG),
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
        Item("tatansFast", "天坦读屏快速打字", true, Group.FIXED),
        Item("labelViews", "按钮中文名", true, Group.FIXED),
        Item("queryNative", "查询键盘类型", true, Group.FIXED),
    )

    val defaults: Map<String, Boolean> = items.associate { it.key to it.default }
    val fixed: Set<String> = items.filter { it.group == Group.FIXED }.map { it.key }.toSet()

    /** 多选一的设置项（值是整数），设置页里点一下弹出选择框 */
    class IntItem(val key: String, val label: String, val default: Int, val options: List<Pair<Int, String>>, val group: Group)

    /** 0.5 到 5 秒，0.1 秒一挡；值以毫秒保存 */
    private val holdOptions = (5..50).map { it * 100 to "${it / 10}.${it % 10} 秒" }

    /** 输入方式：0 抬手输入（读屏焦点留在编辑框）；1 双击输入（按键交给读屏焦点，摸到读、双击输入） */
    const val TYPE_LIFT = 0
    const val TYPE_DOUBLE_TAP = 1

    val intItems = listOf(
        IntItem("typeMode", "输入方式", TYPE_LIFT, listOf(
            TYPE_LIFT to "抬手输入", TYPE_DOUBLE_TAP to "双击输入",
        ), Group.INPUT),
        IntItem("deleteHoldMs", "删除键停留时间", 1500, holdOptions, Group.HOLD),
        IntItem("spaceHoldMs", "空格键停留时间", 1000, holdOptions, Group.HOLD),
        IntItem("explainMaxLen", "逐字解释字数", 1, listOf(
            1 to "1 个字", 2 to "2 个字", 3 to "3 个字", 4 to "4 个字", 5 to "5 个字", 0 to "全部",
        ), Group.CANDIDATE),
        IntItem("vibrateStrength", "震动强度", 2, listOf(1 to "轻", 2 to "中", 3 to "强"), Group.VIBRATE),
    )

    val intDefaults: Map<String, Int> = intItems.associate { it.key to it.default }
}
