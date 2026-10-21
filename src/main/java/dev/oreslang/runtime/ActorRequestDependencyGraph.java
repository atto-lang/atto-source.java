package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Bounded, runtime-only detector for serial actor request/await cycles.
 *
 * <p>Edges represent a suspended request whose caller cannot process another
 * message until its reply arrives. Register an edge only when the caller's
 * serial lane is actually blocked by this dependency, never for fire-and-forget
 * sends or independently progressing concurrent message lifetimes.</p>
 *
 * <p>The returned lease must be closed on success, failure, cancellation,
 * timeout and actor teardown. This is a safety aid for a single supervisor's
 * known local dependency graph, not a global distributed deadlock detector.
 * Do not invoke guest callbacks while holding its lock.</p>
 */
final class ActorRequestDependencyGraph {
    record ActorIdentity(String id, long incarnation) {
        ActorIdentity {
            if (id == null || id.isBlank() || incarnation < 0) {
                throw new IllegalArgumentException("actor identity and incarnation required");
            }
        }
    }

    static final class CyclicRequestException extends IllegalStateException {
        CyclicRequestException(String message) { super(message); }
    }

    final class Lease implements AutoCloseable {
        private final ActorIdentity caller;
        private final ActorIdentity callee;
        private boolean closed;

        private Lease(ActorIdentity caller, ActorIdentity callee) {
            this.caller = caller;
            this.callee = callee;
        }

        @Override public void close() {
            synchronized (ActorRequestDependencyGraph.this) {
                if (closed) return;
                closed = true;
                Map<ActorIdentity, Integer> outgoing = edges.get(caller);
                if (outgoing == null) return;
                int count = outgoing.getOrDefault(callee, 0);
                if (count <= 1) outgoing.remove(callee);
                else outgoing.put(callee, count - 1);
                if (outgoing.isEmpty()) edges.remove(caller);
                activeEdges--;
            }
        }
    }

    private final Map<ActorIdentity, Map<ActorIdentity, Integer>> edges = new HashMap<>();
    private final int maxEdges;
    private int activeEdges;

    ActorRequestDependencyGraph(int maxEdges) {
        if (maxEdges < 1 || maxEdges > 1_000_000) {
            throw new IllegalArgumentException("bounded dependency graph required");
        }
        this.maxEdges = maxEdges;
    }

    synchronized Lease suspendUntilReply(ActorIdentity caller, ActorIdentity callee) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(callee, "callee");
        if (caller.equals(callee) || canReach(callee, caller)) {
            throw new CyclicRequestException("serial actor request/await dependency cycle");
        }
        if (activeEdges == maxEdges) {
            throw new IllegalStateException("actor request dependency capacity exhausted");
        }
        edges.computeIfAbsent(caller, ignored -> new HashMap<>())
                .merge(callee, 1, Integer::sum);
        activeEdges++;
        return new Lease(caller, callee);
    }

    synchronized int activeCount() { return activeEdges; }

    /**
     * Traverse only already-admitted edges. A bounded depth-first walk avoids
     * recursive stack overflow for adversarially deep dependency chains.
     */
    private boolean canReach(ActorIdentity start, ActorIdentity target) {
        Set<ActorIdentity> seen = new HashSet<>();
        ArrayDeque<ActorIdentity> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            ActorIdentity current = queue.removeFirst();
            if (!seen.add(current)) continue;
            if (current.equals(target)) return true;
            var successors = edges.get(current);
            if (successors != null) queue.addAll(successors.keySet());
        }
        return false;
    }
}
