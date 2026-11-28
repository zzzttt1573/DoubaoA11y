import io.github.doubaoa11y.hook.NativeBackup;
import io.github.doubaoa11y.ui.AppStorage;
import io.github.doubaoa11y.ui.BackupFormat;
import io.github.doubaoa11y.ui.BackupResourceTransaction;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.stream.Stream;
import org.json.JSONObject;
import kotlin.Unit;

/** JVM check using an Android-compatible org.json implementation ahead of android.jar. */
public final class BackupSafetyCheck {
    private static void check(boolean value) {
        if (!value) throw new AssertionError();
    }

    private static void words() {
        String words = "高\t提高\n〇\t零\n㐀\t扩展A\n" + Character.toString(0x2EBF0) + "\t扩展I\n豈\t兼容";
        byte[] bytes = BackupFormat.INSTANCE.encode(Collections.emptyMap(), words, true);
        check(words.equals(BackupFormat.INSTANCE.parse(bytes).getWords()));
        for (String invalid : new String[]{"〇\t重复\n〇\t重复", "〇〇\t两个字", "\ud800\t半个代理对"}) {
            try {
                BackupFormat.INSTANCE.encode(Collections.emptyMap(), invalid, true);
                throw new AssertionError("Accepted invalid entry");
            } catch (IllegalArgumentException expected) { }
        }
    }

    private static void recover(Path root, boolean committed) throws Exception {
        Path rollback = Files.createDirectories(root.resolve("rollback/shared_prefs")).getParent();
        Path pending = Files.createDirectories(root.resolve("pending"));
        Files.writeString(pending.resolve("restore.ready"), committed ? "old" : "new");
        String existing = "shared_prefs/existing.xml";
        String created = "shared_prefs/created.xml";
        String untouched = "shared_prefs/untouched.xml";
        Files.createDirectories(root.resolve("shared_prefs"));
        Files.writeString(root.resolve(existing), "updated");
        Files.writeString(root.resolve(created), "created");
        Files.writeString(root.resolve(untouched), "unchanged");
        Files.writeString(rollback.resolve(existing), "original");
        JSONObject units = new JSONObject().put(existing, true).put(created, false).put(untouched, true);
        Files.writeString(rollback.resolve("journal.json"), new JSONObject().put("ready", "old").put("units", units).toString());
        if (committed) Files.createFile(rollback.resolve("committed"));
        Method finish = NativeBackup.class.getDeclaredMethod("finishTransaction", File.class, File.class, File.class);
        finish.setAccessible(true);
        if (!committed) {
            String blocked = "databases/fixture.db";
            units.put(blocked, true);
            Files.writeString(rollback.resolve("journal.json"), new JSONObject().put("ready", "old").put("units", units).toString());
            Files.createDirectory(rollback.resolve("databases"));
            Files.writeString(rollback.resolve(blocked), "database");
            Files.writeString(root.resolve("databases"), "blocks directory creation");
            try {
                finish.invoke(NativeBackup.INSTANCE, root.toFile(), pending.toFile(), rollback.toFile());
                throw new AssertionError("Rollback failure ignored");
            } catch (InvocationTargetException expected) {
                check(expected.getCause() instanceof IllegalStateException);
                check(Files.readString(rollback.resolve(blocked)).equals("database"));
                check(Files.isRegularFile(rollback.resolve("journal.json")));
            }
            Files.delete(root.resolve("databases"));
        }
        finish.invoke(NativeBackup.INSTANCE, root.toFile(), pending.toFile(), rollback.toFile());
        if (!committed) check(Files.readString(root.resolve("databases/fixture.db")).equals("database"));
        check(Files.readString(root.resolve(existing)).equals(committed ? "updated" : "original"));
        check(Files.readString(root.resolve(untouched)).equals("unchanged"));
        check(Files.exists(root.resolve(created)) == committed);
        check(Files.exists(pending) != committed);
        check(!Files.exists(rollback));

        Files.createDirectories(rollback);
        Files.writeString(rollback.resolve("original"), "keep");
        try {
            finish.invoke(NativeBackup.INSTANCE, root.toFile(), pending.toFile(), rollback.toFile());
            throw new AssertionError("Missing journal accepted");
        } catch (InvocationTargetException expected) {
            check(expected.getCause() instanceof IllegalStateException);
            check(Files.readString(rollback.resolve("original")).equals("keep"));
        }
    }

    private static void publish(Path dir) throws Exception {
        Path target = Files.writeString(dir.resolve("export.tsv"), "original");
        try {
            AppStorage.INSTANCE.publish(target.toFile(), out -> { throw new IllegalStateException("injected"); });
            throw new AssertionError();
        } catch (IllegalStateException expected) { }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            ArrayList<Future<File>> futures = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                final int index = i;
                futures.add(pool.submit(() -> AppStorage.INSTANCE.publish(target.toFile(), out -> {
                    try { out.write(index); } catch (IOException e) { throw new AssertionError(e); }
                    return Unit.INSTANCE;
                })));
            }
            HashSet<File> files = new HashSet<>();
            for (int i = 0; i < futures.size(); i++) {
                File file = futures.get(i).get();
                check(files.add(file));
                check(Files.readAllBytes(file.toPath())[0] == i);
            }
        } finally { pool.shutdownNow(); }
        check(Files.readString(target).equals("original"));
        try (Stream<Path> files = Files.list(dir)) { check(files.count() == 13); }
    }

    private static void limits(Path root) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            AppStorage.INSTANCE.copyLimited(new ByteArrayInputStream(new byte[70000]), output, 65536L);
            throw new AssertionError("Copied past limit");
        } catch (IllegalArgumentException expected) {
            check(output.size() <= 65536);
        }
        kotlin.jvm.functions.Function0<Unit> unexpected = () -> {
            throw new AssertionError("Preference or icon callback during audio preparation");
        };
        try {
            BackupResourceTransaction.INSTANCE.restore(root.resolve("sounds").toFile(),
                Collections.singletonMap("invalid slot", root.toFile()), true,
                unexpected, unexpected, unexpected, unexpected);
            throw new AssertionError("Invalid audio slot accepted");
        } catch (IllegalArgumentException expected) { }
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("backup-safety-");
        try {
            words();
            recover(root.resolve("interrupted"), false);
            recover(root.resolve("committed"), true);
            publish(Files.createDirectory(root.resolve("exports")));
            limits(root);
            System.out.println("PASS backup Unicode, interrupted restore, committed cleanup, exclusive exports, I/O limits and rollback scope");
        } finally {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(file);
            }
        }
    }
}
