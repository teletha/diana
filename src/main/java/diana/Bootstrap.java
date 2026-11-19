package diana;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Prepares the environment required by AWT when Diana runs as a GraalVM native
 * executable.
 *
 * <p>
 * A native image does not provide a java.home, but the font manager needs one to
 * locate its logical font configuration. This class creates a small runtime
 * directory under the user's home that only contains a self-contained font
 * configuration, and points the font manager at it.
 * </p>
 */
final class Bootstrap {

    /**
     * Logical font mapping used by AWT. Only fonts which are shipped with
     * Windows are referenced, and the fallback sequence keeps Japanese, Chinese
     * and Korean text displayable.
     */
    private static final String FONTCONFIG = """
            version=1

            allfonts.alphabetic=Segoe UI
            allfonts.japanese=Yu Gothic UI
            allfonts.chinese-ms936=Microsoft YaHei
            allfonts.chinese-ms950=Microsoft JhengHei
            allfonts.korean=Malgun Gothic
            allfonts.symbols=Segoe UI Symbol

            dialog.plain.alphabetic=Segoe UI
            dialog.bold.alphabetic=Segoe UI Bold
            dialog.italic.alphabetic=Segoe UI Italic
            dialog.bolditalic.alphabetic=Segoe UI Bold Italic

            dialoginput.plain.alphabetic=Consolas
            dialoginput.bold.alphabetic=Consolas Bold
            dialoginput.italic.alphabetic=Consolas Italic
            dialoginput.bolditalic.alphabetic=Consolas Bold Italic

            sansserif.plain.alphabetic=Segoe UI
            sansserif.bold.alphabetic=Segoe UI Bold
            sansserif.italic.alphabetic=Segoe UI Italic
            sansserif.bolditalic.alphabetic=Segoe UI Bold Italic

            serif.plain.alphabetic=Times New Roman
            serif.bold.alphabetic=Times New Roman Bold
            serif.italic.alphabetic=Times New Roman Italic
            serif.bolditalic.alphabetic=Times New Roman Bold Italic

            monospaced.plain.alphabetic=Consolas
            monospaced.bold.alphabetic=Consolas Bold
            monospaced.italic.alphabetic=Consolas Italic
            monospaced.bolditalic.alphabetic=Consolas Bold Italic

            sequence.allfonts=alphabetic
            sequence.fallback=japanese,chinese-ms936,chinese-ms950,korean,symbols
            """;

    private Bootstrap() {
    }

    /**
     * Set up the environment required by AWT when running in a native image.
     */
    static void initialize() {
        if (System.getProperty("org.graalvm.nativeimage.imagecode") == null) {
            return;
        }
        if (!System.getProperty("os.name", "").toLowerCase().contains("windows")) {
            return;
        }
        enableDpiAwareness();
        configureFonts();
    }

    /**
     * A Java launcher declares DPI awareness for its process, but a native image
     * has no launcher. Without it Windows bitmap-scales the whole window on
     * HiDPI displays, which makes the text blurry regardless of antialiasing.
     * System DPI awareness lets AWT compute its UI scale and render at native
     * resolution.
     */
    private static void enableDpiAwareness() {
        try (Arena arena = Arena.ofConfined()) {
            Linker linker = Linker.nativeLinker();
            SymbolLookup user32 = SymbolLookup.libraryLookup("user32.dll", arena);
            MethodHandle setContext = linker.downcallHandle(user32.find("SetProcessDpiAwarenessContext").get(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            // DPI_AWARENESS_CONTEXT_SYSTEM_AWARE = -2
            if ((int) setContext.invokeExact(MemorySegment.ofAddress(-2L)) != 0) {
                return;
            }
        } catch (Throwable ignored) {
        }
        try (Arena arena = Arena.ofConfined()) {
            Linker linker = Linker.nativeLinker();
            SymbolLookup shcore = SymbolLookup.libraryLookup("shcore.dll", arena);
            MethodHandle set = linker.downcallHandle(shcore.find("SetProcessDpiAwareness").get(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            set.invokeExact(1); // PROCESS_SYSTEM_DPI_AWARE
        } catch (Throwable ignored) {
        }
    }

    private static void configureFonts() {
        try {
            String javaHome = System.getProperty("java.home");
            if (javaHome != null && Files.isRegularFile(Path.of(javaHome, "lib", "fontconfig.properties"))) {
                return;
            }

            Path runtime = Path.of(System.getProperty("user.home"), ".diana", "runtime").toAbsolutePath();
            Path config = runtime.resolve("lib").resolve("fontconfig.properties");

            if (!Files.isRegularFile(config) || !FONTCONFIG.equals(Files.readString(config, StandardCharsets.UTF_8))) {
                Files.createDirectories(config.getParent());
                Files.writeString(config, FONTCONFIG, StandardCharsets.UTF_8);
            }

            System.setProperty("java.home", runtime.toString());
            System.setProperty("sun.awt.fontconfig", config.toString());
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }
}
