package dev.oreslang.runtime;

import com.oracle.truffle.api.TruffleContext;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleLanguage.ContextReference;
import com.oracle.truffle.api.nodes.Node;
import dev.oreslang.OresLanguage;
import dev.oreslang.ast.Ast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public final class OresContext implements AutoCloseable {
    private static final ContextReference<OresContext> REFERENCE = ContextReference.create(OresLanguage.class);

    private final OresLanguage language;
    private final TruffleLanguage.Env env;
    private final BufferedReader input;
    private final PrintWriter output;
    private final OresVM vm;
    // Host pull requests are staged independently of guest execution. Only a
    // real VM root turn may dispatch them; the current virtual-thread generator
    // producer is not yet allowed through this admission boundary.
    private final HostGeneratorTurnPump hostGeneratorTurns;
    private final AtomicBoolean hostGeneratorSessionOpened = new AtomicBoolean();
    // A scheduler-current ThreadLocal by itself cannot prove a physical
    // Truffle guest entry. Scope this marker inside executeGuestTurn after
    // TruffleContext.enter succeeds and always clear it on leave.
    private final ThreadLocal<Boolean> rootGuestEntryScope = new ThreadLocal<>();
    // A Truffle access check may be performed by a DIFFERENT caller than
    // the requested already-entering runtime CONTROL/actor carrier. Only
    // physical entry wrappers publish these short-lived thread permits.
    private final ConcurrentHashMap<Thread, AtomicInteger> physicalGuestEntryPermits =
            new ConcurrentHashMap<>();
    private final ArrayBlockingQueue<Runnable> isolatedRootTurns;
    private final ActorRuntime actors;
    private final AsyncRuntime asyncRuntime;
    private final RuntimeGarbageCollector garbageCollector;
    private final AsyncNativeIo nativeIo = new AsyncNativeIo();
    public AsyncNativeIo nativeIo() { return nativeIo; }
    private final NativeHttp http = new NativeHttp(this);
    public NativeHttp http() { return http; }

    private final UUID contextId = UUID.randomUUID();
    private final AtomicLong schedulerSafepoints = new AtomicLong();
    private final AtomicLong sourceLoopCooperates = new AtomicLong();
    private final IsolatePolicy isolatePolicy;
    private final RuntimePermissions runtimePermissions;
    private final PermissionCheckMode permissionCheckMode;
    private final ExecutionProfile executionProfile;
    private final ReentrantLock adversarialActorTurnLock = new ReentrantLock(true);
    // Separate from the context monitor used by code/image registration: an
    // unwinding guest continuation may still need that monitor while close()
    // waits for its carrier to finish.
    private final Object closeLock = new Object();
    private Thread closeOwner;
    private boolean resourcesRetired;
    private final Map<String, Object> linkedCodeUnits = new HashMap<>();
    // Exactly one immutable code image per linked source unit for all actor kinds.
    // Per-actor mutable heaps/closures are never published into this store.
    private final SharedCodeImageStore sharedCodeImages = new SharedCodeImageStore();
    private final Map<String, Map<String, String>> linkedImportResolutions = new HashMap<>();

    public Object asGuestHostValue(Object value) {
        requireCapability(IsolatePolicy.Capability.JAVA_INTEROP, "Java host argument conversion");
        return env.asGuestValue(value);
    }

    /** Recover only supported runtime handles from an allowlisted host call. */
    public Object unwrapHostRuntimeValue(Object value) {
        requireCapability(IsolatePolicy.Capability.JAVA_INTEROP, "Java runtime handle conversion");
        if (env.isHostObject(value)) {
            Object host = env.asHostObject(value);
            if (host instanceof ChannelRuntime.Channel<?>
                    || host instanceof OresFuture<?>
                    || host instanceof CompletionStage<?>) {
                return host;
            }
        }
        return value;
    }

    public OresContext(OresLanguage language, TruffleLanguage.Env env) {
        long contextPerf = CorePerf.start();
        this.language = language;
        this.env = env;
        this.input = new BufferedReader(new InputStreamReader(env.in()));
        this.output = new PrintWriter(env.out(), true);
        this.isolatePolicy = IsolatePolicy.fromApplicationArguments(env.getApplicationArguments());
        this.runtimePermissions = RuntimePermissions.fromApplicationArguments(
                env.getApplicationArguments(), isolatePolicy);
        this.permissionCheckMode = PermissionCheckMode.fromApplicationArguments(
                env.getApplicationArguments());
        this.executionProfile = IsolatePolicy.executionProfileFromApplicationArguments(env.getApplicationArguments());
        // UNTRUSTED contexts admit one guest thread and the isolate's JNI
        // scope belongs to the calling thread. Root task turns retain that
        // admission while runtime CONTROL carriers remain prestarted.
        this.isolatedRootTurns = isolatePolicy.adversarial()
                ? new ArrayBlockingQueue<>(65_536) : null;
        long controlPerf = CorePerf.start();
        OresVM vm = OresVM.create(this::executeRootTurn,
                isolatedRootTurns == null ? null : turn -> {
                    if (!isolatedRootTurns.offer(turn)) {
                        throw new RejectedExecutionException("isolated root turn queue is full");
                    }
                });
        CorePerf.end(CorePerf.VM_CONTROL_STARTUP, controlPerf);
        if (!vm.started()) {
            vm.close();
            throw new IllegalStateException(
                    "Oreslang VM startup barrier was not established before context admission");
        }

        ActorRuntime actors = null;
        AsyncRuntime async = null;
        RuntimeGarbageCollector gc = null;
        try {
            actors = new ActorRuntime(
                    isolatePolicy,
                    ActorRuntime.DispatcherConfig.defaults(),
                    this::executeActorTurn,
                    vm::executeControl);
            async = new AsyncRuntime(this::executeAsyncTurn);
            gc = new RuntimeGarbageCollector();
            actors.setActorExitHook(gc::retireActorDomain);

            this.vm = vm;
            this.actors = actors;
            this.asyncRuntime = async;
            this.garbageCollector = gc;
            var generatorPumpRegistration =
                    new java.util.concurrent.atomic.AtomicReference<HostGeneratorTurnPump>();
            this.hostGeneratorTurns = new HostGeneratorTurnPump(
                    contextId, () -> generatorRootTurnAuthorized(
                            vm.isRootSchedulerCurrent(),
                            Boolean.TRUE.equals(rootGuestEntryScope.get())),
                    () -> {
                        HostGeneratorTurnPump registered = generatorPumpRegistration.get();
                        if (registered == null) {
                            throw new IllegalStateException("generator pump not initialized");
                        }
                        // Host threads only enqueue. The prestarted root
                        // carrier must enter Truffle before certified dispatch.
                        OresFuture<Void> wake = vm.rootScheduler().startSync(() -> {
                            registered.dispatchOne();
                            return null;
                        });
                        wake.whenCompleteRuntime((ignored, failure) -> {
                            if (failure != null) registered.seal();
                        });
                    },
                    256, vm.rootScheduler());
            generatorPumpRegistration.set(this.hostGeneratorTurns);
        } catch (RuntimeException | Error failure) {
            if (async != null) {
                try { async.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            }
            if (actors != null) {
                try { actors.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            }
            try { vm.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        CorePerf.end(CorePerf.CONTEXT_STARTUP, contextPerf);
    }

    public static OresContext get(Node node) {
        return REFERENCE.get(node);
    }

    public OresLanguage language() { return language; }
    public TruffleLanguage.Env env() { return env; }
    public BufferedReader input() { return input; }
    public PrintWriter output() { return output; }
    public OresVM vm() { return vm; }
    public ActorRuntime actors() { return actors; }
    public AsyncRuntime asyncRuntime() { return asyncRuntime; }
    public RuntimeGarbageCollector garbageCollector() { return garbageCollector; }
    public UUID contextId() { return contextId; }
    public IsolatePolicy isolatePolicy() { return isolatePolicy; }
    public RuntimePermissions runtimePermissions() { return runtimePermissions; }
    public PermissionCheckMode permissionCheckMode() { return permissionCheckMode; }
    public ExecutionProfile executionProfile() { return executionProfile; }

    public Object lookupHostSymbol(String className) {
        requireCapability(IsolatePolicy.Capability.JAVA_INTEROP, "Java host import " + className);
        if (!env.isHostLookupAllowed()) {
            throw new SecurityException("Java host class lookup is disabled by the embedding Context");
        }
        try {
            return env.lookupHostSymbol(className);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(
                    "Java host class is not allowlisted or unavailable: " + className,
                    failure);
        }
    }

    public void requireCapability(IsolatePolicy.Capability capability, String api) {
        IsolatePolicy actorPolicy = ActorRuntime.currentActorPolicy();
        if (actorPolicy != null && ActorRuntime.currentActorRuntime() != actors) {
            throw new SecurityException(
                    "actor capability check crossed ActorRuntime boundary for " + api);
        }
        requireEffectiveCapability(isolatePolicy, capability, api);
    }

    public void requirePermission(
            RuntimePermissions.Permission permission,
            String resource,
            String api) {
        requireCapability(permission.capability(), api);
        runtimePermissions.require(permission, resource, api);
    }

    static void requireEffectiveCapability(
            IsolatePolicy contextPolicy,
            IsolatePolicy.Capability capability,
            String api) {
        // Actor turns execute inside the parent Truffle context, but they may
        // have a strictly narrower capability set than that context. Always
        // enforce the actor-local policy first so helper functions, imported
        // code, and ordinary class methods cannot launder authority from the
        // parent context into a private actor.
        IsolatePolicy actorPolicy = ActorRuntime.currentActorPolicy();
        if (actorPolicy != null) actorPolicy.require(capability, api);
        contextPolicy.require(capability, api);
    }

    /**
     * Compiler-injected VM checkpoint for cancellation and quota accounting.
     *
     * <p>This is not, by itself, a resumable preemption operation. Code that
     * wants to release its carrier must be continuation-lowered and return an
     * {@link OresScheduler.Cooperate} step. Keeping these concepts separate
     * prevents an OS-thread yield from being mistaken for actor suspension.</p>
     */
    public void schedulerSafepoint() {
        schedulerSafepoints.incrementAndGet();
        actors.schedulerSafepoint();
    }

    public long schedulerSafepoints() { return schedulerSafepoints.get(); }

    /** Count completed VM decisions to hand a source loop off cooperatively. */
    public void recordSourceLoopCooperate() { sourceLoopCooperates.incrementAndGet(); }

    public long sourceLoopCooperates() { return sourceLoopCooperates.get(); }

    /**
     * Host-managed cross-file link registry. Guest imports may only observe
     * units that the host has explicitly loaded into this context; import
     * syntax never grants filesystem access.
     */
    public SharedCodeImageStore.CodeImage registerSharedCodeImage(
            String codeUnitId, Ast.Program immutableProgram) {
        return sharedCodeImages.publish(codeUnitId, immutableProgram);
    }

    public SharedCodeImageStore.CodeImage sharedCodeImage(String codeUnitId) {
        return sharedCodeImages.get(codeUnitId);
    }

    public synchronized void registerLinkedCodeUnit(String codeUnitId, Object unit) {
        if (codeUnitId == null || codeUnitId.isBlank()) {
            throw new IllegalArgumentException("linked code unit id cannot be blank");
        }
        Object previous = linkedCodeUnits.putIfAbsent(codeUnitId, unit);
        if (previous != null && previous != unit) {
            throw new IllegalStateException("code unit already linked in this context: " + codeUnitId);
        }
    }

    public synchronized Object linkedCodeUnit(String codeUnitId) {
        return linkedCodeUnits.get(codeUnitId);
    }

    public synchronized boolean hasLinkedCodeUnit(String codeUnitId) {
        return linkedCodeUnits.containsKey(codeUnitId);
    }

    /**
     * Registers the host compiler's exact filesystem resolution for one import.
     * Guest code can only consume these aliases; it does not gain filesystem
     * access by knowing the resolved target.
     */
    public synchronized void registerLinkedImportResolution(
            String importerCodeUnitId,
            String importPath,
            String targetCodeUnitId) {
        if (importerCodeUnitId == null || importerCodeUnitId.isBlank()) {
            throw new IllegalArgumentException("importer code unit id cannot be blank");
        }
        if (importPath == null || importPath.isBlank()) {
            throw new IllegalArgumentException("linked import path cannot be blank");
        }
        if (targetCodeUnitId == null || targetCodeUnitId.isBlank()) {
            throw new IllegalArgumentException("target code unit id cannot be blank");
        }

        Map<String, String> imports = linkedImportResolutions.computeIfAbsent(
                normalizeCodeUnitId(importerCodeUnitId),
                ignored -> new HashMap<>());
        String target = normalizeCodeUnitId(targetCodeUnitId);
        String previous = imports.putIfAbsent(importPath, target);
        if (previous != null && !previous.equals(target)) {
            throw new IllegalStateException(
                    "conflicting import resolution for '" + importPath + "' in '" + importerCodeUnitId + "'");
        }
    }

    public synchronized String resolvedLinkedImport(String importerCodeUnitId, String importPath) {
        Map<String, String> imports = linkedImportResolutions.get(normalizeCodeUnitId(importerCodeUnitId));
        return imports == null ? null : imports.get(importPath);
    }

    private static String normalizeCodeUnitId(String id) {
        return java.nio.file.Path.of(id).normalize().toString().replace('\\', '/');
    }

    /**
     * Run the VM root task only through the prestarted CONTROL scheduler.
     * Completion of an async main Future is awaited through the same resumable
     * scheduler ABI, so the CONTROL carrier itself is never parked waiting for
     * the terminal Future.
     */
    public Object runRootMain(Callable<?> main) {
        if (vm.isRootSchedulerCurrent()) {
            try {
                return main.call();
            } catch (Exception failure) {
                throw new IllegalStateException("root main failed", failure);
            }
        }

        OresFuture<Object> task = vm.rootScheduler().start(new OresScheduler.Task<>() {
            private int pc;

            @Override
            public OresScheduler.Step<Object> resume(OresScheduler.Resume resume)
                    throws Exception {
                if (pc == 0) {
                    if (!resume.initial()) {
                        throw new IllegalStateException(
                                "root main task must begin with an initial resume");
                    }
                    Object result = main.call();
                    if (result instanceof OresFuture<?> future) {
                        pc = 1;
                        return OresScheduler.await(future);
                    }
                    if (result instanceof CompletionStage<?> stage) {
                        pc = 1;
                        return OresScheduler.await(OresFuture.from(stage));
                    }
                    return OresScheduler.done(result);
                }

                if (pc == 1) {
                    if (resume.failure() != null) {
                        Throwable failure = OresFuture.unwrap(resume.failure());
                        if (failure instanceof ProcessExitSignal exit) {
                            return OresScheduler.done(exit.exitStatus());
                        }
                        if (failure instanceof Exception checked) throw checked;
                        if (failure instanceof Error error) throw error;
                        throw new RuntimeException(failure);
                    }
                    @SuppressWarnings("unchecked")
                    Object value = resume.value();
                    pc = 2;
                    return OresScheduler.done(value);
                }

                throw new IllegalStateException(
                        "root main scheduler task resumed after terminal state");
            }
        });
        if (isolatedRootTurns != null) {
            while (!task.isDone()) {
                Runnable turn = isolatedRootTurns.poll();
                if (turn == null) {
                    // Waiting is outside guest execution. Re-entering on this
                    // same caller preserves both sandbox admission and JNI scope.
                    turn = env.getContext().leaveAndEnter(null, () -> {
                        try {
                            return isolatedRootTurns.poll(100, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new java.util.concurrent.CancellationException(
                                    "isolated root execution interrupted");
                        }
                    });
                }
                if (turn != null) turn.run();
            }
        }
        return task.join();
    }

    /**
     * Staging only: a future compiler-lowered generator continuation will
     * dispatch this pump from a root guest turn. Never expose guest execution
     * rights directly through a public host Future.
     */
    HostGeneratorTurnPump hostGeneratorTurns() {
        return hostGeneratorTurns;
    }

    /**
     * Create a single host-owned, typechecked generator session for this
     * context. The capture ticket is verified by OresEvalRootNode before
     * invoking this method; it never crosses the guest argument boundary.
     * Parallel sessions are denied until generator-specific pump isolation
     * and context-wide admission have been formally refined.
     */
    public HostGeneratorSession openHostGeneratorSession(
            Ast.Program program, String function, Map<String, ?> dependencies) {
        if (isolatePolicy.adversarial()) {
            throw new SecurityException(
                    "host generator session not supported under adversarial isolation");
        }
        if (hostGeneratorSessionOpened.get()) {
            throw new RejectedExecutionException(
                    "this context already owns a host generator session");
        }
        HostGeneratorSession session = HostGeneratorSession.fromTypecheckedProgram(
                vm.rootScheduler(), hostGeneratorTurns, program, function, dependencies);
        if (!hostGeneratorSessionOpened.compareAndSet(false, true)) {
            throw new RejectedExecutionException(
                    "concurrent generator session publication rejected");
        }
        return session;
    }

    /** Neither a scheduler binding nor a guest-entry lease alone suffices. */
    static boolean generatorRootTurnAuthorized(
            boolean rootSchedulerBound, boolean rootGuestPhysicallyEntered) {
        return rootSchedulerBound && rootGuestPhysicallyEntered;
    }

    private void executeRootTurn(Runnable turn) {
        executeGuestTurn(() -> {
            // Scoped only after a successful physical guest Context.enter.
            // An actor/async carrier cannot manufacture this root-turn token.
            Boolean prior = rootGuestEntryScope.get();
            rootGuestEntryScope.set(Boolean.TRUE);
            try {
                turn.run();
            } finally {
                if (prior == null) rootGuestEntryScope.remove();
                else rootGuestEntryScope.set(prior);
            }
        }, isolatePolicy.adversarial());
    }

    private void executeActorTurn(Runnable turn) {
        executeGuestTurn(turn, isolatePolicy.adversarial());
    }

    private void executeAsyncTurn(Runnable turn) {
        // The current interpreter executes an async callable as one virtual-
        // thread task. Holding the adversarial actor serialization lock across
        // an await could deadlock a nested async task, so strict/adversarial
        // profiles fail closed until compiler continuation lowering can release
        // the guest turn at each await suspension point.
        if (isolatePolicy.adversarial()) {
            throw new SecurityException(
                    "async callable execution in adversarial contexts requires continuation lowering");
        }
        executeGuestTurn(turn, false);
    }

    public boolean hasRegisteredPhysicalGuestEntry(Thread requested) {
        AtomicInteger registered = physicalGuestEntryPermits.get(requested);
        return registered != null && registered.get() > 0;
    }

    private void acquirePhysicalGuestEntryPermit() {
        if (!OresScheduler.isSchedulerCarrierThread()
                && !ActorRuntime.isActorCarrierThread()
                && !AsyncRuntime.isAsyncCarrierThread()) {
            throw new SecurityException(
                    "physical guest entry requires a runtime-owned carrier");
        }
        physicalGuestEntryPermits.compute(Thread.currentThread(), (thread, old) -> {
            if (old == null) return new AtomicInteger(1);
            old.incrementAndGet();
            return old;
        });
    }

    private void releasePhysicalGuestEntryPermit() {
        Thread current = Thread.currentThread();
        physicalGuestEntryPermits.compute(current, (thread, counter) -> {
            if (counter == null || counter.decrementAndGet() < 0) {
                throw new IllegalStateException("physical guest entry permit underflow");
            }
            return counter.get() == 0 ? null : counter;
        });
    }

    private void executeGuestTurn(Runnable turn, boolean serialize) {
        if (serialize) adversarialActorTurnLock.lock();
        // The permit is published immediately before TruffleContext.enter,
        // never when a Future is merely queued. Failed entry and failed leave
        // both revoke it; physical DONE proof remains a separate barrier.
        acquirePhysicalGuestEntryPermit();
        TruffleContext truffleContext = env.getContext();
        Object previous = null;
        boolean entered = false;
        try {
            previous = truffleContext.enter(null);
            entered = true;
            turn.run();
        } finally {
            try {
                if (entered) truffleContext.leave(null, previous);
            } finally {
                releasePhysicalGuestEntryPermit();
                if (serialize) adversarialActorTurnLock.unlock();
            }
        }
    }


    public Map<String, Object> processDescriptor() {
        Map<String, Object> descriptor = new java.util.HashMap<>(Map.of(
                "context_id", contextId.toString(),
                "runtime", "graalvm-truffle",
                "language", "oreslang",
                "execution_mode", executionProfile.mode().name(),
                "platform", executionProfile.platform().name(),
                "actor_carrier_backend", actors.carrierBackend().name().toLowerCase(java.util.Locale.ROOT),
                "root_scheduler", vm.rootScheduler().name(),
                "scheduler_started", vm.started(),
                "control_carriers_started", vm.controlCarrierCount(),
                "scheduler_safepoints", schedulerSafepoints.get()));
        descriptor.put("source_loop_cooperates", sourceLoopCooperates.get());
        // All actors in this OresContext share its managed guest heap, even
        // when the context itself runs inside a Graal polyglot isolate.
        // Per-actor physical collector isolation is not implemented here.
        descriptor.put("actor_heap_backend", "shared_truffle_context");
        descriptor.put("actor_heap_independently_collectable", false);
        descriptor.put("actor_gc_operation", "host_cleanup_only");
        return Map.copyOf(descriptor);
    }

    /**
     * Stop admitting actor work, then drain all guest and async owners before
     * invalidating any context-owned I/O, code images or collector state.
     *
     * A deadline is not quiescence. On a failed close these resources must
     * remain intact for already-authorized CONTROL cleanup continuations;
     * another host close can retry after those continuations actually finish.
     */
    @Override
    public void close() {
        synchronized (closeLock) {
            if (resourcesRetired) return;
            if (closeOwner == Thread.currentThread()) {
                throw new IllegalStateException(
                        "OresContext close cannot be re-entered by its own cleanup callback");
            }
            closeOwner = Thread.currentThread();
            try {
                hostGeneratorTurns.seal();
                // Actor-owned finalizers may still need the VM CONTROL scheduler,
                // async workers, imported code, network or native I/O. Never
                // retire those dependencies before actor quiescence is proven.
                // A cancelled host pull does not release its physical guest
                // turn lease until its explicit exit barrier has completed.
                // Check BEFORE shutting down actor/CONTROL dependencies; if
                // cleanup still owns this context, retries must leave its
                // resources available rather than retiring actors too early.
                hostGeneratorTurns.requireQuiescent();
                actors.close();
                vm.close();
                // An async carrier may still own an entered Truffle context
                // even after its Future was cancelled. Its executor shutdown
                // is a physical-exit barrier, not merely Future.isDone().
                asyncRuntime.close();

                // Physical guest and host carriers are now retired. Only then
                // revoke the remaining external resources and linked code.
                nativeIo.close();
                http.close();
                synchronized (this) {
                    linkedCodeUnits.clear();
                    linkedImportResolutions.clear();
                    sharedCodeImages.close();
                }
                garbageCollector.close();
                output.flush();
                resourcesRetired = true;
            } finally {
                closeOwner = null;
            }
        }
    }
}
