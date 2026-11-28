import io.github.doubaoa11y.hook.DrawTable;
import kotlin.jvm.functions.Function0;

public class DrawRevisionCheck {
    public static void main(String[] args) {
        DrawTable table = new DrawTable("fixture");
        int[] scheduled = {0};
        Function0<Boolean> accept = () -> { scheduled[0]++; return true; };
        table.clear();
        table.scheduleAccessibilityIfChanged(() -> false);
        table.setDumpRevision(table.getRevision());
        table.scheduleAccessibilityIfChanged(accept);
        table.scheduleAccessibilityIfChanged(accept);
        if (scheduled[0] != 1) throw new AssertionError("first enabled refresh");
        table.clear();
        table.scheduleAccessibilityIfChanged(accept);
        if (scheduled[0] != 2 || table.getDumpRevision() == table.getRevision())
            throw new AssertionError("independent consumers");
        System.out.println("PASS independent draw revision consumers");
    }
}
