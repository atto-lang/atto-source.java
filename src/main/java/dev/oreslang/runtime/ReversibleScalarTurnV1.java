package dev.oreslang.runtime;

/**
 * Actor-local scalar transition primitive. Caller must own the serialized actor
 * turn; no guest entry point, I/O, suspension, persistence or effect publication.
 * Capacity is reserved at construction; no implicit history or receipt eviction.
 */
final class ReversibleScalarTurnV1 {
    enum Kind { COMMIT, UNDO, REDO }
    enum Status { OK, CONFLICT, INVALID_HISTORY, QUOTA, ID_REUSE }
    record Receipt(Status status, long version, long value) {}
    private record Delta(long id, long before, long after) {}
    private record Entry(long id, long expected, Kind kind, long argument, Receipt receipt) {}
    private final Delta[] history;
    private final Entry[] receipts;
    private int size, cursor, used;
    private long version, value;

    ReversibleScalarTurnV1(long initial, int historyCapacity, int commandCapacity) {
        if (historyCapacity < 1 || historyCapacity > 4096 ||
                commandCapacity < 1 || commandCapacity > 4096)
            throw new IllegalArgumentException("invalid reversible turn quota");
        history = new Delta[historyCapacity];
        receipts = new Entry[commandCapacity];
        value = initial;
    }

    long value() { return value; }
    long version() { return version; }

    // Candidate is fully evaluated/validated by caller before commit. Undo and
    // redo arguments identify the original transition, not the undo command.
    Receipt apply(long id, long expected, Kind kind, long argument) {
        if (id <= 0 || expected < 0 || kind == null)
            throw new IllegalArgumentException("invalid command");
        for (int i = 0; i < used; i++) {
            Entry entry = receipts[i];
            if (entry.id() == id) {
                if (entry.expected() == expected && entry.kind() == kind &&
                        entry.argument() == argument) return entry.receipt();
                return fail(Status.ID_REUSE);
            }
        }
        if (expected != version) return fail(Status.CONFLICT);
        if (used == receipts.length || version == Long.MAX_VALUE)
            return fail(Status.QUOTA);
        Delta delta;
        long next;
        switch (kind) {
            case COMMIT -> {
                if (cursor == history.length) return fail(Status.QUOTA);
                delta = new Delta(id, value, argument);
                next = argument;
            }
            case UNDO -> {
                if (cursor == 0 || history[cursor - 1].id() != argument)
                    return fail(Status.INVALID_HISTORY);
                delta = history[cursor - 1];
                next = delta.before();
            }
            case REDO -> {
                if (cursor == size || history[cursor].id() != argument)
                    return fail(Status.INVALID_HISTORY);
                delta = history[cursor];
                next = delta.after();
            }
            default -> throw new AssertionError(kind);
        }
        // Allocate immutable bookkeeping before changing any visible state.
        Receipt receipt = new Receipt(Status.OK, version + 1, next);
        Entry entry = new Entry(id, expected, kind, argument, receipt);
        switch (kind) {
            case COMMIT -> {
                for (int i = cursor; i < size; i++) history[i] = null;
                history[cursor++] = delta;
                size = cursor;
            }
            case UNDO -> cursor--;
            case REDO -> cursor++;
        }
        receipts[used++] = entry;
        value = next;
        version++;
        return receipt;
    }

    private Receipt fail(Status status) { return new Receipt(status, version, value); }
}
