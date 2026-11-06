package io.github.doubaoa11y.hook

/** 单字解释：“高” → “高，提高的高”。数据见 CharWordsData（jieba 词频表生成）。 */
object CharWords {
    private val map: Map<Char, String> by lazy {
        val m = HashMap<Char, String>(8000)
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

    private fun isCjk(c: Char) = c in '一'..'鿿'

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
        val parts = t.map { c -> map[c]?.let { "${it}的$c" } ?: c.toString() }
        return t + "，" + parts.joinToString("，")
    }

    /** 单个汉字加解释，其它原样 */
    fun explain(text: String): String {
        val t = text.trim()
        if (t.length != 1 || !isCjk(t[0]) || !Config.b("charExplain")) return t
        val w = map[t[0]] ?: return t
        return "$t，${w}的$t"
    }
}
