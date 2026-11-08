package io.github.doubaoa11y.hook

/** 单字解释：“高” → “高，提高的高”。数据见 CharWordsData（以 NVDA 简体中文字符描述为主，jieba 词频表补充，避开同音词）。 */
object CharWords {
    private val map: Map<Char, String> by lazy {
        val m = LinkedHashMap<Char, String>(16000)  // 按字频顺序，设置页列表也是这个顺序
        try {
            for (part in CharWordsData.parts) {
                for (line in part.split('\n')) {
                    if (line.length >= 2) m[line[0]] = line.substring(1)
                }
            }
        } catch (t: Throwable) {
            L.e("读取单字解释失败", t)
        }
        m
    }

    /** 自带的词表（设置页的“自定义解释词”也用它列出全部字） */
    fun builtIn(): Map<Char, String> = map

    /** 先看用户自定义的，再看自带的 */
    private fun wordOf(c: Char): String? = Config.customWords[c] ?: map[c]

    fun isCjk(c: Char) = c in '一'..'鿿'

    /**
     * 候选加逐字解释：候选字数不超过设置值（0 表示不限）时，每个字都解释。
     * “高” → “高，提高的高”；“高兴” → “高兴，提高的高，高兴的兴”。
     */
    fun explainWord(text: String): String {
        val t = text.trim()
        if (t.isEmpty() || !Config.b("charExplain") || !t.all { isCjk(it) }) return t
        val max = Config.i("explainMaxLen")
        if (max > 0 && t.length > max) return t
        if (t.length == 1) return explain(t)
        val parts = t.map { c -> wordOf(c)?.let { "${it}的$c" } ?: c.toString() }
        return t + "，" + parts.joinToString("，")
    }

    /**
     * 候选的读法：汉字候选加解释；符号候选读名称（& 读“和号”）。
     * 先看原文是不是汉字，再换符号名称，免得把“和号”这类名称当成汉字去解释。
     */
    fun candidate(text: String): String {
        val t = text.trim()
        val e = explainWord(t)
        return if (e != t) e else Names.spoken(t)
    }

    /** 单个汉字加解释，其它原样 */
    fun explain(text: String): String {
        val t = text.trim()
        if (t.length != 1 || !isCjk(t[0]) || !Config.b("charExplain")) return t
        val w = wordOf(t[0]) ?: return t
        return "$t，${w}的$t"
    }
}
