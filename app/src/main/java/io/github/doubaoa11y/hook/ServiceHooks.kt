package io.github.doubaoa11y.hook

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.view.inputmethod.EditorInfo
import io.github.doubaoa11y.ConfigSpec

/**
 * ImeService：拿到 Context 后初始化设置、无障碍状态、朗读，并注册设置广播。
 */
object ServiceHooks {
    const val CLS = "com.bytedance.android.doubaoime.ImeService"

    private var initialized = false
    private var appContext: Context? = null

    fun install(cl: ClassLoader) {
        val c = Hooker.findClass(CLS, cl) ?: return
        Hooker.method(c, "onCreate", arrayOf(), SafeHook("ImeService.onCreate", after = { p ->
            ViewA11yHooks.service = p.thisObject as? android.inputmethodservice.InputMethodService
            init(p.thisObject as Context)
        }))
        Hooker.method(c, "onStartInputView", arrayOf(EditorInfo::class.java, Boolean::class.javaPrimitiveType!!),
            SafeHook("onStartInputView", after = { p ->
                val ei = p.args[0] as? EditorInfo
                L.i("onStartInputView 应用=${ei?.packageName} 输入类型=0x${Integer.toHexString(ei?.inputType ?: 0)} 回车动作=0x${Integer.toHexString(ei?.imeOptions ?: 0)} 重启=${p.args[1]}")
            }))
        installHideHooks(cl)
        Hooker.method(c, "onWindowShown", arrayOf(), SafeHook("onWindowShown", after = { _ ->
            L.i("键盘窗口显示（${A11yState.describe()}）")
            if (A11yState.recording) ViewA11yHooks.dumpTreeLater("键盘显示后")
            KeyboardState.onShown()
            TatansBridge.onShown()
            ViewA11yHooks.service?.window?.window?.decorView?.let { StretchPanel.watch(it) }
            windowShown = true
            warnVersionOnce()
            if (A11yState.recording && KeyboardViewHooks.isEmpty()) KeyboardViewHooks.requestFullRedraw("键盘显示时键位表为空")
        }))
        Hooker.method(c, "onWindowHidden", arrayOf(), SafeHook("onWindowHidden", after = { _ ->
            Voice.onInputEnd()
            TatansBridge.onHidden()
            VirtualKeys.reset()
            EditPanel.close()
            L.i("键盘窗口隐藏")
            hideSoon("onWindowHidden")
            windowShown = false
        }))
    }

    private var windowShown = false

    /** 豆包版本和适配版本不同时，第一次弹出键盘读一次提醒；同一个版本只提醒一次 */
    private fun warnVersionOnce() {
        val ctx = appContext ?: return
        try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
            if (code in ConfigSpec.SUPPORTED_VERSIONS || !VirtualKeys.enabled) return
            val p = ctx.getSharedPreferences(ConfigSpec.PREFS_NAME, Context.MODE_PRIVATE)
            if (p.getLong("warnedVersion", 0L) == code) return
            p.edit().putLong("warnedVersion", code).apply()
            L.w("豆包版本 ${pi.versionName}（$code）与适配版本不同，已提醒")
            main.postDelayed({
                Speaker.speak("注意：豆包输入法已更新到 ${pi.versionName}，无障碍模块适配的是 ${ConfigSpec.SUPPORTED_NAMES}，部分功能可能失效")
            }, 1200)
        } catch (t: Throwable) {
            L.e("检查版本失败", t)
        }
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingHide: Runnable? = null

    /** 键盘开始收起：在收起动画开始时就提示，同一次收起只读一次 */
    private fun onHiding(from: String) {
        L.i("键盘开始收起（$from）")
        KeyboardState.onHidden()
        TatansBridge.onHidden()
    }

    /**
     * 稍等一下再读：系统收起又马上重新弹出（换输入框）时，期间会来“显示键盘”，就取消。
     */
    private fun hideSoon(from: String) {
        if (!windowShown) return
        pendingHide?.let { main.removeCallbacks(it) }
        val r = Runnable {
            pendingHide = null
            onHiding(from)
        }
        pendingHide = r
        main.postDelayed(r, 80)
    }

    private fun cancelHide(from: String) {
        val r = pendingHide ?: return
        main.removeCallbacks(r)
        pendingHide = null
        L.i("取消收起提示（$from）")
    }

    /**
     * 豆包自己收起（右上角按钮）走 requestHideSelf，立即读。
     * 返回键、滚动屏幕是应用这边先做收起动画，豆包最早收到的是 updateEditorToolType（动画开始时），
     * 弹出键盘时也会先来这个，但紧跟着就是 showSoftInput，所以等 80 毫秒看有没有“显示键盘”。
     */
    private fun installHideHooks(cl: ClassLoader) {
        val ims = android.inputmethodservice.InputMethodService::class.java
        Hooker.byName(ims, "requestHideSelf", SafeHook("requestHideSelf", before = { _ -> if (windowShown) onHiding("requestHideSelf") }))
        Hooker.byName(ims, "hideWindow", SafeHook("hideWindow", before = { _ -> hideSoon("hideWindow") }))
        val impl = Hooker.findClassQuiet("android.inputmethodservice.InputMethodService\$InputMethodImpl", cl) ?: return
        fun has(n: String) = impl.declaredMethods.any { it.name == n }
        for (n in listOf("hideSoftInput", "hideSoftInputWithToken")) {
            if (has(n)) Hooker.byName(impl, n, SafeHook(n, before = { _ -> hideSoon(n) }))
        }
        if (has("updateEditorToolType")) Hooker.byName(impl, "updateEditorToolType",
            SafeHook("updateEditorToolType", before = { _ -> hideSoon("updateEditorToolType") }))
        for (n in listOf("showSoftInput", "showSoftInputWithToken")) {
            if (has(n)) Hooker.byName(impl, n, SafeHook(n, before = { _ -> cancelHide(n) }))
        }
    }

