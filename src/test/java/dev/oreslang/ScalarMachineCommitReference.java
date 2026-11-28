package dev.oreslang;

import dev.oreslang.parser.FlatMachineTable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Host-only reference: scalar transaction semantics, never guest or scheduler admission. */
final class ScalarMachineCommitReference {
    record Snapshot(int node, long value, long version, long generation) {}
    record Operation(int node, int event, long delta) {}
    enum Outcome { COMMITTED, STALE, TERMINAL, NO_EDGE, INVALID_EVENT, CANCELLED, OVERFLOW, INVARIANT, EXHAUSTED }

    private final FlatMachineTable table;
    private final Map<Long, Long> deltas;
    private final long limit;
    private Snapshot current;

    ScalarMachineCommitReference(FlatMachineTable table, List<Operation> operations, long initial, long limit) {
        this.table = Objects.requireNonNull(table);
        if (initial < 0 || limit < 1) throw new IllegalArgumentException("invalid initial snapshot or limit");
        this.limit = limit;
        var plan = new HashMap<Long, Long>();
        int expected = 0;
        for (var row : table.rows()) expected += row.transitions().size();
        if (operations.size() != expected) throw new IllegalArgumentException("incomplete transition plan");
        for (Operation op : operations) {
            if (table.successor(op.node(), op.event()).isEmpty()
                    || plan.putIfAbsent(key(op.node(), op.event()), op.delta()) != null)
                throw new IllegalArgumentException("unknown or duplicate operation");
        }
        this.deltas = Map.copyOf(plan);
        this.current = new Snapshot(table.initial(), initial, 0, 1);
    }

    private static long key(int node, int event) {
        return ((long) node << 32) | (event & 0xffffffffL);
    }

    synchronized Snapshot snapshot() { return current; }

    synchronized Outcome apply(long expectedVersion, long expectedGeneration, int event, boolean cancelled) {
        var old = current;
        if (old.version() != expectedVersion || old.generation() != expectedGeneration) return Outcome.STALE;
        if (table.rows().get(old.node()).terminal()) return Outcome.TERMINAL;
        if (event < 0 || event >= table.events().size()) return Outcome.INVALID_EVENT;
        var next = table.successor(old.node(), event);
        if (next.isEmpty()) return Outcome.NO_EDGE;
        if (cancelled) return Outcome.CANCELLED;
        if (old.version() >= limit || old.generation() >= limit) return Outcome.EXHAUSTED;
        long value;
        try { value = Math.addExact(old.value(), deltas.get(key(old.node(), event))); }
        catch (ArithmeticException overflow) { return Outcome.OVERFLOW; }
        if (value < 0) return Outcome.INVARIANT;
        current = new Snapshot(next.getAsInt(), value, old.version() + 1, old.generation() + 1);
        return Outcome.COMMITTED;
    }
}
