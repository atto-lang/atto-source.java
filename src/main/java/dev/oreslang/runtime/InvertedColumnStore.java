package dev.oreslang.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Allocation-backed, scalar-only reference storage for inverted classes.
 *
 * This is a host runtime primitive, NOT source language lowering. Each column
 * owns a contiguous primitive byte array with a stable little-endian encoding.
 * No raw array, ByteBuffer, or writable view escapes the owning store.
 */
public final class InvertedColumnStore implements AutoCloseable {
    public enum Kind {
        I32(4), I64(8), F32(4), F64(8), BOOL(1);
        private final int bytes;
        Kind(int bytes) { this.bytes = bytes; }
        public int bytes() { return bytes; }
    }

    public record Handle(long owner, int slot, long generation) { }

    private static final AtomicLong NEXT_OWNER = new AtomicLong(1);
    private static long nextOwner() {
        while (true) {
            long current = NEXT_OWNER.get();
            if (current == Long.MAX_VALUE) {
                throw new IllegalStateException("inverted store identity exhausted");
            }
            if (NEXT_OWNER.compareAndSet(current, current + 1)) return current;
        }
    }

    private final long owner = nextOwner();
    private final Kind[] kinds;
    private byte[][] columns;
    private ByteBuffer[] buffers;
    private int[] rowSlots;
    private int[] slotRows;
    private long[] generations;
    private boolean[] live;
    private int[] reusable;
    private int capacity;
    private int length;
    private int slotCount;
    private int freeCount;
    private final int[] readers;
    private final boolean[] writers;
    private boolean closed;

    public InvertedColumnStore(Kind... types) {
        if (types == null || types.length == 0) {
            throw new IllegalArgumentException("inverted store requires at least one field");
        }
        kinds = types.clone();
        for (Kind kind : kinds) Objects.requireNonNull(kind, "column kind");
        columns = new byte[kinds.length][];
        buffers = new ByteBuffer[kinds.length];
        for (int i = 0; i < kinds.length; i++) {
            columns[i] = new byte[0];
            buffers[i] = ByteBuffer.wrap(columns[i]).order(ByteOrder.LITTLE_ENDIAN);
        }
        rowSlots = slotRows = reusable = new int[0];
        generations = new long[0];
        live = new boolean[0];
        readers = new int[kinds.length];
        writers = new boolean[kinds.length];
    }

    private void open() {
        if (closed) throw new IllegalStateException("inverted store is closed");
    }

    private int column(int index) {
        if (index < 0 || index >= kinds.length) {
            throw new IndexOutOfBoundsException("invalid inverted column index");
        }
        return index;
    }

    private void unborrowed() {
        for (int i = 0; i < kinds.length; i++) {
            if (readers[i] != 0 || writers[i]) {
                throw new IllegalStateException("structural mutation conflicts with active column loan");
            }
        }
    }

    public synchronized int size() {
        open();
        return length;
    }

    public synchronized int capacity() {
        open();
        return capacity;
    }

    public synchronized int fieldCount() { open(); return kinds.length; }

    /** All allocations complete before publishing any new backing columns. */
    public synchronized void reserve(int required) {
        open();
        unborrowed();
        if (required < 0) throw new IllegalArgumentException("negative capacity");
        if (required <= capacity) return;
        byte[][] newColumns = new byte[kinds.length][];
        ByteBuffer[] newBuffers = new ByteBuffer[kinds.length];
        for (int i = 0; i < kinds.length; i++) {
            int bytes = Math.multiplyExact(required, kinds[i].bytes());
            newColumns[i] = Arrays.copyOf(columns[i], bytes);
            newBuffers[i] = ByteBuffer.wrap(newColumns[i]).order(ByteOrder.LITTLE_ENDIAN);
        }
        int[] newRowSlots = Arrays.copyOf(rowSlots, required);
        int[] newSlotRows = Arrays.copyOf(slotRows, required);
        long[] newGenerations = Arrays.copyOf(generations, required);
        boolean[] newLive = Arrays.copyOf(live, required);
        int[] newReusable = Arrays.copyOf(reusable, required);
        columns = newColumns;
        buffers = newBuffers;
        rowSlots = newRowSlots;
        slotRows = newSlotRows;
        generations = newGenerations;
        live = newLive;
        reusable = newReusable;
        capacity = required;
    }

    private static void validateValue(Kind kind, Object value) {
        boolean valid = switch (kind) {
            case I32 -> value instanceof Integer;
            case I64 -> value instanceof Long;
            case F32 -> value instanceof Float;
            case F64 -> value instanceof Double;
            case BOOL -> value instanceof Boolean;
        };
        if (!valid) throw new IllegalArgumentException("invalid scalar for column " + kind);
    }

    private int resolve(Handle handle) {
        open();
        if (handle == null || handle.owner() != owner || handle.slot() < 0
                || handle.slot() >= slotCount) {
            throw new IllegalArgumentException("foreign or invalid inverted row handle");
        }
        int slot = handle.slot();
        if (!live[slot] || generations[slot] != handle.generation()) {
            throw new IllegalArgumentException("stale inverted row handle");
        }
        return slotRows[slot];
    }

    private Object load(int column, int row) {
        Kind kind = kinds[column];
        int offset = row * kind.bytes();  // within checked reserved capacity
        ByteBuffer buffer = buffers[column];
        return switch (kind) {
            case I32 -> buffer.getInt(offset);
            case I64 -> buffer.getLong(offset);
            case F32 -> buffer.getFloat(offset);
            case F64 -> buffer.getDouble(offset);
            case BOOL -> buffer.get(offset) != 0;
        };
    }

