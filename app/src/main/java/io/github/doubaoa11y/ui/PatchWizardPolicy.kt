package io.github.doubaoa11y.ui

import io.github.doubaoa11y.patch.ApkPatcher

/**
 * 修补向导页面纯决策表（脱离 Android View，便于 JVM 全分支回归测试，守住集成/本地分流与防误卸红线）。
 */
internal object PatchWizardPolicy {
    enum class Stage {
        NEED_INSTALL_PERMISSION,
        INSTALL_PREPARED,
        NEED_OFFICIAL_DOUBAO,
        OTHER_PATCHED_UNINSTALL,
        UNINSTALL_ORIGINAL_FOR_PREPARED,
        GENERATE_PATCH,
        INSTALL_HOT_UPDATE,
        GENERATE_HOT_UPDATE,
        INTEGRATED_READY
    }

    data class Input(
        val canInstall: Boolean,
        val kind: ApkPatcher.PatchKind,
        val embeddedModuleMatchesCurrent: Boolean,
        val hasPrepared: Boolean,
        val preparedIsHotUpdate: Boolean,
        val preparedSingleApk: Boolean
    )

    data class Decision(
        val stage: Stage,
        val showSessionFallback: Boolean = false,
        val showManualExport: Boolean = false,
        val shouldClearStalePrepared: Boolean = false
    )

    @JvmStatic
    fun decide(input: Input): Decision = when {
        !input.canInstall -> Decision(Stage.NEED_INSTALL_PERMISSION)

        input.kind == ApkPatcher.PatchKind.NOT_INSTALLED && input.hasPrepared && !input.preparedIsHotUpdate ->
            Decision(
                stage = Stage.INSTALL_PREPARED,
                showSessionFallback = input.preparedSingleApk,
                showManualExport = input.preparedSingleApk
            )

        input.kind == ApkPatcher.PatchKind.NOT_INSTALLED ->
            Decision(Stage.NEED_OFFICIAL_DOUBAO)

        input.kind == ApkPatcher.PatchKind.OTHER_PATCHED ->
            Decision(Stage.OTHER_PATCHED_UNINSTALL)

        input.kind == ApkPatcher.PatchKind.ORIGINAL && input.hasPrepared && !input.preparedIsHotUpdate ->
            Decision(
                stage = Stage.UNINSTALL_ORIGINAL_FOR_PREPARED,
                showManualExport = input.preparedSingleApk
            )

        input.kind == ApkPatcher.PatchKind.ORIGINAL ->
            Decision(Stage.GENERATE_PATCH)

        input.kind == ApkPatcher.PatchKind.LOCAL_INTEGRATED && !input.embeddedModuleMatchesCurrent &&
            input.hasPrepared && input.preparedIsHotUpdate ->
            Decision(
                stage = Stage.INSTALL_HOT_UPDATE,
                showSessionFallback = input.preparedSingleApk,
                showManualExport = input.preparedSingleApk
            )

        input.kind == ApkPatcher.PatchKind.LOCAL_INTEGRATED && !input.embeddedModuleMatchesCurrent ->
            Decision(Stage.GENERATE_HOT_UPDATE)

        else ->
            Decision(
                stage = Stage.INTEGRATED_READY,
                shouldClearStalePrepared = input.hasPrepared
            )
    }
}
