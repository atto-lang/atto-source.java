package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Trusted-host mutable shared-domain arena backed by Java 25 FFM.
 *
 * <p>This is separate from every actor-local {@link ActorArena}. CONTROL may
 * grant a live SHARED actor incarnation membership, but byte access is admitted
 * only from the runtime's actual current actor turn. No MemorySegment, native
 * address, domain coordinate, or writable raw buffer escapes this class.</p>
 *
 * <p>Logical reader/writer admission is FIFO and non-parking via
 * {@link ProxyRwLock}. A queued acquisition settles an {@link OresFuture};
 * source/runtime lowering must suspend the logical task and call
 * {@link #enter(Grant, Admission)} only after its scheduler resumes that same
 * actor. The returned Access is turn-scoped and cannot be reused in a later
 * mailbox/continuation turn.</p>
 */
public final class SharedDomainArena {
    public static final int MAX_MEMBERS = 256;
    private static final AtomicLong NEXT_DOMAIN_ID = new AtomicLong(1);

    public static final class Control {
        private final SharedDomainArena arena;
        private final long domainId;

        private Control(SharedDomainArena arena, long domainId) {
            this.arena = arena;
            this.domainId = domainId;
        }
    }

    public static final class Grant {
        private final SharedDomainArena arena;
        private final long domainId;
        private final int member;
        private final long memberGeneration;
        private final ActorRuntime.ActorId actorId;
        private final long actorGeneration;
        private final boolean read;
        private final boolean write;

        private Grant(
                SharedDomainArena arena,
                long domainId,
                int member,
                long memberGeneration,
                ActorRuntime.ActorId actorId,
                long actorGeneration,
                boolean read,
                boolean write) {
            this.arena = arena;
            this.domainId = domainId;
            this.member = member;
            this.memberGeneration = memberGeneration;
            this.actorId = actorId;
            this.actorGeneration = actorGeneration;
            this.read = read;
            this.write = write;
        }

        long domainId() { return domainId; }
    }

    /** Opaque shared slot handle; its native FFM Slot never escapes. */
    public static final class Handle {
        private final SharedDomainArena arena;
        private final long domainId;
        private final ExplicitNativeI64Region.Slot slot;

        private Handle(
                SharedDomainArena arena,
                long domainId,
                ExplicitNativeI64Region.Slot slot) {
            this.arena = arena;
            this.domainId = domainId;
            this.slot = slot;
        }
    }

    /**
     * Scheduler plumbing result: logical RW authority has been reserved, but
     * guest byte authority has not begun until enter() binds the resumed turn.
     */
    public final class Admission implements AutoCloseable {
        // A logical lease grants one specific domain+membership only.
        // Actor identity alone is insufficient: the same actor may belong
        // to multiple shared domains or hold different rights.
        private final Grant originGrant;
        private final ProxyRwLock.Lease logicalLease;
        private final ActorRuntime.SharedDomainActorIdentity owner;
        private final boolean write;
        private final AtomicBoolean claimed = new AtomicBoolean();
        private final AtomicBoolean released = new AtomicBoolean();

        private Admission(
                Grant originGrant,
                ProxyRwLock.Lease logicalLease,
                ActorRuntime.SharedDomainActorIdentity owner,
                boolean write) {
            this.originGrant = originGrant;
            this.logicalLease = logicalLease;
            this.owner = owner;
            this.write = write;
        }

        public boolean write() { return write; }
        private SharedDomainArena getOuterArena() { return SharedDomainArena.this; }

        private boolean claim() {
            if (released.get()) return false;
            return claimed.compareAndSet(false, true);
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                logicalLease.close();
            }
        }
    }

    /** Lexical byte authority, bound to one exact actor turn. */
    public final class Access implements AutoCloseable {
        private final Grant grant;
        private final Admission admission;
        private final Object turnToken;
        private final AtomicBoolean released = new AtomicBoolean();

        private Access(Grant grant, Admission admission, Object turnToken) {
            this.grant = grant;
            this.admission = admission;
            this.turnToken = turnToken;
        }

        public Handle allocate(long value) {
            requireLive(true);
            return new Handle(
                    SharedDomainArena.this,
                    domainId,
                    region.allocate(value));
        }

        public long read(Handle handle) {
            requireLive(false);
            return region.read(requireHandle(handle));
        }

        public void write(Handle handle, long value) {
            requireLive(true);
            region.write(requireHandle(handle), value);
        }

        public void drop(Handle handle) {
            requireLive(true);
            region.drop(requireHandle(handle));
        }

        private void requireLive(boolean writeRequired) {
            if (released.get()) {
                throw new IllegalStateException("shared-domain access released");
            }
            ActorRuntime.SharedDomainTurnIdentity current =
                    runtime.requireCurrentSharedDomainTurn();
            if (current.turnToken() != turnToken
                    || !sameActor(current.actor(), admission.owner)) {
                throw new SecurityException(
                        "shared-domain access escaped its admitted actor turn");
            }
            synchronized (SharedDomainArena.this) {
                validateGrantLocked(grant, current.actor(), writeRequired);
                if (closing) {
                    throw new IllegalStateException("shared-domain arena retired");
                }
            }
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                admission.close();
            }
        }
    }

    /** Lifetime-only continuation/FFI/I/O ownership; no byte API. */
    public final class LifetimePin implements AutoCloseable {
        private final ExplicitNativeI64Region.LifetimePin pin;
        private final AtomicBoolean released = new AtomicBoolean();

        private LifetimePin(ExplicitNativeI64Region.LifetimePin pin) {
            this.pin = pin;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                pin.close();
            } else {
                // Underlying pin is retry-safe after a physical-close failure.
                pin.close();
            }
        }
    }

    private static final class Member {
        private ActorRuntime.ActorId actorId;
        private long actorGeneration;
        private long generation = 1;
        private boolean active;
        private boolean read;
        private boolean write;
    }

    private final ActorRuntime runtime;
    private final long domainId;
    private final ExplicitNativeI64Region region;
    private final ProxyRwLock gate = new ProxyRwLock();
    private final Member[] members = new Member[MAX_MEMBERS];
    private boolean controlIssued;
    private boolean closing;

    private SharedDomainArena(
            ActorRuntime runtime,
            long domainId,
            ExplicitNativeI64Region region) {
        this.runtime = Objects.requireNonNull(runtime);
        this.domainId = domainId;
        this.region = Objects.requireNonNull(region);
        for (int i = 0; i < members.length; i++) members[i] = new Member();
    }

    static SharedDomainArena open(ActorRuntime runtime, int slots) {
        Objects.requireNonNull(runtime, "runtime");
        if (slots < 1 || slots > 65536) {
            throw new IllegalArgumentException(
                    "shared-domain FFM slots must be 1..65536");
        }
        long domainId = claimDomainId();
        NativeMemoryReservation reservation = runtime.reserveSharedDomainNativeBytes(
                Math.multiplyExact((long) slots, Long.BYTES));
        AtomicReference<SharedDomainArena> self = new AtomicReference<>();
        try {
            ExplicitNativeI64Region region = new ExplicitNativeI64Region(
                    slots,
                    reservation,
                    () -> { }, // Authority is enforced by Access, never region exposure.
                    () -> {
                        SharedDomainArena arena = self.get();
                        if (arena != null) runtime.unregisterSharedDomainArena(arena);
                    });
            SharedDomainArena arena = new SharedDomainArena(runtime, domainId, region);
            self.set(arena);
            return arena;
        } catch (RuntimeException | Error failure) {
            try { reservation.close(); }
            catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    long domainId() { return domainId; }

    public synchronized Control takeControl() {
        runtime.requireSharedDomainControlAuthority(
                "take shared-domain CONTROL");
        if (closing || controlIssued) {
            throw new IllegalStateException(
                    "shared-domain CONTROL unavailable");
        }
        controlIssued = true;
        return new Control(this, domainId);
    }

    public synchronized Grant grant(
            Control control,
            ActorRuntime.ActorRef<?> actor,
            boolean allowRead,
            boolean allowWrite) {
        runtime.requireSharedDomainControlAuthority(
                "grant shared-domain membership");
        validateControlLocked(control);
        if (closing || (!allowRead && !allowWrite)) {
            throw new IllegalStateException("invalid shared-domain grant");
        }
        ActorRuntime.SharedDomainActorIdentity identity =
                runtime.resolveSharedDomainMember(actor);
        for (Member member : members) {
            if (member.active
                    && member.actorId.equals(identity.actorId())
                    && member.actorGeneration == identity.generation()) {
                throw new IllegalStateException(
                        "actor incarnation already has shared-domain membership");
            }
        }
        for (int i = 0; i < members.length; i++) {
            Member member = members[i];
            if (!member.active && member.generation != 0) {
                member.active = true;
                member.actorId = identity.actorId();
                member.actorGeneration = identity.generation();
                member.read = allowRead || allowWrite;
                member.write = allowWrite;
                return new Grant(
                        this, domainId, i, member.generation,
                        identity.actorId(), identity.generation(),
                        member.read, member.write);
            }
        }
        throw new IllegalStateException(
                "shared-domain membership quota exhausted");
    }

    /**
     * CONTROL revoke never parks behind an active critical section. It returns
     * false if any logical reader/writer or queued waiter exists.
     */
    public synchronized boolean tryRevoke(Control control, Grant grant) {
        runtime.requireSharedDomainControlAuthority(
                "revoke shared-domain membership");
        validateControlLocked(control);
        if (closing) return false;
        validateGrantCoordinatesLocked(grant);
        if (!gate.idle()) return false;

        Member member = members[grant.member];
        member.active = false;
        member.actorId = null;
        member.actorGeneration = 0;
        member.read = false;
        member.write = false;
        if (member.generation == Long.MAX_VALUE) {
            member.generation = 0; // permanently exhausted
        } else {
            member.generation++;
        }
        return true;
    }

    public OresFuture<Admission> read(Grant grant) {
        return acquire(grant, false);
    }

    public OresFuture<Admission> write(Grant grant) {
        return acquire(grant, true);
    }

    private OresFuture<Admission> acquire(Grant grant, boolean write) {
        ActorRuntime.SharedDomainTurnIdentity turn =
                runtime.requireCurrentSharedDomainTurn();
        OresFuture<ProxyRwLock.Lease> pending;
        synchronized (this) {
            validateGrantLocked(grant, turn.actor(), write);
            if (closing) {
                return OresFuture.failed(
                        new IllegalStateException("shared-domain arena retired"));
            }
            pending = gate.acquireAsync(write);
        }

        OresFuture<Admission> result =
                new OresFuture<>(() -> pending.cancel(false));
        pending.whenCompleteRuntime((lease, failure) -> {
            if (failure != null) {
                Throwable unwrapped = OresFuture.unwrap(failure);
                if (unwrapped instanceof CancellationException) {
                    result.cancel(false);
                } else {
                    result.failFromRuntime(unwrapped);
                }
                return;
            }
            Admission admission = new Admission(grant, lease, turn.actor(), write);
            boolean permitted;
            synchronized (SharedDomainArena.this) {
                permitted = !closing && grantStillLiveLocked(grant, turn.actor(), write);
            }
            if (!permitted) {
                admission.close();
                result.failFromRuntime(
                        new IllegalStateException(
                                "shared-domain authority retired before resume"));
                return;
            }
            if (!result.completeFromRuntime(admission)) {
                admission.close();
            }
        });
        return result;
    }

    /**
     * Bind a granted logical admission to the actor turn that the scheduler
     * actually resumed. A queued request from an earlier turn cannot carry byte
     * authority across that suspension by itself.
     */
    public Access enter(Grant grant, Admission admission) {
        Objects.requireNonNull(admission, "admission");
        // Fail before calling claim()/close(): replaying an admission from
        // another domain or membership must not consume its valid source
        // lease or grant byte authority in this arena.
        if (admission.getOuterArena() != this || admission.originGrant != grant) {
            throw new SecurityException("foreign shared-domain admission or mismatched grant");
        }
        ActorRuntime.SharedDomainTurnIdentity turn =
                runtime.requireCurrentSharedDomainTurn();
        synchronized (this) {
            validateGrantLocked(grant, turn.actor(), admission.write);
            if (closing || !sameActor(turn.actor(), admission.owner)) {
                admission.close();
                throw new SecurityException(
                        "shared-domain admission resumed by wrong/retired actor");
            }
            if (!admission.claim()) {
                throw new IllegalStateException(
                        "shared-domain admission already entered or released");
            }
            return new Access(grant, admission, turn.turnToken());
        }
    }

    public LifetimePin pin(Grant grant) {
        ActorRuntime.SharedDomainTurnIdentity turn =
                runtime.requireCurrentSharedDomainTurn();
        synchronized (this) {
            validateGrantLocked(grant, turn.actor(), false);
            if (closing) {
                throw new IllegalStateException("shared-domain arena retired");
            }
            return new LifetimePin(region.pinLifetime());
        }
    }

    /**
     * Logical retirement is monotonic. Pending admissions fail immediately;
     * physical FFM retirement begins only after active logical accesses drain.
     */
    public boolean tryRetire(Control control) {
        runtime.requireSharedDomainControlAuthority(
                "retire shared-domain arena");
        synchronized (this) {
            validateControlLocked(control);
            if (!closing) closing = true;
        }
        requestPhysicalRetirement(
                new IllegalStateException("shared-domain arena retired"));
        return region.physicallyClosed();
    }

    void retireFromRuntime() {
        synchronized (this) { closing = true; }
        // An earlier attempt may have sealed the domain but failed before
        // physical FFM reclamation. Repeated runtime close must be retryable.
        requestPhysicalRetirement(
                new IllegalStateException("shared-domain runtime retired"));
    }

    public synchronized boolean closed() { return closing; }
    public boolean physicallyClosed() { return region.physicallyClosed(); }
    public int queuedWaiters() { return gate.queuedWaiters(); }

    private boolean physicalRetirementArmed;

    private void requestPhysicalRetirement(Throwable failure) {
        boolean install;
        synchronized (this) {
            install = !physicalRetirementArmed;
            if (install) physicalRetirementArmed = true;
        }
        if (!install) return;
        try {
            gate.failWaiters(failure);
            gate.whenIdle(this::finishPhysicalRetirement);
        } catch (RuntimeException | Error error) {
            synchronized (this) { physicalRetirementArmed = false; }
            throw error;
        }
    }

    private void finishPhysicalRetirement() {
        try {
            region.retireFromActor();
        } finally {
            // One failure or deferred physical pin-release must not strand
            // retirement permanently. A later trusted retry may re-arm.
            synchronized (this) { physicalRetirementArmed = false; }
        }
    }

    private ExplicitNativeI64Region.Slot requireHandle(Handle handle) {
        if (handle == null || handle.arena != this || handle.domainId != domainId) {
            throw new IllegalStateException(
                    "foreign shared-domain handle");
        }
        return handle.slot;
    }

    private void validateControlLocked(Control control) {
        if (!controlIssued || control == null
                || control.arena != this || control.domainId != domainId) {
            throw new SecurityException(
                    "foreign or stale shared-domain CONTROL");
        }
    }

    private void validateGrantCoordinatesLocked(Grant grant) {
        if (grant == null || grant.arena != this
                || grant.domainId != domainId
                || grant.member < 0 || grant.member >= members.length) {
            throw new SecurityException(
                    "foreign shared-domain grant");
        }
        Member member = members[grant.member];
        if (!member.active || member.generation != grant.memberGeneration
                || member.actorId == null
                || !member.actorId.equals(grant.actorId)
                || member.actorGeneration != grant.actorGeneration) {
            throw new SecurityException(
                    "stale shared-domain grant");
        }
    }

    private void validateGrantLocked(
            Grant grant,
            ActorRuntime.SharedDomainActorIdentity actor,
            boolean writeRequired) {
        validateGrantCoordinatesLocked(grant);
        Member member = members[grant.member];
        if (!sameActor(actor, new ActorRuntime.SharedDomainActorIdentity(
                    member.actorId, member.actorGeneration))
                || !member.read || (writeRequired && !member.write)
                || !grant.read || (writeRequired && !grant.write)) {
            throw new SecurityException(
                    "shared-domain grant belongs to another actor or lacks rights");
        }
    }

    private boolean grantStillLiveLocked(
            Grant grant,
            ActorRuntime.SharedDomainActorIdentity actor,
            boolean writeRequired) {
        try {
            validateGrantLocked(grant, actor, writeRequired);
            return true;
        } catch (RuntimeException denied) {
            return false;
        }
    }

    private static boolean sameActor(
            ActorRuntime.SharedDomainActorIdentity left,
            ActorRuntime.SharedDomainActorIdentity right) {
        return left.actorId().equals(right.actorId())
                && left.generation() == right.generation();
    }

    private static long claimDomainId() {
        long candidate = NEXT_DOMAIN_ID.get();
        while (candidate > 0 && candidate < Long.MAX_VALUE) {
            if (NEXT_DOMAIN_ID.compareAndSet(candidate, candidate + 1)) {
                return candidate;
            }
            candidate = NEXT_DOMAIN_ID.get();
        }
        throw new IllegalStateException(
                "shared-domain identity sequence exhausted");
    }
}
