package io.github.doubaoa11y.hook

import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Modifier

/**
 * KeyboardJni：原生层回调 Java 的静态方法。阶段 0 只记日志，参数原样输出。
 */
object JniHooks {
    const val CLS = "com.bytedance.android.doubaoime.KeyboardJni"

    var jniClass: Class<*>? = null
        private set
    private val main = Handler(Looper.getMainLooper())

    /** 需要记录的回调（按名称 hook 全部重载） */
    private val callbacks = listOf(
        "PlayKeyClick",
        "DoFunctionKey",
        "DoCommit",
        "notifyCandidateBarPinyin",
        "notifyCandidateBarSnapshot",
        "notifyMoreCandidateSnapshot",
        "onKeyboardBoardTypeChanged",
        "StartAsrOnSpaceLPress",
        "AsrStopCommand",
        "showToast",
        "hideToast",
        "onSendStateChanged",
        "triggerLongPressVibrate",
    )

    fun install(cl: ClassLoader) {
        val c = Hooker.findClass(CLS, cl) ?: return
        jniClass = c
        for (name in callbacks) {
            Hooker.byName(c, name, SafeHook("KeyboardJni.$name", before = { p ->
                if (A11yState.recording && Config.b("logJni")) {
                    L.i("JNI $name/${p.args.size}参 线程=${Thread.currentThread().name} ${Fmt.args(p.args)}")
                }
            }))
        }

        // 候选栏更新：朗读第一个候选
        Hooker.byName(c, "notifyCandidateBarSnapshot", SafeHook("candidate.speak", after = { p ->
            Candidates.onSnapshot(p.args)
        }))

        // 键盘类型切换：真正的处理被 post 到主线程，处理完再查询类型
        val queried = Hooker.byName(c, "lambda\$onKeyboardBoardTypeChanged\$49", SafeHook("boardTypeChanged.lambda", after = { _ ->
            if (A11yState.recording && Config.b("queryNative")) queryBoardType("键盘切换后")
            if (A11yState.recording) ViewA11yHooks.dumpTreeLater("键盘切换后")
        }))
        if (!queried) {
            // 降级：自己 post 一次
            Hooker.byName(c, "onKeyboardBoardTypeChanged", SafeHook("boardTypeChanged.post", after = { _ ->
                if (A11yState.recording && Config.b("queryNative")) main.postDelayed({ queryBoardType("键盘切换后(降级)") }, 50)
            }))
        }
    }

    private fun jni(): Any? = try {
        XposedHelpers.callStaticMethod(jniClass, "getKeyboardJni")
    } catch (t: Throwable) {
        L.hookError("getKeyboardJni", t)
        null
    }

    /** 当前是不是英文键盘（键盘切换后查询得到） */
    @Volatile
    var isEnglish = false
        private set

    /** 查询键盘类型（这几个原生方法豆包自己也在主线程调用，安全） */
    fun queryBoardType(why: String) {
        val j = jni() ?: return
        val sb = StringBuilder("键盘类型（$why）：")
        var type: String? = null
        var kbd: Int? = null
        for (m in listOf("getKeyboardType", "getCurrentKbdType", "IsEnglishKeyboard")) {
            val r = try {
                val v = XposedHelpers.callMethod(j, m)
                if (m == "getKeyboardType") type = v as? String
                if (m == "getCurrentKbdType") kbd = v as? Int
                Fmt.arg(v)
            } catch (t: Throwable) {
                "出错:${t.javaClass.simpleName}"
            }
            sb.append(m).append('=').append(r).append(' ')
            if (m == "IsEnglishKeyboard") isEnglish = r == "true"
        }
        L.i(sb.toString())
        if (why.startsWith("键盘切换后")) KeyboardState.onBoard(type, kbd)
    }

    /** 调试：跟踪 KeyboardJni 全部非原生静态方法（跳过 get/is 开头和 lambda，量依然很大） */
    fun installTraceAll() {
        val c = jniClass ?: return
        var n = 0
        for (m in c.declaredMethods) {
            val mod = m.modifiers
            if (!Modifier.isStatic(mod) || Modifier.isNative(mod) || m.isSynthetic) continue
            val name = m.name
            if (name in callbacks || name.startsWith("lambda$") || name.startsWith("access$") ||
                name.startsWith("get") || name.startsWith("is")) continue
            if (Hooker.hookMember(m, SafeHook("trace.$name", before = { p ->
                    if (A11yState.recording) L.i("JNI跟踪 ${Hooker.sig(m)} ${Fmt.args(p.args)}")
                }))) n++
        }
        L.i("已开启 KeyboardJni 全量跟踪：$n 个方法")
    }
}
