package io.github.doubaoa11y.hook

import android.view.View
import android.widget.TextView
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

/**
 * 语音长按区域读取适配器（从 VirtualKeys 剥离）：
 * - 兼容豆包 1.5.4–1.5.6（r/s）与 1.5.7+（s/t）布尔字段映射；
 * - 优先结合宿主资源（如 1.5.7 AsrNotchedEllipseView.u:int 发送文案资源 ID）与按钮实时文案，
 *   配合 asr_long_press_text 动态区分普通模式与“说完即发”模式；
 * - 触摸释放与 Voice.discard() 主链仍留在 VirtualKeys。
 */
internal object VoiceZoneReader {
    data class Reading(
        val zone: String,
        val centerText: String,
        val debugInfo: () -> String
    )

    private class VoiceFields(type: Class<*>) {
        val flags = listOf("r", "s", "t").associateWith { name ->
            XposedHelpers.findFieldIfExists(type, name)?.takeIf { it.type == Boolean::class.javaPrimitiveType }
        }
        val sendResId: Field? = XposedHelpers.findFieldIfExists(type, "u")
            ?.takeIf { it.type == Int::class.javaPrimitiveType }
            ?.apply { isAccessible = true }
        val strings = accessibleFields(type, String::class.java)
        val booleans = accessibleFields(type, Boolean::class.javaPrimitiveType!!)

        private fun accessibleFields(type: Class<*>, fieldType: Class<*>) = try {
            type.declaredFields.filter { it.type == fieldType }.onEach { it.isAccessible = true }
        } catch (t: Throwable) {
            L.hookError("VoiceZoneReader.fields ${type.name} ${fieldType.name}", t)
            emptyList<Field>()
        }
    }

    private val fieldCache = ConcurrentHashMap<Class<*>, VoiceFields>()
    private fun fieldsOf(o: Any) = fieldCache.computeIfAbsent(o.javaClass) { VoiceFields(it) }

    private var cachedPkg: String? = null
    private var actionBtnId = 0
    private var longPressTextId = 0
    private var retractTipId = 0
    private var lastAsrRef: java.lang.ref.WeakReference<View>? = null
    private var lastBtnRef: java.lang.ref.WeakReference<View>? = null
    private var lastTextViewRef: java.lang.ref.WeakReference<TextView>? = null

    private fun ensureIds(asr: View, pkg: String) {
        if (cachedPkg == pkg && (actionBtnId != 0 || longPressTextId != 0)) return
        val res = asr.resources
        cachedPkg = pkg
        actionBtnId = res.getIdentifier("asr_action_btn", "id", pkg)
        longPressTextId = res.getIdentifier("asr_long_press_text", "id", pkg)
        retractTipId = res.getIdentifier("asr_long_press_retract_tip", "string", pkg)
    }

    @JvmStatic
    fun isCancelZone(zone: String): Boolean = zone.contains("撤回") || zone.contains("取消")

    @JvmStatic
    fun resolveZone(
        left: Boolean,
        right: Boolean,
        centerText: String,
        leftLabel: String?,
        rightLabel: String?
    ): String {
        val leftFinal = leftLabel?.takeIf { it.isNotEmpty() } ?: "松手撤回输入"
        val rightFinal = rightLabel?.takeIf { it.isNotEmpty() } ?: if (centerText.contains("发送")) {
            "松手仅上屏"
        } else {
            "松手一键发送"
        }
        val centerFinal = centerText.ifEmpty { "松手结束" }
        return when {
            left -> leftFinal
            right -> rightFinal
            else -> centerFinal
        }
    }

