package io.github.doubaoa11y.hook

import android.graphics.Rect
import kotlin.math.hypot
import kotlin.math.max

/** 一次绘制回调记录下来的东西 */
class DrawItem(
    val kind: Kind,
    val rect: Rect,
    val text: String? = null,
    val font: String? = null,
    val size: Int = 0,
    val flags: Int = 0,
    val color: Int = 0,
    val radius: Float = 0f,
    val extra: String? = null,
) {
    enum class Kind { TEXT, RECT, IMAGE }

    /** 画在按键预览气泡里的（按下时弹出的大字、长按候选），不算键 */
    var popup = false

    fun cx() = rect.exactCenterX()
    fun cy() = rect.exactCenterY()

    fun describe(): String = when (kind) {
        Kind.TEXT -> "${IconNames.annotate(text ?: "")}（字体=$font 字号=$size 对齐=0x${Integer.toHexString(flags)} 颜色=${Fmt.color(color)} 区域=${Fmt.rect(rect)}）"
        Kind.RECT -> "背景${Fmt.rect(rect)} 圆角=$radius 颜色=${Fmt.color(color)}"
        Kind.IMAGE -> "图片${Fmt.rect(rect)} $extra"
    }
}

/** 一个“键”：一块背景框 + 落在里面的文字和图片 */
class Key(val rect: Rect, val bg: DrawItem?, val labels: List<DrawItem>) {
    /** 是不是竖长符号栏里的一格（可以滚动） */
    val inColumn: Boolean get() = bg != null && bg.rect.height() >= bg.rect.width() * 3 / 2 && rect != bg.rect

    /** 主标签：字号最大的；一样大取离中心最近的 */
    val primary: DrawItem? = labels.filter { it.kind == DrawItem.Kind.TEXT }
        .maxWithOrNull(compareBy<DrawItem> { it.size }.thenBy { -dist(it) })

    private fun dist(it: DrawItem) = hypot(it.cx() - rect.exactCenterX(), it.cy() - rect.exactCenterY())

    /** 主标签的读法（不含角标） */
    fun mainName(): String {
        val p = primary ?: return if (labels.any { it.kind == DrawItem.Kind.IMAGE }) "图片按键" else ""
        return labelName(p)
    }

    fun speakable(): String {
        toggleTarget()?.let { return it }
        nineDigit()?.let { return it }
        val p = primary ?: return mainName()
        val main = labelName(p)
        val others = labels.filter { it !== p && it.kind == DrawItem.Kind.TEXT }
            .mapNotNull { o ->
                val n = labelName(o)
                // 主标签上方的小字是上滑输入的字符：读屏手势没法上滑输入，不读
                if (n.isBlank() || n == main || (o.cy() < p.cy() - 4 && o.size < p.size)) null else n
            }
        return if (others.isEmpty()) main else main + "，" + others.joinToString("，")
    }

    /** 九键拼音按数字读（固定开）：@# 读“符号”，ABC 读 2 …… WXYZ 读 9 */
    private fun nineDigit(): String? {
        if (!Config.b("nineDigits") || KeyboardState.boardName != "九键拼音") return null
        return when (primary?.text?.trim()?.uppercase()) {
            "@#" -> "符号"
            "ABC" -> "2"
            "DEF" -> "3"
            "GHI" -> "4"
            "JKL" -> "5"
            "MNO" -> "6"
            "PQRS" -> "7"
            "TUV" -> "8"
            "WXYZ" -> "9"
            else -> null
        }
    }

    /** 中英切换键：角上的小图标是点了之后去的键盘，读那个键盘的名字 */
    private fun toggleTarget(): String? {
        for (it in labels) {
            val t = it.text?.trim() ?: continue
            if (t.length != 1) continue
            val target = when (t[0].code) {
                0xe359 -> "英文键盘"
                0xe376 -> "双拼键盘"
                0xe39f -> "笔画键盘"
                0xe3a1 -> "五笔键盘"
                0xe385 -> "手写键盘"
                0xe358 -> KeyboardState.lastChinese.ifEmpty { "中文键盘" }
                else -> null
            }
            if (target != null) return target
        }
        return null
    }

