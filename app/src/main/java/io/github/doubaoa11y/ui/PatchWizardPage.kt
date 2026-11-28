package io.github.doubaoa11y.ui

import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.github.doubaoa11y.ConfigSpec
import io.github.doubaoa11y.patch.ApkPatcher
import io.github.doubaoa11y.patch.DoubaoInstaller
import io.github.doubaoa11y.patch.PatchLog
import java.util.WeakHashMap

/**
 * 读屏友好的「一键安装/修补豆包输入法」向导页。
 *
 * 首次安装（3 步）：
 * 1. 本地提取官方豆包并嵌入本模块、使用本机密钥签名；
 * 2. 卸载官方原版豆包；
 * 3. 安装修补版豆包并在系统设置中启用。
 *
 * 后续本模块更新（1 步）：
 * 直接热替换已修补豆包中的内嵌模块并用同一本机密钥重签名，可覆盖安装、不丢词库。
 */
object PatchWizardPage {
    private val main = Handler(Looper.getMainLooper())

    private class Holder(
        val act: SettingsActivity,
        val summary: TextView,
        val primary: Button,
        val secondaryBox: LinearLayout
    ) {
        var probing = false
        var announceResult = false
        var dialog: AlertDialog? = null
        var busy = false
        var lastMessage = ""
    }

    private val holders = WeakHashMap<SettingsActivity, Holder>()

