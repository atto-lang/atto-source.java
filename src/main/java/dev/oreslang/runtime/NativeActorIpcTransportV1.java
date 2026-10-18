package dev.oreslang.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Linux exact-object process transport for IPC actors.
 *
 * <p>The native side creates a sealed memfd from the exact admitted artifact
 * bytes and maps five one-way pipes into the child: bootstrap, DATA in/out and
 * CONTROL in/out. No guest-selected executable path or command line is used.
 * The spawned child receives an empty environment and only descriptors 3..8;
 * descriptor 8 is the sealed executable image itself.</p>
 *
 * <p>This class is a transport primitive, not an untrusted sandbox. Callers
 * must keep UNTRUSTED actors fail-closed until a parent-enforced sandbox
 * provider is available.</p>
 */
final class NativeActorIpcTransportV1 implements AutoCloseable {
    static final class ReadTimeoutException extends RuntimeException {
        ReadTimeoutException(String message) {
            super(message);
        }
    }

    private static final String LIBRARY = "oresipc";
    private static final Object LOAD_LOCK = new Object();
    private static volatile boolean loaded;

    static final int DATA = 1;
    static final int CONTROL = 2;
    private static final int DATA_WIRE_LIMIT =
            ActorIpcSessionV1.maxWireFrameBytes(
                    ActorIpcSessionV1.Lane.DATA);
    private static final int CONTROL_WIRE_LIMIT =
            ActorIpcSessionV1.maxWireFrameBytes(
                    ActorIpcSessionV1.Lane.CONTROL);

    private final ReentrantReadWriteLock lifecycle =
            new ReentrantReadWriteLock();
    private long nativeHandle;

    private NativeActorIpcTransportV1(long nativeHandle) {
        if (nativeHandle == 0L) {
            throw new IllegalArgumentException("native IPC process handle cannot be zero");
        }
        this.nativeHandle = nativeHandle;
    }

    static boolean supported() {
        ensureLoaded();
        return nativeSupported();
    }

    static NativeActorIpcTransportV1 launch(
            byte[] exactArtifactBytes,
            byte[] bootstrapWire) {
        Objects.requireNonNull(exactArtifactBytes, "exactArtifactBytes");
        Objects.requireNonNull(bootstrapWire, "bootstrapWire");
        if (exactArtifactBytes.length == 0) {
            throw new IllegalArgumentException("actor executable bytes cannot be empty");
        }
        if (bootstrapWire.length != ActorIpcBootstrapV1.WIRE_BYTES) {
            throw new IllegalArgumentException("unexpected IPC bootstrap wire size");
        }
        ensureLoaded();
        if (!nativeSupported()) {
            throw new UnsupportedOperationException(
                    "exact-object IPC actor process transport is currently Linux-only");
        }
        return new NativeActorIpcTransportV1(
                nativeLaunch(exactArtifactBytes, bootstrapWire));
    }

    long pid() {
        var lock = lifecycle.readLock();
        lock.lock();
        try {
            return nativePid(requireOpen());
        } finally {
            lock.unlock();
        }
    }

    boolean isAlive() {
        var lock = lifecycle.readLock();
        lock.lock();
        try {
            return nativeIsAlive(requireOpen());
        } finally {
            lock.unlock();
        }
    }

    void writeDataFrame(byte[] frame) {
        write(DATA, frame, DATA_WIRE_LIMIT);
    }

    void writeControlFrame(byte[] frame) {
        write(CONTROL, frame, CONTROL_WIRE_LIMIT);
    }

    byte[] readDataFrame(int timeoutMillis) {
        return read(DATA, DATA_WIRE_LIMIT, timeoutMillis);
    }

    byte[] readControlFrame(int timeoutMillis) {
        return read(CONTROL, CONTROL_WIRE_LIMIT, timeoutMillis);
    }

    private void write(int lane, byte[] frame, int limit) {
        Objects.requireNonNull(frame, "frame");
        if (frame.length == 0 || frame.length > limit) {
            throw new IllegalArgumentException(
                    "IPC frame is outside transport bounds");
        }
        var lock = lifecycle.readLock();
        lock.lock();
        try {
            nativeWriteFrame(requireOpen(), lane, frame, limit);
        } finally {
            lock.unlock();
        }
    }

    private byte[] read(int lane, int limit, int timeoutMillis) {
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis must be positive");
        }
        var lock = lifecycle.readLock();
        lock.lock();
        try {
            byte[] frame = nativeReadFrame(
                    requireOpen(),
                    lane,
                    limit,
                    timeoutMillis);
            if (frame == null) {
                throw new ReadTimeoutException(
                        "IPC " + (lane == DATA ? "DATA" : "CONTROL")
                                + " frame read timed out before any frame byte");
            }
            return frame;
        } finally {
            lock.unlock();
        }
    }

    private long requireOpen() {
        if (nativeHandle == 0L) {
            throw new IllegalStateException("IPC actor process transport is closed");
        }
        return nativeHandle;
    }

    @Override
    public void close() {
        var lock = lifecycle.writeLock();
        lock.lock();
        try {
            long handle = nativeHandle;
            if (handle == 0L) return;
            nativeHandle = 0L;
            nativeClose(handle);
        } finally {
            lock.unlock();
        }
    }

    private static void ensureLoaded() {
        if (loaded) return;
        synchronized (LOAD_LOCK) {
            if (loaded) return;
            String explicit = System.getProperty("ores.ipc.native.path");
            if (explicit != null && !explicit.isBlank()) {
                System.load(
                        Path.of(explicit)
                                .toAbsolutePath()
                                .normalize()
                                .toString());
            } else {
                Path local = Path.of(
                                "target",
                                "native",
                                System.mapLibraryName(LIBRARY))
                        .toAbsolutePath()
                        .normalize();
                if (Files.isRegularFile(local)) {
                    System.load(local.toString());
                } else {
                    System.loadLibrary(LIBRARY);
                }
            }
            loaded = true;
        }
    }

    private static native boolean nativeSupported();
    private static native long nativeLaunch(
            byte[] exactArtifactBytes,
            byte[] bootstrapWire);
    private static native long nativePid(long handle);
    private static native boolean nativeIsAlive(long handle);
    private static native void nativeWriteFrame(
            long handle,
            int lane,
            byte[] frame,
            int maxBytes);
    private static native byte[] nativeReadFrame(
            long handle,
            int lane,
            int maxBytes,
            int timeoutMillis);
    private static native void nativeClose(long handle);
}
