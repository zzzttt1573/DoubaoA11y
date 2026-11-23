package io.github.doubaoa11y.hook

import android.content.Context
import android.content.SharedPreferences
import io.github.doubaoa11y.ConfigSpec
import java.util.concurrent.ConcurrentHashMap

/** 豆包进程里的设置。没有 Context 之前使用默认值。 */
object Config {
    private val values = ConcurrentHashMap(ConfigSpec.defaults)
    private val strs = ConcurrentHashMap(ConfigSpec.strDefaults)
    private var prefs: SharedPreferences? = null

    fun b(key: String): Boolean =
        if (key in ConfigSpec.fixed) ConfigSpec.defaults[key] ?: false else values[key] ?: false

    private val ints = ConcurrentHashMap(ConfigSpec.intDefaults)

    fun i(key: String): Int = ints[key] ?: 0

    fun s(key: String): String = strs[key] ?: ""

    fun setInt(key: String, v: Int) {
        if (!ConfigSpec.intDefaults.containsKey(key)) return
        ints[key] = v
        try {
            prefs?.edit()?.putInt(key, v)?.apply()
        } catch (t: Throwable) {
            L.e("保存设置失败", t)
        }
    }

    fun setStr(key: String, v: String) {
        if (!ConfigSpec.strDefaults.containsKey(key)) return
        strs[key] = v
        try {
            prefs?.edit()?.putString(key, v)?.apply()
        } catch (t: Throwable) {
            L.e("保存设置失败", t)
        }
    }

    fun load(ctx: Context) {
        try {
            val p = ctx.getSharedPreferences(ConfigSpec.PREFS_NAME, Context.MODE_PRIVATE)
            prefs = p
            // 仅补充缺省手势，不覆盖用户自定义。
            ConfigSpec.migrateGestureDefaults(p)
            ConfigSpec.migrateEditOptions(p)
            applyPrefs(p)
            customWords = parseWords(p.getString(ConfigSpec.EXTRA_CUSTOM_WORDS, null))
        } catch (t: Throwable) {
            L.e("读取设置失败，使用默认值", t)
        }
    }

    private fun applyPrefs(p: SharedPreferences) {
        for (item in ConfigSpec.items) {
            values[item.key] = p.getBoolean(item.key, item.default)
        }
        for (item in ConfigSpec.intItems) {
            ints[item.key] = p.getInt(item.key, item.default)
        }
        for (item in ConfigSpec.strItems) {
            strs[item.key] = p.getString(item.key, item.default) ?: item.default
        }
    }

