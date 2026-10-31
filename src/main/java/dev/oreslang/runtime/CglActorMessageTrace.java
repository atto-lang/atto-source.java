package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.HashSet;
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
    public record Sample(UUID actorId, long actorGeneration, long messageSequence,
                         long enterNanos, long exitNanos, boolean sourceActor) { }
    private record HandlerIdentity(UUID actorId, long generation, long sequence) { }
    public record Snapshot(List<Sample> samples, long dropped, long invalid,
                           boolean sealed) {
        /** An expected admitted handler count is required for coverage. */
        public boolean hasCompleteCapture(long expectedHandlers) {
            if (expectedHandlers <= 0 || !sealed || dropped != 0 || invalid != 0
                    || samples.size() != expectedHandlers) {
                return false;
            }
            // Offline only: a duplicate ticket must not mask a missing
            // handler even when observed and expected counts agree.
            HashSet<HandlerIdentity> identities = new HashSet<>();
            for (Sample sample : samples) {
                if (sample.actorId() == null || sample.actorGeneration() <= 0
                        || sample.messageSequence() < 0
                        || sample.exitNanos() < sample.enterNanos()
                        || !identities.add(new HandlerIdentity(sample.actorId(),
                                sample.actorGeneration(), sample.messageSequence()))) {
                    return false;
                }
            }
            return true;
        }
    }

    private final UUID[] actors;
    private final long[] generations;
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
        generations = new long[capacity];
        sequences = new long[capacity];
        starts = new long[capacity];
        finishes = new long[capacity];
        sourceActors = new boolean[capacity];
    }

    /** Caller must pass timestamps measured around the actual handler invocation. */
    public synchronized boolean record(UUID actor, long generation, long sequence,
                                       long enter, long exit, boolean sourceActor) {
        if (sealed || actor == null || generation <= 0 || sequence < 0 || exit < enter) {
            if (invalid != Long.MAX_VALUE) invalid++;
            return false;
        }
        if (size == actors.length) {
            if (dropped != Long.MAX_VALUE) dropped++;
            return false;
        }
        actors[size] = actor;
        generations[size] = generation;
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
            samples.add(new Sample(actors[i], generations[i], sequences[i],
                                   starts[i], finishes[i], sourceActors[i]));
        }
        return new Snapshot(List.copyOf(samples), dropped, invalid, sealed);
    }
}
