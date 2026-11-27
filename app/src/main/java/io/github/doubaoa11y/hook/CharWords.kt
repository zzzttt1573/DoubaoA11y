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
    private fun wordOf(ch: String): String? =
        Config.customWords[ch] ?: if (ch.length == 1) map[ch[0]] else null

    fun isHanCodePoint(cp: Int) = io.github.doubaoa11y.Han.isHanCodePoint(cp)
    fun isSingleHan(key: String) = io.github.doubaoa11y.Han.isSingleHan(key)

    fun isCjk(c: Char): Boolean = isHanCodePoint(c.code)

    /** 将文本拆成完整 Unicode 码点单字列表 */
    fun splitHanChars(text: String): List<String>? {
        if (text.isEmpty()) return null
        val out = ArrayList<String>(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (!isHanCodePoint(cp)) return null
            val step = Character.charCount(cp)
            out.add(text.substring(i, i + step))
            i += step
        }
        return out
    }

    /**
     * 候选加逐字解释：候选字数不超过设置值（0 表示不限）时，每个字都解释。
     * “高” → “高，提高的高”；“高兴” → “高兴，提高的高，高兴的兴”。
     */
    fun explainWord(text: String): String {
        val t = text.trim()
        if (t.isEmpty() || !Config.b("charExplain")) return t
        val chars = splitHanChars(t) ?: return t
        val max = Config.i("explainMaxLen")
        if (max > 0 && chars.size > max) return t
        if (chars.size == 1) return explain(t)
        val parts = chars.map { ch -> wordOf(ch)?.let { "${it}的$ch" } ?: ch }
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

    /** 单个汉字加解释（直接读成“某某的某”），其它原样 */
    fun explain(text: String): String {
        val t = text.trim()
        if (!isSingleHan(t) || !Config.b("charExplain")) return t
        val w = wordOf(t) ?: return t
        return "${w}的$t"
    }

    /** 单个汉字带字头解释：“好” → “好，好坏的好”；无解释或未开单字解释时返回 null */
    fun explainWithChar(c: Char, force: Boolean = false): String? {
        if (!isCjk(c) || (!force && !Config.b("charExplain"))) return null
        val s = c.toString()
        val w = wordOf(s) ?: return null
        return "$s，${w}的$s"
    }

    /** 仅返回单个汉字的解释部分：“好” → “好坏的好”；无解释或未开单字解释时返回 null */
    fun explainOnly(c: Char, force: Boolean = false): String? {
        if (!isCjk(c) || (!force && !Config.b("charExplain"))) return null
        val s = c.toString()
        val w = wordOf(s) ?: return null
        return "${w}的$s"
    }
}
