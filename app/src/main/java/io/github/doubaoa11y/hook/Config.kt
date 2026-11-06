package io.github.doubaoa11y.hook

import android.content.Context
import android.content.SharedPreferences
import io.github.doubaoa11y.ConfigSpec
import java.util.concurrent.ConcurrentHashMap

/** 豆包进程里的设置。没有 Context 之前使用默认值。 */
object Config {
    private val values = ConcurrentHashMap(ConfigSpec.defaults)
    private var prefs: SharedPreferences? = null

    fun b(key: String): Boolean =
        if (key in ConfigSpec.fixed) ConfigSpec.defaults[key] ?: false else values[key] ?: false

    private val ints = ConcurrentHashMap(ConfigSpec.intDefaults)

    fun i(key: String): Int = ints[key] ?: 0

    fun setInt(key: String, v: Int) {
        if (!ConfigSpec.intDefaults.containsKey(key)) return
        ints[key] = v
        try {
            prefs?.edit()?.putInt(key, v)?.apply()
        } catch (t: Throwable) {
            L.e("保存设置失败", t)
        }
    }

    fun load(ctx: Context) {
        try {
            val p = ctx.getSharedPreferences(ConfigSpec.PREFS_NAME, Context.MODE_PRIVATE)
            prefs = p
            for (item in ConfigSpec.items) {
                values[item.key] = p.getBoolean(item.key, item.default)
            }
            for (item in ConfigSpec.intItems) {
                ints[item.key] = p.getInt(item.key, item.default)
            }
        } catch (t: Throwable) {
            L.e("读取设置失败，使用默认值", t)
        }
    }

    fun set(key: String, v: Boolean) {
        if (!ConfigSpec.defaults.containsKey(key)) return
        values[key] = v
        try {
            prefs?.edit()?.putBoolean(key, v)?.apply()
        } catch (t: Throwable) {
            L.e("保存设置失败", t)
        }
    }

    fun reset() {
        for (item in ConfigSpec.items) set(item.key, item.default)
        for (item in ConfigSpec.intItems) setInt(item.key, item.default)
    }

    fun summary(): String =
        ConfigSpec.items.joinToString("，") { "${it.key}=${if (b(it.key)) "开" else "关"}" } +
            ConfigSpec.intItems.joinToString("") { "，${it.key}=${i(it.key)}" }
}
