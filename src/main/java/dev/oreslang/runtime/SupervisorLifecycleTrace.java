package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Opt-in bounded execution trace of a supervisor CONTROL FSM. A journal
 * entry is published only AFTER the pure reducer has accepted the event.
 * The runtime never invokes a guest callback or a network exporter here.
 *
 * No tracing is performed unless the host explicitly enables it. Overflow
 * is observable and prevents claiming a complete trace refinement proof.
 */
public final class SupervisorLifecycleTrace {
    private SupervisorLifecycleTrace() {}

    public record Entry(long sequence, long supervisorId,
                        SupervisorLifecycleFsm.State before,
                        SupervisorLifecycleFsm.Event event,
                        SupervisorLifecycleFsm.State after) {
        public Entry {
            if (sequence < 1 || supervisorId < 1) throw new IllegalArgumentException("bad trace identity");
            Objects.requireNonNull(before, "before");
            Objects.requireNonNull(event, "event");
            Objects.requireNonNull(after, "after");
        }
    }

    public record Snapshot(List<Entry> entries, long dropped, long issued) {
        public Snapshot {
            entries = List.copyOf(entries);
            if (dropped < 0 || issued < 0 || dropped > issued) {
                throw new IllegalArgumentException("invalid trace counters");
            }
        }
        public boolean complete() { return dropped == 0 && entries.size() == issued; }
    }

    public static final class Journal {
        private final int capacity;
        private final ArrayDeque<Entry> entries = new ArrayDeque<>();
        private long issued;
        private long dropped;

        public Journal(int capacity) {
            if (capacity < 1 || capacity > 65536) {
                throw new IllegalArgumentException("trace capacity must be 1..65536");
            }
            this.capacity = capacity;
        }

        /**
         * Called under supervisor treeLock after committing the state. The
         * trace is a bounded observer, never an authority to change outcomes.
         */
        public synchronized void record(long id,
                SupervisorLifecycleFsm.State before,
                SupervisorLifecycleFsm.Event event,
                SupervisorLifecycleFsm.State after) {
            if (before == after) return; // exact duplicate or stale receipt
            if (issued == Long.MAX_VALUE) {
                throw new IllegalStateException("supervisor trace sequence overflow");
            }
            if (entries.size() == capacity) {
                entries.removeFirst();
                dropped++;
            }
            entries.addLast(new Entry(++issued, id, before, event, after));
        }

        public synchronized Snapshot snapshot() {
            return new Snapshot(new ArrayList<>(entries), dropped, issued);
        }
    }

    /**
     * Replay recorded transitions through the same pure FSM implementation.
     * This is an execution-trace conformance test, NOT an independent proof
     * that the reducer itself is correct (TLA+ is checked separately).
     */
    public static void verifyComplete(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!snapshot.complete()) {
            throw new IllegalStateException("incomplete supervisor trace: "
                    + snapshot.dropped() + " entries dropped");
        }
        long sequence = 0;
        Map<Long, SupervisorLifecycleFsm.State> current = new HashMap<>();
        Map<Long, Long> parentOf = new HashMap<>();
        for (Entry entry : snapshot.entries()) {
            if (entry.sequence() != ++sequence) {
                throw new IllegalStateException("supervisor trace sequence discontinuity");
            }
            var old = current.getOrDefault(entry.supervisorId(),
                    SupervisorLifecycleFsm.State.initial());
            if (!old.equals(entry.before())) {
                throw new IllegalStateException("supervisor trace before-state mismatch at "
                        + entry.sequence());
            }
            if (entry.event() instanceof SupervisorLifecycleFsm.ShutdownRequested request) {
                for (Long childId : request.children()) {
                    if (childId.longValue() == entry.supervisorId()) {
                        throw new IllegalStateException("supervisor cannot be its own child");
                    }
                    Long priorParent = parentOf.putIfAbsent(childId, entry.supervisorId());
                    if (priorParent != null && priorParent.longValue() != entry.supervisorId()) {
                        throw new IllegalStateException(
                                "supervisor child has two parents in trace: " + childId);
                    }
                    // Walk ancestor identities, bounded by the actual trace
                    // graph: a circular hierarchy must never verify.
                    Long ancestor = entry.supervisorId();
                    int budget = parentOf.size() + 1;
                    while (ancestor != null && budget-- > 0) {
                        if (ancestor.equals(childId)) {
                            throw new IllegalStateException("supervisor hierarchy cycle");
                        }
                        ancestor = parentOf.get(ancestor);
                    }
                    if (ancestor != null) {
                        throw new IllegalStateException("unbounded supervisor parent chain");
                    }
                }
            }
            // Cross-node refinement: a parent cannot accept logical child
            // retirement before that child's OWN lifecycle reached STOPPED.
            // The local reducer alone cannot check this ordering because it
            // only knows the parent's expected child identities.
            if (entry.event() instanceof SupervisorLifecycleFsm.ChildFinished child) {
                var childState = current.get(child.childId());
                if (childState == null || !childState.logicallyStopped()
                        || childState.failed() != child.failed()) {
                    throw new IllegalStateException(
                            "child retirement preceded recorded child settlement at "
                                    + entry.sequence());
                }
            }
            if (entry.event() instanceof SupervisorLifecycleFsm.ChildReclaimed child) {
                var childState = current.get(child.childId());
                if (childState == null
                        || childState.phase() != SupervisorLifecycleFsm.Phase.RECLAIMED) {
                    throw new IllegalStateException(
                            "child physical receipt without recorded reclamation at "
                                    + entry.sequence());
                }
            }
            var computed = SupervisorLifecycleFsm.reduce(old, entry.event());
            if (computed == old || !computed.equals(entry.after())) {
                throw new IllegalStateException("supervisor trace FSM mismatch at "
                        + entry.sequence());
            }
            current.put(entry.supervisorId(), computed);
        }
    }
}
