package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Bounded, allocation-free selection of a less-loaded SOFT actor lane.
 *
 * <p>This does not rebind threads, move actor ownership, or grant access to
 * actor state. CPU-bound dispatchers must bypass it entirely. Contiguous
 * windows rotate on consecutive spill attempts, so cold lanes outside a
 * prior window can still receive work; locality is an advisory trade-off.</p>
 */
final class SoftLaneSelector {
    static final int MAX_PROBES = 64;

    private SoftLaneSelector() { }

    /** Returns a preferred or less-backed-up existing lane. Never allocates. */
    static int select(int preferred, int preferredQueued,
                      AtomicIntegerArray queued, int sampleSeed) {
        Objects.requireNonNull(queued, "queued");
        final int lanes = queued.length();
        if (lanes < 1 || preferred < 0 || preferred >= lanes || preferredQueued < 0) {
            throw new IllegalArgumentException("invalid soft lane sampling state");
        }
        int best = preferred;
        int minimum = preferredQueued;
        // Preferred was sampled by caller. Visit at most 63 additional lanes.
        final int limit = Math.min(lanes - 1, MAX_PROBES - 1);
        int cursor = Math.floorMod(sampleSeed, lanes);
        for (int checked = 0; checked < limit;) {
            if (cursor != preferred) {
                final int depth = queued.get(cursor);
                if (depth < 0) throw new IllegalStateException("negative lane queue depth");
                if (depth < minimum) {
                    minimum = depth;
                    best = cursor;
                    if (depth == 0) break;
                }
                checked++;
            }
            if (++cursor == lanes) cursor = 0;
        }
        return best;
    }
}
