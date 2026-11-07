package io.github.doubaoa11y.hook

/**
 * 朗读用的名称表：标点符号读法、Java 控件的中文名。
 */
object Names {
    /** 单个标点符号的读法。读屏常常把标点读成停顿或干脆不读，所以全部换成文字 */
    private val punct: Map<String, String> = hashMapOf(
        "，" to "逗号", "," to "英文逗号",
        "。" to "句号", "." to "点",
        "？" to "问号", "?" to "英文问号",
        "！" to "感叹号", "!" to "英文感叹号",
        "、" to "顿号",
        "；" to "分号", ";" to "英文分号",
        "：" to "冒号", ":" to "英文冒号",
        "“" to "左引号", "”" to "右引号", "\"" to "英文引号",
        "‘" to "左单引号", "’" to "右单引号", "'" to "英文单引号",
        "（" to "左括号", "）" to "右括号", "(" to "英文左括号", ")" to "英文右括号",
        "【" to "左方括号", "】" to "右方括号", "[" to "英文左方括号", "]" to "英文右方括号",
        "《" to "左书名号", "》" to "右书名号", "<" to "小于", ">" to "大于",
        "{" to "左花括号", "}" to "右花括号",
        "「" to "左直角引号", "」" to "右直角引号",
        "…" to "省略号", "……" to "省略号",
        "—" to "破折号", "——" to "破折号",
        "-" to "减号", "_" to "下划线", "+" to "加号", "=" to "等号",
        "*" to "星号", "/" to "斜杠", "\\" to "反斜杠", "|" to "竖线",
        "#" to "井号", "@" to "艾特", "&" to "和号", "%" to "百分号",
        "^" to "脱字符", "~" to "波浪号", "～" to "波浪号", "`" to "反引号",
        "$" to "美元", "￥" to "人民币", "¥" to "人民币", "€" to "欧元", "£" to "英镑",
        "·" to "间隔号", "×" to "乘号", "÷" to "除号", "°" to "度",
        "@#" to "符号",
    )

    /** 把一段键面文字转成读法：单个标点读名称，其余原样 */
    fun spoken(s: String): String {
        val t = s.trim()
        if (Config.b("editPanel")) {
            if (t == "移动光标") return "编辑面板（原移动光标）"
            if (t == "单手模式") return "编辑面板（原单手模式）"
        }
        punct[t]?.let { return it }
        return t
    }

    /**
     * 豆包工具栏按钮把内部标识写在 contentDescription 里（自己的代码也靠它找按钮，不能改），
     * 读屏读到的就是“btn_speech_bar_two”这样的字。这里只在无障碍节点上换成中文。
     */
    val toolbarKeys: Map<String, String> = hashMapOf(
        "btn_speech_bar_two" to "语音输入",
        "btn_speech_bar_single" to "语音输入",
        "btn_toolbar_emoji" to "表情",
        "btn_toolbar_text_organize" to "智能整理",
        "btn_toolbar_common_phrase" to "常用语",
        "btn_toolbar_clipboard" to "剪贴板",
        "btn_toolbar_handwriting_lookup" to "手写找字",
        "btn_toolbar_translate" to "翻译",
        "btn_toolbar_select_board" to "切换键盘",
        "btn_toolbar_hans_hant" to "简繁切换",
        "btn_toolbar_one_hand" to "编辑面板",
        "btn_toolbar_move_cursor" to "编辑面板",
        "btn_toolbar_keyboard_adjust" to "调整键盘",
        "btn_toolbar_send" to "发送",
        "btn_hide" to "收起键盘",
        "btn_logo" to "菜单",
        "btn_switch_window" to "切换窗口",
        "btn_pinyin9_keyboard_side" to "九键侧栏",
        "btn_landscape_26_layout" to "横屏布局",
    )