    private fun init(service: Context) {
        if (initialized) return
        initialized = true
        val ctx = service.applicationContext ?: service
        appContext = ctx
        Config.load(ctx)
        Speaker.init(ctx)
        Haptic.init(ctx)
        A11yState.init(ctx)
        TatansBridge.init(ctx)
        L.i("模块初始化完成。${versionInfo(ctx)}。${A11yState.describe()}。${Hooker.summary()}。设置：${Config.summary()}")
        if (Config.b("traceAllJni")) JniHooks.installTraceAll()
        registerReceiver(ctx)
    }

    private fun versionInfo(ctx: Context): String = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
        val warn = if (code !in ConfigSpec.SUPPORTED_VERSIONS) "（注意：模块适配 ${ConfigSpec.SUPPORTED_NAMES}，版本不同可能有部分功能失效）" else ""
        "豆包输入法 ${pi.versionName}（$code）$warn"
    } catch (t: Throwable) {
        "豆包版本未知"
    }

    private fun registerReceiver(ctx: Context) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                try {
                    handle(intent)
                    val cmd = intent.getStringExtra(ConfigSpec.EXTRA_CMD)
                    val msg = when (cmd) {
                        ConfigSpec.CMD_EDIT_ADD -> EditHooks.setToolbar(true)
                        ConfigSpec.CMD_EDIT_REMOVE -> EditHooks.setToolbar(false)
                        else -> null
                    }
                    if (isOrderedBroadcast) {
                        resultCode = Activity.RESULT_OK
                        resultData = msg ?: if (cmd == null) "已保存" else shortStatus(ctx)
                        // 豆包里正在运行的模块版本：设置页拿它和自己比，不一样就是更新后还没强行停止豆包
                        setResultExtras(android.os.Bundle().apply { putInt("moduleVersion", ConfigSpec.MODULE_VERSION_CODE); putBoolean("active", VirtualKeys.enabled) })
                        L.i("状态：${status(ctx)}")
                    }
                } catch (t: Throwable) {
                    L.e("处理设置广播出错", t)
                }
            }
        }
        try {
            val f = IntentFilter(ConfigSpec.ACTION_CONFIG)
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED)
            else ctx.registerReceiver(r, f)
            L.i("设置广播已注册")
        } catch (t: Throwable) {
            L.e("注册设置广播失败", t)
        }
    }

    private fun handle(intent: Intent) {
        val ex = intent.extras
        val cmd = intent.getStringExtra(ConfigSpec.EXTRA_CMD)
        if (cmd == ConfigSpec.CMD_RESET) Config.reset()
        if (ex != null) {
            for (item in ConfigSpec.items) {
                if (ex.containsKey(item.key)) Config.set(item.key, ex.getBoolean(item.key, item.default))
            }
            for (item in ConfigSpec.intItems) {
                if (ex.containsKey(item.key)) Config.setInt(item.key, ex.getInt(item.key, item.default))
            }
            if (ex.containsKey(ConfigSpec.EXTRA_CUSTOM_WORDS)) Config.setCustomWords(ex.getString(ConfigSpec.EXTRA_CUSTOM_WORDS) ?: "")
        }
        L.i("收到设置（cmd=$cmd）：${Config.summary()}")
        TatansBridge.refresh("设置变化")
        A11yState.refresh("设置变化")
        if (cmd == ConfigSpec.CMD_DUMP) {
            L.forceUntil = android.os.SystemClock.uptimeMillis() + 3000
            KeyboardViewHooks.dumpAll("手动输出")
            JniHooks.queryBoardType("手动查询")
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    ViewA11yHooks.dumpTree("手动输出")
                } catch (t: Throwable) {
                    L.e("输出控件树失败", t)
                }
            }
        }
    }

    /** 设置页显示的简短状态：模块是否生效、豆包版本、读屏和触摸浏览 */
    fun shortStatus(ctx: Context): String {
        val ver = try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
            if (code in ConfigSpec.SUPPORTED_VERSIONS) "豆包输入法 ${pi.versionName}，版本匹配"
            else "注意：豆包输入法是 ${pi.versionName}，版本不匹配，模块适配 ${ConfigSpec.SUPPORTED_NAMES}，部分功能可能失效"
        } catch (t: Throwable) {
            "豆包版本未知"
        }
        val on = if (VirtualKeys.enabled) "模块已生效" else "模块已加载，但没有接管键盘（读屏没开）"
        return if (ver.startsWith("注意")) "$ver。$on。${A11yState.describeShort()}。"
        else "$on。$ver。${A11yState.describeShort()}。"
    }

    fun status(ctx: Context): String =
        "模块已在豆包输入法中加载。${versionInfo(ctx)}。${A11yState.describe()}。${Hooker.summary()}。${KeyboardViewHooks.status()}"
}
