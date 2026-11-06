package io.github.doubaoa11y.hook

/**
 * 图标字体私有区字符 → 中文名。
 * oimeui2023 = res/9U.ttf（U+E300–E3A1），oimeui2025 = res/c3.ttf（U+E900–E905）。
 * 已按字体渲染图核对外形，并用皮肤样式 assets/skin/default 下的 xml 里的用法核对含义。
 * 渲染图和皮肤摘录用 tools/render_icons.py 从自己的豆包安装包生成，属于豆包的资源，不放进仓库。
 * 值为空字符串表示角标，朗读时跳过。
 */
object IconNames {
    private val map: Map<Int, String> = hashMapOf(
        // ---- 常用功能键 ----
        0xe30b to "删除",
        0xe316 to "删除",
        0xe30c to "回车",
        0xe310 to "小写",
        0xe30e to "大写",
        0xe30f to "锁定大写",
        0xe31c to "空格",
        0xe358 to "",
        0xe35e to "中文",
        0xe35c to "源语言",
        0xe360 to "英文输入",
        0xe359 to "",
        0xe35f to "目标语言",
        0xe35d to "A",
        0xe361 to "中文输入",
        0xe35a to "123",
        0xe366 to "123",
        0xe38c to "数字",
        0xe38b to "中文数字",
        0xe33b to "表情",
        0xe36d to "表情",
        0xe387 to "AI 表情",
        0xe367 to "语音",
        0xe369 to "语音",
        0xe374 to "语音设置",
        0xe305 to "语音",
        0xe368 to "空格",
        0xe36a to "按住说话",
        0xe36b to "按住说话",
        0xe337 to "发送",
        0xe35b to "发送",
        0xe304 to "关闭",
        0xe306 to "关闭",
        0xe314 to "关闭",
        0xe307 to "收起",
        0xe365 to "更多候选",
        0xe34a to "隐藏键盘",
        0xe33c to "收起",
        0xe33d to "展开",
        0xe308 to "向左",
        0xe362 to "向左",
        0xe309 to "向右",
        0xe378 to "向右",
        0xe30a to "向上",
        0xe373 to "收起更多候选",
        0xe31a to "返回",
        0xe312 to "清空",
        0xe356 to "清空",
        0xe357 to "撤销",
        0xe388 to "撤销",
        0xe340 to "翻译",
        0xe36f to "翻译",
        0xe371 to "切换键盘",
        0xe327 to "切换键盘",
        0xe370 to "完成",
        0xe375 to "添加符号",
        0xe363 to "更多",
        0xe349 to "反馈",
        // ---- 输入方式 ----
        0xe31d to "九键拼音",
        0xe31e to "全键拼音",
        0xe376 to "双拼",
        0xe377 to "双拼",
        0xe382 to "双拼",
        0xe379 to "繁体",
        0xe37a to "简体",
        0xe37b to "繁体",
        0xe39e to "笔画",
        0xe39f to "笔画",
        0xe3a0 to "五笔",
        0xe3a1 to "五笔",
        0xe385 to "手写",
        0xe389 to "货币符号",
        0xe38a to "数学符号",
        0xe34d to "符号",
        0xe34e to "序号",
        0xe38d to "拼音声调",
        0xe38e to "日文平假名",
        0xe38f to "日文平假名",
        0xe390 to "手写查字",
        0xe391 to "日文片假名",
        0xe392 to "日文片假名",
        0xe393 to "日文片假名",
        0xe394 to "希腊字母",
        0xe395 to "希腊字母",
        0xe396 to "拉丁字母",
        0xe397 to "拉丁字母",
        0xe398 to "拉丁字母",
        // ---- 编辑和工具 ----
        0xe320 to "复制",
        0xe364 to "剪贴板历史",
        0xe36c to "剪贴板",
        0xe321 to "光标",
        0xe347 to "调整键盘高度",
        0xe333 to "编辑",
        0xe336 to "剪贴板",
        0xe31b to "工具栏菜单",
        0xe345 to "常用语",
        0xe36e to "常用语",
        0xe37c to "快捷短语",
        0xe34c to "历史",
        0xe34f to "收藏",
        0xe331 to "喜欢",
        0xe332 to "聊天",
        0xe343 to "词典",
        0xe354 to "缩小",
        0xe355 to "放大",
        0xe301 to "设置",
        0xe326 to "设置",
        0xe302 to "声音",
        0xe30d to "帮助",
        0xe328 to "帮助",
        0xe34b to "反馈",
        0xe31f to "信息",
        0xe325 to "提示",
        0xe318 to "刷新",
        0xe319 to "刷新",
        0xe300 to "云",
        0xe383 to "云",
        0xe322 to "加载中",
        0xe334 to "无痕模式",
        0xe339 to "小红书",
        // ---- AI ----
        0xe32b to "改写",
        0xe33a to "AI 助手",
        0xe311 to "AI",
        0xe342 to "AI",
        0xe344 to "AI",
        0xe329 to "AI 文档",
        0xe330 to "AI 文档",
        0xe386 to "单手模式",
        0xe346 to "单手模式",
        0xe372 to "自定义工具栏",
        0xe384 to "手写",
        0xe32a to "校对",
        0xe32c to "总结",
        0xe37d to "折",
        // ---- oimeui2025（res/c3.ttf） ----
        0xe900 to "123",
        0xe901 to "小写",
        0xe902 to "大写",
        0xe903 to "回车",
        0xe904 to "删除",
        0xe905 to "锁定大写",
    )

    fun name(code: Int): String? = map[code]

    /** 把一段绘制文字转成可朗读的文字；未知图标读作“图标 e3xx” */
    fun speakable(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            if (Fmt.isPua(c)) {
                val n = name(c.code) ?: ("图标 " + Integer.toHexString(c.code))
                if (n.isEmpty()) continue
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(n)
            } else {
                sb.append(c)
            }
        }
        return sb.toString().trim()
    }

    /** 日志用：文字 + 图标猜测，如 ""(删除?) */
    fun annotate(s: String): String {
        val guesses = s.filter { Fmt.isPua(it) }.map { name(it.code)?.ifEmpty { "角标" } ?: "未知" }
        val base = "\"" + Fmt.esc(s) + "\""
        return if (guesses.isEmpty()) base else base + "(" + guesses.joinToString("/") + "?)"
    }
}
