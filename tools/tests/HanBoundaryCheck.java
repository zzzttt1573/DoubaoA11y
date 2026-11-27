import io.github.doubaoa11y.hook.CharWords;
import io.github.doubaoa11y.ui.WordTransferFormat;
import java.util.List;
import java.util.Map;

class HanBoundaryCheck {
    private static void checkKey(String key, boolean expected) {
        if (CharWords.INSTANCE.isSingleHan(key) != expected ||
                WordTransferFormat.INSTANCE.isSingleHan(key) != expected)
            throw new AssertionError("Unexpected classification: " + key);
    }

    public static void main(String[] args) {
        for (int cp : new int[]{0x3007, 0x3400, 0x4e00, 0xf900, 0x20000, 0x2f800,
                0x31350, 0x2ebef, 0x2ebf0, 0x2ee5f}) {
            String key = new String(Character.toChars(cp));
            checkKey(key, true);
            Map<String, String> words = Map.of(key, "word");
            if (!words.equals(WordTransferFormat.INSTANCE.parse(WordTransferFormat.INSTANCE.encode(words))))
                throw new AssertionError("Word transfer lost U+" + Integer.toHexString(cp));
            if (!List.of(key, key).equals(CharWords.INSTANCE.splitHanChars(key + key)))
                throw new AssertionError("Code point split");
            checkKey(key + key, false);
        }
        for (String key : new String[]{"", "a", "\ud800", "\udc00", new String(Character.toChars(0x2ee60))})
            checkKey(key, false);
        System.out.println("HanBoundaryCheck passed");
    }
}
