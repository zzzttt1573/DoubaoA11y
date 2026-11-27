package io.github.doubaoa11y.hook

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.doubaoa11y.ConfigSpec

/** 模块入口（assets/xposed_init） */
class HookEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != ConfigSpec.TARGET_PACKAGE) return
        if (installed) return
        installed = true
        try {
            L.i("加载到进程 ${lpparam.processName}，开始安装 hook")
            val cl = lpparam.classLoader
            installStep("MetasecCompat.install") { MetasecCompat.install(lpparam) }
            installStep("NativeBackup.installEarlyRestoreHook") { NativeBackup.installEarlyRestoreHook(lpparam.appInfo?.dataDir) }
            installStep("KeyboardViewHooks.install") { KeyboardViewHooks.install(cl) }
            installStep("JniHooks.install") { JniHooks.install(cl) }
            installStep("ServiceHooks.install") { ServiceHooks.install(cl) }
            installStep("InputHooks.install") { InputHooks.install() }
            installStep("InputHooks.installInner") { InputHooks.installInner(cl) }
            installStep("ViewA11yHooks.install") { ViewA11yHooks.install() }
            installStep("Voice.install") { Voice.install() }
            installStep("Candidates.install") { Candidates.install() }
            installStep("CustomToolbar.install") { CustomToolbar.install() }
            installStep("ToolbarInject.install") { ToolbarInject.install(cl) }
            installStep("EditHooks.install") { EditHooks.install(cl) }
            installStep("StepSlider.install") { StepSlider.install(cl) }
            // 豆包自己的设置页在 :Setting 进程，只在那里补开关、复选框、滑块的无障碍信息
            if (lpparam.processName == ConfigSpec.TARGET_PACKAGE + ":Setting") installStep("NativeSettingsA11y.install") { NativeSettingsA11y.install(cl) }
            L.i("hook 安装结束：${Hooker.summary()}")
            val d = Hooker.details()
            if (d.isNotBlank()) L.w("hook 问题明细：\n$d")
        } catch (t: Throwable) {
            L.e("安装 hook 出错（输入法不受影响）", t)
        }
    }

    private inline fun installStep(name: String, install: () -> Unit) {
        try { install() } catch (t: Throwable) { L.hookError("安装 $name", t) }
    }

    companion object {
        @Volatile
        private var installed = false
    }
}
