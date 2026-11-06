package io.github.doubaoa11y.hook

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import java.util.Collections

/**
 * 输入法写到编辑框的所有动作（InputConnection）。
 * 手写和语音上屏不走 KeyboardJni.DoCommit，只能在这里看到。
 * InputConnection 是接口，具体类要等拿到实例才知道，所以在 getCurrentInputConnection 返回时按类补 hook。
 */
object InputHooks {
    private val hookedClasses = Collections.synchronizedSet(HashSet<Class<*>>())
    private val I = Int::class.javaPrimitiveType!!

    /** 上屏监听（后面阶段用来朗读上屏内容） */
    private val commitListeners = ArrayList<(String, String) -> Unit>()

    fun onCommit(l: (text: String, source: String) -> Unit) {
        commitListeners.add(l)
    }

    private val composingListeners = ArrayList<(String) -> Unit>()
    private val finishListeners = ArrayList<() -> Unit>()

    fun onComposing(l: (String) -> Unit) {
        composingListeners.add(l)
    }

    fun onFinishComposing(l: () -> Unit) {
        finishListeners.add(l)
    }

    fun install() {
        Hooker.method(InputMethodService::class.java, "getCurrentInputConnection", arrayOf(),
            SafeHook("getCurrentInputConnection", after = { p ->
                val ic = p.result as? InputConnection ?: return@SafeHook
                hookClass(ic.javaClass)
            }))
    }

    private fun hookClass(c: Class<*>) {
        if (!hookedClasses.add(c)) return
        L.i("输入连接类 ${c.name}，开始记录上屏")
        val cs = CharSequence::class.java
        hook(c, "commitText", arrayOf(cs, I)) { a -> "上屏 commitText 「${Fmt.esc(a[0]?.toString())}」 光标=${a[1]}" }
        hook(c, "setComposingText", arrayOf(cs, I)) { a -> "组字 setComposingText 「${Fmt.esc(a[0]?.toString())}」" }
        hook(c, "finishComposingText", arrayOf()) { _ -> "结束组字 finishComposingText" }
        hook(c, "deleteSurroundingText", arrayOf(I, I)) { a -> "删除 deleteSurroundingText 前=${a[0]} 后=${a[1]}" }
        hook(c, "sendKeyEvent", arrayOf(KeyEvent::class.java)) { a ->
            val e = a[0] as? KeyEvent
            "按键事件 sendKeyEvent ${if (e?.action == KeyEvent.ACTION_DOWN) "按下" else "抬起"} ${e?.let { KeyEvent.keyCodeToString(it.keyCode) }}"
        }
        hook(c, "performEditorAction", arrayOf(I)) { a -> "编辑器动作 performEditorAction ${a[0]}" }
        hook(c, "setSelection", arrayOf(I, I)) { a -> "选择 setSelection ${a[0]}-${a[1]}" }
        hook(c, "setComposingRegion", arrayOf(I, I)) { a -> "组字区域 setComposingRegion ${a[0]}-${a[1]}" }
    }

    private val hookedMethods = Collections.synchronizedSet(HashSet<java.lang.reflect.Method>())

    private fun hook(c: Class<*>, name: String, params: Array<Class<*>>, msg: (Array<Any?>) -> String) {
        // 用 getMethod：具体类可能只继承了实现（InputConnectionWrapper 之类）
        val m = try {
            c.getMethod(name, *params)
        } catch (t: Throwable) {
            L.w("输入连接没有 $name：${c.name}")
            return
        }
        if (!hookedMethods.add(m)) return
        Hooker.hookMember(m, SafeHook("IC.$name", before = { p ->
            if (!A11yState.recording) return@SafeHook
            if (Config.b("logJni")) L.i(msg(p.args) + " 来源=" + caller())
            try {
                if (name == "setComposingText") {
                    val t = p.args[0]?.toString() ?: ""
                    for (l in composingListeners) l(t)
                }
                if (name == "finishComposingText") for (l in finishListeners) l()
            } catch (t: Throwable) {
                L.hookError("IC.listeners", t)
            }
            // 语音输入时先不把识别中间结果写进编辑框，说完再一起上屏
            try {
                val ic = p.thisObject as? InputConnection
                if (ic != null && Voice.intercept(name, ic, p.args)) {
                    p.result = true
                    return@SafeHook
                }
            } catch (t: Throwable) {
                L.hookError("Voice.intercept", t)
            }
            if (name == "commitText") {
                val text = p.args[0]?.toString() ?: return@SafeHook
                val src = caller()
                for (l in commitListeners) {
                    try {
                        l(text, src)
                    } catch (t: Throwable) {
                        L.hookError("onCommit", t)
                    }
                }
            }
        }))
    }

    /** 调用栈里豆包自己的前几层，用来区分键盘、手写、语音上屏 */
    private fun caller(): String {
        val all = Throwable().stackTrace
        // 跳过 LSPosed / LSPatch 自己的调用层
        val last = all.indexOfLast { e ->
            val n = e.className
            n.contains("Hooker") || n.contains("LegacyDelegate") || n.contains("XposedBridge") || n.contains("LSPHooker")
        }
        val st = if (last >= 0) all.copyOfRange(last + 1, all.size) else all
        val out = ArrayList<String>()
        for (e in st) {
            val cn = e.className
            if (cn.startsWith("io.github.doubaoa11y") || cn.startsWith("de.robv") || cn.startsWith("android.") ||
                cn.startsWith("java.") || cn.startsWith("com.android.") || cn.startsWith("LSP") || cn.startsWith("org.lsposed") ||
                cn.startsWith("J.") || cn.startsWith("dalvik.")) continue
            out.add(cn.substringAfterLast('.') + "." + e.methodName)
            if (out.size >= 3) break
        }
        return if (out.isEmpty()) "?" else out.joinToString(" < ")
    }
}
