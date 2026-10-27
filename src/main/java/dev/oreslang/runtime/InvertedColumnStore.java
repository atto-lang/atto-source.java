package dev.oreslang.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Allocation-backed, one-column-per-declared-field reference storage for inverted classes.
 *
 * This is a host runtime primitive, NOT source language lowering. Each column
 * owns a contiguous byte array with a stable little-endian encoding.
 * A fixed-size record is opaque within its column: its members are NOT SoA-flattened.
 * No raw array, ByteBuffer, or writable view escapes the owning store.
 */
public final class InvertedColumnStore implements AutoCloseable {
    private static final int MAX_RECORD_BYTES = 64 * 1024;
    // Keep schema metadata and per-column allocations bounded before
    // constructing buffers, loan vectors or generational row metadata.
    static final int MAX_COLUMN_COUNT = 1024;
    // Preallocated empty backing for allocation-free teardown. No subsequent
    // store operation may reach these arrays after closed=true is published.
    private static final byte[] EMPTY_COLUMN = new byte[0];

    public enum Kind {
        I32(4), I64(8), F32(4), F64(8), BOOL(1), RECORD(0);
        private final int bytes;
        Kind(int bytes) { this.bytes = bytes; }
        public int bytes() { return bytes; }
    }

    /**
     * Fixed-stride physical columns. RECORD holds an entire value record in
     * AoS layout per element; only its declaring inverted-class field becomes
     * a column. Record payloads are copied both into and out of the store.
     */
    public record ColumnSpec(Kind kind, int bytes) {
        public ColumnSpec {
            Objects.requireNonNull(kind, "column kind");
            if (bytes <= 0
                    || (kind != Kind.RECORD && kind.bytes() != bytes)
                    || (kind == Kind.RECORD && bytes > MAX_RECORD_BYTES)) {
                throw new IllegalArgumentException("invalid inverted column stride");
            }
        }
        public static ColumnSpec scalar(Kind kind) {
            if (kind == null || kind == Kind.RECORD)
                throw new IllegalArgumentException("expected primitive inverted column kind");
            return new ColumnSpec(kind, kind.bytes());
        }
        public static ColumnSpec record(int bytes) {
            return new ColumnSpec(Kind.RECORD, bytes);
        }
    }

    /**
     * An opaque, store-issued row capability. Numeric owner/slot/generation
     * are diagnostic metadata, not sufficient authority to manufacture a
     * reference to another row. No public constructor or mutator is exposed.
     */
    public static final class Handle {
        private final Object authority;
        private final long owner;
        private final int slot;
        private final long generation;

        private Handle(Object authority, long owner, int slot, long generation) {
            this.authority = authority;
            this.owner = owner;
            this.slot = slot;
            this.generation = generation;
        }

        public long owner() { return owner; }
        public int slot() { return slot; }
        public long generation() { return generation; }
    }

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
    // Opaque issue-only capability shared with row handles, without keeping
    // the entire owning store strongly reachable from every stale handle.
    private final Object capability = new Object();
    private final Kind[] kinds;
    private final ColumnSpec[] specs;
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
    // Synchronized is reentrant: block nested access while compiler plans
    // are evaluating a detached row snapshot prior to atomic commit.
    private boolean transforming;
    private boolean rejectedReentrantAccess;

    /** Package-private, one-shot allocation failure injection for host tests only. */
    enum AllocationFailurePoint {
        BEFORE_HANDLE_ISSUE, BEFORE_RESERVE_COMMIT, BEFORE_COLUMN_LOAN_ISSUE
    }
    private AllocationFailurePoint injectedFailure;

    synchronized void armAllocationFailureForTest(AllocationFailurePoint at) {
        open();
        Objects.requireNonNull(at, "allocation failure point");
        if (injectedFailure != null) {
            throw new IllegalStateException("inverted allocation failure is already armed");
        }
        injectedFailure = at;
    }

    private void failAllocationOnce(AllocationFailurePoint at) {
        if (injectedFailure == at) {
            injectedFailure = null;
            throw new OutOfMemoryError("injected inverted allocation failure: " + at);
        }
    }

