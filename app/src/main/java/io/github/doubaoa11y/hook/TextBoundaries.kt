package io.github.doubaoa11y.hook

import java.text.BreakIterator

internal object TextBoundaries {
    private val localIterator = object : ThreadLocal<BreakIterator>() {
        override fun initialValue(): BreakIterator = BreakIterator.getCharacterInstance()
    }

    private inline fun <T> withIterator(text: String, block: (BreakIterator) -> T): T {
        val boundaries = localIterator.get() ?: BreakIterator.getCharacterInstance().also { localIterator.set(it) }
        boundaries.setText(text)
        return try {
            block(boundaries)
        } finally {
            boundaries.setText("")
        }
    }

    fun step(text: String, offset: Int, direction: Int): Int = withIterator(text) { boundaries ->
        if (direction < 0) boundaries.preceding(offset) else boundaries.following(offset)
    }

    fun count(text: String, skipWhitespace: Boolean = false): Int = withIterator(text) { boundaries ->
        var start = boundaries.first()
        var total = 0
        while (true) {
            val end = boundaries.next()
            if (end == BreakIterator.DONE) return@withIterator total
            if (!skipWhitespace || (start until end).any { !text[it].isWhitespace() }) total++
            start = end
        }
        total
    }

    fun selectedCount(text: String, start: Int, end: Int) =
        count(text.substring(minOf(start, end), maxOf(start, end)))
}
