package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.SandboxPolicy;
import org.graalvm.polyglot.Source;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
    private static final int MAX_SOURCE_CHARACTERS = 1_048_576;
    private static final Object GLOBAL_LOCK = new Object();
    private static SharedUntrustedGraalIsolate processDomain;

    private final long aggregateHeapLimit;
    // submitSource() is synchronized so this monotonically increasing ID is
    // assigned before work is queued and cannot be shared by two sources.
    private long nextCodeUnitId;
    private final AtomicReference<Thread> submitCarrier = new AtomicReference<>();
    private final java.util.Set<CompletableFuture<Void>> pending =
            ConcurrentHashMap.newKeySet();
    private final Engine engine;
    private final Context guest;
    private final ThreadPoolExecutor submissions;
    private final AtomicBoolean closed = new AtomicBoolean();

    private SharedUntrustedGraalIsolate(long isolateHeapBytes) {
        if (isolateHeapBytes < MIN_HEAP)
            throw new IllegalArgumentException("shared untrusted isolate heap must be at least 32 MiB");
        this.aggregateHeapLimit = isolateHeapBytes;
        Engine created = null;
        Context opened = null;
        try {
            // Engine is created exactly once for the entire untrusted actor
            // domain. Do not configure spawnIsolate or engine memory limits
            // on a per-actor Context.Builder.
            Engine.Builder engineBuilder = Engine.newBuilder(OresLanguage.ID)
                    .sandbox(SandboxPolicy.UNTRUSTED);
            // Explicit library paths are an embedder-only bootstrap/test
            // override. Do not resolve the location from guest-controlled
            // source, environment variables or actor message payloads.
            String isolateLibrary = System.getProperty("ores.untrusted.isolate.library");
            if (isolateLibrary != null && !isolateLibrary.isBlank()) {
                java.nio.file.Path library = java.nio.file.Path.of(isolateLibrary)
                        .toAbsolutePath().normalize();
                if (!java.nio.file.Files.isRegularFile(library)) {
                    throw new IllegalStateException(
                            "configured Oreslang isolate library is not a regular file: " + library);
                }
                engineBuilder.allowExperimentalOptions(true)
                        .option("engine.IsolateLibrary", library.toString());
            }
            created = engineBuilder
                    // The sandbox forbids ambient host stdio even before
                    // the first Context is built. Per-Context output streams
                    // are configured separately by IsolatePolicy.
                    .in(java.io.InputStream.nullInputStream())
                    .out(java.io.OutputStream.nullOutputStream())
                    .err(java.io.OutputStream.nullOutputStream())
                    .spawnIsolate(true)
                    .option("engine.MaxIsolateMemory", isolateHeapBytes + "B")
                    .build();
            // A domain root may create bounded actors, but individual
            // untrusted actors still have zero ambient capabilities.
            IsolatePolicy baseline = IsolatePolicy.sharedUntrustedDomain(isolateHeapBytes);
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
                    submitCarrier.set(t);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    public static SharedUntrustedGraalIsolate open() {
        return open(DEFAULT_HEAP);
    }

    /**
     * Exactly one live untrusted Engine/Context pair per host process.
     * A different aggregate budget is not silently accepted on a later call.
     * The host supervisor owns shutdown; this is not a per-actor resource.
     */
    public static SharedUntrustedGraalIsolate open(long aggregateIsolateHeapBytes) {
        if (aggregateIsolateHeapBytes < MIN_HEAP)
            throw new IllegalArgumentException("shared untrusted isolate heap must be at least 32 MiB");
        synchronized (GLOBAL_LOCK) {
            if (processDomain != null) {
                if (processDomain.aggregateHeapLimit != aggregateIsolateHeapBytes)
                    throw new IllegalStateException("shared untrusted isolate already exists with a different heap cap");
                return processDomain;
            }
            SharedUntrustedGraalIsolate created =
                    new SharedUntrustedGraalIsolate(aggregateIsolateHeapBytes);
            processDomain = created;
            return created;
        }
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
        if (source.length() > MAX_SOURCE_CHARACTERS)
            throw new IllegalArgumentException("untrusted source exceeds entry payload limit");
        if (closed.get()) throw new IllegalStateException("untrusted isolate is closed");
        // OresLanguage.parse() uses Source.name as its code-image identity.
        // Context.eval(languageId, text) names EVERY source "Unnamed"; that
        // accidentally aliases distinct code images in a persistent Context.
        // Never use guest-supplied names or paths as trusted registry keys.
        long sourceId = ++nextCodeUnitId;
        if (sourceId <= 0)
            throw new IllegalStateException("untrusted code-unit identity space exhausted");
        Source code = Source.newBuilder(
                OresLanguage.ID, source, "untrusted-entry-" + sourceId + ".ores")
                .buildLiteral();
        CompletableFuture<Void> result = new CompletableFuture<>();
        pending.add(result);
        try {
            submissions.execute(() -> {
                try {
                    if (result.isDone()) return;
                    guest.eval(code);
                    result.complete(null);
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                    if (failure instanceof VirtualMachineError fatal) throw fatal;
                } finally {
                    pending.remove(result);
                }
            });
        } catch (RejectedExecutionException rejected) {
            pending.remove(result);
            result.completeExceptionally(rejected);
        }
        return result;
    }

    /** Host-only instrumentation; never grant this as an Oreslang capability. */
    boolean usesOneEngineForWholeDomain() {
        return guest.getEngine() == engine;
    }

    @Override
    public void close() {
        if (Thread.currentThread() == submitCarrier.get())
            throw new IllegalStateException("cannot close shared untrusted isolate from its entry carrier");
        synchronized (GLOBAL_LOCK) {
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
                    // Cancel wedged guest execution; do not let untrusted
                    // code retain the isolate indefinitely after shutdown.
                    guest.close(true);
                    submissions.shutdownNow();
                } else {
                    guest.close();
                }
            } finally {
                try {
                    engine.close(true);
                } finally {
                    CancellationException canceled = new CancellationException(
                            "shared untrusted isolate terminated");
                    for (CompletableFuture<Void> future : pending)
                        future.completeExceptionally(canceled);
                    pending.clear();
                    if (processDomain == this) processDomain = null;
                }
            }
        }
    }
}