    public InvertedColumnStore(Kind... types) {
        this(scalarSpecs(types));
    }

    private static ColumnSpec[] scalarSpecs(Kind[] types) {
        if (types == null) throw new IllegalArgumentException("null inverted column kinds");
        if (types.length > MAX_COLUMN_COUNT) {
            throw new IllegalArgumentException("inverted store exceeds maximum column count");
        }
        ColumnSpec[] specs = new ColumnSpec[types.length];
        for (int i = 0; i < types.length; i++) specs[i] = ColumnSpec.scalar(types[i]);
        return specs;
    }

    public InvertedColumnStore(ColumnSpec... types) {
        if (types == null || types.length == 0) {
            throw new IllegalArgumentException("inverted store requires at least one field");
        }
        if (types.length > MAX_COLUMN_COUNT) {
            throw new IllegalArgumentException(
                    "inverted store exceeds maximum column count: " + MAX_COLUMN_COUNT);
        }
        specs = types.clone();
        kinds = new Kind[specs.length];
        for (int i = 0; i < specs.length; i++) {
            Objects.requireNonNull(specs[i], "column specification");
            kinds[i] = specs[i].kind();
        }
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
        if (transforming) {
            rejectedReentrantAccess = true;
            throw new IllegalStateException("reentrant access during inverted row transaction");
        }
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

    /**
     * Checked []-lowering target: a numeric index selects the CURRENT dense
     * physical position, not a stable logical row identity. The returned
     * issued capability follows its logical row through swap-delete; deleted
     * handles are revoked. Check a wide signed index BEFORE narrowing it.
     */
    private int checkedDenseRow(long index) {
        open();
        if (index < 0 || index >= length) {
            throw new IndexOutOfBoundsException(
                    "inverted row index " + index + " outside [0, " + length + ")");
        }
        return (int) index; // checked before narrowing, capacity is never indexed
    }

    public synchronized Handle handleAt(long index) {
        int row = checkedDenseRow(index);
        int slot = rowSlots[row];
        if (slot < 0 || slot >= slotCount || !live[slot] || slotRows[slot] != row) {
            throw new IllegalStateException("inverted dense row mapping is invalid");
        }
        return new Handle(capability, owner, slot, generations[slot]);
    }

    /** Int compiler compatibility shim; delegates to the checked wide-index path. */
    public synchronized Handle handleAt(int rowIndex) {
        return handleAt((long) rowIndex);
    }

    /** All allocations complete before publishing any new backing columns. */
    public synchronized void reserve(int required) {
        open();
        unborrowed();
        if (required < 0) throw new IllegalArgumentException("negative capacity");
        if (required <= capacity) return;
        byte[][] newColumns = new byte[kinds.length][];
        ByteBuffer[] newBuffers = new ByteBuffer[kinds.length];
        for (int i = 0; i < kinds.length; i++) {
            int bytes = Math.multiplyExact(required, specs[i].bytes());
            newColumns[i] = Arrays.copyOf(columns[i], bytes);
            newBuffers[i] = ByteBuffer.wrap(newColumns[i]).order(ByteOrder.LITTLE_ENDIAN);
        }
        int[] newRowSlots = Arrays.copyOf(rowSlots, required);
        int[] newSlotRows = Arrays.copyOf(slotRows, required);
        long[] newGenerations = Arrays.copyOf(generations, required);
        boolean[] newLive = Arrays.copyOf(live, required);
        int[] newReusable = Arrays.copyOf(reusable, required);
        // Simulate failure after preparing every replacement array but before
        // publishing any of them. This is a host-only one-shot test seam.
        failAllocationOnce(AllocationFailurePoint.BEFORE_RESERVE_COMMIT);
        byte[][] retiredColumns = columns;
        columns = newColumns;
        buffers = newBuffers;
        rowSlots = newRowSlots;
        slotRows = newSlotRows;
        generations = newGenerations;
        live = newLive;
        reusable = newReusable;
        capacity = required;
        // After the new physical image is fully published, scrub all
        // retired copies. They are inaccessible through the store, but
        // otherwise retain row payload bytes until garbage collection.
        // This is allocation-free and cannot invalidate new backing.
        for (byte[] retired : retiredColumns) Arrays.fill(retired, (byte) 0);
    }

    private static void validateValue(ColumnSpec spec, Object value) {
        boolean valid = switch (spec.kind()) {
            case I32 -> value instanceof Integer;
            case I64 -> value instanceof Long;
            case F32 -> value instanceof Float;
            case F64 -> value instanceof Double;
            case BOOL -> value instanceof Boolean;
            case RECORD -> value instanceof byte[] payload && payload.length == spec.bytes();
        };
        if (!valid) throw new IllegalArgumentException("invalid value for column " + spec.kind());
    }

    private int resolve(Handle handle) {
        open();
        if (handle == null || handle.authority != capability
                || handle.owner() != owner || handle.slot() < 0
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
        int offset = row * specs[column].bytes();  // within checked reserved capacity
        ByteBuffer buffer = buffers[column];
        return switch (kind) {
            case I32 -> buffer.getInt(offset);
            case I64 -> buffer.getLong(offset);
            case F32 -> buffer.getFloat(offset);
            case F64 -> buffer.getDouble(offset);
            case BOOL -> buffer.get(offset) != 0;
            case RECORD -> Arrays.copyOfRange(columns[column], offset, offset + specs[column].bytes());
        };
    }

    private void store(int column, int row, Object value) {
        Kind kind = kinds[column];
        int offset = row * specs[column].bytes();
        ByteBuffer buffer = buffers[column];
        switch (kind) {
            case I32 -> buffer.putInt(offset, (Integer) value);
            case I64 -> buffer.putLong(offset, (Long) value);
            case F32 -> buffer.putFloat(offset, (Float) value);
            case F64 -> buffer.putDouble(offset, (Double) value);
            case BOOL -> buffer.put(offset, (byte) ((Boolean) value ? 1 : 0));
            case RECORD -> System.arraycopy((byte[]) value, 0, columns[column], offset, specs[column].bytes());
        }
    }

    public synchronized Handle push(Object... values) {
        open();
        unborrowed();
        if (values == null || values.length != kinds.length) {
            throw new IllegalArgumentException("row width must equal inverted column count");
        }
        for (int i = 0; i < kinds.length; i++) validateValue(specs[i], values[i]);
        if (length == Integer.MAX_VALUE) throw new IllegalStateException("row capacity exhausted");
        // A retired generation-MAX slot is not reusable. Slot metadata can
        // therefore fill up even when live row count is below row capacity.
        if (length == capacity || (freeCount == 0 && slotCount == capacity)) {
            int next = capacity == 0 ? 1 : Math.addExact(capacity, Math.max(capacity, 1));
            reserve(next);
        }
        int row = length;
        // Issue the capability BEFORE committing any logical row state.
        // The allocation in new Handle can fail (e.g. OOME); publishing the
        // row first would leave an unreachable live row after push throws.
        // reserve() may have increased spare capacity, but length, slot
        // ownership and free-list membership must remain unchanged on
        // handle issuance failure.
        int slot = freeCount != 0 ? reusable[freeCount - 1] : slotCount;
        long generation = generations[slot] == 0 ? 1 : generations[slot];
        failAllocationOnce(AllocationFailurePoint.BEFORE_HANDLE_ISSUE);
        Handle issued = new Handle(capability, owner, slot, generation);
        // The remaining writes are fixed-capacity primitive stores, with
        // widths and values validated before this publication sequence.
        if (freeCount != 0) freeCount--;
        else slotCount++;
        generations[slot] = generation;
        for (int i = 0; i < kinds.length; i++) store(i, row, values[i]);
        rowSlots[row] = slot;
        slotRows[slot] = row;
        live[slot] = true;
        length++;
        return issued;
    }

    /** Unordered O(column-count) erase, preserving surviving logical handles. */
    public synchronized void eraseSwap(Handle handle) {
        int row = resolve(handle);
        unborrowed();
        int last = length - 1;
        if (row != last) {
            for (int i = 0; i < kinds.length; i++) {
                int stride = specs[i].bytes();
                System.arraycopy(columns[i], last * stride, columns[i], row * stride, stride);
            }
            int displaced = rowSlots[last];
            rowSlots[row] = displaced;
            slotRows[displaced] = row;
        }
        // The vacated trailing physical row must not retain values from a
        // deleted row after a swap. This also clears references to old scalar
        // payloads before that storage slot can be reused.
        for (int i = 0; i < kinds.length; i++) {
            int stride = specs[i].bytes();
            int start = last * stride;
            Arrays.fill(columns[i], start, start + stride, (byte) 0);
        }
        length--;
        int slot = handle.slot();
        live[slot] = false;
        if (generations[slot] != Long.MAX_VALUE) {
            generations[slot]++;
            reusable[freeCount++] = slot;
        }
    }

    /**
     * Compiler's transposed field-read target for rows[index].field. Select
     * the dense position and load the column under ONE lock: allocating a
     * handle and releasing the lock in between could allow a swap-delete to
     * change which row is selected. No RowView or name lookup on this path.
     */
    public synchronized Object getAt(long index, int field) {
        int row = checkedDenseRow(index);
        int i = column(field);
        if (writers[i]) throw new IllegalStateException("mutably borrowed column");
        return load(i, row);
    }

    /** Checked positional field write, atomic against row structural changes. */
    public synchronized void setAt(long index, int field, Object value) {
        int row = checkedDenseRow(index);
        int i = column(field);
        validateValue(specs[i], value);
        if (readers[i] != 0 || writers[i]) {
            throw new IllegalStateException("borrowed column");
        }
        store(i, row, value);
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
        validateValue(specs[i], value);
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
        for (int i = 0; i < kinds.length; i++) validateValue(specs[i], values[i]);
        for (int i = 0; i < kinds.length; i++) store(i, row, values[i]);
    }

    /**
     * Primitive-only, allocation-free checked lowering target for
     * `rows[index].f64Field += delta`. Row resolution, loan checks and RMW
     * execute under ONE store lock, with no guest callback/closure.
     * Arbitrary arithmetic/effect lowering must not route through this hook.
     */
    public synchronized void addF64At(long index, int field, double delta) {
        int row = checkedDenseRow(index);
        int i = column(field);
        requireAddableF64(i);
        store(i, row, (Double) load(i, row) + delta);
    }

    /**
     * The stable-handle variant of the same f64-only operation.
     * Unlike positional indexing it follows the logical row over swap-delete.
     */
    public synchronized void addF64(Handle handle, int field, double delta) {
        int row = resolve(handle);
        int i = column(field);
        requireAddableF64(i);
        store(i, row, (Double) load(i, row) + delta);
    }

    private void requireAddableF64(int field) {
        if (kinds[field] != Kind.F64) {
            throw new IllegalArgumentException("atomic f64 add requires a f64 column");
        }
        if (readers[field] != 0 || writers[field]) {
            throw new IllegalStateException("borrowed column");
        }
    }

    /**
     * Internal checked lowering hook for a compiler-verified pure row update.
     * The callback sees a detached scalar snapshot, not the physical columns.
     * Validation and commit occur under the same store lock so concurrent
     * methods cannot lose updates or expose partially modified rows.
     *
     * Never pass arbitrary guest callbacks here: the compiler must first
     * prove the generated transform is pure, bounded and non-suspending.
     */
    synchronized void transformRow(
            Handle handle, java.util.function.UnaryOperator<Object[]> verifiedTransform) {
        int row = resolve(handle);
        unborrowed();
        Objects.requireNonNull(verifiedTransform, "verified row transform");
        Object[] snapshot = new Object[kinds.length];
        for (int i = 0; i < snapshot.length; i++) snapshot[i] = load(i, row);
        // Even a package-private callback may be malformed. Never let a
        // nested read/write/resize observe or change the logical row mid-plan.
        // Release the guard on every Throwable, including OOME and errors.
        Object[] updated;
        rejectedReentrantAccess = false;
        transforming = true;
        try {
            updated = verifiedTransform.apply(snapshot);
        } finally {
            transforming = false;
        }
        // Catching the nested-access exception does not turn a tainted
        // callback into a valid transaction. Nothing was published yet.
        if (rejectedReentrantAccess) {
            throw new IllegalStateException("row transaction attempted reentrant store access");
        }
        open();
        if (updated == null || updated.length != kinds.length) {
            throw new IllegalStateException("invalid compiler row transform result");
        }
        for (int i = 0; i < kinds.length; i++) validateValue(specs[i], updated[i]);
        for (int i = 0; i < kinds.length; i++) store(i, row, updated[i]);
    }

    /**
     * Internal target for a compiler-verified compound indexed field update
     * (e.g. rows[i].x += delta). Resolving the position, reading the prior
     * field, evaluating its pure transform and writing the new value are one
     * synchronized transaction. NEVER lower compound writes as getAt+setAt:
     * another task could update the field or swap-delete the row between them.
     *
     * This hook is package-private intentionally: arbitrary guest callbacks,
     * suspending expressions, resources and effects have not been verified.
     * The callback receives a detached value for RECORD columns.
     */
    synchronized void transformAt(
            long index, int field, java.util.function.UnaryOperator<Object> verifiedTransform) {
        int row = checkedDenseRow(index);
        int i = column(field);
        if (readers[i] != 0 || writers[i]) {
            throw new IllegalStateException("borrowed column");
        }
        Objects.requireNonNull(verifiedTransform, "verified column transform");
        Object before = load(i, row);
        Object updated;
        rejectedReentrantAccess = false;
        transforming = true;
        try {
            updated = verifiedTransform.apply(before);
        } finally {
            // A failure, even an Error, must never leave the store locked out.
            transforming = false;
        }
        if (rejectedReentrantAccess) {
            throw new IllegalStateException("column transaction attempted reentrant store access");
        }
        open();
        validateValue(specs[i], updated);
        store(i, row, updated);
    }

    public synchronized ColumnLoan borrowRead(int field) {
        open();
        int i = column(field);
        if (writers[i]) throw new IllegalStateException("column is mutably borrowed");
        if (readers[i] == Integer.MAX_VALUE) throw new IllegalStateException("reader count overflow");
        // Issue the loan before publishing its read hold. A failed host
        // allocation must never create a borrow that no caller can release.
        failAllocationOnce(AllocationFailurePoint.BEFORE_COLUMN_LOAN_ISSUE);
        ColumnLoan issued = new ColumnLoan(i, false);
        readers[i]++;
        return issued;
    }

    public synchronized ColumnLoan borrowWrite(int field) {
        open();
        int i = column(field);
        if (writers[i] || readers[i] != 0) throw new IllegalStateException("column is borrowed");
        // Exclusive hold publication is the final, allocation-free step.
        failAllocationOnce(AllocationFailurePoint.BEFORE_COLUMN_LOAN_ISSUE);
        ColumnLoan issued = new ColumnLoan(i, true);
        writers[i] = true;
        return issued;
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
                validateValue(specs[field], value);
                store(field, row, value);
            }
        }

        @Override
        public void close() {
            synchronized (InvertedColumnStore.this) {
                // A verified row/column transform must be effect-free while
                // holding its transaction monitor. Closing an unrelated loan
                // from a callback would otherwise mutate loan state without
                // going through open() and could evade the reentrance fence.
                if (transforming) {
                    rejectedReentrantAccess = true;
                    throw new IllegalStateException(
                            "cannot release a column loan during a row transaction");
                }
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
        // close() does not use open(), so guard against reentrant teardown
        // during the detached snapshot evaluation explicitly.
        if (transforming) {
            rejectedReentrantAccess = true;
            throw new IllegalStateException("cannot close an inverted store during row transform");
        }
        if (closed) return;
        closed = true;
        // Erase ALL previously allocated column contents before dropping even
        // one backing reference. Never allocate a new array or ByteBuffer in
        // this teardown path: allocation failure between columns could strand
        // later sensitive columns unsanitized while the store appears closed.
        for (byte[] column : columns) Arrays.fill(column, (byte) 0);
        for (int i = 0; i < kinds.length; i++) {
            columns[i] = EMPTY_COLUMN;
            buffers[i] = null;
        }
        length = capacity = 0;
    }
}
