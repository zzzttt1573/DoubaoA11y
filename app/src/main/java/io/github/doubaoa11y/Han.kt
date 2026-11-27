package io.github.doubaoa11y

internal object Han {
    fun isHanCodePoint(cp: Int): Boolean =
        cp == 0x3007 ||
            cp in 0x3400..0x4DBF ||
            cp in 0x4E00..0x9FFF ||
            cp in 0xF900..0xFAFF ||
            cp in 0x20000..0x2A6DF ||
            cp in 0x2A700..0x2EE5F ||
            cp in 0x2F800..0x2FA1F ||
            cp in 0x30000..0x323AF

    fun isSingleHan(key: String): Boolean {
        if (key.isEmpty()) return false
        val cp = key.codePointAt(0)
        return Character.charCount(cp) == key.length && isHanCodePoint(cp)
    }
}
