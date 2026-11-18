package diana;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Folders scanned for executables and shortcuts. The list is editable from the
 * settings window and persisted in {@code ~/.diana/scanpaths.txt}.
 */
public class ScanLocations {

    public record Location(Path path, int depth) {
        @Override
        public String toString() {
            return path + "  (depth " + depth + ")";
        }
    }

    private static final Path FILE = Path.of(System.getProperty("user.home"), ".diana", "scanpaths.txt");
    private static final int MIN_DEPTH = 1;
    private static final int MAX_DEPTH = 8;

    private final List<Location> locations = new ArrayList<>();

    public ScanLocations() {
        if (!load()) {
            locations.addAll(defaults());
            save();
        }
    }

    public synchronized List<Location> get() {
        return List.copyOf(locations);
    }

    public synchronized void set(List<Location> newLocations) {
        Map<String, Location> unique = new LinkedHashMap<>();
        for (Location loc : newLocations) {
            if (loc == null || loc.path() == null) {
                continue;
            }
            int depth = Math.max(MIN_DEPTH, Math.min(MAX_DEPTH, loc.depth()));
            unique.put(key(loc.path()), new Location(loc.path(), depth));
        }
        locations.clear();
        locations.addAll(unique.values());
        save();
    }

    private static String key(Path path) {
        return path.toString().toLowerCase(Locale.ROOT);
    }

    private boolean load() {
        if (!Files.isRegularFile(FILE)) {
            return false;
        }
        try {
            for (String line : Files.readAllLines(FILE, StandardCharsets.UTF_8)) {
                String[] parts = line.split("\t", 2);
                if (parts.length != 2) {
                    continue;
                }
                try {
                    locations.add(new Location(Paths.get(parts[1]), Integer.parseInt(parts[0])));
                } catch (RuntimeException ignored) {
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void save() {
        try {
            Files.createDirectories(FILE.getParent());
            StringBuilder sb = new StringBuilder();
            for (Location loc : locations) {
                sb.append(loc.depth()).append('\t').append(loc.path()).append('\n');
            }
            Files.writeString(FILE, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private static List<Location> defaults() {
        Map<String, Location> map = new LinkedHashMap<>();
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(";")) {
                if (!dir.isBlank()) {
                    add(map, Paths.get(dir), 1);
                }
            }
        }
        String appData = System.getenv("APPDATA");
        if (appData != null) {
            add(map, Paths.get(appData, "Microsoft", "Windows", "Start Menu", "Programs"), 4);
        }
        add(map, Paths.get("C:\\ProgramData\\Microsoft\\Windows\\Start Menu\\Programs"), 4);
        add(map, Paths.get("C:\\Program Files"), 2);
        add(map, Paths.get("C:\\Program Files (x86)"), 2);
        add(map, Paths.get("D:\\Application"), 2);
        String userHome = System.getProperty("user.home");
        if (userHome != null) {
            add(map, Paths.get(userHome, "Desktop"), 2);
        }
        return new ArrayList<>(map.values());
    }

    private static void add(Map<String, Location> map, Path path, int depth) {
        map.putIfAbsent(key(path), new Location(path, depth));
    }
}
