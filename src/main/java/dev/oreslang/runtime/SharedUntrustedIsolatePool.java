package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.SandboxPolicy;

import java.time.Duration;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Supervisor-owned, ONE-isolate hosting substrate for multiple untrusted
 * actor groups. Each group's Context may contain many actors; all Contexts
 * share one Engine, JIT and physical collector.
 *
 * This owns engine/context lifetimes, NOT ActorRuntime.spawnUntrusted routing.
 * Actor routing across this boundary requires an explicit data-only protocol.
 * No caller should claim a guest actor ran in this isolate merely because
 * this pool was allocated.
 */
public final class SharedUntrustedIsolatePool implements AutoCloseable {
    private static final long MIN_ISOLATE_BYTES = 64L * 1024 * 1024;
    private static final int MAX_CODE_UNITS_PER_CONTEXT = 128;
    // Process-local admission guard: a second pool would silently create an
    // additional native-image isolate and violate the two-domain deployment.
    // Do not release the slot until the engine has actually closed.
    private static final AtomicBoolean ISOLATE_SLOT = new AtomicBoolean();
    private final Engine engine;
    // Separate from the trusted process image store; scoped to this one physical isolate.
    private final UntrustedSourceImageCache codeImages = new UntrustedSourceImageCache();
    private final long isolateHeapBytes;
    private final int maxGroups;
    private final Set<Group> groups = new HashSet<>();
    private boolean closed;

    /**
     * Opens one native-image polyglot isolate. If Oreslang's language-isolate
     * image or the optimizing engine is unavailable, startup must fail rather
     * than run hostile code in the trusted JVM.
     */
    public SharedUntrustedIsolatePool(long isolateHeapBytes, int maxGroups) {
        validateConfiguration(isolateHeapBytes, maxGroups);
        if (!ISOLATE_SLOT.compareAndSet(false, true)) {
            throw new IllegalStateException(
                    "only one shared untrusted Graal isolate pool is permitted per application");
        }
        this.isolateHeapBytes = isolateHeapBytes;
        this.maxGroups = maxGroups;
        try {
            this.engine = Engine.newBuilder(OresLanguage.ID)
                    .sandbox(SandboxPolicy.UNTRUSTED)
                    .spawnIsolate(true)
                    .option("engine.MaxIsolateMemory", isolateHeapBytes + "B")
                    .build();
        } catch (RuntimeException | Error failure) {
            // An unsuccessful engine build must not permanently prevent retry.
            ISOLATE_SLOT.set(false);
            throw failure;
        }
    }

    public static void validateConfiguration(long isolateHeapBytes, int maxGroups) {
        if (isolateHeapBytes < MIN_ISOLATE_BYTES) {
            throw new IllegalArgumentException(
                    "shared untrusted isolate must have at least 64 MiB of memory");
        }
        if (maxGroups < 1 || maxGroups > 1024) {
            throw new IllegalArgumentException("shared untrusted isolate group limit must be 1..1024");
        }
    }

    /** Validate the host/group boundary independently of native-isolate availability. */
    public static void validateGroupPolicy(IsolatePolicy groupPolicy, long isolateHeapBytes) {
        Objects.requireNonNull(groupPolicy, "groupPolicy");
        if (!groupPolicy.adversarial()) {
            throw new SecurityException("untrusted actor group requires adversarial policy");
        }
        if (!groupPolicy.capabilities().isEmpty()) {
            throw new SecurityException(
                    "untrusted actor group must not acquire ambient host capabilities");
        }
        if (groupPolicy.maxHeapBytes() > isolateHeapBytes) {
            throw new IllegalArgumentException("group heap quota exceeds shared isolate cap");
        }
        // Graal's MaxCPUTime is CUMULATIVE per Context, not per actor
        // message/turn. Accidentally using the 250ms per-actor policy here
        // would terminate the entire group under ordinary sustained load.
        if (groupPolicy.maxWallTime().compareTo(Duration.ofMinutes(1)) < 0) {
            throw new IllegalArgumentException(
                    "untrusted group context requires >=1m cumulative CPU budget; "
                    + "use IsolatePolicy.untrustedActorGroup(), not untrustedActor()");
        }
    }

    /** Create a long-lived group using the separate cumulative CPU budget. */
    public Group createGroup() {
        return createGroup(IsolatePolicy.untrustedActorGroup());
    }

    /**
     * Each group may host many untrusted actors, subject to its adversarial
     * policy and actor-level budgets. Creating a Group does NOT create an
     * additional Graal isolate or an independent per-actor collector.
     */
    public synchronized Group createGroup(IsolatePolicy groupPolicy) {
        if (closed) throw new IllegalStateException("untrusted isolate pool is closed");
        validateGroupPolicy(groupPolicy, isolateHeapBytes);
        if (groups.size() >= maxGroups) {
            throw new IllegalStateException("untrusted isolate group capacity exceeded");
        }

        // Guest JIT runs inside the isolated engine regardless of host Native
        // Image's AOT-only compilation mode. iOS/JIT prohibition remains
        // untouched; this is explicitly a server-only execution path.
        ExecutionProfile guest = ExecutionProfile.serverHybrid();
        Context context = groupPolicy
                .sharedUntrustedIsolateContextBuilder(guest, engine)
                .build();
        Group result = new Group(context);
        groups.add(result);
        return result;
    }

