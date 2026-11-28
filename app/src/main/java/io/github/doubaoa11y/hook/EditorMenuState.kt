package io.github.doubaoa11y.hook

import io.github.doubaoa11y.ConfigSpec

internal data class EditorMenuState(
    val empty: Boolean? = null,
    val selected: Boolean? = null,
    val canSend: Boolean = false,
) {
    fun available(configured: List<Int>): List<Int> = configured.filter {
        when (it) {
            ConfigSpec.ACT_READ_SELECTION, ConfigSpec.ACT_COPY, ConfigSpec.ACT_CUT, ConfigSpec.ACT_DESELECT -> selected != false
            ConfigSpec.ACT_SEND, ConfigSpec.ACT_SEND_AND_HIDE -> empty != true || canSend
            ConfigSpec.ACT_VOICE, ConfigSpec.ACT_PASTE, ConfigSpec.ACT_PREV_CLIPBOARD, ConfigSpec.ACT_NEXT_CLIPBOARD, ConfigSpec.ACT_HIDE,
            ConfigSpec.ACT_TOGGLE_FULLSCREEN, ConfigSpec.ACT_SWITCH_SCREEN, ConfigSpec.ACT_SMART_ORGANIZE, ConfigSpec.ACT_UNDO, ConfigSpec.ACT_CHAR_COUNT -> true
            else -> empty != true
        }
    }

    fun current(ids: List<Int>, previous: Int?): Int? {
        if (previous in ids) return previous
        val preferred = if (empty == true) ConfigSpec.ACT_PASTE else ConfigSpec.ACT_DEFAULT
        return if (preferred in ids) preferred else ids.firstOrNull()
    }
}
