package io.github.doubaoa11y.hook

import java.text.BreakIterator

internal object TextBoundaries {
    private fun iterator(text: String) = BreakIterator.getCharacterInstance().apply { setText(text) }

    fun step(text: String, offset: Int, direction: Int): Int {
        val boundaries = iterator(text)
        return if (direction < 0) boundaries.preceding(offset) else boundaries.following(offset)
    }

    fun count(text: String, skipWhitespace: Boolean = false): Int {
        val boundaries = iterator(text)
        var start = boundaries.first()
        var total = 0
        while (true) {
            val end = boundaries.next()
            if (end == BreakIterator.DONE) return total
            if (!skipWhitespace || (start until end).any { !text[it].isWhitespace() }) total++
            start = end
        }
    }

    fun selectedCount(text: String, start: Int, end: Int) =
        count(text.substring(minOf(start, end), maxOf(start, end)))
}