    companion object {
        fun labelName(it: DrawItem): String {
            val t = it.text ?: return ""
            if (t.any { c -> Fmt.isPua(c) }) return IconNames.speakable(t)
            // 中文键盘的字母键画的是大写，读屏会读成“大写G”，改成小写
            val tt = t.trim()
            if (!JniHooks.isEnglish && tt.length in 1..4 && tt.all { it in 'A'..'Z' }) return tt.lowercase()
            return Names.spoken(t)
        }
    }
}

/**
 * 每个 KeyboardView 一张绘制项表。nativeDraw 前删掉与脏区域相交的旧项，回调里记录新项。
 * 只在主线程访问。
 */
class DrawTable(var windowName: String) {
    val items = ArrayList<DrawItem>()
    var width = 0
    var height = 0
    var frames = 0L
    var drawsOutsideOnDraw = 0L
    var changed = false
    var lastDumpText: String? = null
    val lastDirty = Rect()

    /** 本帧画过预览气泡，之后的绘制项都属于气泡 */
    var inPopup = false

    fun beginFrame(dirty: Rect) {
        frames++
        inPopup = false
        lastDirty.set(dirty)
        val before = items.size
        items.removeAll { Rect.intersects(it.rect, dirty) || it.rect.isEmpty && dirty.contains(it.rect.left, it.rect.top) }
        if (items.size != before) changed = true
    }

    fun add(item: DrawItem) {
        if (inPopup) item.popup = true
        items.add(item)
        changed = true
    }

    fun clear() {
        items.clear()
        changed = true
    }

    /** 把绘制项归并成键 */
    fun keys(): List<Key> {
        val viewArea = max(1, width) * max(1, height).toLong()
        // 太大的背景框（整块键盘背景等）不算键
        val live = items.filter { !it.popup }
        val boxes = live.filter {
            it.kind == DrawItem.Kind.RECT && (it.color ushr 24) != 0 && it.rect.width() >= 8 && it.rect.height() >= 8 &&
                it.rect.width().toLong() * it.rect.height() < viewArea * 0.4
        }
        val labelsOf = HashMap<DrawItem, MutableList<DrawItem>>()
        val orphans = ArrayList<DrawItem>()
        for (it in live) {
            if (it.kind == DrawItem.Kind.RECT) continue
            if (it.kind == DrawItem.Kind.TEXT && (it.text.isNullOrBlank())) continue
            val cx = it.cx().toInt()
            val cy = it.cy().toInt()
            val box = boxes.filter { b -> b.rect.contains(cx, cy) }
                .minByOrNull { b -> b.rect.width().toLong() * b.rect.height() }
            if (box == null) orphans.add(it) else labelsOf.getOrPut(box) { ArrayList() }.add(it)
        }
        val keys = ArrayList<Key>()
        // 同一位置可能叠画多层背景（阴影、按下态），只保留有标签的
        for ((box, all) in labelsOf) {
            val inside = all.filter { l ->
                val r = Rect(l.rect)
                val area = l.rect.width().toLong() * l.rect.height()
                area <= 0 || (r.intersect(box.rect) && r.width().toLong() * r.height() * 10 >= area * 6)
            }
            val labels = if (inside.isEmpty()) all else inside
            val split = splitStacked(box, labels)
            if (split != null) keys.addAll(split) else keys.add(Key(Rect(box.rect), box, labels))
        }
        // 竖长符号栏滚出去的文字：原生层画了但被裁掉，看不见，不能算键
        // 只算真正的符号栏：竖长、里面上下排着两个以上文字（键盘变高时普通按键也是竖长的，不能算）
        val columns = boxes.filter { b ->
            b.rect.height() >= b.rect.width() * 3 / 2 && (labelsOf[b]?.count { it.kind == DrawItem.Kind.TEXT } ?: 0) >= 2
        }
        for (o in orphans) {
            val clipped = columns.any { c ->
                val ov = minOf(o.rect.right, c.rect.right) - maxOf(o.rect.left, c.rect.left)
                ov * 2 > o.rect.width() && !c.rect.contains(o.cx().toInt(), o.cy().toInt())
            }
            if (!clipped) keys.add(Key(Rect(o.rect), null, listOf(o)))
        }
        keys.sortWith(compareBy<Key> { it.rect.top / 8 }.thenBy { it.rect.left })
        return keys
    }

