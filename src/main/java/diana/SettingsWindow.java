package diana;

import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.event.ItemEvent;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

public class SettingsWindow extends JFrame {
    private final FontSettings settings;

    private final LauncherWindow launcher;

    private final JComboBox<String> familyBox;

    private final JSpinner sizeSpinner;

    private final JSpinner widthSpinner;

    private final JCheckBox fullPathBox;

    private final JButton scanButton;

    private final JComboBox<String> modifierBox;

    private final JComboBox<String> keyBox;

    private final Map<String, Integer> keyMap = new LinkedHashMap<>();

    public SettingsWindow(FontSettings settings, LauncherWindow launcher) {
        super("Diana Settings");
        this.settings = settings;
        this.launcher = launcher;
        setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        setSize(420, 300);
        setLocationRelativeTo(null);

        String[] mods = {"None", "Ctrl", "Alt", "Shift"};
        for (int code = 1; code <= 255; code++) {
            String name = java.awt.event.KeyEvent.getKeyText(code);
            if (name != null && !name.isEmpty() && !name.startsWith("Unknown") && !name.contains("0x")) {
                keyMap.put(name, code);
            }
        }

        JPanel root = new JPanel(new GridLayout(7, 2, 8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        root.add(new JLabel("Font family:"));
        familyBox = new JComboBox<>(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        familyBox.setSelectedItem(settings.getFamily());
        familyBox.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED) apply();
        });
        root.add(familyBox);

        root.add(new JLabel("Font size:"));
        sizeSpinner = new JSpinner(new SpinnerNumberModel(settings.getSize(), 8, 48, 1));
        sizeSpinner.addChangeListener(e -> apply());
        root.add(sizeSpinner);

        root.add(new JLabel("Window width:"));
        widthSpinner = new JSpinner(new SpinnerNumberModel(settings.getWidth(), 240, 1200, 10));
        widthSpinner.addChangeListener(e -> apply());
        root.add(widthSpinner);

        root.add(new JLabel("List display:"));
        fullPathBox = new JCheckBox("Show full path", settings.isShowFullPath());
        fullPathBox.addItemListener(e -> apply());
        root.add(fullPathBox);

        root.add(new JLabel("Scan locations:"));
        scanButton = new JButton("Edit...");
        scanButton.addActionListener(e -> editScanLocations());
        root.add(scanButton);

        root.add(new JLabel("Hotkey modifier:"));
        modifierBox = new JComboBox<>(mods);
        modifierBox.setSelectedItem(modToName(settings.getHotkeyModifier()));
        modifierBox.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED) apply();
        });
        root.add(modifierBox);

        root.add(new JLabel("Hotkey key:"));
        keyBox = new JComboBox<>(keyMap.keySet().toArray(new String[0]));
        keyBox.setSelectedItem(keyToName(settings.getHotkeyKey()));
        keyBox.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED) apply();
        });
        root.add(keyBox);

        setContentPane(root);
    }

    private void apply() {
        String family = (String) familyBox.getSelectedItem();
        int size = (Integer) sizeSpinner.getValue();
        settings.set(family, size);
        settings.setWidth((Integer) widthSpinner.getValue());
        settings.setShowFullPath(fullPathBox.isSelected());
        settings.setHotkey(nameToMod((String) modifierBox.getSelectedItem()), keyMap.getOrDefault(keyBox.getSelectedItem(), 0x20));
        launcher.applyFont(settings);
        launcher.applyHotkey(settings);
        launcher.applyWidth(settings.getWidth());
        launcher.applyDisplay();
    }

    private void editScanLocations() {
        ScanLocationsDialog dialog = new ScanLocationsDialog(this, launcher.getScanLocations());
        dialog.setVisible(true);
        if (dialog.isSaved()) {
            launcher.refreshIndex();
        }
    }

    private String modToName(int m) {
        if (m == 0) return "None";
        if (m == 0x12) return "Alt";
        if (m == 0x10) return "Shift";
        return "Ctrl";
    }

    private int nameToMod(String n) {
        if ("None".equals(n)) return 0;
        if ("Alt".equals(n)) return 0x12;
        if ("Shift".equals(n)) return 0x10;
        return 0x11;
    }

    private String keyToName(int k) {
        for (Map.Entry<String, Integer> e : keyMap.entrySet()) {
            if (e.getValue() == k) return e.getKey();
        }
        return "Space";
    }
}
