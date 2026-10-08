package org.wwhdrecomp.app;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Synthetic files only: tests the import transaction without a game dump or Android device. */
public final class GameFolderImportTest {
    private static int checks;
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
    private static class FilesSource implements GameFolderImport.Source {
        public List<GameFolderImport.Node> children(String id) throws IOException {
            List<GameFolderImport.Node> nodes = new ArrayList<>();
            try (var children = Files.list(Path.of(id))) {
                for (Path p : children.sorted().toList()) nodes.add(new GameFolderImport.Node(p.toString(),
                        p.getFileName().toString(), Files.isDirectory(p), Files.isDirectory(p) ? 0 : Files.size(p)));
            }
            return nodes;
        }
        public InputStream open(String id) throws IOException { return Files.newInputStream(Path.of(id)); }
    }
    private static void write(Path root, String name, String contents) throws IOException {
        Path p = root.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, contents);
    }
    private static Path source(Path base, String name) throws IOException {
        Path p = base.resolve(name);
        write(p, "code/cking.rpx", "synthetic executable");
        write(p, "content/sub/data.bin", "synthetic asset");
        write(p, "meta/meta.xml", "synthetic metadata");
        write(p, "unrelated.txt", "do not import");
        return p;
    }
    private static void rejected(GameFolderImport.Source source, Path root, File game,
                                 BooleanSupplier cancelled, String reason) throws IOException {
        boolean threw = false;
        try { GameFolderImport.run(source, root.toString(), game, cancelled, p -> null); }
        catch (IOException e) { threw = true; }
        check(threw, reason);
        check(Files.readString(game.toPath().resolve("previous.txt")).equals("keep"), "previous game survives " + reason);
        check(!new File(game.getParent(), "game-importing").exists(), "staging cleaned after " + reason);
    }
    public static void main(String[] args) throws Exception {
        Path base = Files.createTempDirectory("wwhd-import-test");
        try {
            Path root = source(base, "dump");
            File game = base.resolve("installed/game").toFile();
            Files.createDirectories(game.toPath().getParent());
            FilesSource provider = new FilesSource();
            GameFolderImport.run(provider, root.toString(), game, () -> false, p -> {
                check(Files.exists(Path.of(p, "code/cking.rpx")), "validator receives staged executable");
                return null;
            });
            check(Files.readString(game.toPath().resolve("content/sub/data.bin")).equals("synthetic asset"), "nested assets copied");
            check(Files.exists(game.toPath().resolve("meta/meta.xml")), "metadata copied");
            check(!Files.exists(game.toPath().resolve("unrelated.txt")), "unrelated files excluded");
            check(GameFolderImport.progress()[0] == GameFolderImport.progress()[1], "byte progress completes");
            write(game.toPath(), "previous.txt", "keep");

            Path missing = source(base, "missing");
            Files.delete(missing.resolve("code/cking.rpx"));
            rejected(provider, missing, game, () -> false, "missing executable");
            rejected(provider, root, game, () -> true, "cancel during scan");
            boolean invalid = false;
            try { GameFolderImport.run(provider, root.toString(), game, () -> false, p -> "unsupported game"); }
            catch (IOException e) { invalid = true; }
            check(invalid && Files.exists(game.toPath().resolve("previous.txt")), "native validation failure preserves old game");

            rejected(new FilesSource() {
                @Override public InputStream open(String id) { return new ByteArrayInputStream(new byte[0]); }
            }, root, game, () -> false, "short reads");
            rejected(new FilesSource() {
                @Override public List<GameFolderImport.Node> children(String id) throws IOException {
                    if (id.endsWith("content")) return List.of(new GameFolderImport.Node("bad", "../escape", false, 1));
                    return super.children(id);
                }
            }, root, game, () -> false, "unsafe names");
            rejected(new FilesSource() {
                @Override public List<GameFolderImport.Node> children(String id) throws IOException {
                    List<GameFolderImport.Node> list = super.children(id);
                    if (id.endsWith("code")) list.add(list.get(0));
                    return list;
                }
            }, root, game, () -> false, "duplicate provider entries");
            AtomicBoolean cancelled = new AtomicBoolean();
            rejected(new FilesSource() {
                @Override public InputStream open(String id) throws IOException {
                    cancelled.set(true);
                    return super.open(id);
                }
            }, root, game, cancelled::get, "cancel while copying");

            // Uppercase top-level directories and executable, unknown sizes, absent optional meta.
            Path upper = base.resolve("uppercase");
            write(upper, "CODE/CKING.RPX", "synthetic executable");
            write(upper, "CONTENT/data.bin", "synthetic asset");
            GameFolderImport.run(new FilesSource() {
                @Override public List<GameFolderImport.Node> children(String id) throws IOException {
                    List<GameFolderImport.Node> out = new ArrayList<>();
                    for (var n : super.children(id)) out.add(new GameFolderImport.Node(n.id, n.name, n.directory, -1));
                    return out;
                }
            }, upper.toString(), game, () -> false, p -> null);
            check(Files.exists(game.toPath().resolve("code/cking.rpx")), "canonical executable path");
            check(GameFolderImport.progress()[1] == 0, "unknown sizes use indeterminate progress");
            check(!Files.exists(game.toPath().resolve("previous.txt")), "successful import replaces old data");

            // Recover an interrupted installation before validating a new import.
            write(game.toPath(), "previous.txt", "keep");
            File backup = new File(game.getParentFile(), "game-before-import");
            check(game.renameTo(backup), "simulate interrupted rename");
            rejected(provider, missing, game, () -> false, "recovery then rejected import");
        } finally {
            try (var paths = Files.walk(base)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
        System.out.println("Game folder import: " + checks + " checks passed");
    }
}
