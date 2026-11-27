import io.github.doubaoa11y.hook.Candidates.RawSnapshot;
import io.github.doubaoa11y.hook.VoiceEchoGate;
import java.util.List;

public final class InputSessionCheck {
    public static void main(String[] args) {
        RawSnapshot first = new RawSnapshot("py", List.of("甲|乙", "丙"), List.of(0, 1));
        if (first.equals(new RawSnapshot("py", List.of("甲", "乙|丙"), List.of(0, 1))))
            throw new AssertionError("candidate delimiter collision");
        if (!first.equals(new RawSnapshot("py", List.of("甲|乙", "丙"), List.of(0, 1))))
            throw new AssertionError("identical snapshot");
        if (first.equals(new RawSnapshot("py", first.getItems(), List.of(1, 2))))
            throw new AssertionError("changed engine indices");

        VoiceEchoGate gate = new VoiceEchoGate();
        long old = gate.begin();
        long current = gate.begin();
        if (gate.accept(old) || !gate.accept(current) || gate.accept(current))
            throw new AssertionError("stale or duplicate voice delivery");
        long cancelled = gate.begin();
        gate.cancel();
        if (gate.accept(cancelled)) throw new AssertionError("delivery after input end");
        System.out.println("PASS candidate snapshot equality and voice echo generations");
    }
}
