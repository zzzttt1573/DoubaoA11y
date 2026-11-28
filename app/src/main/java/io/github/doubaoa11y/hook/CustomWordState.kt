package io.github.doubaoa11y.hook

internal class CustomWordState {
    private var lastWords: String? = null

    @Volatile
    var customWords: Map<String, String> = emptyMap()
        private set

    @Synchronized
    fun applyWords(text: String?): Boolean {
        if (text == lastWords) return false
        customWords = parseWords(text)
        lastWords = text
        return true
    }

    private fun parseWords(s: String?): Map<String, String> {
        if (s.isNullOrBlank()) return emptyMap()
        val m = HashMap<String, String>()
        for (raw in s.split('\n')) {
            val line = raw.removeSuffix("\r")
            val tab = line.indexOf('\t')
            if (tab !in 1..2 || tab >= line.lastIndex) continue
            val key = line.substring(0, tab)
            val cp = key.codePointAt(0)
            if (Character.charCount(cp) != key.length) continue
            val w = line.substring(tab + 1).trim()
            if (w.isNotEmpty()) m[key] = w
        }
        return m
    }
}