    fun read(kb: View): Reading? {
        val asr = try {
            XposedHelpers.getObjectField(kb, "mAsrLongPressView") as? View
        } catch (_: Throwable) {
            null
        } ?: return null
        if (asr.visibility != View.VISIBLE) return null
        val pkg = asr.context.packageName
        ensureIds(asr, pkg)
        val sameAsr = lastAsrRef?.get() === asr
        val btn = if (sameAsr) {
            lastBtnRef?.get()?.takeIf { it.isAttachedToWindow }
                ?: if (actionBtnId != 0) asr.findViewById<View>(actionBtnId).also { lastBtnRef = it?.let { v -> java.lang.ref.WeakReference(v) } } else null
        } else {
            lastAsrRef = java.lang.ref.WeakReference(asr)
            val foundBtn = if (actionBtnId != 0) asr.findViewById<View>(actionBtnId) else null
            lastBtnRef = foundBtn?.let { java.lang.ref.WeakReference(it) }
            lastTextViewRef = null
            foundBtn
        }
        val (left, right) = if (btn != null) {
            val tVal = boolField(btn, "t")
            val sVal = boolField(btn, "s")
            val rVal = boolField(btn, "r")
            if (tVal != null && sVal != null) {
                // 1.5.7+：s = 左（撤回），t = 右（发送 / 仅上屏）
                sVal to tVal
            } else {
                // 1.5.4~1.5.6：r = 左（撤回），s = 右（发送）
                (rVal ?: false) to (sVal ?: false)
            }
        } else {
            false to false
        }
        val textView = lastTextViewRef?.get()?.takeIf { sameAsr && it.isAttachedToWindow }
            ?: if (longPressTextId != 0) {
                (asr.findViewById<View>(longPressTextId) as? TextView).also { lastTextViewRef = it?.let { tv -> java.lang.ref.WeakReference(tv) } }
            } else null
        val centerText = textView?.text?.toString()?.trim().orEmpty()
        val btnTexts = btn?.let { readBtnTexts(it) }
        val resRight = btn?.let { readSendResourceLabel(asr, it) }
        val leftLabel = btnTexts?.first?.takeIf { it.isNotEmpty() }
            ?: readHostTip(asr)
        val rightLabel = btnTexts?.second?.takeIf { it.isNotEmpty() } ?: resRight
        val zone = resolveZone(left, right, centerText, leftLabel, rightLabel)
        return Reading(zone, centerText) {
            val dbg = btn?.let { dumpBoolFields(it) }.orEmpty()
            val txtDbg = btn?.let { dumpStringFields(it) }.orEmpty()
            "语音区域：$zone（中=$centerText${if (dbg.isNotEmpty()) "，$dbg" else ""}${if (txtDbg.isNotEmpty()) "，$txtDbg" else ""}）"
        }
    }

    /** 豆包 1.5.7 AsrNotchedEllipseView.u 保存当前右侧动作的资源 ID（如“发送”或“仅上屏/转文字”） */
    private fun readSendResourceLabel(asr: View, btn: Any): String? = try {
        val resId = fieldsOf(btn).sendResId?.getInt(btn) ?: 0
        if (resId == 0) null
        else asr.resources.getString(resId).trim().takeIf { it.isNotEmpty() }?.let { raw ->
            if (raw.startsWith("松手")) raw else "松手$raw"
        }
    } catch (_: Throwable) {
        null
    }

    private fun readHostTip(asr: View): String? = try {
        val id = retractTipId
        if (id == 0) null else asr.resources.getString(id).trim().takeIf { it.isNotEmpty() }
    } catch (_: Throwable) {
        null
    }

    private fun readBtnTexts(btn: Any): Pair<String, String>? = try {
        val texts = fieldsOf(btn).strings
            .mapNotNull { f ->
                (f.get(btn) as? String)?.trim()?.takeIf { it.length in 2..16 && it.any { ch -> ch in '\u4e00'..'\u9fff' } }
            }
            .distinct()
        if (texts.isEmpty()) null else {
            val cancelText = texts.firstOrNull { isCancelZone(it) }.orEmpty()
            val rightText = texts.firstOrNull {
                it != cancelText && (it.contains("发送") || it.contains("上屏") || it.contains("文字") || it.contains("输入"))
            }.orEmpty()
            if (cancelText.isEmpty() && rightText.isEmpty()) null else cancelText to rightText
        }
    } catch (_: Throwable) {
        null
    }

    private fun dumpStringFields(o: Any): String = try {
        fieldsOf(o).strings
            .mapNotNull { f ->
                val v = (f.get(o) as? String)?.trim()
                if (v.isNullOrEmpty()) null else "${f.name}=$v"
            }
            .joinToString(",")
    } catch (_: Throwable) {
        ""
    }

    private fun dumpBoolFields(o: Any): String = try {
        fieldsOf(o).booleans.joinToString(",") { f -> "${f.name}=${f.getBoolean(o)}" }
    } catch (_: Throwable) {
        ""
    }

    private fun boolField(o: Any, name: String): Boolean? = try {
        fieldsOf(o).flags[name]?.getBoolean(o)
    } catch (_: Throwable) {
        null
    }
}
