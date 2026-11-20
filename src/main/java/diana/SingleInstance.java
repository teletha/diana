package diana;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Keeps Diana to a single running process.
 *
 * <p>
 * When a new process starts, it terminates the process which held the lock
 * before, so the freshly started one takes over the global keyboard hook. The
 * lock itself is released by the operating system when a process ends, so it
 * also recovers from a crash without leaving a stale lock behind.
 * </p>
 */
final class SingleInstance implements AutoCloseable {

    private static final Path DIRECTORY = Path.of(System.getProperty("user.home"), ".diana");

    /**
     * Empty file used only for locking. Windows file locks cover a byte range,
     * so the process id must not be stored in the locked file itself or it
     * could not be read by the process which wants to take over.
     */
    private static final Path LOCK_FILE = DIRECTORY.resolve("instance.lock");

    /**
     * Process id and start time of the current lock holder, used to detect a
     * reused process id.
     */
    private static final Path PID_FILE = DIRECTORY.resolve("instance.pid");

    private static final int MAX_ATTEMPTS = 60;

    private static final long RETRY_DELAY_MILLIS = 50;

    private static final long TERMINATION_TIMEOUT_MILLIS = 3000;

    /**
     * Keeps the lock reachable for the lifetime of the process. Closing or
     * collecting the channel would release it.
     */
    private static SingleInstance current;

    private final FileChannel channel;

    private final FileLock lock;

    private SingleInstance(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * Acquires the single-instance lock. Any process which is still running and
     * holds the lock is terminated first.
     *
     * @return the held lock, which must stay reachable while Diana runs
     * @throws IllegalStateException when the previous process could not be
     *             stopped and the lock stayed occupied
     */
    static SingleInstance acquire() {
        try {
            Files.createDirectories(DIRECTORY);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create " + DIRECTORY, e);
        }

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                FileChannel channel = FileChannel.open(LOCK_FILE, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock lock;
                try {
                    lock = channel.tryLock();
                } catch (OverlappingFileLockException e) {
                    lock = null;
                }
                if (lock != null) {
                    SingleInstance instance = new SingleInstance(channel, lock);
                    instance.record();
                    current = instance;
                    return instance;
                }
                channel.close();
            } catch (IOException e) {
                // The file may be temporarily unavailable, retry below.
            }

            terminatePrevious();
            try {
                Thread.sleep(RETRY_DELAY_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IllegalStateException("Another Diana instance is still running");
    }

    /**
     * Registers this process so the next launch can verify it before
     * terminating it.
     */
    private void record() {
        ProcessHandle self = ProcessHandle.current();
        long start = self.info().startInstant().map(Instant::toEpochMilli).orElse(0L);
        try {
            Files.writeString(PID_FILE, self.pid() + "\n" + start + "\n", StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    /**
     * Terminates the process recorded in the pid file, unless it has already
     * ended or the recorded process id now belongs to an unrelated process.
     */
    private static void terminatePrevious() {
        long pid;
        long start;
        try {
            String[] lines = Files.readString(PID_FILE, StandardCharsets.UTF_8).split("\\R");
            if (lines.length < 2) {
                return;
            }
            pid = Long.parseLong(lines[0].trim());
            start = Long.parseLong(lines[1].trim());
        } catch (IOException | RuntimeException e) {
            return;
        }

        if (pid <= 0 || pid == ProcessHandle.current().pid()) {
            return;
        }

        Optional<ProcessHandle> previous = ProcessHandle.of(pid);
        if (previous.isEmpty() || !previous.get().isAlive() || !isSameProcess(previous.get(), start)) {
            return;
        }

        ProcessHandle process = previous.get();
        process.destroy();
        try {
            process.onExit().get(TERMINATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            process.destroyForcibly();
        }
    }

    /**
     * A recorded start time of zero means it could not be determined when the
     * pid file was written, so the pid alone is trusted in that case.
     */
    private static boolean isSameProcess(ProcessHandle process, long start) {
        if (start == 0L) {
            return true;
        }
        return process.info().startInstant().map(instant -> instant.toEpochMilli() == start).orElse(true);
    }

    @Override
    public void close() {
        try {
            lock.release();
        } catch (IOException ignored) {
        }
        try {
            channel.close();
        } catch (IOException ignored) {
        }
        if (current == this) {
            current = null;
        }
    }
}
