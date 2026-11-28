package io.github.doubaoa11y.patch

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File
import java.lang.ref.WeakReference
import java.util.UUID

/** 包写入在工作线程完成。注册、提交、结果处理与页面解绑在主线程串行执行。 */
internal object InstallSession {
    private val main = Handler(Looper.getMainLooper())
    private val sessions = mutableMapOf<Int, BroadcastReceiver>()

    private class Listener(val owner: WeakReference<Activity>, var callback: ((Boolean, Boolean, String) -> Unit)?) {
        fun report(done: Boolean, ok: Boolean, message: String) {
            if (owner.get()?.let { !it.isFinishing && !it.isDestroyed } == true) callback?.invoke(done, ok, message)
            if (done) callback = null
        }
    }
    private val listeners = mutableSetOf<Listener>()

    fun detach(act: Activity) {
        listeners.filter { it.owner.get() === act }.forEach { it.callback = null }
    }

    fun install(act: Activity, pkg: String, mode: Int, files: List<File>,
                afterCopy: () -> Unit = {}, onSuccess: () -> Unit = {},
                callback: (Boolean, Boolean, String) -> Unit) {
        val app = act.applicationContext
        val listener = Listener(WeakReference(act), callback)
        listeners.add(listener)
        Thread {
            val pi = app.packageManager.packageInstaller
            var created: Int? = null
            try {
                val params = PackageInstaller.SessionParams(mode).apply {
                    setAppPackageName(pkg)
                    setSize(files.sumOf { it.length() })
                }
                val id = pi.createSession(params)
                created = id
                pi.openSession(id).use { session ->
                    files.forEachIndexed { index, file ->
                        val name = if (index == 0) "base.apk" else "split_$index.apk"
                        session.openWrite(name, 0, file.length()).use { out ->
                            file.inputStream().use { it.copyTo(out) }
                            session.fsync(out)
                        }
                    }
                }
                main.post {
                    val action = "${app.packageName}.INSTALL.$id.${UUID.randomUUID()}"
                    fun finish(ok: Boolean, message: String) {
                        sessions.remove(id)?.let { runCatching { app.unregisterReceiver(it) } }
                        if (ok) runCatching(onSuccess) else runCatching { pi.abandonSession(id) }
                        listeners.remove(listener)
                        listener.report(true, ok, message)
                    }
                    try {
                        val receiver = object : BroadcastReceiver() {
                            override fun onReceive(context: Context, intent: Intent) {
                                if (intent.action != action || intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != id ||
                                    sessions[id] !== this) return
                                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                                try {
                                    when (status) {
                                        PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                                            @Suppress("DEPRECATION")
                                            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                                                ?: error("系统未返回安装确认界面")
                                            val owner = listener.owner.get()?.takeUnless { it.isFinishing || it.isDestroyed }
                                            if (owner == null || runCatching { owner.startActivity(confirm) }.isFailure) {
                                                app.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                            }
                                            listener.report(false, true, "请在系统弹窗中确认安装")
                                        }
                                        PackageInstaller.STATUS_SUCCESS -> finish(true, "安装成功")
                                        else -> {
                                            val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                                            finish(false, "安装未完成（状态码 $status）$detail")
                                        }
                                    }
                                } catch (t: Throwable) {
                                    finish(false, "打开安装确认界面失败：${t.message}")
                                }
                            }
                        }
                        val filter = IntentFilter(action)
                        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                        else app.registerReceiver(receiver, filter)
                        sessions[id] = receiver
                        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                        val pending = PendingIntent.getBroadcast(app, id, Intent(action).setPackage(app.packageName), flags)
                        pi.openSession(id).use { it.commit(pending.intentSender) }
                    } catch (t: Throwable) {
                        PatchLog.e(app, "安装会话 $id 提交失败", t)
                        finish(false, "提交安装会话失败：${t.message}")
                    }
                }
            } catch (t: Throwable) {
                created?.let { runCatching { pi.abandonSession(it) } }
                main.post {
                    listeners.remove(listener)
                    listener.report(true, false, "准备安装失败：${t.message}")
                }
            } finally {
                runCatching(afterCopy)
            }
        }.start()
    }
}
