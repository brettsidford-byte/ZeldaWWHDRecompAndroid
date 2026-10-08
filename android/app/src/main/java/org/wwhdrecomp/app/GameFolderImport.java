package org.wwhdrecomp.app;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** Imports decrypted game data through a provider, without depending on Android file paths. */
final class GameFolderImport {
    static final class Node {
        final String id, name;
        final boolean directory;
        final long size; // -1 if the provider cannot supply it
        Node(String id, String name, boolean directory, long size) {
            this.id = id; this.name = name; this.directory = directory; this.size = size;
        }
    }
    interface Source {
        List<Node> children(String id) throws IOException;
        InputStream open(String id) throws IOException;
    }
    private static final class Entry {
        final Node node;
        final String path;
        Entry(Node node, String path) { this.node = node; this.path = path; }
    }
    private static volatile long copied, total;
    static long[] progress() { return new long[] {copied, total}; }

    static void run(Source source, String root, File game, BooleanSupplier cancelled,
                    Function<String, String> validate) throws IOException {
        copied = total = 0;
        File stage = new File(game.getParentFile(), "game-importing");
        File old = new File(game.getParentFile(), "game-before-import");
        // Recover an installation interrupted between the two renames.
        if (!game.exists() && old.exists() && !old.renameTo(game))
            throw new IOException("Cannot restore the previous game folder.");
        remove(stage);
        List<Entry> entries = new ArrayList<>();
        Set<String> paths = new HashSet<>();
        boolean code = false, content = false, executable = false;
        for (Node node : source.children(root)) {
            if (node.name == null) throw new IOException("The game folder contains an invalid file name.");
            String name = node.name.toLowerCase(Locale.ROOT);
            if (!node.directory || !(name.equals("code") || name.equals("content") || name.equals("meta"))) continue;
            if (name.equals("code")) code = true;
            if (name.equals("content")) content = true;
            collect(source, node, name, entries, paths, cancelled, 0);
        }
        boolean knownSizes = true, assets = false;
        for (Entry entry : entries) {
            if (entry.path.equals("code/cking.rpx") && !entry.node.directory) executable = true;
            if (entry.path.startsWith("content/") && !entry.node.directory) assets = true;
            if (!entry.node.directory) {
                if (entry.node.size < 0) knownSizes = false;
                else {
                    if (Long.MAX_VALUE - total < entry.node.size) throw new IOException("The game folder is too large.");
                    total += entry.node.size;
                }
            }
        }
        if (!code || !content || !executable || !assets)
            throw new IOException("Choose the parent folder containing code/cking.rpx and content. The files must be decrypted Wind Waker HD game files.");
        long reserve = 300L << 20; // room for the compiled game code
        if (total > Long.MAX_VALUE - reserve || game.getParentFile().getUsableSpace() < total + reserve)
            throw new IOException("Not enough free storage to copy the game and prepare its code.");
        if (!knownSizes) total = 0; // indeterminate progress for providers without file sizes
        try {
            if (!stage.mkdirs()) throw new IOException("Cannot create the game import folder.");
            byte[] buffer = new byte[1 << 16];
            for (Entry entry : entries) {
                checkCancelled(cancelled);
                File out = new File(stage, entry.path);
                if (entry.node.directory) {
                    if (!out.mkdirs() && !out.isDirectory()) throw new IOException("Cannot create " + entry.path);
                    continue;
                }
                long bytes = 0;
                try (InputStream in = source.open(entry.node.id); FileOutputStream file = new FileOutputStream(out)) {
                    if (in == null) throw new IOException("Cannot read " + entry.path);
                    for (int n; (n = in.read(buffer)) != -1; ) {
                        checkCancelled(cancelled);
                        file.write(buffer, 0, n);
                        bytes += n;
                        copied += n;
                    }
                }
                if (entry.node.size >= 0 && bytes != entry.node.size)
                    throw new IOException("Incomplete copy of " + entry.path + ". Try importing again.");
            }
            checkCancelled(cancelled);
            String problem = validate.apply(stage.getAbsolutePath());
            if (problem != null) throw new IOException(problem);
            checkCancelled(cancelled);
            remove(old);
            boolean hadGame = game.exists();
            if (hadGame && !game.renameTo(old)) throw new IOException("Cannot preserve the previous game folder.");
            if (!stage.renameTo(game)) {
                if (hadGame && !old.renameTo(game)) throw new IOException("Import failed; the previous game is in game-before-import.");
                throw new IOException("Cannot install the imported game folder.");
            }
            // The installed game is valid; old data can be cleaned up on the next import if needed.
            try { remove(old); } catch (IOException ignored) { }
        } finally {
            remove(stage);
        }
    }

    private static void collect(Source source, Node node, String path, List<Entry> entries,
                                Set<String> paths, BooleanSupplier cancelled, int depth) throws IOException {
        checkCancelled(cancelled);
        if (depth > 64) throw new IOException("The game folder contains too many nested folders.");
        if (!paths.add(path)) throw new IOException("Duplicate game file: " + path);
        entries.add(new Entry(node, path));
        if (node.directory) for (Node child : source.children(node.id)) {
            String name = child.name;
            if (name == null || name.isEmpty() || name.equals(".") || name.equals("..") ||
                    name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0)
                throw new IOException("The game folder contains an invalid file name.");
            if (path.equals("code") && name.equalsIgnoreCase("cking.rpx")) name = "cking.rpx";
            collect(source, child, path + "/" + name, entries, paths, cancelled, depth + 1);
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean()) throw new IOException("cancelled");
    }

    private static void remove(File file) throws IOException {
        if (!file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("Cannot read the previous import folder.");
            for (File child : children) remove(child);
        }
        if (!file.delete()) throw new IOException("Cannot remove " + file.getName());
    }
}
