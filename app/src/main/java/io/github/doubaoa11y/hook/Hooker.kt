package io.github.doubaoa11y.hook

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Field
import java.lang.reflect.Member
import java.lang.reflect.Method

/**
 * 回调包装：before/after 里的任何异常都只记日志，绝不抛回输入法。
 */
class SafeHook(
    private val where: String,
    private val before: ((XC_MethodHook.MethodHookParam) -> Unit)? = null,
    private val after: ((XC_MethodHook.MethodHookParam) -> Unit)? = null,
) : XC_MethodHook() {
    override fun beforeHookedMethod(param: MethodHookParam) {
        val b = before ?: return
        try {
            b(param)
        } catch (t: Throwable) {
            L.hookError("$where (before)", t)
        }
    }

    override fun afterHookedMethod(param: MethodHookParam) {
        val a = after ?: return
        try {
            a(param)
        } catch (t: Throwable) {
            L.hookError("$where (after)", t)
        }
    }
}

/**
 * 方法定位：先按“类名 + 方法名 + 参数类型”精确查找；找不到就降级为按方法名 hook 全部重载，
 * 并把实际找到的签名记进日志，方便跟版本。
 */
object Hooker {
    var ok = 0
    var degraded = 0
    var missing = 0
    private val report = StringBuilder()

    fun summary(): String = "hook 成功 $ok 处，降级 $degraded 处，缺失 $missing 处"

    fun details(): String = report.toString()

    private fun note(s: String) {
        report.append(s).append('\n')
    }

    fun findClass(name: String, cl: ClassLoader): Class<*>? {
        val c = try {
            XposedHelpers.findClassIfExists(name, cl)
        } catch (t: Throwable) {
            null
        }
        if (c == null) {
            missing++
            note("缺失类 $name")
            L.w("找不到类：$name")
        }
        return c
    }

    /** 找类但不计入缺失（可选的类） */
    fun findClassQuiet(name: String, cl: ClassLoader): Class<*>? = try {
        XposedHelpers.findClassIfExists(name, cl)
    } catch (t: Throwable) {
        null
    }

    fun field(cls: Class<*>?, name: String): Field? {
        if (cls == null) return null
        return try {
            XposedHelpers.findField(cls, name)
        } catch (t: Throwable) {
            note("缺失字段 ${cls.name}.$name")
            L.w("找不到字段：${cls.name}.$name")
            null
        }
    }

    /** 精确 hook；签名不对时降级为按名称 hook 全部同名方法 */
    fun method(cls: Class<*>?, name: String, params: Array<Class<*>>, cb: XC_MethodHook): Boolean {
        if (cls == null) return false
        try {
            val m = XposedHelpers.findMethodExactIfExists(cls, name, *params)
            if (m != null) {
                XposedBridge.hookMethod(m, cb)
                ok++
                return true
            }
            L.w("签名不匹配：${cls.name}.$name(${params.joinToString { it.simpleName }})，尝试按名称降级")
            return byName(cls, name, cb, countAsDegraded = true)
        } catch (t: Throwable) {
            missing++
            note("失败 ${cls.name}.$name")
            L.e("hook 失败：${cls.name}.$name", t)
            return false
        }
    }

    /** 按名称 hook 该类自己声明的全部同名方法（不含父类） */
    fun byName(cls: Class<*>?, name: String, cb: XC_MethodHook, countAsDegraded: Boolean = false): Boolean {
        if (cls == null) return false
        try {
            val all = cls.declaredMethods.filter { it.name == name }
            if (all.isEmpty()) {
                missing++
                note("缺失方法 ${cls.name}.$name")
                L.w("找不到方法：${cls.name}.$name")
                return false
            }
            all.forEach { XposedBridge.hookMethod(it, cb) }
            if (countAsDegraded) {
                degraded++
                note("降级 ${cls.name}.$name -> ${all.joinToString { sig(it) }}")
                L.w("已降级 hook ${all.size} 个重载：${all.joinToString { sig(it) }}")
            } else {
                ok++
            }
            return true
        } catch (t: Throwable) {
            missing++
            note("失败 ${cls.name}.$name")
            L.e("hook 失败：${cls.name}.$name", t)
            return false
        }
    }

    fun hookMember(m: Member, cb: XC_MethodHook): Boolean = try {
        XposedBridge.hookMethod(m, cb)
        true
    } catch (t: Throwable) {
        L.e("hook 失败：$m", t)
        false
    }

    fun sig(m: Method): String =
        "${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }})"
}
