import io.github.doubaoa11y.hook.TombstoneParser;
import java.io.ByteArrayInputStream;

class TombstoneBudgetCheck {
    private static final int LIMIT = 4 * 1024 * 1024;

    private static int consumed(long length) {
        byte[] data = new byte[LIMIT + 16384];
        data[0] = (byte) 0xa2;
        data[1] = 6; // Unknown length-delimited field 100.
        int pos = 2;
        do {
            int bits = (int) (length & 127);
            length >>>= 7;
            data[pos++] = (byte) (bits | (length == 0 ? 0 : 128));
        } while (length != 0);
        var input = new ByteArrayInputStream(data);
        TombstoneParser.INSTANCE.summarize(input);
        return data.length - input.available();
    }

    public static void main(String[] args) {
        // Two tag bytes and four length bytes count toward the same budget.
        int valid = consumed(LIMIT - 6);
        if (valid != LIMIT) throw new AssertionError("Exact budget consumed " + valid);
        for (long invalid : new long[]{LIMIT - 5, Long.MAX_VALUE, Long.MIN_VALUE, -1}) {
            int read = consumed(invalid);
            if (read > 8192) throw new AssertionError("Read oversized body: " + read);
        }
        System.out.println("TombstoneBudgetCheck passed");
    }
}
