package io.github.doubaoa11y.ui

import android.app.AlertDialog
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import io.github.doubaoa11y.hook.CharWords

/**
 * 自定义解释词：列出全部单字解释，每个一行；顶上搜索。点一行改这个字的解释词。
 * 改动存在设置页里，和其他设置一起发给豆包进程，豆包优先用自定义的。
 */
class WordsPage(private val act: SettingsActivity) {
    private class Row(val c: String, val builtIn: String?)

    private val builtIn: Map<String, String> = try {
        CharWords.builtIn().entries.associate { it.key.toString() to it.value }
    } catch (_: Throwable) {
        emptyMap()
    }
    private val all: List<Row> = builtIn.map { Row(it.key, it.value) }
    private var custom: MutableMap<String, String> = load()
    private var shown: List<Row> = all
    private lateinit var adapter: BaseAdapter
    private lateinit var count: TextView

    private fun load(): MutableMap<String, String> {
        val m = LinkedHashMap<String, String>()
        for (raw in (act.prefs.getString(SettingsActivity.PREF_WORDS, "") ?: "").split('\n')) {
            val line = raw.removeSuffix("\r")
            val tab = line.indexOf('\t')
            if (tab in 1..2 && tab < line.lastIndex) {
                val key = line.substring(0, tab)
                if (CharWords.isSingleHan(key)) m[key] = line.substring(tab + 1)
            }
        }
        return m
    }

    private fun save(key: String, value: String?): Boolean {
        val next = LinkedHashMap(load())
        if (value == null) next.remove(key) else next[key] = value
        val text = try { WordTransferFormat.serialize(next) } catch (e: IllegalArgumentException) {
            act.toast(e.message ?: "解释词无效")
            return false
        }
        act.prefs.edit().putString(SettingsActivity.PREF_WORDS, text).apply()
        custom = next
        act.send(null)
        return true
    }

    private fun word(r: Row) = custom[r.c] ?: r.builtIn

    private fun label(r: Row): String {
        val w = word(r)
        val base = if (w == null) "${r.c}，没有解释" else "${r.c}，${w}的${r.c}"
        return if (custom.containsKey(r.c)) "$base（自定义）" else base
    }

    fun show() {
        act.title = act.titleOf(SettingsActivity.WORDS)
        val pad = act.pad
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        fill(root, standalone = true)
        act.setContentView(root)
    }

