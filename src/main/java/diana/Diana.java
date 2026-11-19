package diana;

import javax.swing.SwingUtilities;

import com.formdev.flatlaf.FlatDarkLaf;

public class Diana {
    public static void main(String[] args) {
        Bootstrap.initialize();
        SwingUtilities.invokeLater(() -> {
            try {
                FlatDarkLaf.setup();
            } catch (Exception ignored) {
            }
            LauncherWindow window = new LauncherWindow();
            GlobalKeyboardHook hook = new GlobalKeyboardHook();
            window.setHook(hook);
            hook.setHotkey(window.getFontSettings().getHotkeyModifier(), window.getFontSettings().getHotkeyKey());
            hook.start(() -> SwingUtilities.invokeLater(window::toggle));
        });
    }
}
