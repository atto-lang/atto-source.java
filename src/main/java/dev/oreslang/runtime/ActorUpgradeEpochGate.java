package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Trusted-host, actor-identity-preserving hot-upgrade admission fence.
 *
 * This class is deliberately not wired into ActorRuntime yet: a host-only fence
 * cannot certify scheduler quiescence, state migration, sandbox isolation,
 * capability provenance, or FFI/arena leases. The caller must supply compiler-
 * verified digests; guest code must never obtain this coordinator or mint them.
 */
final class ActorUpgradeEpochGate implements AutoCloseable {
    // This is an *admission description*, not proof that the process or
    // isolate exists. The trusted loader must attest physical placement.
    enum ActorKind { SHARED, PRIVATE, UNTRUSTED }
    enum ProtectionDomain {
        TRUSTED_HOST, TRUSTED_PROCESS, SANDBOX_ISOLATE, SANDBOX_PROCESS
    }
    enum CodeMode {
        GRAAL_INTERPRETED, GRAAL_JIT, GRAAL_NATIVE_AOT,
        LLVM_PIC_AOT, LLVM_ORC_JIT, WASM_AOT
    }
    enum LeaseKind {
        ACTOR_TURN, SUSPENDED_TASK, FFI_CALLBACK, SHARED_REGION,
        OUTPUT_READER, EXTERNAL_OPERATION
    }

    record Placement(ActorKind actorKind, ProtectionDomain protection,
                     String sharedCellSchemaSha256) {
        Placement {
            Objects.requireNonNull(actorKind, "actor kind");
            Objects.requireNonNull(protection, "protection domain");
            Image.validateDigest(sharedCellSchemaSha256);
            if (actorKind == ActorKind.UNTRUSTED) {
                if (protection != ProtectionDomain.SANDBOX_PROCESS
                        && protection != ProtectionDomain.SANDBOX_ISOLATE)
                    throw new SecurityException(
                            "untrusted actor requires an independently enforced sandbox");
            } else if (protection == ProtectionDomain.SANDBOX_PROCESS
                    || protection == ProtectionDomain.SANDBOX_ISOLATE) {
                throw new SecurityException(
                        "trusted and untrusted actor protection domains cannot be conflated");
            }
        }
    }

    record Image(String codeSha256, String stateSchemaSha256,
                 String messageAbiSha256, String capabilitySha256,
                 CodeMode codeMode) {
        Image {
            Objects.requireNonNull(codeMode, "code mode");
            validateDigest(codeSha256);
            validateDigest(stateSchemaSha256);
            validateDigest(messageAbiSha256);
            validateDigest(capabilitySha256);
        }

        // Compatibility with the old host-only gate constructor. The execution
        // backend can change between generations without changing actor ABI.
        Image(String codeSha256, String stateSchemaSha256,
              String messageAbiSha256, String capabilitySha256) {
            this(codeSha256, stateSchemaSha256, messageAbiSha256,
                    capabilitySha256, CodeMode.GRAAL_INTERPRETED);
        }

        private static void validateDigest(String sha) {
            if (sha == null || !sha.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("expected canonical lowercase SHA-256");
            }
        }

        boolean sameUpgradeBoundary(Image other) {
            return stateSchemaSha256.equals(other.stateSchemaSha256)
                    && messageAbiSha256.equals(other.messageAbiSha256)
                    && capabilitySha256.equals(other.capabilitySha256);
        }
    }

    static final class Lease implements AutoCloseable {
        private final ActorUpgradeEpochGate owner;
        private final long epoch;
        private final LeaseKind kind;
        private final AtomicBoolean released = new AtomicBoolean();

        private Lease(ActorUpgradeEpochGate owner, long epoch, LeaseKind kind) {
            this.owner = owner;
            this.epoch = epoch;
            this.kind = kind;
        }

        long epoch() { return epoch; }
        LeaseKind kind() { return kind; }

        @Override public synchronized void close() {
            // Accounting and authorization retirement share the gate lock.
            // Failed accounting retains the lease so retirement is retryable.
            owner.release(this);
        }
    }

    static final class Candidate {
        private final ActorUpgradeEpochGate owner;
        private final long originEpoch;
        private final Image proposed;

        private Candidate(ActorUpgradeEpochGate owner, long originEpoch, Image proposed) {
            this.owner = owner;
            this.originEpoch = originEpoch;
            this.proposed = proposed;
        }
    }

    private final long actorIdentity;
    private final Placement placement;
    private final int[] pinsByKind = new int[LeaseKind.values().length];
    private long epoch = 1;
    private int pins;
    private boolean closed;
    private Image active;
    private Candidate candidate;

