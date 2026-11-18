package diana;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * A global (low-level) keyboard hook for Windows implemented with the
 * Foreign Function & Memory API (FMM / FFM).
 *
 * <p>
 * The hook runs on a dedicated thread that owns a Win32 message pump, as
 * required by {@code WH_KEYBOARD_LL} hooks.
 * </p>
 */
public class GlobalKeyboardHook implements AutoCloseable {

    public interface Listener {
        void onHotkey();
    }

    // Win32 constants
    public static final int WH_KEYBOARD_LL = 13;

    private static final int WM_APP = 0x8000;

    private static final int WM_TRIGGER = WM_APP + 1;

    public static final int WM_KEYDOWN = 0x0100;

    public static final int WM_KEYUP = 0x0101;

    public static final int WM_SYSKEYDOWN = 0x0104;

    public static final int WM_SYSKEYUP = 0x0105;

    public static final int WM_QUIT = 0x0012;

    public static final int VK_SPACE = 0x20;

    public static final int VK_CONTROL = 0x11;

    private static final Linker LINKER = Linker.nativeLinker();

    private static final SymbolLookup USER32 = SymbolLookup.libraryLookup("user32.dll", Arena.global());

    private static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("kernel32.dll", Arena.global());

    private static final MemoryLayout KBDLLHOOKSTRUCT = MemoryLayout
            .structLayout(ValueLayout.JAVA_INT.withName("vkCode"), ValueLayout.JAVA_INT.withName("scanCode"), ValueLayout.JAVA_INT
                    .withName("flags"), ValueLayout.JAVA_INT.withName("time"), ValueLayout.ADDRESS.withName("dwExtraInfo"));

    private static final MemoryLayout MSG_LAYOUT = MemoryLayout
            .structLayout(ValueLayout.ADDRESS.withName("hwnd"), ValueLayout.JAVA_INT.withName("message"), ValueLayout.JAVA_INT
                    .withName("padding"), ValueLayout.JAVA_LONG.withName("wParam"), ValueLayout.JAVA_LONG
                            .withName("lParam"), ValueLayout.JAVA_INT
                                    .withName("time"), ValueLayout.JAVA_INT.withName("ptX"), ValueLayout.JAVA_INT.withName("ptY"));

