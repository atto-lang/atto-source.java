package dev.oreslang.runtime;

import java.util.Arrays;
import java.util.Objects;

/** Internal single-owner assembly buffer; no backing array escapes before detach. */
final class BoundedByteAccumulator implements AutoCloseable {
    private final int limit;
    private byte[] data = new byte[0];
    private int size;
    private boolean closed;

    BoundedByteAccumulator(int limit) {
        if (limit < 0) throw new IllegalArgumentException("negative byte budget");
        this.limit = limit;
    }

    void append(byte[] source) {
        checkOpen();
        Objects.requireNonNull(source, "source");
        if (source.length > limit - size) {
            throw new IllegalArgumentException("OresBytes exceeds bounded payload budget");
        }
        int needed = size + source.length;
        if (needed > data.length) {
            int next = (int) Math.min(limit, Math.max((long) needed, Math.max(64L, 2L * data.length)));
            byte[] replacement = Arrays.copyOf(data, next);
            Arrays.fill(data, (byte) 0);
            data = replacement;
        }
        System.arraycopy(source, 0, data, size, source.length);
        size = needed;
    }

    byte[] detach() {
        checkOpen();
        byte[] result = data.length == size ? data : Arrays.copyOf(data, size);
        if (result != data) Arrays.fill(data, (byte) 0);
        data = new byte[0];
        closed = true;
        size = 0;
        return result;
    }

    private void checkOpen() {
        if (closed) throw new IllegalStateException("byte accumulator retired");
    }

    @Override public void close() {
        if (closed) return;
        Arrays.fill(data, (byte) 0);
        data = new byte[0];
        size = 0;
        closed = true;
    }
}
