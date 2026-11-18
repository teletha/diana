package diana;

import java.awt.Font;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

public class FontSettings {
    private static final File FILE = new File(System.getProperty("user.home"), ".diana" + File.separator + "config.properties");
    private String family = "Segoe UI";
    private int size = 14;
    private int width = 420;
    private boolean showFullPath = true;
    private int hotkeyModifier = 0x10; // VK_SHIFT
    private int hotkeyKey = 0x1C; // VK_CONVERT (変換)

    public FontSettings() {
        load();
    }

    public String getFamily() { return family; }
    public int getSize() { return size; }
    public int getWidth() { return width; }
    public boolean isShowFullPath() { return showFullPath; }
    public int getHotkeyModifier() { return hotkeyModifier; }
    public int getHotkeyKey() { return hotkeyKey; }

    public void set(String family, int size) {
        this.family = family;
        this.size = size;
        save();
    }

    public void setWidth(int width) {
        this.width = width;
        save();
    }

    public void setShowFullPath(boolean showFullPath) {
        this.showFullPath = showFullPath;
        save();
    }

    public void setHotkey(int modifier, int key) {
        this.hotkeyModifier = modifier;
        this.hotkeyKey = key;
        save();
    }

    public Font font(int style) {
        return new Font(family, style, size);
    }

    private void load() {
        try {
            if (FILE.exists()) {
                Properties p = new Properties();
                try (FileInputStream in = new FileInputStream(FILE)) {
                    p.load(in);
                }
                family = p.getProperty("font.family", family);
                size = Integer.parseInt(p.getProperty("font.size", String.valueOf(size)));
                width = Integer.parseInt(p.getProperty("window.width", String.valueOf(width)));
                showFullPath = Boolean.parseBoolean(p.getProperty("list.fullPath", String.valueOf(showFullPath)));
                hotkeyModifier = Integer.parseInt(p.getProperty("hotkey.modifier", String.valueOf(hotkeyModifier)));
                hotkeyKey = Integer.parseInt(p.getProperty("hotkey.key", String.valueOf(hotkeyKey)));
            }
        } catch (Exception ignored) {
        }
    }

    private void save() {
        try {
            FILE.getParentFile().mkdirs();
            Properties p = new Properties();
            p.setProperty("font.family", family);
            p.setProperty("font.size", String.valueOf(size));
            p.setProperty("window.width", String.valueOf(width));
            p.setProperty("list.fullPath", String.valueOf(showFullPath));
            p.setProperty("hotkey.modifier", String.valueOf(hotkeyModifier));
            p.setProperty("hotkey.key", String.valueOf(hotkeyKey));
            try (FileOutputStream out = new FileOutputStream(FILE)) {
                p.store(out, "Diana settings");
            }
        } catch (Exception ignored) {
        }
    }
}
