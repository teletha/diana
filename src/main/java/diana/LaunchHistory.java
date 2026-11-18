package diana;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers how often and how recently each path was launched so the launcher
 * can show frequently used items first.
 */
public class LaunchHistory {
    private static final Path FILE = Path.of(System.getProperty("user.home"), ".diana", "history.txt");
    private static final int MAX_ENTRIES = 500;

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public LaunchHistory() {
        load();
    }

    public void record(Path path) {
        String key = key(path.toString());
        entries.compute(key, (k, entry) -> {
            Entry e = entry == null ? new Entry() : entry;
            e.count++;
            e.lastUsed = System.currentTimeMillis();
            return e;
        });
        save();
    }

    public int count(Path path) {
        Entry e = entries.get(key(path.toString()));
        return e == null ? 0 : e.count;
    }

    public long lastUsed(Path path) {
        Entry e = entries.get(key(path.toString()));
        return e == null ? 0L : e.lastUsed;
    }

    private static String key(String path) {
        return path.toLowerCase(Locale.ROOT);
    }

    private void load() {
        if (!Files.isRegularFile(FILE)) return;
        try {
            for (String line : Files.readAllLines(FILE, StandardCharsets.UTF_8)) {
                String[] parts = line.split("\t", 3);
                if (parts.length != 3) continue;
                try {
                    Entry e = new Entry();
                    e.count = Integer.parseInt(parts[0]);
                    e.lastUsed = Long.parseLong(parts[1]);
                    entries.put(key(parts[2]), e);
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    private synchronized void save() {
        try {
            Files.createDirectories(FILE.getParent());
            List<Map.Entry<String, Entry>> list = new ArrayList<>(entries.entrySet());
            list.sort(Comparator.comparingInt((Map.Entry<String, Entry> e) -> e.getValue().count).reversed()
                    .thenComparing(Comparator.comparingLong((Map.Entry<String, Entry> e) -> e.getValue().lastUsed)
                            .reversed()));
            StringBuilder sb = new StringBuilder();
            list.stream().limit(MAX_ENTRIES)
                    .forEach(e -> sb.append(e.getValue().count).append('\t').append(e.getValue().lastUsed)
                            .append('\t').append(e.getKey()).append('\n'));
            Files.writeString(FILE, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private static final class Entry {
        int count;
        long lastUsed;
    }
}
