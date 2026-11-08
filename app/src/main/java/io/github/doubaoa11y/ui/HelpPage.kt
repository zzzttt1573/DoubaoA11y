package io.github.doubaoa11y.ui

import android.widget.LinearLayout
import android.widget.TextView

/**
 * 使用帮助：显示整合包里的“使用说明.txt”（构建时打进 assets）。
 * 单独一行、前后是空行、比较短的算章节标题，读屏可以按标题跳。
 */
object HelpPage {
    private const val FILE = "使用说明.txt"

    fun fill(act: SettingsActivity, root: LinearLayout) {
        val text = try {
            act.assets.open(FILE).bufferedReader(Charsets.UTF_8).readText().removePrefix("﻿")
        } catch (_: Throwable) {
            null
        }
        if (text == null) {
            root.addView(act.note("没有找到使用说明。"))
            root.addView(act.button("打开 GitHub 页面") { act.open(SettingsActivity.GITHUB) })
            return
        }
        val lines = text.replace("\r", "").split('\n')
        val para = StringBuilder()
        fun flush() {
            val t = para.toString().trim()
            para.setLength(0)
            if (t.isEmpty()) return
            root.addView(TextView(act).apply {
                this.text = t
                isFocusable = true
                setPadding(0, act.pad / 4, 0, act.pad / 2)
                setLineSpacing(0f, 1.15f)
            })
        }
        for (i in lines.indices) {
            val l = lines[i].trimEnd()
            if (l.isBlank()) {
                flush()
                continue
            }
            if (isHeading(lines, i)) {
                flush()
                root.addView(act.heading(l.trim()))
                continue
            }
            para.append(l).append('\n')
        }
        flush()
        root.addView(act.button("打开 GitHub 页面") { act.open(SettingsActivity.GITHUB) })
    }

    private fun isHeading(lines: List<String>, i: Int): Boolean {
        val l = lines[i]
        if (l.isBlank() || l.startsWith(" ") || l.length > 24) return false
        // “第 4 步”这类前面不一定空行，也算标题
        if (Regex("^第 ?\\d+ ?步").containsMatchIn(l)) return true
        val prevBlank = i == 0 || lines[i - 1].isBlank()
        val nextBlank = i + 1 >= lines.size || lines[i + 1].isBlank()
        if (!prevBlank) return false
        // “一、包里有什么”这类，或者单独一行的短标题（下面紧跟正文）
        if (Regex("^[一二三四五六七八九十]+、").containsMatchIn(l)) return true
        return !nextBlank && !l.contains('：') && !l.contains('。') && !Regex("^\\d+[.、]").containsMatchIn(l)
    }
}