    /**
     * 一块背景里排着好几个互不重叠、字号相近的文字（九键左边的符号栏），每个文字单独算一个键。
     * 角标和上滑字符跟主标签重叠或字号差很多，不会被拆开。
     */
    private fun splitStacked(box: DrawItem, labels: List<DrawItem>): List<Key>? {
        val texts = labels.filter { it.kind == DrawItem.Kind.TEXT }
        if (texts.size < 2 || texts.size != labels.size) return null
        val b = box.rect
        if (b.height() >= b.width() * 3 / 2) {
            // 竖长的一列（九键、数字键盘左边可滚动的符号栏）：互相重叠的文字算一组，每组一个键
            val visible = texts.filter { t ->
                val r = Rect(t.rect)
                r.intersect(b) && r.height() * 2 >= t.rect.height()
            }
            val groups = ArrayList<MutableList<DrawItem>>()
            for (t in visible.sortedBy { it.rect.top }) {
                val a = Rect(t.rect).apply { inset(2, 2) }
                val g = groups.firstOrNull { gr -> gr.any { Rect.intersects(a, Rect(it.rect).apply { inset(2, 2) }) } }
                if (g != null) g.add(t) else groups.add(mutableListOf(t))
            }
            if (groups.size < 2) return null
            return groups.map { g ->
                val r = Rect(g[0].rect)
                for (x in g) r.union(x.rect)
                r.intersect(b)
                Key(r, box, g)
            }
        }
        val maxSize = texts.maxOf { it.size }
        val minSize = texts.minOf { it.size }
        if (maxSize <= 0 || minSize < maxSize * 0.8) return null
        for (i in texts.indices) for (j in i + 1 until texts.size) {
            val a = Rect(texts[i].rect).apply { inset(2, 2) }
            val c = Rect(texts[j].rect).apply { inset(2, 2) }
            if (Rect.intersects(a, c)) return null
        }
        return texts.map { t ->
            val r = Rect(t.rect)
            if (!r.intersect(box.rect)) r.set(t.rect)
            Key(r, box, listOf(t))
        }
    }

    /** 按坐标找键：先找包含该点的，再找一定距离内最近的 */
    fun keyAt(x: Float, y: Float, slop: Float): Key? {
        val ks = keys()
        val inside = ks.filter { it.rect.contains(x.toInt(), y.toInt()) }
            .minByOrNull { it.rect.width().toLong() * it.rect.height() }
        if (inside != null) return inside
        return ks.map { it to rectDist(it.rect, x, y) }
            .filter { it.second <= slop }
            .minByOrNull { it.second }?.first
    }

    /**
     * maxDist 以内最近的、有名字的键和它的距离，没有就返回 null。
     * 给落指死区兜底用：手指落在键盘视图外面时 keyAt 根本不会被问到。距离一样时取小键。
     */
    fun nearest(x: Float, y: Float, maxDist: Float): Pair<Key, Float>? =
        keys().filter { it.speakable().isNotBlank() }
            .map { it to rectDist(it.rect, x, y) }
            .filter { it.second <= maxDist }
            .minWithOrNull(compareBy<Pair<Key, Float>> { it.second }
                .thenBy { it.first.rect.width().toLong() * it.first.rect.height() })

    private fun rectDist(r: Rect, x: Float, y: Float): Float {
        val dx = max(0f, max(r.left - x, x - r.right))
        val dy = max(0f, max(r.top - y, y - r.bottom))
        return hypot(dx, dy)
    }

    fun buildDump(): String {
        val sb = StringBuilder()
        val ks = keys()
        sb.append("窗口=").append(windowName).append(" 尺寸=").append(width).append('x').append(height)
            .append(" 绘制项=").append(items.size).append(" 键=").append(ks.size).append('\n')
        var i = 0
        for (k in ks) {
            i++
            sb.append("  键").append(i).append(' ').append(Fmt.rect(k.rect))
            if (k.bg != null) sb.append(" 圆角=").append(k.bg.radius)
            sb.append(" 朗读=「").append(k.speakable()).append("」\n")
            for (l in k.labels) {
                sb.append("      ").append(if (l === k.primary) "主 " else "副 ").append(l.describe()).append('\n')
            }
        }
        return sb.toString()
    }
}
