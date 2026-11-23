package io.github.doubaoa11y.ui

import android.content.Intent
import android.widget.LinearLayout
import io.github.doubaoa11y.ConfigSpec

/**
 * 全屏与手势设置页。极简：只列手势和它绑定的动作，不写任何说明文字。
 */
object GesturePage {
    fun fill(a: SettingsActivity, root: LinearLayout) {
        a.addKeysNoHint(root, "upperOperation", "gestureSensitivity")

        root.addView(a.heading("编辑菜单"))
        root.addView(a.button("自定义编辑菜单") {
            a.startActivity(Intent(a, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PAGE, SettingsActivity.EDITOPTS))
        })

        // 单指、单指往返、单指折线、双指、拖动，直接排下来，每组一个标题
        root.addView(a.heading("单指"))
        a.addKeysNoHint(
            root,
            *ConfigSpec.GESTURE_SLOTS.filter { it.first in 0..ConfigSpec.G_SINGLE_DOWN }
                .map { ConfigSpec.gestureKey(it.first) }.toTypedArray(),
        )

        root.addView(a.heading("单指往返"))
        a.addKeysNoHint(
            root,
            *ConfigSpec.GESTURE_SLOTS.filter { it.first in ConfigSpec.G_LEFT_RIGHT..ConfigSpec.G_DOWN_UP }
                .map { ConfigSpec.gestureKey(it.first) }.toTypedArray(),
        )

        root.addView(a.heading("单指折线"))
        a.addKeysNoHint(
            root,
            *ConfigSpec.GESTURE_SLOTS.filter { it.first in ConfigSpec.G_UP_LEFT..ConfigSpec.G_RIGHT_DOWN }
                .map { ConfigSpec.gestureKey(it.first) }.toTypedArray(),
        )

        root.addView(a.heading("双指"))
        a.addKeysNoHint(
            root,
            *ConfigSpec.GESTURE_SLOTS.filter {
                it.first == ConfigSpec.G_DOUBLE_SINGLE_TAP || it.first in ConfigSpec.G_DOUBLE_TAP..ConfigSpec.G_DOUBLE_DOWN
            }
                .map { ConfigSpec.gestureKey(it.first) }.toTypedArray(),
        )

        root.addView(a.heading("拖动"))
        a.addKeysNoHint(root, "fsDrag", "fsDragMs")
        a.addKeysNoHint(
            root,
            *ConfigSpec.GESTURE_SLOTS.filter { it.first in ConfigSpec.DRAG_SLOTS }
                .map { ConfigSpec.gestureKey(it.first) }.toTypedArray(),
        )

        root.addView(a.heading("按句移动光标"))
        root.addView(a.button("自定义分隔符号") {
            a.startActivity(Intent(a, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PAGE, SettingsActivity.SENTENCE_SYMBOLS))
        })

        root.addView(a.button("重置所有设置") {
            android.app.AlertDialog.Builder(a).setTitle("重置所有设置")
                .setMessage("恢复手势、编辑菜单和按句移动光标的默认设置？")
                .setPositiveButton("恢复") { _, _ -> a.restoreGestureDefaults() }.setNegativeButton("取消", null).show()
        })
    }

    fun fillSentenceSymbols(a: SettingsActivity, root: LinearLayout) {
        val symbols = ConfigSpec.sentenceList(a.prefs.getString("sentenceSymbols", "")).toMutableList()
        fun save() {
            a.prefs.edit().putString("sentenceSymbols", "list:" + symbols.joinToString("")).apply()
            a.send(null)
            a.rebuild()
        }
        root.addView(a.note("根据下列标点符号分隔句子，长按已有标点可删除"))
        a.addList(
            root,
            symbols.map { if (it == "\n") "换行" else it },
            descriptions = symbols.map { if (it == "\n") "换行" else io.github.doubaoa11y.hook.Names.spoken(it) },
            onClick = {},
            onLongClick = { index ->
                val raw = symbols[index]
                val label = if (raw == "\n") "换行" else io.github.doubaoa11y.hook.Names.spoken(raw)
                android.app.AlertDialog.Builder(a)
                    .setTitle("删除“$label”？")
                    .setMessage("确定要移除分隔符号“$label”吗？")
                    .setPositiveButton("删除") { _, _ ->
                        symbols.removeAt(index)
                        save()
                    }
                    .setNegativeButton("取消", null)
                    .show()
                true
            },
        )
        root.addView(a.button("添加标点") {
            val input = android.widget.EditText(a).apply { hint = "输入一个标点或“换行”" }
            android.app.AlertDialog.Builder(a).setTitle("添加标点").setView(input)
                .setPositiveButton("添加") { _, _ ->
                    val symbol = input.text.toString().let { if (it == "换行") "\n" else it }
                    if (symbol.length == 1 && symbol !in symbols && (symbol == "\n" || Regex("\\p{P}").matches(symbol))) {
                        symbols.add(symbol)
                        save()
                    } else a.toast("请输入一个尚未添加的标点或换行")
                }.setNegativeButton("取消", null).show()
        })
        root.addView(a.button("恢复默认") {
            android.app.AlertDialog.Builder(a)
                .setTitle("恢复默认")
                .setMessage("确定要重置按句移动光标的分隔符号列表吗？")
                .setPositiveButton("恢复") { _, _ ->
                    a.prefs.edit().remove("sentenceSymbols").apply()
                    a.send(null)
                    a.rebuild()
                    a.toast("分隔符号已恢复默认")
                }
                .setNegativeButton("取消", null)
                .show()
        })
    }
}