    // Legacy host-only gate defaults to SHARED/TRUSTED_HOST. Security-sensitive
    // loaders MUST pass an explicit Placement and prove its isolation.
    ActorUpgradeEpochGate(long actorIdentity, Image initial) {
        this(actorIdentity, new Placement(ActorKind.SHARED,
                ProtectionDomain.TRUSTED_HOST, "0".repeat(64)), initial);
    }

    ActorUpgradeEpochGate(long actorIdentity, Placement placement, Image initial) {
        if (actorIdentity <= 0) throw new IllegalArgumentException("actor identity must be positive");
        this.actorIdentity = actorIdentity;
        this.placement = Objects.requireNonNull(placement, "placement");
        this.active = Objects.requireNonNull(initial, "initial image");
    }

    synchronized Placement placement() { return placement; }
    synchronized long actorIdentity() { return actorIdentity; }
    synchronized long epoch() { return epoch; }
    synchronized Image image() { return active; }
    synchronized int livePins() { return pins; }
    synchronized int livePins(LeaseKind kind) {
        return pinsByKind[Objects.requireNonNull(kind, "lease kind").ordinal()];
    }
    synchronized boolean frozen() { return candidate != null; }
    /** A still-owned, unretired preflight token is required at route CAS. */
    synchronized boolean ownsPrepared(Candidate staged) {
        return !closed && staged != null && staged.owner == this
                && staged == candidate && staged.originEpoch == epoch;
    }
    synchronized boolean retired() { return closed && pins == 0; }

    /**
     * A live operation pins its generation. Includes suspended continuations
     * and asynchronous callbacks once integrated into the actor scheduler.
     */
    synchronized Lease pin() { return pin(LeaseKind.ACTOR_TURN); }

    synchronized Lease pin(LeaseKind kind) {
        Objects.requireNonNull(kind, "lease kind");
        if (closed || candidate != null)
            throw new IllegalStateException("actor is closed or upgrade-frozen");
        int slot = kind.ordinal();
        if (pins == Integer.MAX_VALUE || pinsByKind[slot] == Integer.MAX_VALUE)
            throw new IllegalStateException("actor pin limit exhausted");
        // Allocate before charging either counter. Every await/Task, FFI and
        // shared-region lease must live until its real exit/cleanup barrier.
        Lease lease = new Lease(this, epoch, kind);
        ++pins;
        ++pinsByKind[slot];
        return lease;
    }

    synchronized boolean permits(Lease lease) {
        return lease != null && lease.owner == this && !lease.released.get()
                && lease.epoch == epoch && !closed;
    }

    /**
     * Freeze new admission. v0 requires byte-exact schema, wire ABI and
     * capabilities: unsupported state migration MUST fail closed.
     */
    synchronized Candidate prepare(Image proposed) {
        return prepare(placement, proposed);
    }

    synchronized Candidate prepare(Placement target, Image proposed) {
        if (!placement.equals(Objects.requireNonNull(target, "target placement")))
            throw new SecurityException(
                    "actor upgrade cannot change actor kind, protection domain or shared-cell ABI");
        if (closed || candidate != null)
            throw new IllegalStateException("actor already closed or preparing upgrade");
        Objects.requireNonNull(proposed, "proposed");
        if (!active.sameUpgradeBoundary(proposed))
            throw new SecurityException("actor upgrade changes state, message ABI or capabilities");
        if (active.codeSha256().equals(proposed.codeSha256()))
            throw new IllegalArgumentException("identical code image is not an upgrade");
        candidate = new Candidate(this, epoch, proposed);
        return candidate;
    }

    /** Commit only with no outstanding execution, mailbox, or FFI pins. */
    synchronized boolean publish(Candidate prepared) {
        checkCandidate(prepared);
        if (pins != 0) return false;
        if (epoch == Long.MAX_VALUE)
            throw new IllegalStateException("actor upgrade epoch exhausted");
        active = prepared.proposed;
        ++epoch;
        candidate = null;
        return true;
    }

    synchronized void abort(Candidate prepared) {
        checkCandidate(prepared);
        candidate = null; // preserve old code and identity
    }

    private void checkCandidate(Candidate prepared) {
        if (closed || prepared == null || prepared.owner != this
                || prepared != candidate || prepared.originEpoch != epoch)
            throw new IllegalStateException("stale, foreign or retired upgrade candidate");
    }

    private synchronized void release(Lease lease) {
        if (lease.released.get()) return;
        int slot = lease.kind.ordinal();
        if (pins <= 0 || pinsByKind[slot] <= 0)
            throw new IllegalStateException("actor pin accounting underflow");
        --pins;
        --pinsByKind[slot];
        // permits(), publish() and livePins() cannot observe a retired pin
        // while its lease still authorizes work.
        lease.released.set(true);
    }

    @Override public synchronized void close() {
        closed = true;
        candidate = null;
        // Existing leases survive and are released by their owners.
        // This gate does not reclaim the actor heap or native code image.
    }
}