    public synchronized int activeGroups() { return groups.size(); }
    public long isolateHeapBytes() { return isolateHeapBytes; }
    int liveCodeImagesForSupervisor() { return codeImages.liveImages(); }

    /**
     * A supervisor-only lifetime lease. Never expose the raw Context or
     * polyglot Value handles across the actor message transport boundary.
     */
    public final class Group implements AutoCloseable {
        private final Context context;
        // Image admission must not use the guest execution monitor (this):
        // forceCancel/close must be able to fence new acquisitions even when
        // malicious guest execution owns the group monitor indefinitely.
        private final Object imageAdmissionLock = new Object();
        // Exactly one code-image lease per logical module in this Context.
        // Reimported code is compared against the immutable Source contents,
        // not retained as an extra per-actor source-string copy.
        private final Map<String, UntrustedSourceImageCache.Lease> codeUnits = new HashMap<>();
        private volatile boolean groupClosed;
        // Exactly-once close task: contenders cannot return a false success
        // while another thread still executes Context.close(true).
        private final VerifiedCloseFence closeFence;
        private Group(Context context) {
            this.context = context;
            this.closeFence = new VerifiedCloseFence(() -> {
                try {
                    this.context.close(true);
                    // Keep code pinned through confirmed Context.close, not merely
                    // until the supervisor has issued a cancellation request.
                    synchronized (imageAdmissionLock) {
                        for (UntrustedSourceImageCache.Lease lease : codeUnits.values()) lease.close();
                        codeUnits.clear();
                    }
                    // Only a verified Context.close may release group capacity.
                    synchronized (SharedUntrustedIsolatePool.this) {
                        groups.remove(this);
                    }
                } catch (RuntimeException | Error error) {
                    // No guest exception or native cause chain reaches the host.
                    throw new IllegalStateException("untrusted group retirement failed");
                }
            });
        }

        /**
         * The code image is shared only by Contexts in this ONE untrusted Engine.
         * Leases belong to the Context; no caller can release source while it
         * may still be executing, even during failed retirement or hot reload.
         * This does not expose an AST, JVM object, or function pointer to guests.
         */
        public Source sourceImageForSupervisor(String codeUnitId, String sourceText) {
            Objects.requireNonNull(codeUnitId, "codeUnitId");
            Objects.requireNonNull(sourceText, "sourceText");
            synchronized (imageAdmissionLock) {
                if (groupClosed) throw new IllegalStateException("untrusted group is closed");
                UntrustedSourceImageCache.Lease existing = codeUnits.get(codeUnitId);
                if (existing != null) {
                    if (!existing.source().getCharacters().toString().equals(sourceText)) {
                        throw new IllegalStateException(
                                "a code unit cannot be replaced within one guest Context; "
                                + "open a new hot-reload generation");
                    }
                    return existing.source();
                }
                if (codeUnits.size() >= MAX_CODE_UNITS_PER_CONTEXT) {
                    throw new IllegalStateException("untrusted Context code-unit quota exceeded");
                }
                UntrustedSourceImageCache.Lease lease = codeImages.acquire(codeUnitId, sourceText);
                // Every new image admission is atomic with the close fence.
                // Exceptions cannot leave an untracked lease after shutdown.
                try {
                    Source image = lease.source();
                    codeUnits.put(codeUnitId, lease);
                    return image;
                } catch (RuntimeException | Error failure) {
                    lease.close();
                    throw failure;
                }
            }
        }

        int distinctCodeUnitsForSupervisor() {
            synchronized (imageAdmissionLock) { return codeUnits.size(); }
        }

        /** Host adapter entrypoint; actual actor routing must be supplied separately. */
        public synchronized Context contextForSupervisor() {
            if (groupClosed) throw new IllegalStateException("untrusted group is closed");
            return context;
        }

        @Override
        public void close() {
            // Fence admission immediately, without acquiring the execution
            // monitor held by a possibly stuck guest. FutureTask serializes
            // closing; every concurrent caller awaits the same completion.
            synchronized (imageAdmissionLock) {
                groupClosed = true;
            }
            closeFence.awaitConfirmedClose("untrusted Context");
        }
    }

    private final VerifiedCloseFence engineCloseFence =
            new VerifiedCloseFence(this::retirePhysicalIsolate);

    @Override
    public void close() {
        // An independent contender cannot report successful pool retirement
        // while the first caller is still closing guest Contexts/Engine.
        synchronized (this) { closed = true; }
        engineCloseFence.awaitConfirmedClose("shared untrusted Engine");
    }

    private void retirePhysicalIsolate() {
        Set<Group> snapshot;
        synchronized (this) { snapshot = Set.copyOf(groups); }
        RuntimeException failure = null;
        for (Group group : snapshot) {
            try {
                group.close();
            } catch (RuntimeException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        try {
            engine.close(true);
        } catch (RuntimeException | Error error) {
            if (failure == null) {
                failure = new IllegalStateException(
                        "untrusted Engine close could not be confirmed");
            }
        }
        // A successful Engine.close alone is not sufficient: one failed
        // Context close leaves its native/guest lifetime unproven. The
        // process-global slot must remain denied until ALL close fences
        // were confirmed, otherwise a second isolate can be admitted.
        if (failure != null) throw failure;
        ISOLATE_SLOT.set(false);
    }
}
