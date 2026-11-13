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

    // ---- Bytes v1 guest API (spec chapter 26). Every operation reads under
    // the storage monitor and returns a NEW immutable value; no guest code
    // ever observes or aliases a mutable backing array.

    /** An empty immutable byte string. */
    public static OresBytes empty() {
        return new OresBytes(new Storage(new byte[0]));
    }

    /** Build from guest List<int>; every element must be an integer 0..255. */
    public static OresBytes fromList(java.util.List<?> values) {
        Objects.requireNonNull(values, "values");
        checkLength(values.size());
        byte[] out = new byte[values.size()];
        for (int i = 0; i < out.length; i++) {
            if (!(values.get(i) instanceof Number n)
                    || n.doubleValue() != Math.rint(n.doubleValue())
                    || n.longValue() < 0 || n.longValue() > 255) {
                throw new IllegalArgumentException(
                        "Bytes.from_list element " + i + " must be an integer 0..255");
            }
            out[i] = (byte) n.intValue();
        }
        return new OresBytes(new Storage(out));
    }

    /** UTF-8 encoding of a guest string. */
    public static OresBytes fromString(String text) {
        Objects.requireNonNull(text, "text");
        byte[] out = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        checkLength(out.length);
        return new OresBytes(new Storage(out));
    }

    /** Copy of [start, end); bounds are checked, never clamped. */
    public OresBytes slice(int start, int end) {
        synchronized (storage) {
            checkLive();
            int length = storage.value.length;
            if (start < 0 || end < start || end > length) {
                throw new IndexOutOfBoundsException(
                        "Bytes.slice(" + start + ", " + end + ") outside 0.." + length);
            }
            return new OresBytes(new Storage(Arrays.copyOfRange(storage.value, start, end)));
        }
    }

    /** First index >= from holding value (0..255), or -1. */
    public int indexOf(int value, int from) {
        if (value < 0 || value > 255) {
            throw new IllegalArgumentException("Bytes.index_of value must be 0..255");
        }
        synchronized (storage) {
            checkLive();
            byte[] data = storage.value;
            if (from < 0 || from > data.length) {
                throw new IndexOutOfBoundsException(
                        "Bytes.index_of start " + from + " outside 0.." + data.length);
            }
            byte b = (byte) value;
            for (int i = from; i < data.length; i++) if (data[i] == b) return i;
            return -1;
        }
    }

    /** New value holding this followed by other. Neither input is consumed. */
    public OresBytes concat(OresBytes other) {
        Objects.requireNonNull(other, "other");
        byte[] left = toArrayCopy();
        byte[] right = other.toArrayCopy();
        checkLength(left.length + right.length);
        byte[] out = Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, out, left.length, right.length);
        return new OresBytes(new Storage(out));
    }

    /** Guest List<int> copy (values 0..255). */
    public java.util.List<Object> toList() {
        byte[] data = toArrayCopy();
        java.util.ArrayList<Object> out = new java.util.ArrayList<>(data.length);
        for (byte b : data) out.add((long) (b & 0xff));
        return out;
    }

    /** Strict UTF-8 decode; empty Option-equivalent (null) on malformed input. */
    public String toUtf8OrNull() {
        byte[] data = toArrayCopy();
        try {
            return java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data)).toString();
        } catch (java.nio.charset.CharacterCodingException malformed) {
            return null;
        }
    }

    /**
     * Value semantics: Bytes compare and hash by content so Map<Bytes, V>
     * lookups use the byte string, never handle identity.
     */
    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof OresBytes that)) return false;
        if (storage == that.storage) return true;
        return Arrays.equals(toArrayCopy(), that.toArrayCopy());
    }

    @Override public int hashCode() {
        synchronized (storage) {
            checkLive();
            return Arrays.hashCode(storage.value);
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
