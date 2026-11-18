package diana;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Indexes executable files and shortcuts so the launcher can search them. The
 * result of a scan is cached on disk and loaded on the next start; use
 * {@link #rescan(Runnable)} to rebuild it.
 */
public class ApplicationIndex {

    private static final Path CACHE = Path.of(System.getProperty("user.home"), ".diana", "apps.txt");
    private static final long REFRESH_DELAY_MS = 3_000;
    private static final long DIRECTORY_PAUSE_MS = 100;

    private volatile List<Path> items = List.of();
    private final LaunchHistory history;
    private final ScanLocations locations;
    private final AtomicBoolean scanning = new AtomicBoolean();
    private volatile boolean ready;

    public ApplicationIndex(LaunchHistory history, ScanLocations locations) {
        this.history = history;
        this.locations = locations;
    }

    public void start() {
        Thread t = new Thread(() -> {
            if (loadCache()) {
                ready = true;
                startBackgroundRefresh();
            } else {
                scan(true, false);
                ready = true;
            }
        }, "application-index");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Refreshes the index in the background some time after startup. The thread
     * runs at the lowest priority and pauses between directories so it does not
     * compete with interactive work.
     */
    private void startBackgroundRefresh() {
        Thread t = new Thread(() -> {
            pause(REFRESH_DELAY_MS);
            scan(true, true);
        }, "application-index-refresh");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /**
     * Rebuilds the index in the background, saves it to disk and swaps the
     * in-memory list. {@code onDone} runs on the event dispatch thread.
     */
    public void rescan(Runnable onDone) {
        Thread t = new Thread(() -> {
            try {
                scan(true, false);
            } finally {
                if (onDone != null) {
                    javax.swing.SwingUtilities.invokeLater(onDone);
                }
            }
        }, "application-index-rescan");
        t.setDaemon(true);
        t.start();
    }

    public boolean isReady() {
        return ready;
    }

    public int size() {
        return items.size();
    }

    public List<Path> search(String query, int limit) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Path> result = new ArrayList<>();
        for (Path p : items) {
            String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
            if (q.isEmpty() || name.contains(q)) {
                result.add(p);
            }
        }
        // Items launched before come first, then the most recently used ones.
        result.sort(Comparator.comparingInt((Path p) -> history.count(p)).reversed()
                .thenComparing(Comparator.comparingLong((Path p) -> history.lastUsed(p)).reversed())
                .thenComparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)));
        if (result.size() > limit) {
            return new ArrayList<>(result.subList(0, limit));
        }
        return result;
    }

    private boolean loadCache() {
        if (!Files.isRegularFile(CACHE)) {
            return false;
        }
        try {
            List<Path> loaded = new ArrayList<>();
            for (String line : Files.readAllLines(CACHE, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    loaded.add(Paths.get(line));
                }
            }
            if (loaded.isEmpty()) {
                return false;
            }
            items = loaded;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void saveCache(List<Path> list) {
        try {
            Files.createDirectories(CACHE.getParent());
            StringBuilder sb = new StringBuilder();
            for (Path p : list) {
                sb.append(p).append('\n');
            }
            Files.writeString(CACHE, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private void scan(boolean save, boolean throttle) {
        if (!scanning.compareAndSet(false, true)) {
            return;
        }
        try {
            List<Path> found = new ArrayList<>();
            Set<String> seen = ConcurrentHashMap.newKeySet();
            for (ScanLocations.Location location : locations.get()) {
                scanRoot(found, location.path(), location.depth(), seen, throttle);
            }
            found.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)));
            items = found;
            ready = true;
            if (save) {
                saveCache(found);
            }
        } finally {
            scanning.set(false);
        }
    }

    private void scanRoot(List<Path> target, Path dir, int maxDepth, Set<String> seen, boolean throttle) {
        addDirectory(target, dir, maxDepth, seen);
        if (throttle) {
            pause(DIRECTORY_PAUSE_MS);
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void addDirectory(List<Path> target, Path dir, int maxDepth, Set<String> seen) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(dir, maxDepth)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                String lower = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (lower.endsWith(".exe") || lower.endsWith(".lnk") || lower.endsWith(".bat") || lower.endsWith(".cmd")) {
                    if (seen.add(p.toString().toLowerCase(Locale.ROOT))) {
                        target.add(p);
                    }
                }
            });
        } catch (Exception ignored) {
        }
    }
}
