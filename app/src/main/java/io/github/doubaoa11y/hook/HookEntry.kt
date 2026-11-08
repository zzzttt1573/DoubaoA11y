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
            KeyboardViewHooks.install(cl)
            JniHooks.install(cl)
            ServiceHooks.install(cl)
            InputHooks.install()
            ViewA11yHooks.install()
            Voice.install()
            Candidates.install()
            CustomToolbar.install()
            EditHooks.install(cl)
            StepSlider.install(cl)
            L.i("hook 安装结束：${Hooker.summary()}")
            val d = Hooker.details()
            if (d.isNotBlank()) L.w("hook 问题明细：\n$d")
        } catch (t: Throwable) {
            L.e("安装 hook 出错（输入法不受影响）", t)
        }
    }

    companion object {
        @Volatile
        private var installed = false
    }
}
