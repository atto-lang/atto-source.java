package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Opt-in, bounded observation of actor-message handler call boundaries.
 * Does not observe timer due/notification, guest first bytecode instruction,
 * CPU scheduling guarantees, or hard real-time admission.
 * No per-dispatch allocations: records are materialized only for snapshots.
 */
public final class CglActorMessageTrace {
    public static final int MAX_CAPACITY = 4096;
    public record Sample(UUID actorId, long messageSequence, long enterNanos,
                         long exitNanos, boolean sourceActor) { }
    public record Snapshot(List<Sample> samples, long dropped, long invalid,
                           boolean sealed) {
        public boolean hasCompleteCapture() {
            return sealed && dropped == 0 && invalid == 0;
        }
    }

    private final UUID[] actors;
    private final long[] sequences;
    private final long[] starts;
    private final long[] finishes;
    private final boolean[] sourceActors;
    private int size;
    private long dropped;
    private long invalid;
    private boolean sealed;

    public CglActorMessageTrace(int capacity) {
        if (capacity <= 0 || capacity > MAX_CAPACITY) {
            throw new IllegalArgumentException("invalid CGL trace capacity");
        }
        actors = new UUID[capacity];
        sequences = new long[capacity];
        starts = new long[capacity];
        finishes = new long[capacity];
        sourceActors = new boolean[capacity];
    }

    /** Caller must pass timestamps measured around the actual handler invocation. */
    public synchronized boolean record(UUID actor, long sequence, long enter,
                                       long exit, boolean sourceActor) {
        if (sealed || actor == null || sequence <= 0 || exit - enter < 0) {
            if (invalid != Long.MAX_VALUE) invalid++;
            return false;
        }
        if (size == actors.length) {
            if (dropped != Long.MAX_VALUE) dropped++;
            return false;
        }
        actors[size] = actor;
        sequences[size] = sequence;
        starts[size] = enter;
        finishes[size] = exit;
        sourceActors[size] = sourceActor;
        size++;
        return true;
    }

    /** Explicit caller attestation after all actor workers have quiesced. */
    public synchronized void sealAfterActorsStopped() { sealed = true; }

    /** Snapshot materialization is diagnostic only; never call on an RT path. */
    public synchronized Snapshot snapshot() {
        ArrayList<Sample> samples = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            samples.add(new Sample(actors[i], sequences[i], starts[i],
                                   finishes[i], sourceActors[i]));
        }
        return new Snapshot(List.copyOf(samples), dropped, invalid, sealed);
    }
}
