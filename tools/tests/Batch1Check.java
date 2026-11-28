import io.github.doubaoa11y.ConfigSpec;
import io.github.doubaoa11y.hook.Candidates;
import io.github.doubaoa11y.hook.EditorMenuState;
import io.github.doubaoa11y.hook.Names;
import io.github.doubaoa11y.hook.TextBoundaries;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

class Batch1Check {
    private static void check(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
    }

    public static void main(String[] args) {
        // 1. EditorMenuState empty-editor filtering & cursor preservation
        List<Integer> configured = Arrays.asList(
                ConfigSpec.ACT_DEFAULT,
                ConfigSpec.ACT_PASTE,
                ConfigSpec.ACT_VOICE,
                ConfigSpec.ACT_READ_ALL,
                ConfigSpec.ACT_SMART_ORGANIZE,
                ConfigSpec.ACT_UNDO,
                ConfigSpec.ACT_CHAR_COUNT,
                ConfigSpec.ACT_COPY
        );
        EditorMenuState empty = new EditorMenuState(true, false, false);
        List<Integer> avail = empty.available(configured);
        check(avail.contains(ConfigSpec.ACT_VOICE), "ACT_VOICE should stay visible when editor is empty");
        check(avail.contains(ConfigSpec.ACT_PASTE), "ACT_PASTE should stay visible when editor is empty");
        check(avail.contains(ConfigSpec.ACT_SMART_ORGANIZE), "ACT_SMART_ORGANIZE should stay visible");
        check(avail.contains(ConfigSpec.ACT_UNDO), "ACT_UNDO should stay visible");
        check(avail.contains(ConfigSpec.ACT_CHAR_COUNT), "ACT_CHAR_COUNT should stay visible");
        check(!avail.contains(ConfigSpec.ACT_READ_ALL), "ACT_READ_ALL should be hidden when empty");
        check(!avail.contains(ConfigSpec.ACT_COPY), "ACT_COPY should be hidden when not selected");
        check(Integer.valueOf(ConfigSpec.ACT_PASTE).equals(empty.current(avail, null)), "Default empty focus should be ACT_PASTE");
        check(Integer.valueOf(ConfigSpec.ACT_VOICE).equals(empty.current(avail, ConfigSpec.ACT_VOICE)), "Should preserve ACT_VOICE across refresh");
        EditorMenuState unknown = new EditorMenuState(null, null, false);
        check(unknown.available(configured).size() == configured.size(), "Unknown state should not aggressively hide actions");

        // 2. Toolbar names
        check("超级联想".equals(Names.INSTANCE.getToolbarKeys().get("btn_toolbar_super_asso")), "super_asso toolbar key");
        check("说完即发".equals(Names.INSTANCE.getToolbarKeys().get("btn_toolbar_voice_send")), "voice_send toolbar key");
        check("超级联想".equals(Names.INSTANCE.getViewIds().get("item_super_asso")), "item_super_asso viewId");
        check("超级联想".equals(Names.INSTANCE.getViewIds().get("candidate_super_asso_button")), "candidate_super_asso_button viewId");
        check("说完即发".equals(Names.INSTANCE.getViewIds().get("item_voice_send")), "item_voice_send viewId");

        // 3. TextBoundaries consistency
        String ext = "甲\uD840\uDC00乙";
        check(TextBoundaries.INSTANCE.count(ext, false) == 3, "甲𠀀乙 should count as 3 chars");
        check(TextBoundaries.INSTANCE.selectedCount(ext, 1, 3) == 1, "𠀀 selection should count as 1 char");
        int p1 = TextBoundaries.INSTANCE.step(ext, 0, 1);
        int p2 = TextBoundaries.INSTANCE.step(ext, p1, 1);
        int p3 = TextBoundaries.INSTANCE.step(ext, p2, 1);
        check(p1 == 1 && p2 == 3 && p3 == 4, "step offsets for 甲𠀀乙 should be 1,3,4");
        check(TextBoundaries.INSTANCE.count("e\u0301 a", true) == 2, "combining char + skipWhitespace");

        // 4. Candidates hasMore parsing & RawSnapshot dedup
        Candidates.RawSnapshot sTrue = new Candidates.RawSnapshot("ni", Collections.singletonList("你"), Collections.singletonList(0), Boolean.TRUE);
        Candidates.RawSnapshot sFalse = new Candidates.RawSnapshot("ni", Collections.singletonList("你"), Collections.singletonList(0), Boolean.FALSE);
        check(!sTrue.equals(sFalse), "RawSnapshot must distinguish hasMore true vs false");
        Object[] fullArgs = new Object[36];
        fullArgs[19] = Boolean.FALSE;
        fullArgs[20] = Boolean.FALSE;
        fullArgs[21] = Boolean.TRUE;
        fullArgs[22] = Boolean.FALSE;
        check(Boolean.FALSE.equals(Candidates.RawSnapshot.parseHasMore(fullArgs)), "parseHasMore false");
        fullArgs[20] = Boolean.TRUE;
        check(Boolean.TRUE.equals(Candidates.RawSnapshot.parseHasMore(fullArgs)), "parseHasMore true");
        check(Candidates.RawSnapshot.parseHasMore(new Object[10]) == null, "parseHasMore unknown signature -> null");

        System.out.println("PASS Batch1Check (EditorMenuState + Names + TextBoundaries + Candidates.hasMore)");
    }
}
