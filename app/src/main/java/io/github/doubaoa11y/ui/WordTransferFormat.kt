package io.github.doubaoa11y.ui

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** 原词条格式：单字 TAB 解释词。纯格式层，不读取词库、偏好或读屏数据。 */
object WordTransferFormat {
    const val MAX_BYTES = 1024 * 1024
    const val MAX_CHARS = 200000
    private const val BOM = "\uFEFF"

    fun isHanCodePoint(cp: Int) = io.github.doubaoa11y.Han.isHanCodePoint(cp)
    fun isSingleHan(key: String) = io.github.doubaoa11y.Han.isSingleHan(key)

    fun read(input: InputStream): Map<String, String> {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            require(out.size() + n <= MAX_BYTES) { "解释词文件超过 1 MB" }
            out.write(buffer, 0, n)
        }
        return parse(out.toByteArray())
    }

    fun parse(bytes: ByteArray): Map<String, String> {
        require(bytes.size <= MAX_BYTES) { "解释词文件超过 1 MB" }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix(BOM)
        return parseText(text)
    }

    fun parseText(text: String): Map<String, String> {
        require(text.length <= MAX_CHARS) { "解释词内容超过 200000 字符" }
        val result = LinkedHashMap<String, String>()
        if (text.isEmpty()) return result
        val lines = text.split('\n')
        for ((i, raw) in lines.withIndex()) {
            val line = raw.removeSuffix("\r")
            if (line.isEmpty()) continue
            val tab = line.indexOf('\t')
            require(tab in 1..2 && tab < line.lastIndex) { "第 ${i + 1} 行应为单个汉字、制表符、解释词" }
            val key = line.substring(0, tab)
            val value = line.substring(tab + 1)
            validate(key, value, i + 1)
            require(!result.containsKey(key)) { "第 ${i + 1} 行的字重复" }
            result[key] = value
        }
        return result
    }

    /** 偏好继续使用原来的无 BOM、LF 分隔格式；导出文件单独添加 UTF-8 BOM。 */
    fun serialize(words: Map<String, String>): String {
        for ((c, value) in words) validate(c, value, null)
        val text = words.entries.joinToString("\n") { "${it.key}\t${it.value}" }
        require(text.length <= MAX_CHARS) { "解释词内容超过 200000 字符" }
        require((BOM + text).toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "解释词文件超过 1 MB" }
        return text
    }

    fun encode(words: Map<String, String>): ByteArray = (BOM + serialize(words)).toByteArray(Charsets.UTF_8)

    data class Change(val text: String, val total: Int, val overwritten: Int, val removed: Int)

    fun plan(current: Map<String, String>, imported: Map<String, String>, replace: Boolean): Change {
        val out = LinkedHashMap<String, String>()
        if (!replace) out.putAll(current)
        out.putAll(imported)
        return Change(serialize(out), out.size, imported.keys.count { it in current },
            if (replace) current.keys.count { it !in imported } else 0)
    }

    private fun validate(key: String, value: String, line: Int?) {
        val prefix = if (line == null) "解释词条目" else "第 $line 行"
        require(isSingleHan(key)) { "$prefix 必须使用一个汉字（支持生僻字与扩展区汉字）" }
        require(value.length in 1..256 && value.isNotBlank()) { "$prefix 的解释词须为 1 到 256 个字符且非空白" }
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (Character.isHighSurrogate(ch)) {
                require(i + 1 < value.length && Character.isLowSurrogate(value[i + 1])) { "$prefix 含无效字符" }
                i += 2
                continue
            }
            require(!Character.isLowSurrogate(ch) && !Character.isISOControl(ch) &&
                Character.getType(ch) !in setOf(Character.FORMAT.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt())) {
                "$prefix 的解释词不能含制表符、换行或控制符"
            }
            i++
        }
    }
}
