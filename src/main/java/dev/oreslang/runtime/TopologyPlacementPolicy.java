package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Pure host-side ranking policy for logical work placement.
 *
 * <p>This class does not grant memory authority and never changes CPU affinity.
 * It only orders already-eligible workers. The scheduler remains responsible
 * for queue admission, execution permits, cancellation and ownership checks.</p>
 */
final class TopologyPlacementPolicy {
    record Worker(int lane, int cpu, int llc, int numa, boolean eligible, boolean healthy) {
        Worker {
            if (lane < 0) throw new IllegalArgumentException("lane must be non-negative");
            if (cpu < -1 || llc < -1 || numa < -1) {
                throw new IllegalArgumentException("topology identifiers must be >= -1");
            }
        }
    }

    record Hint(int preferredLane, int preferredLlc, int preferredNuma) {
        Hint {
            if (preferredLane < -1 || preferredLlc < -1 || preferredNuma < -1) {
                throw new IllegalArgumentException("placement hints must be >= -1");
            }
        }
        static Hint none() { return new Hint(-1, -1, -1); }
    }

    private TopologyPlacementPolicy() {}

    static List<Worker> rank(List<Worker> workers, Hint hint) {
        Objects.requireNonNull(workers, "workers");
        Objects.requireNonNull(hint, "hint");
        ArrayList<Worker> eligible = new ArrayList<>();
        for (Worker worker : workers) {
            Objects.requireNonNull(worker, "worker");
            if (worker.eligible() && worker.healthy()) eligible.add(worker);
        }
        eligible.sort(Comparator
                .comparingInt((Worker w) -> localityRank(w, hint))
                .thenComparingInt(Worker::lane));
        return List.copyOf(eligible);
    }

    private static int localityRank(Worker worker, Hint hint) {
        if (hint.preferredLane() >= 0 && worker.lane() == hint.preferredLane()) return 0;
        if (hint.preferredLlc() >= 0 && worker.llc() == hint.preferredLlc()) return 1;
        if (hint.preferredNuma() >= 0 && worker.numa() == hint.preferredNuma()) return 2;
        return 3;
    }
}
