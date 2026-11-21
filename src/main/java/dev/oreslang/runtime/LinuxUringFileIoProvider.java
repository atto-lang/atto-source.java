package dev.oreslang.runtime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
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
    static final int DEFAULT_ENTRIES = 512;
    static final int MAX_PENDING = 128;
    static final int MAX_READ_BYTES = 16 * 1024 * 1024;
    static final int MAX_WRITE_BYTES = 16 * 1024 * 1024;
    static final long MAX_RESERVED_BYTES = 64L * 1024 * 1024;
    static final int ECANCELED = 125;

    interface NativeBridge {
        long create(LinuxUringFileIoProvider owner, int entries);
        boolean submitRead(long handle, long id, byte[] path, int maxBytes);
        boolean submitExists(long handle, long id, byte[] path);
        boolean submitWrite(
                long handle, long id, byte[] path, byte[] value, boolean append);
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
        @Override public boolean submitWrite(
                long handle, long id, byte[] path, byte[] value, boolean append) {
            return nativeSubmitWrite(handle, id, path, value, append);
        }
        @Override public boolean cancel(long handle, long id) {
            return nativeCancel(handle, id);
        }
        @Override public void close(long handle) { nativeClose(handle); }
    }

    private enum Kind { READ, EXISTS, WRITE }

    private static final class Pending<T> {
        final Kind kind;
        final OresFuture<T> future;
        final long reservedBytes;
        Pending(Kind kind, OresFuture<T> future, long reservedBytes) {
            this.kind = kind;
            this.future = future;
            this.reservedBytes = reservedBytes;
        }
    }

    private final NativeBridge bridge;
    private final long handle;
    private final AtomicLong ids = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong reservedBytes = new AtomicLong();
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
        if (!reserveBytes(MAX_READ_BYTES)) {
            return OresFuture.failed(new IllegalStateException(
                    "strict filesystem read byte reservation exhausted"));
        }
        long id = nextId();
        OresFuture<String> future = new OresFuture<>(
                () -> cancelNative(id), () -> { });
        pending.put(id, new Pending<>(Kind.READ, future, MAX_READ_BYTES));
        if (!submitReadNative(id, path)) {
            Pending<?> removed = pending.remove(id);
            if (removed != null) {
                releaseBytes(removed.reservedBytes);
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
        OresFuture<Boolean> future = new OresFuture<>(
                () -> cancelNative(id), () -> { });
        pending.put(id, new Pending<>(Kind.EXISTS, future, 0));
        if (!submitExistsNative(id, path)) {
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

    @Override
    public OresFuture<Void> writeText(String absolutePath, String value) {
        return submitWrite(absolutePath, value, false, "fs.write_text_async");
    }

    @Override
    public OresFuture<Void> appendText(String absolutePath, String value) {
        return submitWrite(absolutePath, value, true, "fs.append_text_async");
    }

    private OresFuture<Void> submitWrite(
            String absolutePath, String value, boolean append, String operation) {
        byte[] path = encodeAbsolutePath(absolutePath);
        byte[] bytes = encodeUtf8Value(value, operation);
        if (!reserveBytes(bytes.length)) {
            return OresFuture.failed(new IllegalStateException(
                    "strict filesystem write byte reservation exhausted"));
        }
        long id = nextId();
        // Guest cancellation is deliberately declined for mutations: an
        // already-started write may have changed the file and cannot promise
        // rollback. Context shutdown may still request native cancellation.
        OresFuture<Void> future = new OresFuture<>(() -> false, () -> { });
        pending.put(id, new Pending<>(Kind.WRITE, future, bytes.length));
        if (!submitWriteNative(id, path, bytes, append)) {
            Pending<?> removed = pending.remove(id);
            if (removed != null) {
                releaseBytes(removed.reservedBytes);
                future.failFromRuntime(new IllegalStateException(
                        closed.get()
                                ? "strict filesystem provider is closed"
                                : "io_uring write submission was rejected"));
            }
        }
        return future;
    }

    private synchronized boolean submitReadNative(long id, byte[] path) {
        if (closed.get() || pending.size() > MAX_PENDING) return false;
        return bridge.submitRead(handle, id, path, MAX_READ_BYTES);
    }

    private synchronized boolean submitExistsNative(long id, byte[] path) {
        if (closed.get() || pending.size() > MAX_PENDING) return false;
        return bridge.submitExists(handle, id, path);
    }

    private synchronized boolean submitWriteNative(
            long id, byte[] path, byte[] value, boolean append) {
        if (closed.get() || pending.size() > MAX_PENDING) return false;
        return bridge.submitWrite(handle, id, path, value, append);
    }

    private boolean reserveBytes(long bytes) {
        if (bytes < 0 || bytes > MAX_RESERVED_BYTES) return false;
        for (;;) {
            long current = reservedBytes.get();
            if (current > MAX_RESERVED_BYTES - bytes) return false;
            if (reservedBytes.compareAndSet(current, current + bytes)) {
                return true;
            }
        }
    }

    private boolean releaseBytes(long bytes) {
        if (bytes == 0) return true;
        for (;;) {
            long current = reservedBytes.get();
            if (current < bytes) return false;
            if (reservedBytes.compareAndSet(current, current - bytes)) {
                return true;
            }
        }
    }

    private static IllegalStateException reservationAccountingFailure() {
        return new IllegalStateException(
                "strict filesystem byte reservation accounting corrupted");
    }

    private long nextId() {
        long id = ids.incrementAndGet();
        if (id <= 0) throw new IllegalStateException("strict filesystem operation id exhausted");
        return id;
    }

    private synchronized boolean cancelNative(long id) {
        return !closed.get() && bridge.cancel(handle, id);
    }

    private static byte[] encodeAbsolutePath(String absolutePath) {
        Objects.requireNonNull(absolutePath, "absolutePath");
        if (absolutePath.indexOf('\0') >= 0 || !absolutePath.startsWith("/")) {
            throw new IllegalArgumentException(
                    "strict filesystem provider requires an absolute NUL-free path");
        }
        return absolutePath.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] encodeUtf8Value(String value, String operation) {
        Objects.requireNonNull(value, "value");
        if (value.length() > MAX_WRITE_BYTES) {
            throw new IllegalArgumentException(
                    operation + " text exceeds strict filesystem write limit");
        }
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            if (encoded.remaining() > MAX_WRITE_BYTES) {
                throw new IllegalArgumentException(
                        operation + " UTF-8 text exceeds strict filesystem write limit");
            }
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException(
                    operation + " text is not valid Unicode", invalid);
        }
    }

    @SuppressWarnings("unchecked")
    void nativeReadCompleted(long id, byte[] bytes, int error) {
        Pending<?> raw = pending.remove(id);
        if (raw == null) return;
        boolean released = releaseBytes(raw.reservedBytes);
        if (raw.kind != Kind.READ) return;
        OresFuture<String> future = (OresFuture<String>) raw.future;
        if (!released) {
            future.failFromRuntime(reservationAccountingFailure());
            return;
        }
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
        if (raw == null) return;
        boolean released = releaseBytes(raw.reservedBytes);
        if (raw.kind != Kind.EXISTS) return;
        OresFuture<Boolean> future = (OresFuture<Boolean>) raw.future;
        if (!released) {
            future.failFromRuntime(reservationAccountingFailure());
            return;
        }
        if (future.isCancelled()) return;
        if (error != 0) {
            future.failFromRuntime(ioFailure("exists", error));
            return;
        }
        future.completeFromRuntime(exists);
    }

    @SuppressWarnings("unchecked")
    void nativeWriteCompleted(long id, int error) {
        Pending<?> raw = pending.remove(id);
        if (raw == null) return;
        boolean released = releaseBytes(raw.reservedBytes);
        if (raw.kind != Kind.WRITE) return;
        OresFuture<Void> future = (OresFuture<Void>) raw.future;
        if (!released) {
            future.failFromRuntime(reservationAccountingFailure());
            return;
        }
        if (error != 0) {
            future.failFromRuntime(ioFailure("write", error));
            return;
        }
        future.completeFromRuntime(null);
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
    long reservedByteCount() { return reservedBytes.get(); }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;
        IllegalStateException failure =
                new IllegalStateException("strict filesystem provider is closed");
        for (Map.Entry<Long, Pending<?>> entry : pending.entrySet()) {
            bridge.cancel(handle, entry.getKey());
            Pending<?> removed = pending.remove(entry.getKey());
            if (removed != null) {
                releaseBytes(removed.reservedBytes);
                removed.future.failFromRuntime(failure);
            }
        }
        bridge.close(handle);
    }

    private static native long nativeCreate(
            LinuxUringFileIoProvider owner, int entries);
    private static native boolean nativeSubmitRead(
            long handle, long id, byte[] path, int maxBytes);
    private static native boolean nativeSubmitExists(
            long handle, long id, byte[] path);
    private static native boolean nativeSubmitWrite(
            long handle, long id, byte[] path, byte[] value, boolean append);
    private static native boolean nativeCancel(long handle, long id);
    private static native void nativeClose(long handle);
}
