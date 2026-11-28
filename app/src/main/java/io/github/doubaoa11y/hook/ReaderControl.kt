package io.github.doubaoa11y.hook

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import io.github.doubaoa11y.ConfigSpec
import io.github.doubaoa11y.ReaderControlTransport

object ReaderControl {
    private var context: Context? = null
    private var recovery: SharedPreferences? = null
    private val main = Handler(Looper.getMainLooper())
    private val policy = ReaderControlPolicy()
    private var visible = false
    val requested: Boolean get() = policy.owner.isNotEmpty()

    fun init(c: Context) {
        context = c.applicationContext ?: c
        try {
            val prefs = c.getSharedPreferences("reader_control_recovery", Context.MODE_PRIVATE)
            recovery = prefs
            policy.recover(prefs.getString("owner", "").orEmpty())
            restore()
            scheduleTick()
        } catch (t: Throwable) {
            L.hookError("ReaderControl.init", t)
        }
    }

    fun shown() {
        visible = true
        scheduleTick()
    }

    fun hidden() {
        visible = false
        restore()
        scheduleTick()
    }

    private val tick = Runnable {
        try {
            if (visible) {
                sync()
            } else {
                restore()
            }
        } catch (t: Throwable) {
            L.hookError("ReaderControl.tick", t)
        } finally {
            scheduleTick()
        }
    }

    private fun scheduleTick() {
        main.removeCallbacks(tick)
        when {
            visible -> main.postDelayed(tick, 300)
            requested -> main.postDelayed(tick, ReaderControlPolicy.RETRY_MS)
        }
    }

    fun sync() {
        try {
            val c = context ?: return
            val state = ReaderControlPolicy.Conditions(
                pkg = A11yState.readerPackage,
                fullscreen = FullscreenMode.active,
                awake = (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive,
                recording = A11yState.recording,
                doubleTap = Config.typeMode == ConfigSpec.TYPE_DOUBLE_TAP,
                panelOpen = TatansBridge.panelOpen(),
                explorationOn = !A11yState.explorationOff,
            )
            policy.sync(state, SystemClock::uptimeMillis, ::saveOwner, ::send)
        } catch (t: Throwable) {
            L.hookError("ReaderControl.sync", t)
            restore()
        }
    }

    private fun restore() {
        policy.restore(::saveOwner, ::send)
    }

    private fun saveOwner(pkg: String): Boolean = try {
        val editor = checkNotNull(recovery) { "读屏恢复记录未初始化" }.edit()
        if (pkg.isEmpty()) editor.remove("owner") else editor.putString("owner", pkg)
        check(editor.commit()) { "写入读屏恢复记录失败" }
        true
    } catch (t: Throwable) {
        L.hookError("ReaderControl.saveOwner", t)
        false
    }

    private val proxyUri = Uri.parse("content://${ConfigSpec.SETTINGS_AUTHORITY}/config")

    private fun canSeePackage(c: Context, pkg: String): Boolean {
        if (Build.VERSION.SDK_INT < 30) return true
        return try {
            c.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun sendViaProxy(c: Context, pkg: String, on: Boolean): Boolean = try {
        val result = c.contentResolver.call(
            proxyUri,
            "readerControl",
            null,
            Bundle().apply {
                putString("pkg", pkg)
                putBoolean("on", on)
            },
        )
        check(result?.getBoolean("applied", false) == true) {
            result?.getString("error") ?: "模块代理未返回成功结果"
        }
        true
    } catch (t: Throwable) {
        L.hookError("ReaderControl.proxy($pkg, 恢复=$on)", t)
        false
    }

    private fun sendLocally(c: Context, pkg: String, on: Boolean): Boolean = try {
        ReaderControlTransport.send(c, pkg, on)
        true
    } catch (t: Throwable) {
        L.hookError("ReaderControl.local($pkg, 恢复=$on)", t)
        false
    }

    private fun send(pkg: String, on: Boolean): Boolean {
        val c = context ?: return false
        val viaProxy = if (canSeePackage(c, pkg)) {
            if (sendLocally(c, pkg, on)) false
            else if (sendViaProxy(c, pkg, on)) true
            else return false
        } else {
            if (sendViaProxy(c, pkg, on)) true
            else if (sendLocally(c, pkg, on)) false
            else return false
        }
        L.i("读屏接口请求${if (viaProxy) "（代理）" else ""}：$pkg ${if (on) "恢复触摸浏览" else "直接触摸"}；响应以实际事件为准")
        return true
    }

    fun describe() = if (requested) "已请求保益/点明直接触摸，响应以实际事件为准" else "保留读屏触摸浏览"
}
