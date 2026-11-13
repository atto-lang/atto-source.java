package dev.oreslang.runtime;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Actor-transferable, bounded immutable bytes with one live owning handle.
 *
 * This is a JVM runtime building block, NOT YET an exposed Atto Bytes type.
 * No guest API obtains the mutable Java array. Internal NIO2 callbacks may
 * acquire read leases, which delay zeroization until all leases are retired.
 *
 * Transfer does not copy the payload: the former owner is invalidated, and
 * the receiving owner keeps the original backing storage. Read leases remain
 * immutable across transfers. The caller of fromDetached MUST own its array.
 */
public final class OresBytes implements AutoCloseable {
    public static final int MAX_LENGTH = 1024 * 1024;

    private static final class Storage {
        private final byte[] value;
        private int leases;
        private boolean retired;

        private Storage(byte[] value) { this.value = value; }

        private void eraseIfIdle() {
            if (retired && leases == 0) Arrays.fill(value, (byte) 0);
        }
    }

    private final Storage storage;
    private final AtomicBoolean valid = new AtomicBoolean(true);

    private OresBytes(Storage storage) { this.storage = storage; }

    /** Copy an untrusted caller buffer before accepting ownership. */
    public static OresBytes copyOf(byte[] input) {
        Objects.requireNonNull(input, "input");
        checkLength(input.length);
        return new OresBytes(new Storage(input.clone()));
    }

    /** Internal: caller irrevocably detaches the array and never uses it again. */
    static OresBytes fromDetached(byte[] owned) {
        Objects.requireNonNull(owned, "owned");
        checkLength(owned.length);
        return new OresBytes(new Storage(owned));
    }

    private static void checkLength(int length) {
        if (length > MAX_LENGTH) {
            throw new IllegalArgumentException("OresBytes exceeds bounded payload budget");
        }
    }

    /** O(1) linear ownership transfer; use-after-take is rejected. */
    public OresBytes take() {
        synchronized (storage) {
            checkLive();
            valid.set(false);
            return new OresBytes(storage);
        }
    }

    public int size() {
        synchronized (storage) {
            checkLive();
            return storage.value.length;
        }
    }

    public int byteAt(int index) {
        synchronized (storage) {
            checkLive();
            return storage.value[index] & 0xff;
        }
    }

    /** Safe external boundary; intentionally copies instead of escaping the array. */
    public byte[] toArrayCopy() {
        synchronized (storage) {
            checkLive();
            return storage.value.clone();
        }
    }

    /**
     * Trusted NIO2 transport read lease. Leased buffers are read-only and may
     * be used until the callback closes the lease. Never publish the buffer
     * to guest code or retain it after lease.close().
     */
    ReadLease readLease() {
        synchronized (storage) {
            checkLive();
            storage.leases++;
            return new ReadLease(storage);
        }
    }

    private void checkLive() {
        if (!valid.get() || storage.retired) {
            throw new IllegalStateException("OresBytes owner retired or taken");
        }
    }

    @Override public void close() {
        synchronized (storage) {
            if (!valid.compareAndSet(true, false)) return;
            storage.retired = true;
            storage.eraseIfIdle();
        }
    }

    static final class ReadLease implements AutoCloseable {
        private final Storage storage;
        private final AtomicBoolean valid = new AtomicBoolean(true);

        private ReadLease(Storage storage) { this.storage = storage; }

        ByteBuffer buffer() {
            synchronized (storage) {
                if (!valid.get()) throw new IllegalStateException("retired OresBytes lease");
                return ByteBuffer.wrap(storage.value).asReadOnlyBuffer();
            }
        }

        @Override public void close() {
            synchronized (storage) {
                if (!valid.compareAndSet(true, false)) return;
                storage.leases--;
                if (storage.leases < 0) throw new AssertionError("negative bytes leases");
                storage.eraseIfIdle();
            }
        }
    }
}
