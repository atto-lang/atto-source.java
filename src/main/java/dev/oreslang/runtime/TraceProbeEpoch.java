package dev.oreslang.runtime;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.Truffle;
import java.util.Objects;

/**
 * Internal per-execution-domain tracing policy gate for precompiled probe sites.
 *
 * This does NOT insert missing compiler sites, scan bytecode, or rewrite code.
 * A Truffle entry/exit/I/O probe already emitted by the compiler can consult
 * this gate; an operator policy change invalidates specialization assumptions
 * and allows a recompiled path to take the new branch without redeployment.
 *
 * No global tenant state, guest callbacks, I/O, exporter, or arbitrary objects
 * are admitted by this class. The installing runtime must retain one instance
 * per isolate/supervisor domain and authenticate the controller separately.
 */
final class TraceProbeEpoch {
    enum Level { OFF, BOUNDARIES, CALLS, FULL }

    private record Epoch(Level enabled, long generation, Assumption assumption) { }

    private final Level compiledCoverage;
    private final boolean sealedPolicy;
    private volatile Epoch current;

    TraceProbeEpoch(Level compiledCoverage, boolean sealedPolicy) {
        this.compiledCoverage = Objects.requireNonNull(compiledCoverage, "compiledCoverage");
        this.sealedPolicy = sealedPolicy;
        current = new Epoch(Level.OFF, 0L, newAssumption());
    }

    /**
     * Called only from compiled, trusted probe instructions. A disabled gate
     * performs no clock reads or event allocation. Invalidations deoptimize
     * Truffle call targets that inlined the old assumption.
     */
    boolean shouldRecord(Level needed) {
        Objects.requireNonNull(needed, "needed");
        Epoch snapshot = current;
        if (!snapshot.assumption().isValid()) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            snapshot = current;
        }
        return needed != Level.OFF
                && needed.ordinal() <= compiledCoverage.ordinal()
                && needed.ordinal() <= snapshot.enabled().ordinal();
    }

    long generation() {
        return current.generation();
    }

    Level currentMode() {
        return current.enabled();
    }

    Level compiledCoverage() {
        return compiledCoverage;
    }

    /**
     * Trusted control-plane operation, never guest-visible. Change publication
     * and assumption invalidation are serialised; already-active operations
     * may complete under their captured policy and must be generation fenced
     * before a collector attaches any recorded outcome.
     */
    @CompilerDirectives.TruffleBoundary
    synchronized boolean reconfigure(Level next) {
        Objects.requireNonNull(next, "next");
        if (sealedPolicy || next.ordinal() > compiledCoverage.ordinal()) {
            return false; // no JIT can retroactively add absent call sites
        }
        Epoch previous = current;
        if (previous.enabled() == next) return true;
        // Avoid wraparound of control generations; stop accepting changes
        // rather than reusing a generation with a stale callback.
        if (previous.generation() == Long.MAX_VALUE) return false;
        Epoch replacement = new Epoch(next, previous.generation() + 1L, newAssumption());
        current = replacement;
        previous.assumption().invalidate();
        return true;
    }

    private static Assumption newAssumption() {
        return Truffle.getRuntime().createAssumption("atto-trace-probe-policy");
    }
}
