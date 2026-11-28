package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Supervisor-side immutable code-generation dispatch for an opt-in trusted JIT.
 * It does NOT compile IR: the trusted backend produces Candidate outside any
 * actor/critical/carrier turn, after independently validating code and effects.
 * Never accept Candidate, Identity or Ticket from guest code or an FFI boundary.
 * Checks here protect revision/tenant/ABI/borrow/effect continuity and budget.
 *
 * Pinned invocations, including suspended continuations, own their original
 * executable until close(). New invocations see the latest published version.
 * This class neither rewrites active machine-code frames nor exports telemetry.
 */
final class AdaptiveTraceCodeV1<T> {
    record Identity(String tenant, String symbol, String typedIrSha256,
                    String abiSha256, String effectsSha256, String ownershipSha256) {
        Identity {
            if (tenant == null || tenant.isBlank() || symbol == null || symbol.isBlank()
                    || !sha(typedIrSha256) || !sha(abiSha256)
                    || !sha(effectsSha256) || !sha(ownershipSha256)) {
                throw new IllegalArgumentException("invalid trusted function identity");
            }
        }
        private static boolean sha(String s) {
            if (s == null || s.length() != 64) return false;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
            }
            return true;
        }
    }

    /** Backend-provided immutable code artifact; metadata must match pinned IR. */
    record Candidate<T>(Identity identity, int probeMask, T executable) {
        Candidate { Objects.requireNonNull(identity); Objects.requireNonNull(executable); }
    }

    static final class Ticket {
        private AdaptiveTraceCodeV1<?> owner;
        private final long epoch;
        private final long sequence;
        private final int probeMask;
        private final long policyVersion;
        private boolean consumed;

        private Ticket(AdaptiveTraceCodeV1<?> owner, long epoch, long sequence,
                int mask, long policyVersion) {
            this.owner = owner;
            this.epoch = epoch;
            this.sequence = sequence;
            this.probeMask = mask;
            this.policyVersion = policyVersion;
        }
    }

    private static final class Generation<T> {
        private final long id;
        private final int probeMask;
        private final long policyVersion;
        private final T executable;
        private int pins;
        private Generation(long id, int probeMask, long policyVersion, T executable) {
            this.id = id;
            this.probeMask = probeMask;
            this.policyVersion = policyVersion;
            this.executable = executable;
        }
    }

    /** Close only when the logical invocation ends, NOT at await/suspension. */
    static final class Invocation<T> implements AutoCloseable {
        private final AdaptiveTraceCodeV1<T> owner;
        private final Generation<T> generation;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private Invocation(AdaptiveTraceCodeV1<T> owner, Generation<T> generation) {
            this.owner = owner;
            this.generation = generation;
        }
        T executable() {
            if (closed.get()) throw new IllegalStateException("invocation already released");
            return generation.executable;
        }
        long generation() { return generation.id; }
        int compiledMask() { return generation.probeMask; }
        @Override public void close() {
            if (closed.compareAndSet(false, true)) owner.release(generation);
        }
    }

    private final TraceInstrumentationControlV1 policy;
    private final Identity identity;
    private final long supervisorEpoch;
    private final int recompileBudget;
    private final int maxLiveGenerations;
    private final ArrayList<Generation<T>> generations = new ArrayList<>();
    private final Generation<T> baseline;
    private Generation<T> active;
    private Ticket pending;
    private long sequence;
    private int requestedCompilations;
    private boolean retired;

    AdaptiveTraceCodeV1(TraceInstrumentationControlV1 policy, Identity identity,
            long supervisorEpoch, T initialExecutable, int recompileBudget,
            int maxLiveGenerations) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.identity = Objects.requireNonNull(identity, "identity");
        if (!policy.canRecompile() || supervisorEpoch <= 0 || initialExecutable == null
                || recompileBudget < 1 || maxLiveGenerations < 2) {
            throw new IllegalArgumentException("requires adaptive trusted JIT and bounded budgets");
        }
        this.supervisorEpoch = supervisorEpoch;
        this.recompileBudget = recompileBudget;
        this.maxLiveGenerations = maxLiveGenerations;
        baseline = new Generation<>(0, 0, -1, initialExecutable);
        active = baseline;
        generations.add(baseline);
    }

    synchronized Invocation<T> pin() {
        if (retired) throw new IllegalStateException("trace code owner retired");
        Generation<T> selected = active;
        // Revocation must affect new entries even if an older frame remains pinned.
        if (selected != baseline && !policy.permitsAtVersion(selected.probeMask, selected.policyVersion)) {
            selected = baseline;
        }
        if (selected.pins == Integer.MAX_VALUE) throw new IllegalStateException("too many invocation pins");
        selected.pins++;
        return new Invocation<>(this, selected);
    }

    /** Reserve bounded compile authority; no compiler runs while lock held. */
    synchronized Ticket prepare(int probeMask, long epoch) {
        if (retired || epoch != supervisorEpoch || pending != null
                || requestedCompilations >= recompileBudget
                || sequence == Long.MAX_VALUE) {
            throw new IllegalStateException("JIT authorization, epoch or quota denied");
        }
        long policyVersion = policy.version();
        if (!policy.permitsAtVersion(probeMask, policyVersion)) {
            throw new IllegalArgumentException("probes not authorized by current policy");
        }
        prune();
        if (!hasCapacityForReplacement()) {
            throw new IllegalStateException("live code-generation quota exceeded");
        }
        requestedCompilations++; // Includes failed/aborted compiles to prevent DoS.
        pending = new Ticket(this, epoch, ++sequence, probeMask, policyVersion);
        return pending;
    }

    /** Publish only an independently verified trusted-compiler artifact. */
    synchronized long publish(Ticket ticket, Candidate<T> artifact) {
        verifyTicket(ticket);
        Objects.requireNonNull(artifact, "compiled artifact");
        if (!identity.equals(artifact.identity) || ticket.probeMask != artifact.probeMask
                || !policy.permitsAtVersion(ticket.probeMask, ticket.policyVersion)) {
            throw new SecurityException("changed IR, ABI, effects, ownership or trace policy");
        }
        prune();
        if (!hasCapacityForReplacement()) {
            throw new IllegalStateException("live code-generation quota exceeded");
        }
        // After verification, evict only an unpinned superseded target.
        // The baseline and any in-flight code generation remain owned.
        if (active != baseline && active.pins == 0) {
            generations.remove(active);
        }
        Generation<T> replacement = new Generation<>(ticket.sequence, ticket.probeMask,
                ticket.policyVersion, artifact.executable);
        generations.add(replacement);
        active = replacement;
        ticket.consumed = true;
        ticket.owner = null;
        pending = null;
        prune();
        return replacement.id;
    }

    synchronized void abort(Ticket ticket) {
        verifyTicket(ticket);
        ticket.consumed = true;
        ticket.owner = null;
        pending = null;
    }

    synchronized void retire() {
        if (retired) return;
        retired = true;
        if (pending != null) {
            pending.consumed = true;
            pending.owner = null;
            pending = null;
        }
        prune();
    }

    private void verifyTicket(Ticket ticket) {
        if (retired || ticket == null || ticket != pending || ticket.owner != this
                || ticket.consumed || ticket.epoch != supervisorEpoch
                || ticket.sequence != sequence) {
            throw new SecurityException("stale, replayed or foreign JIT ticket");
        }
    }

    private synchronized void release(Generation<T> generation) {
        if (generation.pins <= 0) throw new IllegalStateException("invocation pin underflow");
        generation.pins--;
        prune();
    }

    private boolean hasCapacityForReplacement() {
        // Replacing an unpinned active JIT generation reuses its bounded slot.
        int count = generations.size() - (active != baseline && active.pins == 0 ? 1 : 0);
        return count < maxLiveGenerations;
    }

    private void prune() {
        if (active != baseline && active.pins == 0
                && !policy.permitsAtVersion(active.probeMask, active.policyVersion)) {
            active = baseline;
        }
        generations.removeIf(gen -> gen != baseline && gen != active && gen.pins == 0);
    }

    synchronized int retainedGenerations() { return generations.size(); }
    synchronized int compilationAttempts() { return requestedCompilations; }
    synchronized boolean isRetired() { return retired; }
}
