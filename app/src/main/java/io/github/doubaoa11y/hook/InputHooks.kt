package io.github.doubaoa11y.hook

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
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

    private val main = Handler(Looper.getMainLooper())

    /**
     * 删字之后编辑框可能空了：隔一会儿问一次，好把编辑选项换回“粘贴 / 收起键盘”。
     * 删除是逐次触发的（长按连删会连着来），所以合并一下。
     */
    private val emptyCheck = Runnable { EditOptions.refresh("删字后") }

    fun noteDeleted() {
        main.removeCallbacks(emptyCheck)
        main.postDelayed(emptyCheck, 90)
    }

    /** 上屏监听（后面阶段用来朗读上屏内容） */
    private val commitListeners = ArrayList<() -> Unit>()

    fun onCommit(l: () -> Unit) {
        commitListeners.add(l)
    }

    private val composingListeners = ArrayList<(String) -> Unit>()
    private val finishListeners = ArrayList<() -> Unit>()
    private val deleteListeners = ArrayList<() -> Unit>()

    fun onComposing(l: (String) -> Unit) {
        composingListeners.add(l)
    }

    fun onFinishComposing(l: () -> Unit) {
        finishListeners.add(l)
    }

    /** 删字（deleteSurroundingText，或者发了个删除键）：候选栏要变了 */
    fun onDelete(l: () -> Unit) {
        deleteListeners.add(l)
    }

    fun install() {
        // 键盘上直接按删除键（豆包原生删除，非手势）：选择态下删掉选中文本就自动退出选择态
        onDelete { EditActions.onExternalDelete() }
        Hooker.method(InputMethodService::class.java, "getCurrentInputConnection", arrayOf(),
            SafeHook("getCurrentInputConnection", after = { p ->
                val ic = p.result as? InputConnection ?: return@SafeHook
                hookClass(ic.javaClass)
            }))
    }

    /**
     * 豆包键盘里自带编辑框的输入连接：超级联想的指令框打开时，打字和语音写给它，
     * 不经过 getCurrentInputConnection，要按类名单独 hook（说完再上屏才管得到）。
     */
    private val innerConnections = listOf(
        "com.bytedance.android.input.keyboard.superassociation.SuperAssociationEditText\$b",
    )

    fun installInner(cl: ClassLoader) {
        for (name in innerConnections) {
            try {
                Hooker.findClassQuiet(name, cl)?.let { hookClass(it) } ?: L.w("找不到键盘内编辑框的输入连接：$name")
            } catch (t: Throwable) {
                L.hookError("InputHooks.installInner", t)
            }
        }
    }

    private fun hookClass(c: Class<*>) {
        if (!hookedClasses.add(c)) return
        L.i("输入连接类 ${c.name}，开始记录上屏")
        val cs = CharSequence::class.java
        hook(c, "commitText", arrayOf(cs, I)) { a -> "上屏 commitText 「${Fmt.esc(a[0]?.toString())}」 光标=${a[1]}" }
        hook(c, "setComposingText", arrayOf(cs, I)) { a -> "组字 setComposingText 「${Fmt.esc(a[0]?.toString())}」" }
        hook(c, "finishComposingText", arrayOf()) { _ -> "结束组字 finishComposingText" }
        hook(c, "deleteSurroundingText", arrayOf(I, I)) { a -> "删除 deleteSurroundingText 前=${a[0]} 后=${a[1]}" }
        hook(c, "deleteSurroundingTextInCodePoints", arrayOf(I, I)) { a -> "删除代码点 前=${a[0]} 后=${a[1]}" }
        hook(c, "sendKeyEvent", arrayOf(KeyEvent::class.java)) { a ->
            val e = a[0] as? KeyEvent
            "按键事件 sendKeyEvent ${if (e?.action == KeyEvent.ACTION_DOWN) "按下" else "抬起"} ${e?.let { KeyEvent.keyCodeToString(it.keyCode) }}"
        }
        hook(c, "performEditorAction", arrayOf(I)) { a -> "编辑器动作 performEditorAction ${a[0]}" }
        hook(c, "setSelection", arrayOf(I, I)) { a -> "选择 setSelection ${a[0]}-${a[1]}" }
        hook(c, "setComposingRegion", arrayOf(I, I)) { a -> "组字区域 setComposingRegion ${a[0]}-${a[1]}" }
    }

    private val mutationDepth = ThreadLocal<MutableMap<String, Int>>()
    private const val ENTERED = "doubao.echo.entered"
    private const val ROOT = "doubao.echo.root"
    private const val SNAPSHOT = "doubao.echo.snapshot"
    private const val INTERCEPTED = "doubao.echo.intercepted"

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
            val depths = mutationDepth.get() ?: HashMap<String, Int>().also { mutationDepth.set(it) }
            val depth = depths[name] ?: 0
            depths[name] = depth + 1
            p.setObjectExtra(ENTERED, true)
            p.setObjectExtra(ROOT, depth == 0)
            if (depth != 0) return@SafeHook
            if (Config.b("logJni")) L.i(msg(p.args) + " 来源=" + caller())
            val ic = p.thisObject as? InputConnection
            var intercepted = false
            try {
                if (ic != null && Voice.intercept(name, ic, p.args)) {
                    p.setObjectExtra(INTERCEPTED, true)
                    p.result = true
                    intercepted = true
                }
            } catch (t: Throwable) { L.hookError("Voice.intercept", t) }
            if (!intercepted && ic != null) {
                val snap = when {
                    name == "commitText" || name == "setComposingText" -> TypingEcho.snapshotForInput(ic)
                    isDeletion(name, p.args) -> TypingEcho.snapshotForDelete(ic)
                    else -> null
                }
                if (snap != null) p.setObjectExtra(SNAPSHOT, snap)
            }
        }, after = { p ->
            if (p.getObjectExtra(ENTERED) != true) return@SafeHook
            val depths = mutationDepth.get()
            val depth = ((depths?.get(name) ?: 1) - 1).coerceAtLeast(0)
            if (depth == 0) depths?.remove(name) else depths?.set(name, depth)
            if (p.getObjectExtra(ROOT) != true || p.getObjectExtra(INTERCEPTED) == true ||
                p.throwable != null || p.result != true || !A11yState.recording) return@SafeHook
            val ic = p.thisObject as? InputConnection ?: return@SafeHook
            val text = p.args.firstOrNull() as? CharSequence
            when (name) {
                "setComposingText" -> {
                    val value = text?.toString().orEmpty()
                    for (listener in composingListeners) try { listener(value) } catch (t: Throwable) { L.hookError("IC.composing", t) }
                    if (value.isNotBlank()) {
                        EditOptions.markTyped()
                        ClipboardNav.onTyping()
                    }
                    TypingEcho.successful(ic, name, value, p.getObjectExtra(SNAPSHOT) as? TypingEcho.Snapshot)
                }
                "commitText" -> {
                    val value = text?.toString().orEmpty()
                    if (value.isNotBlank()) {
                        EditOptions.markTyped()
                        ClipboardNav.onTyping()
                    }
                    for (listener in commitListeners) try { listener() } catch (t: Throwable) { L.hookError("IC.commit", t) }
                    TypingEcho.successful(ic, name, value, p.getObjectExtra(SNAPSHOT) as? TypingEcho.Snapshot)
                    if (KeyboardOps.isWatchingOrganize) KeyboardOps.onEditorTextMayHaveChanged()
                }
                "finishComposingText" -> {
                    for (listener in finishListeners) try { listener() } catch (t: Throwable) { L.hookError("IC.finish", t) }
                    TypingEcho.successful(ic, name, "", null)
                }
                "deleteSurroundingText", "deleteSurroundingTextInCodePoints", "sendKeyEvent" -> {
                    if (isDeletion(name, p.args)) {
                        for (listener in deleteListeners) try { listener() } catch (t: Throwable) { L.hookError("IC.delete", t) }
                        noteDeleted()
                        TypingEcho.deleted(ic, p.getObjectExtra(SNAPSHOT) as? TypingEcho.Snapshot)
                    }
                }
                "setSelection" -> if (!Voice.isRecording() && Candidates.engineTyping() == false) Candidates.onCursorMove()
            }
        }))
    }

    private fun isDeletion(name: String, args: Array<out Any?>): Boolean {
        if (name != "sendKeyEvent") return name == "deleteSurroundingText" || name == "deleteSurroundingTextInCodePoints"
        val event = args.firstOrNull() as? KeyEvent ?: return false
        return event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_DEL || event.keyCode == KeyEvent.KEYCODE_FORWARD_DEL)
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
