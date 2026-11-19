package diana;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.ActionMap;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileSystemView;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.JTextComponent;

public class LauncherWindow extends JFrame {
    private static final String[] COMMANDS = {"/config", "/exit", "/scan"};
    private static final int MIN_WIDTH = 240;
    private static final int MAX_WIDTH = 1200;
    private static final int GAP = 8;
    private static final int LIST_HEIGHT = 148;

    private final JTextField input = new JTextField();

    private final JLabel status = new JLabel(" ");

    private final DefaultListModel<Object> listModel = new DefaultListModel<>();

    private final JList<Object> resultList = new JList<>(listModel);

    private final JScrollPane scroll = new JScrollPane(resultList);

    private final LaunchHistory history = new LaunchHistory();

    private final ScanLocations scanLocations = new ScanLocations();

    private final ApplicationIndex index = new ApplicationIndex(history, scanLocations);

    private final FontSettings fontSettings = new FontSettings();

    private final Map<String, Icon> iconCache = new ConcurrentHashMap<>();

    private final Set<String> loadingIcons = ConcurrentHashMap.newKeySet();

    /**
     * Placeholder shown while a shell icon is resolved in the background. It is
     * transparent but keeps the cell layout stable.
     */
    private static final Icon PLACEHOLDER_ICON = new ImageIcon(new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB));

    /**
     * Extracting an icon from the Windows shell can take seconds or block on
     * unavailable devices, so it must never run on the event dispatch thread.
     */
    private final ExecutorService iconLoader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "application-icon-loader");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private List<Path> currentResults = List.of();

    private List<String> currentCommands = List.of();

    private boolean commandMode = false;

    private SettingsWindow settingsWindow;

    private GlobalKeyboardHook hook;

    public LauncherWindow() {
        super("Diana Launcher");
        setUndecorated(true);
        setAlwaysOnTop(true);
        setType(Window.Type.UTILITY);
        setBackground(UIManager.getColor("Panel.background"));
        setSize(windowWidth(), 56);
        setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);

        index.start();

        input.setBorder(new EmptyBorder(6, 10, 6, 10));
        silenceDeleteBeep(input);
        input.addActionListener(e -> executeSelectedOrInput());
        input.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refresh();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refresh();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                refresh();
            }
        });
        input.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                switch (e.getKeyCode()) {
                case KeyEvent.VK_ESCAPE -> hideLauncher();
                case KeyEvent.VK_DOWN -> {
                    moveSelection(1);
                    e.consume();
                }
                case KeyEvent.VK_UP -> {
                    moveSelection(-1);
                    e.consume();
                }
                }
            }
        });
        addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowLostFocus(WindowEvent e) {
                hideLauncher();
            }
        });

        resultList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultList.setOpaque(false);
        resultList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof Path path) {
                    setText(fontSettings.isShowFullPath() ? path.toString() : path.getFileName().toString());
                    setIcon(iconFor(path));
                } else {
                    setIcon(null);
                }
                setIconTextGap(8);
                return this;
            }
        });
        resultList.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER)
                    executeSelectedOrInput();
                else if (e.getKeyCode() == KeyEvent.VK_ESCAPE) hideLauncher();
            }
        });

        scroll.setPreferredSize(new Dimension(Math.max(200, windowWidth() - 24), 120));
        scroll.setVisible(false);

        status.setForeground(new Color(0xAAAAAA));
        status.setBorder(new EmptyBorder(3, 10, 5, 10));
        status.setVisible(false);

        JPanel root = new JPanel(new BorderLayout(GAP, GAP));
        root.setOpaque(false);
        root.add(input, BorderLayout.NORTH);
        root.add(scroll, BorderLayout.CENTER);
        root.add(status, BorderLayout.SOUTH);
        setContentPane(root);

        applyFont(fontSettings);
        updateSize();
    }

    /**
     * Sizes the window to the visible parts only. The status line is hidden
     * when it has nothing to show, so no empty strip is left below the list.
     */
    private void updateSize() {
        setSize(windowWidth(), contentHeight());
    }

    private int contentHeight() {
        int height = input.getPreferredSize().height;
        if (scroll.isVisible()) {
            height += GAP + LIST_HEIGHT;
        }
        if (status.isVisible()) {
            height += GAP + status.getPreferredSize().height;
        }
        return height;
    }

    private void setStatus(String text) {
        status.setText(text);
        status.setVisible(text != null && !text.isBlank());
        updateSize();
    }

    public void toggle() {
        if (isVisible())
            hideLauncher();
        else
            showLauncher();
    }

    /**
     * Swing asks the look and feel to beep when Backspace or Delete is pressed
     * although there is nothing to remove. Replace those actions with silent
     * variants, keeping the original behavior whenever something can be
     * deleted.
     */
    private static void silenceDeleteBeep(JTextField field) {
        ActionMap actions = field.getActionMap();
        if (actions == null) {
            return;
        }
        Action deletePrevious = actions.get(DefaultEditorKit.deletePrevCharAction);
        Action deleteNext = actions.get(DefaultEditorKit.deleteNextCharAction);
        ActionMap map = new ActionMap();
        map.setParent(actions);
        if (deletePrevious != null) {
            map.put(DefaultEditorKit.deletePrevCharAction, silentDelete(deletePrevious, false));
        }
        if (deleteNext != null) {
            map.put(DefaultEditorKit.deleteNextCharAction, silentDelete(deleteNext, true));
        }
        field.setActionMap(map);
    }

    private static Action silentDelete(Action original, boolean forward) {
        return new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                JTextComponent target = (JTextComponent) e.getSource();
                boolean selection = target.getSelectionStart() != target.getSelectionEnd();
                boolean removable = forward ? target.getCaretPosition() < target.getDocument().getLength()
                        : target.getCaretPosition() > 0;
                if (selection || removable) {
                    original.actionPerformed(e);
                }
            }
        };
    }

    public void showLauncher() {
        // clearing the text also resets the result list and the window size via refresh()
        input.setText("");
        setStatus(" ");
        setLocationRelativeTo(null);
        Point loc = getLocation();
        setLocation(loc.x, loc.y - 100);
        setVisible(true);
        focusInput();
    }

    public void hideLauncher() {
        setVisible(false);
    }

    public void focusInput() {
        SwingUtilities.invokeLater(() -> {
            forceForegroundNative();
            input.requestFocusInWindow();
        });
    }

    /**
     * Windows refuses to hand the foreground to a window of another process, so
     * the keyboard input keeps going to the previous window. Pressing Alt just
     * before SetForegroundWindow lifts that restriction.
     */
    private void forceForegroundNative() {
        try (Arena arena = Arena.ofConfined()) {
            Linker linker = Linker.nativeLinker();
            SymbolLookup user32 = SymbolLookup.libraryLookup("user32.dll", arena);
            MethodHandle findWindow = linker.downcallHandle(user32.find("FindWindowW").get(), FunctionDescriptor
                    .of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MethodHandle setForeground = linker.downcallHandle(user32.find("SetForegroundWindow").get(), FunctionDescriptor
                    .of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            MethodHandle keybd = linker.downcallHandle(user32.find("keybd_event").get(), FunctionDescriptor
                    .ofVoid(ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));

            byte[] bytes = (getTitle() + "\0").getBytes(StandardCharsets.UTF_16LE);
            MemorySegment title = arena.allocate(bytes.length);
            MemorySegment.copy(bytes, 0, title, ValueLayout.JAVA_BYTE, 0, bytes.length);

            MemorySegment hwnd = (MemorySegment) findWindow.invoke(MemorySegment.NULL, title);
            if (hwnd.address() == 0) return;

            keybd.invoke((byte) 0x12, (byte) 0, 0, 0L); // VK_MENU down
            setForeground.invoke(hwnd);
            keybd.invoke((byte) 0x12, (byte) 0, 2, 0L); // VK_MENU up (KEYEVENTF_KEYUP)
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    public void setHook(GlobalKeyboardHook hook) {
        this.hook = hook;
    }

    public void shutdown() {
        if (hook != null) hook.close();
        System.exit(0);
    }

    public void applyFont(FontSettings settings) {
        Font f = settings.font(Font.PLAIN);
        input.setFont(f);
        resultList.setFont(f);
        resultList.setFixedCellHeight(Math.max(24, resultList.getFontMetrics(f).getHeight() + 6));
        status.setFont(f);
        updateSize();
    }

    public void applyDisplay() {
        resultList.repaint();
    }

    public void applyWidth(int width) {
        int w = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, width));
        scroll.setPreferredSize(new Dimension(Math.max(200, w - 24), 120));
        setSize(w, contentHeight());
        revalidate();
    }

    private int windowWidth() {
        return Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, fontSettings.getWidth()));
    }

    private Icon iconFor(Path path) {
        String key = path.toString().toLowerCase(Locale.ROOT);
        Icon icon = iconCache.get(key);
        if (icon != null) {
            return icon;
        }
        if (loadingIcons.add(key)) {
            File file = path.toFile();
            iconLoader.execute(() -> loadIcon(key, file));
        }
        return PLACEHOLDER_ICON;
    }

    private void loadIcon(String key, File file) {
        try {
            Icon icon = FileSystemView.getFileSystemView().getSystemIcon(file, 16, 16);
            iconCache.put(key, icon != null ? icon : PLACEHOLDER_ICON);
            SwingUtilities.invokeLater(resultList::repaint);
        } catch (Throwable ignored) {
            iconCache.put(key, PLACEHOLDER_ICON);
        } finally {
            loadingIcons.remove(key);
        }
    }

    public void applyHotkey(FontSettings settings) {
        if (hook != null) hook.setHotkey(settings.getHotkeyModifier(), settings.getHotkeyKey());
    }

    public FontSettings getFontSettings() {
        return fontSettings;
    }

    public ScanLocations getScanLocations() {
        return scanLocations;
    }

    public void refreshIndex() {
        index.rescan(null);
    }

    public void openSettings() {
        if (settingsWindow == null) settingsWindow = new SettingsWindow(fontSettings, this);
        settingsWindow.setVisible(true);
        settingsWindow.toFront();
    }

    private void refresh() {
        String q = input.getText().trim();
        listModel.clear();
        commandMode = q.startsWith("/");

        if (q.isEmpty()) {
            currentResults = List.of();
            currentCommands = List.of();
            scroll.setVisible(false);
            updateSize();
        } else {
            if (commandMode) {
                currentResults = List.of();
                currentCommands = Arrays.stream(COMMANDS).filter(c -> c.startsWith(q.toLowerCase())).toList();
                currentCommands.forEach(listModel::addElement);
            } else {
                currentCommands = List.of();
                currentResults = index.search(q, 30);
                currentResults.forEach(listModel::addElement);
            }
            scroll.setVisible(true);
            if (!listModel.isEmpty()) resultList.setSelectedIndex(0);
            updateSize();
        }
        revalidate();
    }

    private void moveSelection(int delta) {
        int size = listModel.size();
        if (size == 0) return;
        int idx = Math.max(0, Math.min(size - 1, resultList.getSelectedIndex() + delta));
        resultList.setSelectedIndex(idx);
        resultList.ensureIndexIsVisible(idx);
    }

    private void executeSelectedOrInput() {
        String text = input.getText().trim();
        int idx = resultList.getSelectedIndex();

        String cmd = text;
        if (commandMode && idx >= 0 && idx < currentCommands.size()) cmd = currentCommands.get(idx);
        cmd = cmd.toLowerCase();

        if (cmd.equals("/config") || cmd.equals("config")) {
            openSettings();
            hideLauncher();
            return;
        }
        if (cmd.equals("/exit") || cmd.equals("exit")) {
            shutdown();
            return;
        }
        if (cmd.equals("/scan")) {
            rescan();
            return;
        }

        String target = !commandMode && idx >= 0 && idx < currentResults.size() ? currentResults.get(idx).toString() : text;
        if (target.isEmpty()) {
            hideLauncher();
            return;
        }
        execute(target);
    }

    private void rescan() {
        input.setText("");
        setStatus("Scanning...");
        index.rescan(() -> setStatus("Scanned " + index.size() + " items"));
    }

    private void execute(String target) {
        try {
            if (target.startsWith("http://") || target.startsWith("https://")) {
                Desktop.getDesktop().browse(new URI(target));
            } else {
                File f = new File(target);
                if (f.exists()) {
                    history.record(f.toPath());
                    openFile(f);
                } else {
                    Runtime.getRuntime().exec(target);
                }
            }
            hideLauncher();
        } catch (Exception e) {
            setStatus("Error: " + e);
        }
    }

    /**
     * Opens a file with the Desktop API and falls back to the Windows shell when
     * the platform does not support it (some environments report the OPEN action
     * as unsupported, which also affects shortcuts such as .lnk files).
     */
    private void openFile(File f) throws IOException {
        if (Desktop.isDesktopSupported()) {
            Desktop desktop = Desktop.getDesktop();
            if (desktop.isSupported(Desktop.Action.OPEN)) {
                try {
                    desktop.open(f);
                    return;
                } catch (IOException | UnsupportedOperationException e) {
                    // fall through to the shell
                }
            }
        }
        new ProcessBuilder("cmd", "/c", "start", "", f.getAbsolutePath()).start();
    }
}