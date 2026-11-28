package io.github.doubaoa11y.hook

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 豆包 1.5.7 原生无障碍增强钩子（全部独立异常保护，非 1.5.7 或类不存在时静默降级）：
 * 1. 智能整理失败/降级原因（SmartOrganizeFallbackReason）即时播报，避免盲等 15 秒；
 * 2. 翻译栏开关与手写找字更多候选展开/收起状态播报；
 * 3. AI 写作（AiTextView）流式渲染去抖后播报完成摘要，避免逐帧刷屏；
 * 4. 图片/GIF 表情面板（ImeEmoticonsAdapter）在宿主缺标签时补全类型描述。
 */
internal object Doubao157Hooks {
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val aiToken = Any()
    private var lastTranslateVisible = false

    fun install(cl: ClassLoader) {
        hookSmartOrganizeFallback(cl)
        hookTranslateVisibility(cl)
        hookHandwritingLookup(cl)
        hookAiTextView(cl)
        hookEmoticonsAdapter(cl)
    }

    @JvmStatic
    fun fallbackMessage(reasonName: String): String? = when (reasonName) {
        "NO_REWRITE" -> "文本无需整理"
        "FIRST_EVENT_TIMEOUT", "STREAM_TIMEOUT", "TIMEOUT" -> "智能整理超时，请稍后再试"
        "CANCELED", "CANCELLED" -> null
        else -> "智能整理未能完成"
    }

    private fun hookSmartOrganizeFallback(cl: ClassLoader) {
        val reasonCls = Hooker.findClass("com.bytedance.android.input.smart_organize.SmartOrganizeFallbackReason", cl) ?: return
        val consumerCls = Hooker.findClass("com.bytedance.android.input.smart_organize.l3", cl) ?: return
        try {
            val m = consumerCls.declaredMethods.firstOrNull { method ->
                val pts = method.parameterTypes
                pts.size == 1 && pts[0] == reasonCls
            } ?: return
            XposedBridge.hookMethod(m, SafeHook("Doubao157.organizeFallback", after = { p ->
                if (!A11yState.recording) return@SafeHook
                val reason = p.args.getOrNull(0)?.toString()?.trim().orEmpty()
                if (reason.isEmpty()) return@SafeHook
                KeyboardOps.onOrganizeFallback(reason, fallbackMessage(reason))
            }))
            L.i("Doubao157Hooks：已挂接智能整理回退原因（${consumerCls.simpleName}.${m.name}）")
        } catch (t: Throwable) {
            L.hookError("Doubao157Hooks.organizeFallback", t)
        }
    }

    private fun hookTranslateVisibility(cl: ClassLoader) {
        val cls = Hooker.findClass("com.bytedance.android.input.keyboard.InputViewRoot", cl) ?: return
        Hooker.method(cls, "setTranslateViewVisibility", arrayOf(Int::class.javaPrimitiveType!!),
            SafeHook("Doubao157.translateVis", after = { p ->
                if (!A11yState.recording || !VirtualKeys.enabled) return@SafeHook
                val visible = (p.args.getOrNull(0) as? Int) == View.VISIBLE
                if (visible == lastTranslateVisible) return@SafeHook
                lastTranslateVisible = visible
                if (visible) Speaker.speak("翻译栏已打开")
            }))
    }

    private fun hookHandwritingLookup(cl: ClassLoader) {
        val cls = Hooker.findClass("com.bytedance.android.input.keyboard.handwriting.HandWritingLookupBoardView", cl) ?: return
        Hooker.method(cls, "setMoreCandExpanded", arrayOf(Boolean::class.javaPrimitiveType!!),
            SafeHook("Doubao157.hwMoreCand", after = { p ->
                if (!A11yState.recording || !VirtualKeys.enabled) return@SafeHook
                val expanded = p.args.getOrNull(0) as? Boolean ?: return@SafeHook
                Speaker.speak(if (expanded) "已展开更多候选" else "已收起更多候选")
            }))
    }

    private fun hookAiTextView(cl: ClassLoader) {
        val cls = Hooker.findClass("com.bytedance.android.input.keyboard.aiwrite.AiTextView", cl) ?: return
        val hook = SafeHook("Doubao157.aiText", after = { p ->
            if (!A11yState.recording || !VirtualKeys.enabled) return@SafeHook
            val v = p.thisObject as? View ?: return@SafeHook
            val text = p.args.getOrNull(0)?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return@SafeHook
            main.removeCallbacksAndMessages(aiToken)
            main.postAtTime({
                try {
                    if (!v.isShown) return@postAtTime
                    v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    if (v.contentDescription.isNullOrBlank()) v.contentDescription = text
                    L.i("AI 写作结果就绪（${text.length} 字）")
                } catch (t: Throwable) {
                    L.hookError("Doubao157.aiText.post", t)
                }
            }, aiToken, android.os.SystemClock.uptimeMillis() + 350L)
        })
        Hooker.method(cls, "setFormatText", arrayOf(String::class.java), hook)
        Hooker.method(cls, "setShowText", arrayOf(CharSequence::class.java), hook)
    }

    private fun hookEmoticonsAdapter(cl: ClassLoader) {
        val cls = Hooker.findClass("com.bytedance.android.input.keyboard.emoji.emoticons.ImeEmoticonsAdapter", cl)
            ?: Hooker.findClass("com.bytedance.android.input.emoticons.ImeEmoticonsAdapter", cl)
            ?: return
        try {
            val m = cls.declaredMethods.firstOrNull { it.name == "onBindViewHolder" && it.parameterTypes.size == 2 } ?: return
            XposedBridge.hookMethod(m, SafeHook("Doubao157.emoticonsBind", after = { p ->
                if (!A11yState.recording || !Config.b("labelViews")) return@SafeHook
                val holder = p.args.getOrNull(0) ?: return@SafeHook
                val itemView = try {
                    XposedHelpers.getObjectField(holder, "itemView") as? View
                } catch (_: Throwable) {
                    null
                } ?: return@SafeHook
                if (!itemView.contentDescription.isNullOrBlank() || hasReadableText(itemView)) return@SafeHook
                val holderName = holder.javaClass.simpleName.lowercase()
                if (holderName.contains("empty")) return@SafeHook
                itemView.contentDescription = when {
                    holderName.contains("gif") -> "动态表情"
                    else -> "图片表情"
                }
            }))
        } catch (t: Throwable) {
            L.hookError("Doubao157Hooks.emoticons", t)
        }
    }

    private fun hasReadableText(v: View): Boolean {
        if (v is TextView && !v.text.isNullOrBlank()) return true
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                if (hasReadableText(v.getChildAt(i))) return true
            }
        }
        return false
    }
}