    private void store(int column, int row, Object value) {
        Kind kind = kinds[column];
        int offset = row * kind.bytes();
        ByteBuffer buffer = buffers[column];
        switch (kind) {
            case I32 -> buffer.putInt(offset, (Integer) value);
            case I64 -> buffer.putLong(offset, (Long) value);
            case F32 -> buffer.putFloat(offset, (Float) value);
            case F64 -> buffer.putDouble(offset, (Double) value);
            case BOOL -> buffer.put(offset, (byte) ((Boolean) value ? 1 : 0));
        }
    }

    public synchronized Handle push(Object... values) {
        open();
        unborrowed();
        if (values == null || values.length != kinds.length) {
            throw new IllegalArgumentException("row width must equal inverted column count");
        }
        for (int i = 0; i < kinds.length; i++) validateValue(kinds[i], values[i]);
        if (length == Integer.MAX_VALUE) throw new IllegalStateException("row capacity exhausted");
        // A retired generation-MAX slot is not reusable. Slot metadata can
        // therefore fill up even when live row count is below row capacity.
        if (length == capacity || (freeCount == 0 && slotCount == capacity)) {
            int next = capacity == 0 ? 1 : Math.addExact(capacity, Math.max(capacity, 1));
            reserve(next);
        }
        int row = length;
        int slot = freeCount != 0 ? reusable[--freeCount] : slotCount++;
        if (generations[slot] == 0) generations[slot] = 1;
        for (int i = 0; i < kinds.length; i++) store(i, row, values[i]);
        rowSlots[row] = slot;
        slotRows[slot] = row;
        live[slot] = true;
        length++;
        return new Handle(owner, slot, generations[slot]);
    }

    /** Unordered O(column-count) erase, preserving surviving logical handles. */
    public synchronized void eraseSwap(Handle handle) {
        int row = resolve(handle);
        unborrowed();
        int last = length - 1;
        if (row != last) {
            for (int i = 0; i < kinds.length; i++) {
                int stride = kinds[i].bytes();
                System.arraycopy(columns[i], last * stride, columns[i], row * stride, stride);
            }
            int displaced = rowSlots[last];
            rowSlots[row] = displaced;
            slotRows[displaced] = row;
        }
        length--;
        int slot = handle.slot();
        live[slot] = false;
        if (generations[slot] != Long.MAX_VALUE) {
            generations[slot]++;
            reusable[freeCount++] = slot;
        }
    }

    public synchronized Object get(Handle handle, int field) {
        int row = resolve(handle);
        int i = column(field);
        if (writers[i]) throw new IllegalStateException("mutably borrowed column");
        return load(i, row);
    }

    public synchronized void set(Handle handle, int field, Object value) {
        int row = resolve(handle);
        int i = column(field);
        validateValue(kinds[i], value);
        if (readers[i] != 0 || writers[i]) {
            throw new IllegalStateException("borrowed column");
        }
        store(i, row, value);
    }

    public synchronized Object[] snapshot(Handle handle) {
        int row = resolve(handle);
        for (boolean writer : writers) {
            if (writer) throw new IllegalStateException("mutably borrowed row");
        }
        Object[] values = new Object[kinds.length];
        for (int i = 0; i < kinds.length; i++) values[i] = load(i, row);
        return values;
    }

    public synchronized void updateRow(Handle handle, Object... values) {
        int row = resolve(handle);
        if (values == null || values.length != kinds.length) {
            throw new IllegalArgumentException("row width mismatch");
        }
        unborrowed();
        for (int i = 0; i < kinds.length; i++) validateValue(kinds[i], values[i]);
        for (int i = 0; i < kinds.length; i++) store(i, row, values[i]);
    }

    public synchronized ColumnLoan borrowRead(int field) {
        open();
        int i = column(field);
        if (writers[i]) throw new IllegalStateException("column is mutably borrowed");
        if (readers[i] == Integer.MAX_VALUE) throw new IllegalStateException("reader count overflow");
        readers[i]++;
        return new ColumnLoan(i, false);
    }

    public synchronized ColumnLoan borrowWrite(int field) {
        open();
        int i = column(field);
        if (writers[i] || readers[i] != 0) throw new IllegalStateException("column is borrowed");
        writers[i] = true;
        return new ColumnLoan(i, true);
    }

    /** Borrow-bound logical column access; backing bytes never escape. */
    public final class ColumnLoan implements AutoCloseable {
        private final int field;
        private final boolean write;
        private boolean released;
        private ColumnLoan(int field, boolean write) {
            this.field = field;
            this.write = write;
        }

        private void active() {
            if (released) throw new IllegalStateException("column loan was released");
            open();
        }

        public int length() {
            synchronized (InvertedColumnStore.this) {
                active();
                return InvertedColumnStore.this.length;
            }
        }

        public Object get(int row) {
            synchronized (InvertedColumnStore.this) {
                active();
                if (row < 0 || row >= length) throw new IndexOutOfBoundsException();
                return load(field, row);
            }
        }

        public void set(int row, Object value) {
            synchronized (InvertedColumnStore.this) {
                active();
                if (!write) throw new IllegalStateException("read-only column loan");
                if (row < 0 || row >= length) throw new IndexOutOfBoundsException();
                validateValue(kinds[field], value);
                store(field, row, value);
            }
        }

        @Override
        public void close() {
            synchronized (InvertedColumnStore.this) {
                if (released) return;
                released = true;
                if (write) writers[field] = false;
                else readers[field]--;
            }
        }
    }

    /** Revokes all handles and live loans; never exposes a mutable backing buffer. */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        for (int i = 0; i < kinds.length; i++) {
            columns[i] = new byte[0];
            buffers[i] = ByteBuffer.wrap(columns[i]).order(ByteOrder.LITTLE_ENDIAN);
        }
        length = capacity = 0;
    }
}