    fun fill(act: SettingsActivity, root: LinearLayout) {
        root.addView(
            act.note(
                "本功能在你的手机本地为官方豆包输入法嵌入无障碍模块，无需安装额外的 LSPatch 管理器，" +
                    "也无需“获取应用列表”权限或后台保活。已使用 LSPosed（Root）的用户无需使用此功能。"
            )
        )

        val summary = TextView(act).apply {
            textSize = 16f
            isFocusable = true
            setPadding(0, act.pad / 2, 0, act.pad / 2)
        }
        root.addView(summary)

        val primary = act.button("正在检测状态…") {}
        root.addView(primary)

        val secondaryBox = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, act.pad / 2, 0, 0)
        }
        root.addView(secondaryBox)

        val holder = Holder(act, summary, primary, secondaryBox)
        holders.put(act, holder)?.dialog?.dismiss()
        refresh(act, announce = false)
    }

    fun detach(act: SettingsActivity) {
        holders.remove(act)?.dialog?.dismiss()
        DoubaoInstaller.detach(act)
    }

    fun onResume(act: SettingsActivity) {
        if (holders.containsKey(act)) {
            refresh(act, announce = false)
        }
    }

    private fun refresh(act: SettingsActivity, announce: Boolean) {
        val h = holders[act] ?: return
        if (h.busy) return

        h.announceResult = h.announceResult || announce
        if (h.probing) return
        h.probing = true
        h.primary.isEnabled = false
        h.secondaryBox.removeAllViews()
        val app = act.applicationContext
        Thread {
            val result = runCatching {
                val st = ApkPatcher.inspect(app)
                st to ApkPatcher.loadPrepared(app, st)
            }
            main.post {
                if (!live(h)) return@post
                h.probing = false
                val speak = h.announceResult
                h.announceResult = false
                result.onSuccess { (st, prepared) -> render(h, st, prepared, speak) }
                    .onFailure {
                        setSummary(h, "检测失败：${it.message}", speak)
                        h.primary.isEnabled = true
                        setPrimary(h, "重新检测") { refresh(act, true) }
                    }
            }
        }.start()
    }

    private fun live(h: Holder) = !h.act.isFinishing && !h.act.isDestroyed && holders[h.act] === h

    private fun render(h: Holder, st: ApkPatcher.DoubaoStatus, prepared: ApkPatcher.PreparedOutput?, announce: Boolean) {
        val act = h.act
        val canInstall = DoubaoInstaller.canInstall(act)
        h.secondaryBox.removeAllViews()
        h.primary.isEnabled = true

        when {
            !canInstall -> {
                setSummary(
                    h,
                    "准备步骤：需要先允许“豆包输入法无障碍”安装应用，才能把修补好的豆包输入法装回手机。\n\n" +
                        "请点击下方按钮打开开关，然后按返回回到本页。",
                    announce
                )
                setPrimary(h, "去开启“安装未知应用”权限") {
                    DoubaoInstaller.openInstallPermissionSettings(act)
                }
                addSecondary(h, "我已开启，刷新状态") { refresh(act, announce = true) }
            }

            st.kind == ApkPatcher.PatchKind.NOT_INSTALLED && prepared != null && !prepared.isHotUpdate -> {
                setSummary(
                    h,
                    "原版豆包输入法已卸载。\n" +
                        "修补版豆包（${prepared.doubaoVersionName}，含无障碍模块 ${ConfigSpec.MODULE_VERSION_NAME}）已就绪，" +
                        "请点击下方按钮安装。",
                    announce
                )
                setPrimary(h, "安装修补版豆包输入法") {
                    startInstall(h, prepared, forceSession = false)
                }
                if (prepared.singleApk) {
                    addSecondary(h, "换用系统会话模式安装（备用）") {
                        startInstall(h, prepared, forceSession = true)
                    }
                }
                addExport(h, prepared)
                addSecondary(h, "复制安装修补日志") {
                    act.toast(PatchLog.copyToClipboard(act))
                }
                addSecondary(h, "刷新状态") { refresh(act, announce = true) }
                addSecondary(h, "放弃并清理缓存的修补包") {
                    ApkPatcher.clearPrepared(act, prepared)
                    refresh(act, announce = true)
                }
            }

            st.kind == ApkPatcher.PatchKind.NOT_INSTALLED -> {
                setSummary(
                    h,
                    "未检测到豆包输入法。\n\n" +
                        "请先从应用商店安装官方版豆包输入法（推荐适配版本：${ConfigSpec.SUPPORTED_NAMES}），" +
                        "装好后回到本页即可一键修补。",
                    announce
                )
                setPrimary(h, "我已安装豆包，刷新状态") {
                    refresh(act, announce = true)
                }
            }

            st.kind == ApkPatcher.PatchKind.OTHER_PATCHED -> {
                setSummary(
                    h,
                    "检测到当前豆包输入法（${st.versionName}）已通过外部 LSPatch 或其他方式修补。\n\n" +
                        "如果你原本就在使用外部 LSPatch 管理器且正常，可以继续沿用，无需在此重装。\n" +
                        "如果想改用本应用的免管理器内置模式，请先卸载当前豆包，再从应用商店安装官方版豆包输入法后回到本页。",
                    announce
                )
                setPrimary(h, "卸载当前豆包输入法") {
                    DoubaoInstaller.requestUninstallDoubao(act)
                }
                addSecondary(h, "刷新状态") { refresh(act, announce = true) }
            }

            st.kind == ApkPatcher.PatchKind.ORIGINAL && prepared != null && !prepared.isHotUpdate -> {
                setSummary(
                    h,
                    "修补版豆包（${prepared.doubaoVersionName}）已经生成完成。\n\n" +
                        "因为安装包签名已变，需要先卸载原版豆包输入法，卸完返回本页即可安装修补版。\n" +
                        "提示：卸载会清除豆包本地词库和登录状态，如需保留请先在豆包里开启云同步。",
                    announce
                )
                setPrimary(h, "卸载原版豆包输入法") {
                    val app = act.applicationContext
                    h.primary.isEnabled = false
                    Thread {
                        val verified = runCatching {
                            val current = ApkPatcher.inspect(app)
                            current.kind == ApkPatcher.PatchKind.ORIGINAL &&
                                ApkPatcher.loadPrepared(app, current)?.dir == prepared.dir
                        }.getOrDefault(false)
                        main.post {
                            if (!live(h)) return@post
                            if (verified && runCatching { ApkPatcher.confirmUninstall(prepared) }.isSuccess) {
                                DoubaoInstaller.requestUninstallDoubao(act)
                            }
                            refresh(act, true)
                        }
                    }.start()
                }
                addExport(h, prepared)
                addSecondary(h, "我已卸载，刷新状态") { refresh(act, announce = true) }
                addSecondary(h, "重新生成修补包") {
                    confirmAndPatch(h, st, autoInstallAfter = false)
                }
            }

            st.kind == ApkPatcher.PatchKind.ORIGINAL -> {
                val verNote = if (st.supported) {
                    "已检测到官方版豆包输入法 ${st.versionName}（已适配）。"
                } else {
                    "已检测到官方版豆包输入法 ${st.versionName}（注意：当前仅验证了 ${ConfigSpec.SUPPORTED_NAMES}，其他版本部分功能可能失效）。"
                }
                val splitNote = if (st.splitApks.isNotEmpty()) {
                    "\n检测到分包安装（共 ${st.splitApks.size + 1} 个包），向导会自动一并签名处理。"
                } else ""
                setSummary(
                    h,
                    "$verNote$splitNote\n\n" +
                        "点击下方按钮，在手机本地生成嵌入了无障碍模块的修补安装包（耗时约 10 到 20 秒）。",
                    announce
                )
                setPrimary(h, "一键生成修补安装包") {
                    confirmAndPatch(h, st, autoInstallAfter = false)
                }
                addSecondary(h, "刷新状态") { refresh(act, announce = true) }
            }

            st.kind == ApkPatcher.PatchKind.LOCAL_INTEGRATED -> {
                val needSync = !st.embeddedModuleMatchesCurrent
                if (needSync) {
                    val preparedHot = prepared?.takeIf { it.isHotUpdate }
                    if (preparedHot != null) {
                        setSummary(
                            h,
                            "新版无障碍模块（${ConfigSpec.MODULE_VERSION_NAME}）已准备就绪。\n" +
                                "点击下方按钮即可直接覆盖安装豆包输入法，无需卸载、不丢词库。",
                            announce
                        )
                        setPrimary(h, "覆盖安装新版豆包输入法") {
                            startInstall(h, preparedHot, forceSession = false)
                        }
                        if (preparedHot.singleApk) {
                            addSecondary(h, "换用系统会话模式安装（备用）") {
                                startInstall(h, preparedHot, forceSession = true)
                            }
                        }
                        addExport(h, preparedHot)
                        addSecondary(h, "复制安装修补日志") {
                            act.toast(PatchLog.copyToClipboard(act))
                        }
                        addSecondary(h, "重新生成覆盖包") {
                            confirmAndPatch(h, st, autoInstallAfter = true)
                        }
                    } else {
                        setSummary(
                            h,
                            "检测到豆包输入法（${st.versionName}）里集成的模块与当前安装的模块（${ConfigSpec.MODULE_VERSION_NAME}）不一致。\n\n" +
                                "点击下方按钮可一键同步当前模块并覆盖安装，无需卸载豆包、不丢词库和登录状态。",
                            announce
                        )
                        setPrimary(h, "一键同步新版模块到豆包") {
                            confirmAndPatch(h, st, autoInstallAfter = true)
                        }
                    }
                    addSecondary(h, "刷新状态") { refresh(act, announce = true) }
                } else {
                    if (prepared != null) ApkPatcher.clearPrepared(act, prepared)
                    setSummary(
                        h,
                        "配置完成：豆包输入法（${st.versionName}）已嵌入最新版无障碍模块（${ConfigSpec.MODULE_VERSION_NAME}）。\n\n" +
                            "如果是刚装好，请先在系统输入法设置里启用豆包输入法并设为默认，然后弹出一次键盘即可使用。",
                        announce
                    )
                    setPrimary(h, "打开系统输入法设置（启用豆包）") {
                        DoubaoInstaller.openInputMethodSettings(act)
                    }
                    addSecondary(h, "切换默认输入法") {
                        DoubaoInstaller.showImePicker(act)
                    }
                    addSecondary(h, "返回首页查询模块状态") {
                        act.finish()
                    }
                    addSecondary(h, "强制重新同步模块（修复异常）") {
                        confirmAndPatch(h, st, autoInstallAfter = true)
                    }
                }
            }
        }
    }

    private fun confirmAndPatch(h: Holder, st: ApkPatcher.DoubaoStatus, autoInstallAfter: Boolean) {
        if (!st.supported) {
            h.dialog = AlertDialog.Builder(h.act)
                .setTitle("豆包版本未在适配列表中")
                .setMessage(
                    "当前豆包输入法版本是 ${st.versionName}，本模块已验证的版本为 ${ConfigSpec.SUPPORTED_NAMES}。" +
                        "继续修补后部分无障碍功能可能失效。是否仍要继续？"
                )
                .setPositiveButton("仍要继续") { _, _ -> startPatch(h, autoInstallAfter) }
                .setNegativeButton("取消", null)
                .show()
        } else {
            startPatch(h, autoInstallAfter)
        }
    }

    private fun startPatch(h: Holder, autoInstallAfter: Boolean) {
        if (h.busy) return
        h.busy = true
        h.primary.isEnabled = false
        h.secondaryBox.removeAllViews()
        setSummary(h, "正在处理中，请稍候，不要关闭页面…", announce = true)

        Thread {
            try {
                val prepared = ApkPatcher.patchDoubao(h.act.applicationContext) { msg ->
                    main.post {
                        if (live(h)) {
                            h.primary.text = msg
                            h.summary.text = "正在处理：$msg"
                        }
                    }
                }
                main.post {
                    h.busy = false
                    if (!live(h)) return@post
                    if (autoInstallAfter) {
                        setSummary(h, "同步包已生成，正在打开系统安装确认界面…", announce = true)
                        startInstall(h, prepared)
                    } else {
                        refresh(h.act, announce = true)
                    }
                }
            } catch (t: Throwable) {
                main.post {
                    h.busy = false
                    if (!live(h)) return@post
                    val detail = t.message ?: t.javaClass.simpleName
                    h.dialog = AlertDialog.Builder(h.act)
                        .setTitle("修补失败")
                        .setMessage("生成修补包时出错：$detail")
                        .setPositiveButton("确定", null)
                        .setNeutralButton("复制错误日志") { _, _ ->
                            h.act.toast(PatchLog.copyToClipboard(h.act))
                        }
                        .show()
                    refresh(h.act, announce = true)
                }
            }
        }.start()
    }

    private fun startInstall(h: Holder, prepared: ApkPatcher.PreparedOutput, forceSession: Boolean = false) {
        if (h.busy) return
        h.busy = true
        h.primary.isEnabled = false
        h.primary.text = "正在准备系统安装器…"
        setSummary(h, "正在提交安装包给系统安装器，请稍候…", announce = true)

        DoubaoInstaller.installPrepared(h.act, prepared, forceSession = forceSession) { done, ok, msg ->
            if (!live(h)) return@installPrepared
            if (!done) {
                // 系统安装界面已打开：解除 busy，安装完成返回本页时 onResume 可自动刷新状态
                h.busy = false
                h.primary.isEnabled = true
                h.primary.text = if (forceSession) "重新用会话模式安装" else "重新打开系统安装界面"
                if (h.secondaryBox.childCount == 0) {
                    if (!forceSession && prepared.singleApk) {
                        addSecondary(h, "换用系统会话模式安装（备用）") {
                            startInstall(h, prepared, forceSession = true)
                        }
                    }
                    if (prepared.singleApk) {
                        addExport(h, prepared)
                    }
                    addSecondary(h, "复制安装修补日志") {
                        h.act.toast(PatchLog.copyToClipboard(h.act))
                    }
                    addSecondary(h, "刷新状态") { refresh(h.act, announce = true) }
                }
                setSummary(h, msg, announce = true)
                return@installPrepared
            }
            h.busy = false
            if (ok) {
                setSummary(h, msg, announce = true)
                refresh(h.act, announce = true)
            } else {
                val dlg = AlertDialog.Builder(h.act)
                    .setTitle("安装未完成")
                    .setMessage(msg)
                    .setPositiveButton("确定", null)
                    .setNeutralButton("复制错误日志") { _, _ ->
                        h.act.toast(PatchLog.copyToClipboard(h.act))
                    }
                if (prepared.singleApk) {
                    dlg.setNegativeButton("导出安装包手动安装") { _, _ ->
                        exportPreparedToFolder(h, prepared)
                    }
                }
                h.dialog = dlg.show()
                refresh(h.act, announce = true)
            }
        }
    }

    private fun addExport(h: Holder, prepared: ApkPatcher.PreparedOutput) {
        if (prepared.singleApk) addSecondary(h, "导出修补安装包到文件夹（备用）") {
            exportPreparedToFolder(h, prepared)
        } else h.secondaryBox.addView(h.act.note("豆包包含分包，必须使用系统会话安装，单独导出主包无法完整安装。"))
    }

    private fun exportPreparedToFolder(h: Holder, prepared: ApkPatcher.PreparedOutput) {
        if (h.busy) return
        AppStorage.ensurePermission(h.act) {
            if (h.busy || !live(h)) return@ensurePermission
            h.busy = true
            h.primary.isEnabled = false
            setSummary(h, "正在导出修补安装包到手机存储，请稍候…", announce = true)
            Thread {
                try {
                    val out = DoubaoInstaller.exportPreparedApk(h.act, prepared)
                    val shownPath = AppStorage.displayPath(out)
                    main.post {
                        h.busy = false
                        if (!live(h)) return@post
                        h.dialog = AlertDialog.Builder(h.act)
                            .setTitle("修补安装包已导出")
                            .setMessage("已保存到：$shownPath\n\n如果刚才无法直接跳转安装，可打开手机“文件管理”找到此安装包点击安装。")
                            .setPositiveButton("知道了", null)
                            .show()
                        refresh(h.act, announce = true)
                    }
                } catch (t: Throwable) {
                    PatchLog.e(h.act, "导出修补安装包失败", t)
                    main.post {
                        h.busy = false
                        if (!live(h)) return@post
                        h.act.toast("导出失败：${t.message ?: t.javaClass.simpleName}")
                        refresh(h.act, announce = true)
                    }
                }
            }.start()
        }
    }

    private fun setSummary(h: Holder, text: String, announce: Boolean) {
        h.summary.text = text
        if (announce && text != h.lastMessage) {
            h.lastMessage = text
            h.summary.announceForAccessibility(text)
        }
    }

    private fun setPrimary(h: Holder, label: String, onClick: () -> Unit) {
        h.primary.text = label
        h.primary.visibility = View.VISIBLE
        h.primary.setOnClickListener { onClick() }
    }

    private fun addSecondary(h: Holder, label: String, onClick: () -> Unit) {
        h.secondaryBox.addView(h.act.button(label, onClick))
    }
}
