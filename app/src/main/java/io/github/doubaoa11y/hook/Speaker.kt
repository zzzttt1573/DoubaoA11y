package io.github.doubaoa11y.hook

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager

/**
 * 朗读：不用 announceForAccessibility，而是发 TYPE_VIEW_HOVER_ENTER 事件，
 * 文字放在 contentDescription 里；打断时发一个空的 TYPE_VIEW_HOVER_EXIT。
 * 国产读屏对这种事件兼容性更好。
 */
object Speaker {
    private var am: AccessibilityManager? = null
    private var pkg: String = ""

    fun init(ctx: Context) {
        try {
            am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            pkg = ctx.packageName
        } catch (t: Throwable) {
            L.e("初始化朗读失败", t)
        }
    }

    private fun newEvent(type: Int): AccessibilityEvent =
        if (Build.VERSION.SDK_INT >= 30) AccessibilityEvent(type)
        else @Suppress("DEPRECATION") AccessibilityEvent.obtain(type)

    fun speak(text: String) {
        if (text.isBlank()) return
        val m = am ?: return
        try {
            if (!m.isEnabled) return
            val ev = newEvent(AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)
            ev.packageName = pkg
            ev.className = "android.inputmethodservice.Keyboard\$Key"
            ev.contentDescription = text
            ev.eventTime = SystemClock.uptimeMillis()
            m.sendAccessibilityEvent(ev)
        } catch (t: Throwable) {
            L.hookError("Speaker.speak", t)
        }
    }

    /** 打断当前朗读 */
    fun stop() {
        val m = am ?: return
        try {
            if (!m.isEnabled) return
            val ev = newEvent(AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
            ev.packageName = pkg
            ev.className = "android.inputmethodservice.Keyboard\$Key"
            ev.eventTime = SystemClock.uptimeMillis()
            m.sendAccessibilityEvent(ev)
        } catch (t: Throwable) {
            L.hookError("Speaker.stop", t)
        }
    }
}