    /** 没有文字也没有描述的控件：按资源 id 名称给中文名 */
    val viewIds: Map<String, String> = hashMapOf(
        // 手写键盘
        "hand_writing_iv_delete" to "删除",
        "hand_writing_key_symbol" to "符号",
        "hand_writing_key_number" to "123",
        "hand_writing_key_space" to "空格",
        "hand_writing_key_switch" to "切换键盘",
        "hand_writing_key_action" to "回车",
        "handwriteView" to "手写区",
        // 手写找字
        "lookup_key_action" to "回车",
        "lookup_key_back" to "返回",
        "lookup_key_delete" to "删除",
        "lookup_key_redo" to "重写",
        "lookup_key_space" to "空格",
        // 第一次打开时的“选择键盘类型”
        "select_board9" to "九键拼音",
        "select_board26" to "全键拼音",
        "select_board_hand" to "手写键盘",
        "select_board_stroke" to "笔画键盘",
        "select_board_shuang" to "双拼键盘",
        "select_board_wubi" to "五笔键盘",
        // 工具栏和候选栏
        "btn_hide" to "收起键盘",
        "btn_hide_remove" to "收起键盘",
        "btn_logo" to "菜单",
        "btn_switch_window" to "切换窗口",
        "candidate_speech_button" to "语音输入",
        "candidate_tip_close" to "关闭提示",
        "more_candidate_arrow_up" to "收起更多候选",
        "more_candidate_delete" to "删除",
        "item_voice_big" to "语音输入",
        "item_voice_small" to "语音输入",
        "item_emoji" to "表情",
        "item_text_organize" to "智能整理",
        "item_quick_phrase" to "常用语",
        "item_clipboard" to "剪贴板",
        "item_handwriting_lookup" to "手写找字",
        "item_collapse_keyboard" to "收起键盘",
        "item_translate" to "翻译",
        "item_keyboard_switch" to "切换键盘",
        "item_hans_hant" to "简繁切换",
        "item_one_hand" to "编辑面板",
        "item_keyboard_adjust" to "调整键盘",
        "item_send" to "发送",
        "item_clear" to "清空",
        // 语音
        "asr_action_btn" to "语音",
        "asr_candidate_close" to "关闭",
        "btn_asr_back" to "返回",
        "btn_asr_backspace" to "删除",
        "btn_asr_rollback" to "撤销",
        "btn_asr_send" to "发送",
        "btn_asr_voice" to "开始说话",
        "btn_asr_voice_paused" to "继续说话",
        // 单手、工具箱
        "btn_one_hand_back" to "退出单手模式",
        "btn_one_hand_left" to "键盘靠左",
        "btn_one_hand_right" to "键盘靠右",
        "btn_toolbox_account" to "账号",
        "btn_toolbox_back" to "返回",
        // 剪贴板、常用语
        "btn_add_common_phrase" to "添加常用语",
        "btn_close_clipboard_clear_view" to "关闭",
        "btn_close_clipboard_common_phrase" to "关闭",
        "btn_close_clipboard_history" to "关闭",
        "btn_close_phrase_empty_view" to "关闭",
        "btn_delete_clipboard" to "删除",
        "clipboard_clear_icon" to "清空剪贴板",
        "clipboard_history_delete_btn" to "删除",
        "common_phrase_delete_btn" to "删除",
        "toolbar_clipboard_close" to "关闭",
        "toolbar_clipboard_icon" to "剪贴板",
        "toolbar_clipboard_smart_organize" to "智能整理",
        "toolbar_clipboard_word_segmentation" to "分词",
        "cross_device_clipboard_close" to "关闭",
        "close_btn" to "关闭",
        "iv_back" to "返回",
        "keyboard_title_bar_left_img" to "返回",
        "o_input_clear" to "清除内容",
    )

    /** id 名称里常见的英文词 → 中文，用于表里没有的控件，读个大概 */
    private val words = listOf(
        "backspace" to "删除", "delete" to "删除", "del" to "删除",
        "space" to "空格", "enter" to "回车", "action" to "回车",
        "symbol" to "符号", "number" to "数字", "switch" to "切换",
        "hide" to "收起键盘", "collapse" to "收起", "expand" to "展开",
        "clear" to "清空", "close" to "关闭", "back" to "返回", "cancel" to "取消", "confirm" to "确定",
        "send" to "发送", "voice" to "语音", "speech" to "语音", "mic" to "语音", "asr" to "语音",
        "emoji" to "表情", "setting" to "设置", "more" to "更多", "translate" to "翻译",
        "clipboard" to "剪贴板", "phrase" to "常用语", "edit" to "编辑", "add" to "添加",
        "left" to "左", "right" to "右", "up" to "上", "down" to "下",
    )

    fun guessFromId(entry: String): String? {
        val parts = entry.lowercase().split('_')
        val hits = ArrayList<String>()
        for (p in parts) {
            val w = words.firstOrNull { it.first == p }?.second ?: continue
            if (w !in hits) hits.add(w)
        }
        return if (hits.isEmpty()) null else hits.joinToString("")
    }
}
