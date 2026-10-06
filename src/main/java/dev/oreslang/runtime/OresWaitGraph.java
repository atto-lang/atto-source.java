package dev.oreslang.runtime;

import java.util.IdentityHashMap;

/** Only records mandatory task-to-task Future dependencies, never external I/O. */
final class OresWaitGraph {
    private static final IdentityHashMap<Object, OresFuture<?>> EDGES = new IdentityHashMap<>();
    private OresWaitGraph() { }

    static synchronized void await(Object waiter, OresFuture<?> awaited) {
        if (awaited.isDone() || awaited.taskProducer() == null) return;
        StringBuilder path = new StringBuilder(waiter.toString());
        OresFuture<?> dependency = awaited;
        while (dependency != null && !dependency.isDone()) {
            Object producer = dependency.taskProducer();
            if (producer == null) break;
            path.append(" -> ").append(producer);
            if (producer == waiter) throw new OresDeadlockError("task Future dependency cycle: " + path);
            dependency = EDGES.get(producer);
        }
        EDGES.put(waiter, awaited);
    }

    static synchronized void detach(Object waiter) { EDGES.remove(waiter); }
    static synchronized int size() { return EDGES.size(); }
}
