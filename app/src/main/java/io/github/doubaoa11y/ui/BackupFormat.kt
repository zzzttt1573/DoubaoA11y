package io.github.doubaoa11y.ui

import io.github.doubaoa11y.ConfigSpec
import org.json.JSONObject
import org.json.JSONTokener
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** 只接受配置白名单；导入全部验证通过之后，调用者才能写入。 */
object BackupFormat {
    const val MAX_BYTES = 1024 * 1024
    private const val FORMAT = "DoubaoA11y-settings"
    private val bools get() = ConfigSpec.items.filter {
        it.group !in setOf(ConfigSpec.Group.LOG, ConfigSpec.Group.DEBUG, ConfigSpec.Group.FIXED)
    }
    data class Data(val settings: Map<String, Any>, val words: String?, val showLauncher: Boolean?)

    fun encode(settings: Map<String, Any>, words: String, showLauncher: Boolean): ByteArray {
        val json = JSONObject().put("format", FORMAT).put("version", 1)
            .put("settings", JSONObject(settings)).put("customWords", words).put("showLauncher", showLauncher)
        val bytes = json.toString(2).toByteArray(Charsets.UTF_8)
        parse(bytes) // 导出的内容也遵守同一份上限与格式约束。
        return bytes
    }

    fun parse(bytes: ByteArray): Data {
        require(bytes.size <= MAX_BYTES) { "备份文件超过 1 MB" }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        val tokener = JSONTokener(text)
        val root = tokener.nextValue() as? JSONObject ?: error("不是配置备份文件")
        require(tokener.nextClean() == '\u0000') { "备份文件含多余内容" }
        require(root.opt("format") == FORMAT && root.opt("version") == 1) { "备份格式或版本不支持" }
        val raw = root.getJSONObject("settings")
        val out = LinkedHashMap<String, Any>()
        val booleanKeys = bools.map { it.key }.toSet()
        for (key in raw.keys()) {
            val value = raw.get(key)
            when {
                key in setOf("nativeEnter", "readerDirectControl", "fsHaptic", "fsCandidateFirst", "fsSpeakAction", "fsSpeakChar") -> require(value is Boolean)
                key == "typeMode" && value == 2 -> {
                    out[key] = ConfigSpec.TYPE_LIFT
                    if (!raw.has("upperOperation")) out["upperOperation"] = true
                }
                key == "fsDragMs" && value == 150 -> out[key] = 200
                key in booleanKeys -> { require(value is Boolean) { "开关格式错误：$key" }; out[key] = value }
                ConfigSpec.intDefaults.containsKey(key) -> {
                    val item = ConfigSpec.intItems.first { it.key == key }
                    require(value is Int || value is Long) { "整数格式错误：$key" }
                    val n = (value as Number).toLong()
                    require(item.options.any { it.first.toLong() == n }) { "设置超出可选范围：$key" }
                    out[key] = n.toInt()
                }
                ConfigSpec.strDefaults.containsKey(key) -> {
                    require(value is String && value.length <= 16000) { "文本格式错误或过长：$key" }
                    if (key == ConfigSpec.EDIT_OPTIONS_KEY && value.isNotBlank() && value != "[]") {
                        val ids = value.split(',').map { it.trim().toIntOrNull() }
                        require(ids.all { it != null } && ids == ConfigSpec.parseEditOptions(value)) { "编辑选项无效" }
                    }
                    out[key] = value
                }
                else -> error("不支持的设置：$key")
            }
        }
        val words = if (root.has("customWords")) root.get("customWords").also {
            require(it is String && it.length <= 200000) { "自定义解释词格式错误或过长" }
        } as String else null
        if (words != null) WordTransferFormat.parseText(words)
        val show = if (root.has("showLauncher")) root.get("showLauncher").also {
            require(it is Boolean) { "桌面图标状态格式错误" }
        } as Boolean else null
        return Data(out, words, show)
    }

    fun exportableBooleans() = bools
}