    private static final FunctionDescriptor HOOK_DESC = FunctionDescriptor
            .of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG);

    // downcalls
    private static final MethodHandle SET_WINDOWS_HOOK_EX = downcall(USER32, "SetWindowsHookExW", FunctionDescriptor
            .of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    private static final MethodHandle CALL_NEXT_HOOK_EX = downcall(USER32, "CallNextHookEx", FunctionDescriptor
            .of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));

    private static final MethodHandle UNHOOK_WINDOWS_HOOK_EX = downcall(USER32, "UnhookWindowsHookEx", FunctionDescriptor
            .of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    private static final MethodHandle GET_MESSAGE = downcall(USER32, "GetMessageW", FunctionDescriptor
            .of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

    private static final MethodHandle TRANSLATE_MESSAGE = downcall(USER32, "TranslateMessage", FunctionDescriptor
            .of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    private static final MethodHandle DISPATCH_MESSAGE = downcall(USER32, "DispatchMessageW", FunctionDescriptor
            .of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

    private static final MethodHandle GET_MODULE_HANDLE = downcall(KERNEL32, "GetModuleHandleW", FunctionDescriptor
            .of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    private static final MethodHandle GET_ASYNC_KEY_STATE = downcall(USER32, "GetAsyncKeyState", FunctionDescriptor
            .of(ValueLayout.JAVA_SHORT, ValueLayout.JAVA_INT));

    private static final MethodHandle POST_THREAD_MESSAGE = downcall(USER32, "PostThreadMessageW", FunctionDescriptor
            .of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));

    private static final MethodHandle GET_CURRENT_THREAD_ID = downcall(KERNEL32, "GetCurrentThreadId", FunctionDescriptor
            .of(ValueLayout.JAVA_INT));

    private volatile Listener listener;

    private volatile int hotkeyModifier = 0x10;

    private volatile int hotkeyKey = 0x1C;

    private volatile boolean ctrlPressed;

    private volatile boolean altPressed;

    private volatile boolean shiftPressed;

    private volatile boolean running;

    private Thread thread;

    private volatile int threadId;

    private MemorySegment hookHandle;

    private final MemorySegment callbackStub;

    public GlobalKeyboardHook() {
        try {
            MethodHandle mh = MethodHandles.lookup()
                    .bind(this, "hookProc", MethodType.methodType(long.class, int.class, long.class, long.class));
            this.callbackStub = LINKER.upcallStub(mh, HOOK_DESC, Arena.global());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static MethodHandle downcall(SymbolLookup lookup, String name, FunctionDescriptor desc) {
        MemorySegment addr = lookup.find(name).orElseThrow(() -> new IllegalStateException(name + " not found"));
        return LINKER.downcallHandle(addr, desc);
    }

    public void start(Listener listener) {
        this.listener = listener;
        this.running = true;
        this.thread = new Thread(this::run, "global-keyboard-hook");
        this.thread.setDaemon(false);
        this.thread.start();
    }

    private void run() {
        try {
            this.threadId = (int) GET_CURRENT_THREAD_ID.invokeExact();
            MemorySegment hMod = (MemorySegment) GET_MODULE_HANDLE.invokeExact(MemorySegment.NULL);
            this.hookHandle = (MemorySegment) SET_WINDOWS_HOOK_EX.invokeExact(WH_KEYBOARD_LL, callbackStub, hMod, 0);
            if (hookHandle == null || hookHandle.address() == 0) {
                throw new IllegalStateException("SetWindowsHookEx failed");
            }
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment msg = arena.allocate(MSG_LAYOUT);
                while (running) {
                    int r = (int) GET_MESSAGE.invokeExact(msg, MemorySegment.NULL, 0, 0);
                    if (r == -1) {
                        break;
                    }
                    if (r == 0) {
                        break; // WM_QUIT
                    }
                    int msgId = msg.get(ValueLayout.JAVA_INT, 8);
                    if (msgId == WM_TRIGGER) {
                        Listener l = listener;
                        if (l != null) {
                            l.onHotkey();
                        }
                        continue;
                    }
                    int translated = (int) TRANSLATE_MESSAGE.invokeExact(msg);
                    long dispatched = (long) DISPATCH_MESSAGE.invokeExact(msg);
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            if (hookHandle != null && hookHandle.address() != 0) {
                try {
                    int unhooked = (int) UNHOOK_WINDOWS_HOOK_EX.invokeExact(hookHandle);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private long hookProc(int nCode, long wParam, long lParam) {
        try {
            if (nCode >= 0) {
                MemorySegment kb = MemorySegment.ofAddress(lParam).reinterpret(KBDLLHOOKSTRUCT.byteSize());
                int vkCode = kb.get(ValueLayout.JAVA_INT, 0);
                boolean down = (wParam == WM_KEYDOWN || wParam == WM_SYSKEYDOWN);
                boolean up = (wParam == WM_KEYUP || wParam == WM_SYSKEYUP);
                if (vkCode == 0xA2 || vkCode == 0xA3) {
                    if (down) ctrlPressed = true;
                    if (up) ctrlPressed = false;
                }
                if (vkCode == 0xA4 || vkCode == 0xA5) {
                    if (down) altPressed = true;
                    if (up) altPressed = false;
                }
                if (vkCode == 0xA0 || vkCode == 0xA1) {
                    if (down) shiftPressed = true;
                    if (up) shiftPressed = false;
                }
                if (down && vkCode == hotkeyKey) {
                    boolean modPressed = false;
                    if (hotkeyModifier == 0)
                        modPressed = true;
                    else if (hotkeyModifier == 0x11)
                        modPressed = ctrlPressed;
                    else if (hotkeyModifier == 0x12)
                        modPressed = altPressed;
                    else if (hotkeyModifier == 0x10)
                        modPressed = shiftPressed;
                    else {
                        short mod = (short) GET_ASYNC_KEY_STATE.invokeExact(hotkeyModifier);
                        modPressed = (mod & 0x8000) != 0;
                    }
                    if (modPressed) {
                        int posted = (int) POST_THREAD_MESSAGE.invokeExact(threadId, WM_TRIGGER, 0L, 0L);
                        return 1; // consume
                    }
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
        try {
            return (long) CALL_NEXT_HOOK_EX.invokeExact(MemorySegment.NULL, nCode, wParam, lParam);
        } catch (Throwable t) {
            return 0;
        }
    }

    public void setHotkey(int modifier, int key) {
        this.hotkeyModifier = modifier;
        this.hotkeyKey = key;
    }

    @Override
    public void close() {
        running = false;
        if (threadId != 0) {
            try {
                int posted = (int) POST_THREAD_MESSAGE.invokeExact(threadId, WM_QUIT, 0L, 0L);
            } catch (Throwable ignored) {
            }
        }
        if (thread != null) {
            try {
                thread.join(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
