import io.github.doubaoa11y.ui.BackupResourceTransaction;
import io.github.doubaoa11y.ui.LegacyBackupFormat;
import io.github.doubaoa11y.ui.ZipBackup;
import io.github.doubaoa11y.NativeRestoreFiles;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;

/** ZIP路径/限额、社区语法与事务回滚检查；只操作新建的隔离临时目录。 */
public final class ZipBackupCheck {
    private static int count;
    private static Path fixture;
    private static void check(boolean ok) { if (!ok) throw new AssertionError("ZIP check " + (count + 1)); count++; }
    private static byte[] utf(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static byte[] zip(Map<String, byte[]> files) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : files.entrySet()) { zip.putNextEntry(new ZipEntry(e.getKey())); zip.write(e.getValue()); zip.closeEntry(); }
        }
        return out.toByteArray();
    }
    private static Map<String, byte[]> files(String... names) {
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (String name : names) result.put(name, utf("{}"));
        return result;
    }
    private static void rejectZip(InputStream input) {
        try { ZipBackup.INSTANCE.read(input, fixture.toFile()).close(); }
        catch (Exception expected) { count++; return; }
        throw new AssertionError("accepted unsafe ZIP");
    }
    private static LegacyBackupFormat.Result legacy(String body) { return LegacyBackupFormat.INSTANCE.parse(utf("DOUBAOA11Y-BACKUP-1\n" + body)); }
    private static void rejectLegacy(String body) {
        try { legacy(body); } catch (Exception expected) { count++; return; }
        throw new AssertionError("accepted invalid legacy settings");
    }
    private static final Function0<Unit> NOOP = () -> Unit.INSTANCE;
    private static void clean(Path path) throws IOException {
        if (!path.toAbsolutePath().normalize().startsWith(fixture.toAbsolutePath().normalize())) throw new IOException("invalid fixture cleanup");
        if (Files.isDirectory(path)) try (java.util.stream.Stream<Path> children = Files.list(path)) {
            for (Path child : (Iterable<Path>) children::iterator) clean(child);
        }
        Files.deleteIfExists(path);
    }
    public static void main(String[] args) throws Exception {
        fixture = Files.createTempDirectory(Paths.get("."), "zip-backup-fixture-").toAbsolutePath();
        try {
            NativeRestoreFiles restore = NativeRestoreFiles.INSTANCE;
            int limit = NativeRestoreFiles.MANIFEST_LIMIT;
            check(restore.readManifest(new ByteArrayInputStream(new byte[limit])).length == limit);
            ByteArrayInputStream largeManifest = new ByteArrayInputStream(new byte[limit + 2]);
            try { restore.readManifest(largeManifest); throw new AssertionError("accepted oversized manifest"); }
            catch (IllegalArgumentException expected) { check(largeManifest.available() == 1); }

            Path pending = Files.createDirectory(fixture.resolve("pending"));
            Files.write(pending.resolve("old"), utf("previous"));
            File staged = fixture.resolve("stage").toFile();
            try { restore.publish(staged, pending.toFile()); throw new AssertionError("published missing stage"); }
            catch (IllegalStateException expected) { check(Files.exists(pending.resolve("old"))); }
            Files.move(pending, fixture.resolve("pending.previous"));
            check(restore.withPending(pending.toFile(), () -> Files.exists(pending.resolve("old"))));
            Files.createDirectory(staged.toPath());
            Files.write(staged.toPath().resolve("new"), utf("verified"));
            restore.publish(staged, pending.toFile());
            check(Files.exists(pending.resolve("new")) && !Files.exists(pending.resolve("old")));
            Map<String, byte[]> nativeFiles = files("settings.json", "sounds/key.audio");
            try (ZipBackup.Contents data = ZipBackup.INSTANCE.read(new ByteArrayInputStream(zip(nativeFiles)), fixture.toFile())) {
                check(!data.getLegacy()); check(data.getSounds().keySet().equals(Collections.singleton("key")));
                check(Arrays.equals(utf("{}"), Files.readAllBytes(data.getSounds().get("key").toPath())));
            }
            Map<String, byte[]> community = files("settings.txt", "sfx/tt_complete.ogg");
            community.put("settings.txt", utf("DOUBAOA11Y-BACKUP-1\nb\tnativeEnter\ttrue\n"));
            try (ZipBackup.Contents data = ZipBackup.INSTANCE.read(new ByteArrayInputStream(zip(community)), fixture.toFile())) {
                check(data.getLegacy()); check(data.getSounds().containsKey("boundary"));
            }
            for (String path : Arrays.asList("../escape", "/absolute", "C:/escape", "sounds/../escape", "sounds\\key.audio", "sounds//key.audio", "unrelated.txt"))
                rejectZip(new ByteArrayInputStream(zip(files("settings.json", path))));
            rejectZip(new ByteArrayInputStream(zip(files("settings.json", "settings.txt"))));
            rejectZip(new ByteArrayInputStream(zip(files("settings.json", "sfx/tt_key.ogg"))));
            rejectZip(new ByteArrayInputStream(zip(files("sounds/key.audio"))));
            Map<String, byte[]> directory = files("settings.json", "sounds/");
            rejectZip(new ByteArrayInputStream(zip(directory)));
            Map<String, byte[]> oversized = files("settings.json", "sounds/key.audio");
            oversized.put("sounds/key.audio", new byte[ZipBackup.MAX_AUDIO + 1]);
            rejectZip(new ByteArrayInputStream(zip(oversized)));
            oversized = files("settings.json"); oversized.put("settings.json", new byte[1024 * 1024 + 1]);
            rejectZip(new ByteArrayInputStream(zip(oversized)));
            byte[] duplicates = zip(files("settings.json", "settings.xson"));
            byte[] from = utf("settings.xson"), to = utf("settings.json");
            for (int i = 0; i <= duplicates.length - from.length; i++) {
                boolean match = true; for (int j = 0; j < from.length; j++) if (duplicates[i+j] != from[j]) { match = false; break; }
                if (match) System.arraycopy(to, 0, duplicates, i, to.length);
            }
            rejectZip(new ByteArrayInputStream(duplicates));
            InputStream padding = new InputStream() {
                long left = ZipBackup.MAX_TOTAL;
                @Override public int read() { if (left-- <= 0) return -1; return 0; }
                @Override public int read(byte[] bytes, int off, int len) { if (left <= 0) return -1; int n = (int)Math.min(left, len); Arrays.fill(bytes, off, off+n, (byte)0); left-=n; return n; }
            };
            rejectZip(new SequenceInputStream(new ByteArrayInputStream(zip(files("settings.json"))), padding));

            LegacyBackupFormat.Result old = legacy("b\tnativeEnter\ttrue\ni\tsfxKey\t3\nb\tmoduleSound\ttrue\ni\tsfxVolume\t70\nb\thideShortcut\ttrue\ns\tcustomWords\t高\\t提高的高\\n\nb\trunLog\ttrue\n");
            check(Boolean.TRUE.equals(old.getData().getSettings().get("nativeEnter")));
            check(Integer.valueOf(3).equals(old.getData().getSettings().get("sfxKey")));
            check(Boolean.TRUE.equals(old.getData().getSettings().get("soundOn")));
            check(Integer.valueOf(70).equals(old.getData().getSettings().get("soundVolume")));
            check(Boolean.FALSE.equals(old.getData().getShowLauncher()));
            check(old.getData().getWords() == null && !old.getWarnings().isEmpty());
            check(!old.getData().getSettings().containsKey("runLog"));
            check(legacy("i\ttypeMode\t999\n").getData().getSettings().isEmpty());
            check(legacy("s\tsentenceSymbols\ta\\\\b\\n。\n").getData().getSettings().get("sentenceSymbols").equals("a\\b\n。"));
            rejectLegacy("b\tnativeEnter\tinvalid\n");
            rejectLegacy("i\tnativeEnter\t1\n");
            rejectLegacy("b\tnativeEnter\ttrue\nb\tnativeEnter\tfalse\n");
            rejectLegacy("b\tmoduleSound\ttrue\nb\tsoundOn\tfalse\n");
            rejectLegacy("b\thideShortcut\ttrue\nb\tshowShortcut\ttrue\n");
            rejectLegacy("s\tsentenceSymbols\tbad\\q\n");
            try { LegacyBackupFormat.INSTANCE.parse(utf("UNKNOWN\n")); throw new AssertionError(); } catch (IllegalArgumentException expected) { count++; }

            Path directoryRoot = Files.createDirectory(fixture.resolve("transaction"));
            Path sounds = Files.createDirectory(directoryRoot.resolve("sounds"));
            Files.write(sounds.resolve("key.audio"), new byte[]{1}); Files.write(sounds.resolve("boundary.audio"), new byte[]{2});
            Path imported = Files.write(fixture.resolve("imported.audio"), new byte[]{9});
            Map<String, File> incoming = Collections.singletonMap("key", imported.toFile());
            String[] setting = {"old"}; boolean[] icon = {false};
            try {
                BackupResourceTransaction.INSTANCE.restore(sounds.toFile(), incoming, true,
                    () -> { setting[0]="new"; return Unit.INSTANCE; }, () -> { setting[0]="old"; return Unit.INSTANCE; },
                    () -> { throw new IllegalStateException("injected icon failure"); }, () -> { icon[0]=false; return Unit.INSTANCE; });
                throw new AssertionError("rollback not triggered");
            } catch (IllegalStateException expected) { count++; }
            check(setting[0].equals("old") && !icon[0]);
            check(Files.readAllBytes(sounds.resolve("key.audio"))[0] == 1 && Files.exists(sounds.resolve("boundary.audio")));
            BackupResourceTransaction.INSTANCE.restore(sounds.toFile(), incoming, false, NOOP, NOOP, NOOP, NOOP);
            check(Files.readAllBytes(sounds.resolve("key.audio"))[0] == 9 && Files.exists(sounds.resolve("boundary.audio")));
            BackupResourceTransaction.INSTANCE.restore(sounds.toFile(), Collections.emptyMap(), true, NOOP, NOOP, NOOP, NOOP);
            try (java.util.stream.Stream<Path> files = Files.list(sounds)) { check(files.count() == 0); }
            BackupResourceTransaction.INSTANCE.restore(sounds.toFile(), null, false, () -> { setting[0]="json"; return Unit.INSTANCE; }, NOOP, NOOP, NOOP);
            check(setting[0].equals("json") && Files.isDirectory(sounds));
            Path absent = Files.createDirectory(fixture.resolve("absent")).resolve("sounds");
            try {
                BackupResourceTransaction.INSTANCE.restore(absent.toFile(), incoming, true,
                    () -> { throw new IllegalStateException("injected preferences failure"); }, NOOP, NOOP, NOOP);
                throw new AssertionError();
            } catch (IllegalStateException expected) { count++; }
            check(!Files.exists(absent));
            try {
                BackupResourceTransaction.INSTANCE.restore(directoryRoot.resolve("other").toFile(), incoming, true, NOOP, NOOP, NOOP, NOOP);
                throw new AssertionError();
            } catch (IllegalArgumentException expected) { count++; }
            if (Arrays.asList(args).contains("--audio")) {
                File valid = Files.write(fixture.resolve("valid.wav"), wave(200)).toFile();
                ZipBackup.INSTANCE.validateAudio(valid); count++;
                for (File invalid : Arrays.asList(Files.write(fixture.resolve("invalid.audio"), utf("not audio")).toFile(),
                    Files.write(fixture.resolve("long.wav"), wave(6000)).toFile())) {
                    try { ZipBackup.INSTANCE.validateAudio(invalid); throw new AssertionError("accepted invalid audio"); }
                    catch (Exception expected) { count++; }
                }
            }
            System.out.println("PASS " + count + " ZIP/legacy/transaction checks; isolated fixtures only");
        } finally { clean(fixture); }
    }
    private static byte[] wave(int milliseconds) throws IOException {
        int data = 16000 * 2 * milliseconds / 1000;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(utf("RIFF")); le(out, data+36, 4); out.write(utf("WAVEfmt ")); le(out,16,4); le(out,1,2); le(out,1,2);
        le(out,16000,4); le(out,32000,4); le(out,2,2); le(out,16,2); out.write(utf("data")); le(out,data,4); out.write(new byte[data]); return out.toByteArray();
    }
    private static void le(OutputStream out, int value, int size) throws IOException { for(int i=0;i<size;i++) out.write(value >>> (8*i) & 255); }
}
