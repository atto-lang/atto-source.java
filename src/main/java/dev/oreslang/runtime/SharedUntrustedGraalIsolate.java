package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.SandboxPolicy;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ONE physical Graal polyglot isolate and ONE guest Context for the untrusted
 * Oreslang actor domain. Actors spawned inside that guest Context share its VM
 * isolate while retaining ActorRuntime's per-actor ownership and arena budgets.
 *
 * <p>There is deliberately no "create an isolate per actor" operation. The
 * Engine owns the physical isolate; the Context owns the OresVM/ActorRuntime
 * and therefore all untrusted actors created by guest Oreslang source.</p>
 *
 * <p>Submitting source is asynchronous and bounded. Neither guest Value nor
 * Context escapes to untrusted callers. This is a host-only bootstrap API, not
 * a general message bridge between host ActorRef and guest ActorRef. Legacy
 * host ActorRuntime.spawnUntrusted() still executes on the host JVM and must
 * NOT be used as the production security boundary.</p>
 *
 * <p>Requires a real Oreslang polyglot language-isolate Native Image. Failure
 * to load or start it aborts construction; no managed-JVM fallback is allowed.</p>
 */
public final class SharedUntrustedGraalIsolate implements AutoCloseable {
    private static final long MIN_HEAP = 32L * 1024 * 1024;
    private static final long DEFAULT_HEAP = 256L * 1024 * 1024;
    private static final int SUBMISSION_CAPACITY = 128;

    private final Engine engine;
    private final Context guest;
    private final ThreadPoolExecutor submissions;
    private final AtomicBoolean closed = new AtomicBoolean();

    private SharedUntrustedGraalIsolate(long isolateHeapBytes) {
        if (isolateHeapBytes < MIN_HEAP)
            throw new IllegalArgumentException("shared untrusted isolate heap must be at least 32 MiB");
        Engine created = null;
        Context opened = null;
        try {
            // Engine is created exactly once for the entire untrusted actor
            // domain. Do not configure spawnIsolate or engine memory limits
            // on a per-actor Context.Builder.
            created = Engine.newBuilder(OresLanguage.ID)
                    .sandbox(SandboxPolicy.UNTRUSTED)
                    .spawnIsolate(true)
                    .option("engine.MaxIsolateMemory", isolateHeapBytes + "B")
                    .build();
            IsolatePolicy baseline = IsolatePolicy.untrustedActor();
            opened = baseline.restrictedContextBuilder(
                    ExecutionProfile.parse(NativeBuildMode.defaultExecutionMode(), "server"),
                    java.util.Set.of(),
                    RuntimePermissions.fromCapabilities(baseline.capabilities()),
                    PermissionCheckMode.COMPILE,
                    created)
                    .build();
            if (opened.getEngine() != created) {
                throw new IllegalStateException("untrusted guest context has a different Engine");
            }
        } catch (RuntimeException | Error failure) {
            if (opened != null) try { opened.close(true); } catch (RuntimeException ignored) { }
            if (created != null) try { created.close(true); } catch (RuntimeException ignored) { }
            throw new IllegalStateException(
                    "single shared untrusted Graal isolate could not start; "
                    + "an Oreslang language-isolate Native Image and GraalVM sandbox are required",
                    failure);
        }
        this.engine = created;
        this.guest = opened;
        this.submissions = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(SUBMISSION_CAPACITY),
                runnable -> {
                    Thread t = new Thread(runnable, "ores-shared-untrusted-isolate-entry");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    public static SharedUntrustedGraalIsolate open() {
        return new SharedUntrustedGraalIsolate(DEFAULT_HEAP);
    }

    public static SharedUntrustedGraalIsolate open(long aggregateIsolateHeapBytes) {
        return new SharedUntrustedGraalIsolate(aggregateIsolateHeapBytes);
    }

    /**
     * Evaluate an Oreslang source unit inside the existing untrusted Context.
     * Guest code may spawn many actors; none creates an additional isolate.
     * Host code receives a completion-only Future, never a guest object.
     *
     * <p>The untrusted actors' mailbox transport across this host boundary
     * still requires a separate serialization/capability-checked bridge.</p>
     */
    public synchronized CompletableFuture<Void> submitSource(String source) {
        Objects.requireNonNull(source, "source");
        if (closed.get()) throw new IllegalStateException("untrusted isolate is closed");
        CompletableFuture<Void> result = new CompletableFuture<>();
        submissions.execute(() -> {
            if (result.isCancelled()) return;
            try {
                guest.eval(OresLanguage.ID, source);
                result.complete(null);
            } catch (Throwable failure) {
                if (failure instanceof VirtualMachineError fatal) throw fatal;
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    /** Host-only instrumentation; never grant this as an Oreslang capability. */
    boolean usesOneEngineForWholeDomain() {
        return guest.getEngine() == engine;
    }

    @Override
    public void close() {
        synchronized (this) {
            if (!closed.compareAndSet(false, true)) return;
            submissions.shutdown();
        }
        boolean terminated = false;
        try {
            terminated = submissions.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        try {
            if (!terminated) {
                // Cancel a wedged guest evaluation before stopping its carrier.
                guest.close(true);
                submissions.shutdownNow();
            } else {
                guest.close();
            }
        } finally {
            engine.close(true);
        }
    }
}