    fun fill(root: LinearLayout, standalone: Boolean = false) {
        val pad = act.pad
        root.addView(act.heading(act.titleOf(SettingsActivity.WORDS), if (standalone) 22f else 18f))
        WordTransfer.fill(act, root)

        val bar = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        val input = EditText(act).apply {
            hint = "输入要找的字或词"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        val search = Button(act).apply {
            text = "搜索"
            isAllCaps = false
        }
        bar.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(search)
        root.addView(bar)
        val add = Button(act).apply {
            text = "添加新字"
            isAllCaps = false
            setOnClickListener { addNew() }
        }
        root.addView(add)
        val onlyCustom = Switch(act).apply {
            text = "只显示自定义的字"
            setPadding(0, pad / 2, 0, pad / 2)
        }
        root.addView(onlyCustom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        count = TextView(act).apply {
            isFocusable = true
            setPadding(0, pad / 4, 0, pad / 4)
        }
        root.addView(count)

        adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(position: Int) = shown[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val tv = (convertView as? TextView) ?: TextView(act).apply {
                    textSize = 18f
                    setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
                }
                tv.text = label(shown[position])
                return tv
            }
        }
        val list = ListView(act)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> edit(shown[pos]) }
        if (standalone) {
            root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        } else {
            val h = (act.resources.displayMetrics.heightPixels * 0.72f).toInt().coerceAtLeast(pad * 20)
            var lastY = 0f
            list.setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        lastY = ev.y
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dy = ev.y - lastY
                        val atTop = list.firstVisiblePosition == 0 && (list.getChildAt(0)?.top ?: 0) >= 0
                        val atBottom = list.lastVisiblePosition >= shown.lastIndex &&
                            (list.getChildAt(list.childCount - 1)?.bottom ?: 0) <= list.height
                        if ((dy > 0 && atTop) || (dy < 0 && atBottom)) {
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                        } else {
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                    }
                }
                false
            }
            root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h))
        }

        fun doSearch() = filter(input.text.toString().trim(), onlyCustom.isChecked)
        search.setOnClickListener { doSearch() }
        input.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH) { doSearch(); true } else false
        }
        onlyCustom.setOnCheckedChangeListener { _, _ -> doSearch() }
        filter("", false, speak = false)
    }

    private var lastQuery = ""
    private var lastOnlyCustom = false

    /** 存了或恢复以后按原来的条件重新列一遍（不读数字，免得盖过“已保存”） */
    private fun refresh() = filter(lastQuery, lastOnlyCustom, speak = false)

    private fun filter(q: String, onlyCustom: Boolean, speak: Boolean = true) {
        lastQuery = q
        lastOnlyCustom = onlyCustom
        // 全部 = 自带的字 + 自己给词表外的字加的解释；开关开着时只在自定义的字里找
        val base = if (onlyCustom) custom.keys.map { c -> Row(c, builtIn[c]) }
        else all + custom.keys.filter { !builtIn.containsKey(it) }.map { Row(it, null) }
        var extraCount = 0
        shown = when {
            q.isEmpty() -> base
            else -> {
                val hit = base.filter { r -> r.c == q || q.contains(r.c) || (word(r)?.contains(q) == true) }
                    .sortedBy { r -> if (r.c == q) 0 else if (q.contains(r.c)) 1 else 2 }
                // 搜的字词表里没有：也给一行，方便添加；这些行不算“找到”
                val have = base.map { it.c }.toSet()
                val extra = if (onlyCustom) emptyList()
                else {
                    val out = LinkedHashSet<String>()
                    var i = 0
                    while (i < q.length) {
                        val cp = q.codePointAt(i)
                        val step = Character.charCount(cp)
                        if (CharWords.isHanCodePoint(cp)) {
                            val ch = q.substring(i, i + step)
                            if (ch !in have) out.add(ch)
                        }
                        i += step
                    }
                    out.map { Row(it, null) }
                }
                extraCount = extra.size
                extra + hit
            }
        }
        adapter.notifyDataSetChanged()
        val found = shown.size - extraCount
        val msg = when {
            q.isEmpty() && onlyCustom -> "自定义的有 ${shown.size} 个"
            q.isEmpty() -> "共 ${shown.size} 个字"
            extraCount == 0 -> "找到 $found 个"
            found == 0 -> "没有找到。有 $extraCount 个字没有解释，点它可以添加"
            else -> "找到 $found 个。另有 $extraCount 个字没有解释，点它可以添加"
        }
        count.text = msg
        if (speak) count.announceForAccessibility(msg)
    }

    /** 添加新字：第一个框只能填一个汉字，两个框都填好才能点“确定” */
    private fun addNew() {
        val pad = act.pad
        val charLabel = TextView(act).apply { text = "要添加的字" }
        val charInput = EditText(act).apply {
            id = View.generateViewId()
            isSingleLine = true
            // 扩展 B-I 区生僻字为 UTF-16 代理对（占 2 个 Char）
            filters = arrayOf(InputFilter.LengthFilter(2))
        }
        charLabel.labelFor = charInput.id
        val tip = TextView(act).apply {
            visibility = View.GONE
            setPadding(0, 0, 0, pad / 2)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val wordLabel = TextView(act).apply {
            text = "要用于解释的词"
            setPadding(0, pad / 2, 0, 0)
        }
        val wordInput = EditText(act).apply {
            id = View.generateViewId()
            isSingleLine = true
        }
        wordLabel.labelFor = wordInput.id
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(charLabel)
            addView(charInput)
            addView(tip)
            addView(wordLabel)
            addView(wordInput)
        }
        val dlg = AlertDialog.Builder(act)
            .setTitle("添加新字")
            .setView(box)
            .setPositiveButton("确定") { _, _ ->
                val c = charInput.text.toString().trim()
                val w = wordInput.text.toString().trim()
                if (!CharWords.isSingleHan(c) || w.isEmpty()) return@setPositiveButton
                if (!save(c, w)) return@setPositiveButton
                refresh()
                count.announceForAccessibility("已添加，$c，${w}的$c")
            }
            .setNegativeButton("取消", null)
            .create()

        fun check() {
            val c = charInput.text.toString().trim()
            val msg = when {
                c.isEmpty() -> null
                !CharWords.isSingleHan(c) -> "请输入一个汉字"
                custom.containsKey(c) -> "“$c”已经自定义过了，现在是“${custom[c]}”。点确定会改成新填的词。"
                builtIn.containsKey(c) -> "词库里已经有“$c”，自带的是“${builtIn[c]}”。点确定会改用新填的词。"
                else -> null
            }
            tip.text = msg ?: ""
            tip.visibility = if (msg == null) View.GONE else View.VISIBLE
            val ok = CharWords.isSingleHan(c) && wordInput.text.toString().isNotBlank()
            dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = ok
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = check()
        }
        charInput.addTextChangedListener(watcher)
        wordInput.addTextChangedListener(watcher)
        dlg.setOnShowListener { check() }
        dlg.show()
    }

    private fun edit(r: Row) {
        val input = EditText(act).apply {
            setText(word(r) ?: "")
            isSingleLine = true
            hint = "包含“${r.c}”的词，或者你能理解的话"
            setSelection(text.length)
        }
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(act.pad, act.pad / 2, act.pad, 0)
            addView(TextView(act).apply {
                text = "读成：${r.c}，某某的${r.c}。" + (r.builtIn?.let { "自带的是“$it”。" } ?: "自带的没有这个字。")
            })
            addView(input)
        }
        val b = AlertDialog.Builder(act)
            .setTitle("改“${r.c}”的解释")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val w = input.text.toString().trim()
                if (!save(r.c, w.takeUnless { it.isEmpty() || it == r.builtIn })) return@setPositiveButton
                refresh()
                count.announceForAccessibility("已保存，" + label(r))
            }
            .setNegativeButton("取消", null)
        if (custom.containsKey(r.c)) b.setNeutralButton("恢复自带的") { _, _ ->
            if (!save(r.c, null)) return@setNeutralButton
            refresh()
            count.announceForAccessibility("已恢复，" + label(r))
        }
        b.show()
    }
}