    /**
     * 主动去模块设置页那边把配置拉一遍（只读 ContentProvider）。
     *
     * 设置页和豆包是两个进程，各有一份 SharedPreferences。广播只在两边都活着的时候有效，
     * 豆包进程被冻结、刚开机、设置页改完就退出去，广播就丢了，豆包这边一直是默认值——
     * 用户看到的就是“设置改了没作用”。所以每次键盘弹出前都来拉一次，广播只当“立刻生效”的加速。
     *
     * 返回 true 表示这次拉到了不一样的值。
     */
    fun pull(ctx: Context): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastPull < 800) return false
        lastPull = now
        return try {
            for (k in listOf("upperOperation", "screenToolbar", "editToolbar")) {
                if (isDirty(k)) setFromIme(ctx, k, values[k] ?: (ConfigSpec.defaults[k] ?: false))
            }
            if (isDirty("toolbarCustomOrder")) setStrFromIme(ctx, "toolbarCustomOrder", strs["toolbarCustomOrder"] ?: "")
            val b = ctx.contentResolver.call(PULL_URI, "dump", null, null) ?: return false
            var changed = false
            val e = prefs?.edit()
            for (item in ConfigSpec.items) {
                if (isDirty(item.key)) continue
                if (!b.containsKey(item.key)) continue
                val v = b.getBoolean(item.key, item.default)
                if (values[item.key] != v) {
                    values[item.key] = v
                    changed = true
                }
                e?.putBoolean(item.key, v)
            }
            for (item in ConfigSpec.intItems) {
                if (!b.containsKey(item.key)) continue
                val v = b.getInt(item.key, item.default)
                if (ints[item.key] != v) {
                    ints[item.key] = v
                    changed = true
                }
                e?.putInt(item.key, v)
            }
            for (item in ConfigSpec.strItems) {
                if (isDirty(item.key)) continue
                if (!b.containsKey(item.key)) continue
                val v = b.getString(item.key) ?: item.default
                if (strs[item.key] != v) {
                    strs[item.key] = v
                    changed = true
                }
                e?.putString(item.key, v)
            }
            b.getString(ConfigSpec.EXTRA_CUSTOM_WORDS)?.let { w ->
                if (w != lastWords) {
                    lastWords = w
                    customWords = parseWords(w)
                    e?.putString(ConfigSpec.EXTRA_CUSTOM_WORDS, w)
                    changed = true
                }
            }
            if (changed) {
                e?.apply()
                Sound.onConfigChanged()

                // 震动效果是预计算的，配置变了要重算，否则改设置不生效

                Haptic.refresh()
                L.i("已从设置页同步配置：${summary()}")
            } else {
                Sound.syncCustomSounds()
            }
            changed
        } catch (t: Throwable) {
            // 模块没装、provider 没起来、跨进程被拦：都不影响，继续用现有配置
            L.w("从设置页同步配置失败：${t.javaClass.simpleName} ${t.message ?: ""}")
            false
        }
    }

    private var lastPull = 0L
    private var lastWords = ""

    /**
     * 把某个设置值写回设置页那一份（豆包进程里改的设置也要能持久）。
     * 不写回去的话，下次弹键盘 [pull] 会拿旧的覆盖回来。
     */
    fun push(ctx: Context?, key: String, v: Int) {
        if (ctx == null) return
        try {
            val b = android.os.Bundle().apply { putInt(key, v) }
            ctx.contentResolver.call(PUSH_URI, "set", null, b)
        } catch (t: Throwable) {
            L.w("写回设置页失败（$key）：${t.javaClass.simpleName}")
        }
    }


    /** 设置页那边的只读 ContentProvider（见 SettingsProvider） */
    private val PULL_URI = android.net.Uri.parse("content://${ConfigSpec.SETTINGS_AUTHORITY}/config")
    private val PUSH_URI = android.net.Uri.parse("content://${ConfigSpec.SETTINGS_AUTHORITY}/config")

    fun set(key: String, v: Boolean) {
        if (!ConfigSpec.defaults.containsKey(key)) return
        values[key] = v
        try {
            prefs?.edit()?.putBoolean(key, v)?.apply()
        } catch (t: Throwable) {
            L.e("保存设置失败", t)
        }
    }

    /**
     * 在输入法进程内切换设置（如工具栏切换上半屏手势、定制工具栏添加/移除按钮）：
     * 即使模块设置页后台已关闭、ContentProvider 暂时不可达，也先在本地生效并标记待同步，
     * 等设置页进程下次可访问时再写回，避免被旧配置覆盖。
     */
    fun setFromIme(ctx: Context?, key: String, v: Boolean) {
        if (!ConfigSpec.defaults.containsKey(key)) return
        values[key] = v
        val method = when (key) {
            "upperOperation" -> "setUpperOperation"
            "screenToolbar", "editToolbar" -> "setToolbar"
            else -> null
        }
        var pushed = false
        if (ctx != null && method != null) {
            pushed = try {
                val res = ctx.contentResolver.call(PUSH_URI, method, null, android.os.Bundle().apply { putBoolean(key, v) })
                res?.getBoolean("saved") == true
            } catch (_: Throwable) {
                false
            }
        }
        try {
            prefs?.edit()?.putBoolean(key, v)?.putBoolean("dirty_$key", !pushed)?.apply()
        } catch (t: Throwable) {
            L.e("保存设置失败", t)
        }
    }

    fun setStrFromIme(ctx: Context?, key: String, v: String) {
        if (!ConfigSpec.strDefaults.containsKey(key)) return
        strs[key] = v
        val method = if (key == "toolbarCustomOrder") "setToolbar" else null
        var pushed = false
        if (ctx != null && method != null) {
            pushed = try {
                val res = ctx.contentResolver.call(PUSH_URI, method, null, android.os.Bundle().apply { putString(key, v) })
                res?.getBoolean("saved") == true
            } catch (_: Throwable) {
                false
            }
        }
        try {
            prefs?.edit()?.putString(key, v)?.putBoolean("dirty_$key", !pushed)?.apply()
        } catch (t: Throwable) {
            L.e("保存设置失败", t)
        }
    }

    fun isDirty(key: String): Boolean = try {
        prefs?.getBoolean("dirty_$key", false) == true
    } catch (_: Throwable) {
        false
    }

    fun clearDirty(key: String) {
        try {
            prefs?.edit()?.putBoolean("dirty_$key", false)?.apply()
        } catch (_: Throwable) {
        }
    }

    /** 自定义解释词：字 -> 词 */
    @Volatile
    var customWords: Map<Char, String> = emptyMap()
        private set

    fun parseWords(s: String?): Map<Char, String> {
        if (s.isNullOrBlank()) return emptyMap()
        val m = HashMap<Char, String>()
        for (line in s.split('\n')) {
            val tab = line.indexOf('\t')
            if (tab != 1) continue
            val w = line.substring(2).trim()
            if (w.isNotEmpty()) m[line[0]] = w
        }
        return m
    }

    fun setCustomWords(s: String) {
        customWords = parseWords(s)
        try {
            prefs?.edit()?.putString(ConfigSpec.EXTRA_CUSTOM_WORDS, s)?.apply()
        } catch (t: Throwable) {
            L.e("保存自定义解释词失败", t)
        }
    }

    fun reset() {
        for (item in ConfigSpec.items) set(item.key, item.default)
        for (item in ConfigSpec.intItems) setInt(item.key, item.default)
        for (item in ConfigSpec.strItems) setStr(item.key, item.default)
    }

    /** 当前输入方式：半屏输入、标准输入或全屏输入 */
    val typeMode: Int get() = i("typeMode")

    val fullscreen: Boolean get() = b("upperOperation")

    fun summary(): String =
        ConfigSpec.items.joinToString("，") { "${it.key}=${if (b(it.key)) "开" else "关"}" } +
            ConfigSpec.intItems.joinToString("") { "，${it.key}=${i(it.key)}" } +
            ConfigSpec.strItems.joinToString("") { "，${it.key}=${s(it.key)}" }
}
