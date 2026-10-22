package dev.oreslang.runtime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Linux strict filesystem provider backed by one bounded io_uring reactor.
 *
 * <p>Path opens, reads, existence probes, closes and cancellation are submitted
 * through io_uring. Paths are opened beneath the process root with
 * RESOLVE_BENEATH + RESOLVE_NO_MAGICLINKS + RESOLVE_NO_SYMLINKS, so a runtime
 * permission check cannot subsequently be redirected through a symlink.</p>
 */
final class LinuxUringFileIoProvider implements StrictFileIoProvider {
    static final int DEFAULT_ENTRIES = 128;
    static final int MAX_READ_BYTES = 16 * 1024 * 1024;
    static final int ECANCELED = 125;

    interface NativeBridge {
        long create(LinuxUringFileIoProvider owner, int entries);
        boolean submitRead(long handle, long id, byte[] path, int maxBytes);
        boolean submitExists(long handle, long id, byte[] path);
        boolean cancel(long handle, long id);
        void close(long handle);
    }

    private static final class JniBridge implements NativeBridge {
        @Override public long create(LinuxUringFileIoProvider owner, int entries) {
            NativeCarrierExecutor.ensureNativeLibraryLoaded();
            return nativeCreate(owner, entries);
        }
        @Override public boolean submitRead(
                long handle, long id, byte[] path, int maxBytes) {
            return nativeSubmitRead(handle, id, path, maxBytes);
        }
        @Override public boolean submitExists(long handle, long id, byte[] path) {
            return nativeSubmitExists(handle, id, path);
        }
        @Override public boolean cancel(long handle, long id) {
            return nativeCancel(handle, id);
        }
        @Override public void close(long handle) { nativeClose(handle); }
    }

    private enum Kind { READ, EXISTS }

    private static final class Pending<T> {
        final Kind kind;
        final OresFuture<T> future;
        Pending(Kind kind, OresFuture<T> future) {
            this.kind = kind;
            this.future = future;
        }
    }

    private final NativeBridge bridge;
    private final long handle;
    private final AtomicLong ids = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ConcurrentHashMap<Long, Pending<?>> pending =
            new ConcurrentHashMap<>();

    private LinuxUringFileIoProvider(NativeBridge bridge, int entries) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        long created = bridge.create(this, entries);
        if (created == 0L) {
            throw new UnsupportedOperationException(
                    "Linux io_uring filesystem provider is unavailable");
        }
        this.handle = created;
    }

    LinuxUringFileIoProvider(NativeBridge bridge) {
        this(bridge, DEFAULT_ENTRIES);
    }

    static StrictFileIoProvider tryCreate() {
        try {
            return new LinuxUringFileIoProvider(new JniBridge(), DEFAULT_ENTRIES);
        } catch (UnsupportedOperationException | UnsatisfiedLinkError
                | SecurityException | IllegalStateException unavailable) {
            return null;
        }
    }

    @Override public boolean supported() { return true; }
    @Override public String backend() { return "linux-io_uring"; }

    @Override
    public OresFuture<String> readText(String absolutePath) {
        byte[] path = encodeAbsolutePath(absolutePath);
        long id = nextId();
        OresFuture<String> future = new OresFuture<>(() -> cancelNative(id));
        pending.put(id, new Pending<>(Kind.READ, future));
        if (closed.get() || !bridge.submitRead(handle, id, path, MAX_READ_BYTES)) {
            Pending<?> removed = pending.remove(id);
            if (removed != null) {
                future.failFromRuntime(new IllegalStateException(
                        closed.get()
                                ? "strict filesystem provider is closed"
                                : "io_uring read submission was rejected"));
            }
        }
        return future;
    }

    @Override
    public OresFuture<Boolean> exists(String absolutePath) {
        byte[] path = encodeAbsolutePath(absolutePath);
        long id = nextId();
        OresFuture<Boolean> future = new OresFuture<>(() -> cancelNative(id));
        pending.put(id, new Pending<>(Kind.EXISTS, future));
        if (closed.get() || !bridge.submitExists(handle, id, path)) {
            Pending<?> removed = pending.remove(id);
            if (removed != null) {
                future.failFromRuntime(new IllegalStateException(
                        closed.get()
                                ? "strict filesystem provider is closed"
                                : "io_uring exists submission was rejected"));
            }
        }
        return future;
    }

    private long nextId() {
        long id = ids.incrementAndGet();
        if (id <= 0) throw new IllegalStateException("strict filesystem operation id exhausted");
        return id;
    }

    private void cancelNative(long id) {
        if (!closed.get()) bridge.cancel(handle, id);
    }

    private static byte[] encodeAbsolutePath(String absolutePath) {
        Objects.requireNonNull(absolutePath, "absolutePath");
        if (absolutePath.indexOf('\0') >= 0 || !absolutePath.startsWith("/")) {
            throw new IllegalArgumentException(
                    "strict filesystem provider requires an absolute NUL-free path");
        }
        return absolutePath.getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    void nativeReadCompleted(long id, byte[] bytes, int error) {
        Pending<?> raw = pending.remove(id);
        if (raw == null || raw.kind != Kind.READ) return;
        OresFuture<String> future = (OresFuture<String>) raw.future;
        if (future.isCancelled()) return;
        if (error != 0) {
            future.failFromRuntime(ioFailure("read", error));
            return;
        }
        try {
            String value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes == null ? new byte[0] : bytes))
                    .toString();
            future.completeFromRuntime(value);
        } catch (CharacterCodingException invalidUtf8) {
            future.failFromRuntime(new IOException(
                    "strict filesystem read returned invalid UTF-8", invalidUtf8));
        }
    }

    @SuppressWarnings("unchecked")
    void nativeExistsCompleted(long id, boolean exists, int error) {
        Pending<?> raw = pending.remove(id);
        if (raw == null || raw.kind != Kind.EXISTS) return;
        OresFuture<Boolean> future = (OresFuture<Boolean>) raw.future;
        if (future.isCancelled()) return;
        if (error != 0) {
            future.failFromRuntime(ioFailure("exists", error));
            return;
        }
        future.completeFromRuntime(exists);
    }

    private static IOException ioFailure(String operation, int errno) {
        if (errno == ECANCELED) {
            return new IOException(
                    "strict filesystem " + operation + " was cancelled (errno " + errno + ")");
        }
        return new IOException(
                "strict filesystem " + operation + " failed (errno " + errno + ")");
    }

    int pendingCount() { return pending.size(); }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        IllegalStateException failure =
                new IllegalStateException("strict filesystem provider is closed");
        for (Map.Entry<Long, Pending<?>> entry : pending.entrySet()) {
            bridge.cancel(handle, entry.getKey());
            Pending<?> removed = pending.remove(entry.getKey());
            if (removed != null) removed.future.failFromRuntime(failure);
        }
        bridge.close(handle);
    }

    private static native long nativeCreate(
            LinuxUringFileIoProvider owner, int entries);
    private static native boolean nativeSubmitRead(
            long handle, long id, byte[] path, int maxBytes);
    private static native boolean nativeSubmitExists(
            long handle, long id, byte[] path);
    private static native boolean nativeCancel(long handle, long id);
    private static native void nativeClose(long handle);
}
