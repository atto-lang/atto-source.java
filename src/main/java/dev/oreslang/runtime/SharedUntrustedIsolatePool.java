package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.SandboxPolicy;

import java.time.Duration;
import java.util.HashSet;
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
    // Process-local admission guard: a second pool would silently create an
    // additional native-image isolate and violate the two-domain deployment.
    // Do not release the slot until the engine has actually closed.
    private static final AtomicBoolean ISOLATE_SLOT = new AtomicBoolean();
    private final Engine engine;
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

    static boolean isVmFatalRetirementFailure(Throwable failure) {
        return failure instanceof VirtualMachineError
                || failure instanceof ThreadDeath
                || failure instanceof LinkageError;
    }

    static Throwable mergeRetirementFailure(Throwable current, Throwable next) {
        Objects.requireNonNull(next, "next");
        if (current == null || current == next) return current == null ? next : current;
        if (isVmFatalRetirementFailure(next) && !isVmFatalRetirementFailure(current)) {
            next.addSuppressed(current);
            return next;
        }
        current.addSuppressed(next);
        return current;
    }

    static RuntimeException sanitizeGroupRetirementFailure(Throwable failure) {
        // Do not accidentally convert process-fatal JVM/Linkage failure into
        // a retryable ordinary exception while closing an untrusted Context.
        if (isVmFatalRetirementFailure(failure)) {
            if (failure instanceof Error fatal) throw fatal;
        }
        // Keep guest/native error messages and causes out of the trusted
        // caller; the confirmed-close fence must still report failure.
        return new IllegalStateException("untrusted group retirement failed");
    }

    static void rethrowRetirementFailure(Throwable failure) {
        if (failure == null) return;
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        throw new IllegalStateException("unexpected checked untrusted-isolate retirement failure", failure);
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

    /**
     * A supervisor-only lifetime lease. Never expose the raw Context or
     * polyglot Value handles across the actor message transport boundary.
     */
    public final class Group implements AutoCloseable {
        private final Context context;
        private volatile boolean groupClosed;
        // Exactly-once close task: contenders cannot return a false success
        // while another thread still executes Context.close(true).
        private final VerifiedCloseFence closeFence;
        private Group(Context context) {
            this.context = context;
            this.closeFence = new VerifiedCloseFence(() -> {
                try {
                    this.context.close(true);
                    // Only a verified Context.close may release group capacity.
                    synchronized (SharedUntrustedIsolatePool.this) {
                        groups.remove(this);
                    }
                } catch (RuntimeException | Error error) {
                    // Preserve VM-fatal identity, redact ordinary guest detail.
                    throw sanitizeGroupRetirementFailure(error);
                }
            });
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
            groupClosed = true;
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
        Throwable failure = null;
        for (Group group : snapshot) {
            try {
                group.close();
            } catch (RuntimeException | Error error) {
                failure = mergeRetirementFailure(failure, error);
            }
        }
        try {
            engine.close(true);
        } catch (RuntimeException | Error error) {
            // Preserve the VM-fatal identity when Engine teardown itself
            // fails, even if an earlier Context close already reported an
            // ordinary retryable failure.
            failure = mergeRetirementFailure(
                    failure,
                    isVmFatalRetirementFailure(error)
                            ? error
                            : new IllegalStateException(
                                    "untrusted Engine close could not be confirmed"));
        }
        // A successful Engine.close alone is not sufficient: one failed
        // Context close leaves its native/guest lifetime unproven. The
        // process-global slot must remain denied until ALL close fences
        // were confirmed, otherwise a second isolate can be admitted.
        rethrowRetirementFailure(failure);
        ISOLATE_SLOT.set(false);
    }
}
