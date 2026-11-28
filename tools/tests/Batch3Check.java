import io.github.doubaoa11y.hook.Doubao157Hooks;
import io.github.doubaoa11y.hook.ToolbarOrderPolicy;
import io.github.doubaoa11y.hook.VoiceZoneReader;
import io.github.doubaoa11y.patch.ApkPatcher;
import io.github.doubaoa11y.ui.PatchWizardPolicy;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import kotlin.Pair;

class Batch3Check {
    private static void check(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
    }

    public static void main(String[] args) {
        // 1. VoiceZoneReader pure resolution & cancel detection
        check(VoiceZoneReader.isCancelZone("松手撤回输入"), "撤回 should be cancel");
        check(VoiceZoneReader.isCancelZone("松手取消"), "取消 should be cancel");
        check(!VoiceZoneReader.isCancelZone("松手一键发送"), "发送 should not be cancel");
        check("松手仅上屏".equals(VoiceZoneReader.resolveZone(false, true, "松手直接发送", null, null)), "send-on-release right fallback");
        check("松手一键发送".equals(VoiceZoneReader.resolveZone(false, true, "松手转文字", null, null)), "normal right fallback");
        check("松手取消语音".equals(VoiceZoneReader.resolveZone(true, false, "松手结束", "松手取消语音", "松手发送")), "dynamic left label");
        check("松手直接发送".equals(VoiceZoneReader.resolveZone(false, false, "松手直接发送", null, null)), "center label");

        // 2. ToolbarOrderPolicy merge & native projection
        List<Pair<String, Integer>> nativeVis = Arrays.asList(
                new Pair<>("btn_toolbar_emoji", 2),
                new Pair<>("btn_toolbar_clipboard", 1)
        );
        List<String> enabledCustom = Collections.singletonList("btn_a11y_upper_gesture");
        List<String> saved = Arrays.asList("btn_a11y_upper_gesture", "btn_toolbar_emoji", "btn_removed_key");
        List<String> merged = ToolbarOrderPolicy.mergeInitialOrder(nativeVis, enabledCustom, saved);
        check(merged.equals(Arrays.asList("btn_a11y_upper_gesture", "btn_toolbar_emoji", "btn_toolbar_clipboard")), "merged order");
        String[] nativeOnly = ToolbarOrderPolicy.nativeOnly(merged, new HashSet<>(enabledCustom));
        check(Arrays.equals(nativeOnly, new String[]{"btn_toolbar_emoji", "btn_toolbar_clipboard"}), "nativeOnly projection");
        List<String> toggled = ToolbarOrderPolicy.applyToggle(merged, "btn_toolbar_emoji", false);
        check(!toggled.contains("btn_toolbar_emoji"), "toggle remove");

        // 3. PatchWizardPolicy decision table
        PatchWizardPolicy.Decision noPerm = PatchWizardPolicy.decide(new PatchWizardPolicy.Input(
                false, ApkPatcher.PatchKind.LOCAL_INTEGRATED, false, false, false, true));
        check(noPerm.getStage() == PatchWizardPolicy.Stage.NEED_INSTALL_PERMISSION, "need permission");

        PatchWizardPolicy.Decision splitPrepared = PatchWizardPolicy.decide(new PatchWizardPolicy.Input(
                true, ApkPatcher.PatchKind.NOT_INSTALLED, false, true, false, false));
        check(splitPrepared.getStage() == PatchWizardPolicy.Stage.INSTALL_PREPARED && !splitPrepared.getShowManualExport(), "split hides manual export");

        PatchWizardPolicy.Decision hotReady = PatchWizardPolicy.decide(new PatchWizardPolicy.Input(
                true, ApkPatcher.PatchKind.LOCAL_INTEGRATED, false, true, true, true));
        check(hotReady.getStage() == PatchWizardPolicy.Stage.INSTALL_HOT_UPDATE && hotReady.getShowSessionFallback(), "hot update ready");

        PatchWizardPolicy.Decision readyWithStale = PatchWizardPolicy.decide(new PatchWizardPolicy.Input(
                true, ApkPatcher.PatchKind.LOCAL_INTEGRATED, true, true, true, true));
        check(readyWithStale.getStage() == PatchWizardPolicy.Stage.INTEGRATED_READY && readyWithStale.getShouldClearStalePrepared(), "clear stale prepared when matched");

        // 4. Doubao157Hooks SmartOrganize fallback mapping
        check("文本无需整理".equals(Doubao157Hooks.fallbackMessage("NO_REWRITE")), "NO_REWRITE");
        check("智能整理超时，请稍后再试".equals(Doubao157Hooks.fallbackMessage("STREAM_TIMEOUT")), "STREAM_TIMEOUT");
        check(Doubao157Hooks.fallbackMessage("CANCELED") == null, "CANCELED silent");

        System.out.println("PASS Batch3Check (VoiceZoneReader + ToolbarOrderPolicy + PatchWizardPolicy + Doubao157Hooks)");
    }
}
