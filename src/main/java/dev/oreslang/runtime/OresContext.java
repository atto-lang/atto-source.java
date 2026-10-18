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
import java.util.concurrent.locks.ReentrantLock;

public final class OresContext implements AutoCloseable {
    private static final ContextReference<OresContext> REFERENCE = ContextReference.create(OresLanguage.class);

    private final OresLanguage language;
    private final TruffleLanguage.Env env;
    private final BufferedReader input;
    private final PrintWriter output;
    private final OresVM vm;
    private final ArrayBlockingQueue<Runnable> isolatedRootTurns;
    private final ActorRuntime actors;
    private final AsyncRuntime asyncRuntime;
    private final RuntimeGarbageCollector garbageCollector;
    private final AsyncNativeIo nativeIo = new AsyncNativeIo();
    private final java.util.Set<NativeNioFutureReactor> nioListeners = new java.util.HashSet<>();
    // Source-level std/net listeners share this context's VM selector group.
    private final java.util.Set<VmNioTcpListener> vmNioListeners = new java.util.HashSet<>();
    private final Object nioLifecycle = new Object();
    private boolean nioContextClosed;
    private int nioOpening;
    // Bounded context-owned reactors while full VM selector sharing is staged.
    private static final int MAX_NIO_LISTENERS_PER_CONTEXT = 4;

    /** Verify both current NETWORK capability and the listener's context owner. */
    public void requireNioOwner(NativeNioFutureReactor listener, String operation) {
        synchronized (nioLifecycle) {
            if (nioContextClosed || !nioListeners.contains(listener))
                throw new SecurityException("foreign or closed nonblocking TCP listener: " + operation);
        }
        requireCapability(IsolatePolicy.Capability.NETWORK, operation);
    }

    public void requireNioOwner(NativeNioFutureReactor.Connection connection, String operation) {
        requireNioOwner(connection.ownerReactor(), operation);
    }

    public void requireNioOwner(VmNioTcpListener listener, String operation) {
        synchronized (nioLifecycle) {
            if (nioContextClosed || !vmNioListeners.contains(listener))
                throw new SecurityException("foreign or closed nonblocking TCP listener: " + operation);
        }
        requireCapability(IsolatePolicy.Capability.NETWORK, operation);
    }

    public void requireNioOwner(VmNioTcpListener.Connection connection, String operation) {
        requireNioOwner(connection.ownerListener(), operation);
    }

    /** VM-owned, supervisor-only TCP listener, not a per-listener selector. */
    public VmNioTcpListener listenNioLoopback(
            String host, int port, int backlog, int maxConnections, int maxPending) {
        if (ActorRuntime.inActorExecution())
            throw new SecurityException("nonblocking TCP listeners are supervisor-only");
        if (!"127.0.0.1".equals(host))
            throw new SecurityException("experimental nonblocking TCP only binds loopback");
        requirePermission(RuntimePermissions.Permission.NET, host + ":" + port,
                "network.listen_nonblocking");
        synchronized (nioLifecycle) {
            if (nioContextClosed)
                throw new IllegalStateException("cannot create TCP listener in closed context");
            // Passive cleanup: reactor threads never execute arbitrary host
            // termination subscribers and closed listeners do not retain quota.
            nioListeners.removeIf(NativeNioFutureReactor::isTerminated);
            vmNioListeners.removeIf(VmNioTcpListener::isTerminated);
            if (nioListeners.size() + vmNioListeners.size() + nioOpening >= MAX_NIO_LISTENERS_PER_CONTEXT)
                throw new RejectedExecutionException("TCP listener capacity exhausted");
            ++nioOpening;
        }
        VmNioTcpListener reactor;
        try {
            reactor = VmNioTcpListener.loopback(
                    vm.nioReadiness(), port, backlog, maxConnections, maxPending);
        } catch (java.io.IOException failure) {
            synchronized (nioLifecycle) {
                --nioOpening;
                nioLifecycle.notifyAll();
            }
            throw new IllegalStateException("nonblocking TCP listen failed", failure);
        } catch (RuntimeException | Error failure) {
            synchronized (nioLifecycle) {
                --nioOpening;
                nioLifecycle.notifyAll();
            }
            throw failure;
        }
        synchronized (nioLifecycle) {
            --nioOpening;
            // If a concurrent host shutdown won, preserve this reactor in
            // the close snapshot instead of losing its owner after starting
            // its selector thread.
            if (nioContextClosed) {
                reactor.close();
                vmNioListeners.add(reactor);
                nioLifecycle.notifyAll();
                throw new IllegalStateException("TCP context closed during listener admission");
            }
            vmNioListeners.add(reactor);
            nioLifecycle.notifyAll();
        }
        return reactor;
    }

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
            // Explicit global budget gives CONTROL one guaranteed slot,
            // statically partitions isolated actor domains, and reserves the
            // bounded lifecycle-observer host capacity inside the same
            // execution-carrier ceiling. Unlike the legacy non-affine fallback,
            // no runtime execution domain may independently expand past it.
            ActorRuntime.DispatcherConfig actorSettings =
                    ActorRuntime.DispatcherConfig.defaults();
            OresCarrierBudget budget = vm.carrierBudget();
            if (budget != null) {
                actorSettings = new ActorRuntime.DispatcherConfig(
                        budget.privateMaximum(), budget.sharedMaximum(),
                        actorSettings.throughput(), actorSettings.maxActors());
            }
            actors = new ActorRuntime(
                    isolatePolicy,
                    actorSettings,
                    this::executeActorTurn,
                    vm::executeControl,
                    budget);
            // NIO completions share the existing actor-carrier admission
            // domain. A full queue stages bounded records and retries after
            // actor turns. The first runnable completion may use the readiness
            // manager's one-shot submitter so cold native-carrier startup never
            // blocks the selector; the callback itself still runs on actors.
            actors.bindNioCompletionCapacityHook(vm.nioReadiness()::retryStagedNotifications);
            vm.nioReadiness().bindVmCompletionExecutor(
                    actors::executeHostIoCompletion, true);
            async = new AsyncRuntime(this::executeAsyncTurn);
            gc = new RuntimeGarbageCollector();
            actors.setActorExitHook(gc::retireActorDomain);

            this.vm = vm;
            this.actors = actors;
            this.asyncRuntime = async;
            this.garbageCollector = gc;
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
        OresScheduler.checkpointCurrentTurn();
        actors.schedulerSafepoint();
    }

    public long schedulerSafepoints() { return schedulerSafepoints.get(); }

    /** A watchdog request is advisory until a continuation-safe boundary. */
    public boolean watchdogPreemptionRequested() {
        return OresScheduler.preemptionRequested()
                || actors.watchdogPreemptionRequested();
    }

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

    private void executeRootTurn(Runnable turn) {
        executeGuestTurn(turn, isolatePolicy.adversarial());
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

    private void executeGuestTurn(Runnable turn, boolean serialize) {
        if (serialize) adversarialActorTurnLock.lock();
        TruffleContext truffleContext = env.getContext();
        Object previous = null;
        boolean entered = false;
        try {
            previous = truffleContext.enter(null);
            entered = true;
            turn.run();
        } finally {
            if (entered) truffleContext.leave(null, previous);
            if (serialize) adversarialActorTurnLock.unlock();
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
        return Map.copyOf(descriptor);
    }

    @Override
    public void close() {
        // This is the external HOST lifecycle boundary. Do not begin actor,
        // IO or CONTROL destruction from a guest/physical scheduler carrier.
        if (OresScheduler.current() != null
                || OresVM.onControlCarrier()
                || OresVM.onNioWorker()
                || ActorRuntime.inActorExecution()
                || ActorRuntime.inActorGroupMailmanExecution()) {
            throw new IllegalStateException(
                    "OresContext shutdown requires an external trusted host thread");
        }
        java.util.Set<NativeNioFutureReactor> closingNio;
        java.util.Set<VmNioTcpListener> closingVmNio;
        final long deadline = System.nanoTime()
                + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        synchronized (nioLifecycle) {
            nioContextClosed = true;
            // Reactor constructors reserve nioOpening BEFORE entering OS/JDK
            // bootstrap. Wait only as an external host so that an unfinished
            // selector cannot be published after the actor scheduler closes.
            while (nioOpening > 0) {
                long left = deadline - System.nanoTime();
                if (left <= 0)
                    throw new IllegalStateException(
                            "Oreslang TCP listener bootstrap failed to quiesce");
                try { java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(nioLifecycle, left); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "interrupted while fencing Oreslang TCP listener bootstrap", interrupted);
                }
            }
            closingNio = java.util.Set.copyOf(nioListeners);
            closingVmNio = java.util.Set.copyOf(vmNioListeners);
        }
        // The VM selector remains alive until vm.close(), so close all
        // source listener descriptors before it begins terminal NIO draining.
        for (VmNioTcpListener listener : closingVmNio) listener.close();
        for (NativeNioFutureReactor reactor : closingNio) reactor.close();
        // No reactor is allowed to complete a Future into a destroyed CONTROL
        // mapping executor. Joining selector threads is reserved to the host.
        for (NativeNioFutureReactor reactor : closingNio) {
            try {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0 || !reactor.awaitTerminated(
                        Math.max(1, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining)))) {
                    throw new IllegalStateException(
                            "Oreslang TCP reactor did not quiesce before actor shutdown");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "interrupted while quiescing Oreslang TCP reactor", interrupted);
            }
        }
        synchronized (nioLifecycle) {
            nioListeners.removeIf(NativeNioFutureReactor::isTerminated);
            vmNioListeners.removeIf(VmNioTcpListener::isTerminated);
        }
        nativeIo.close();
        http.close();

        // Completion delivery is actor-carrier-owned in a full OresContext.
        // The NIO selector and every accepted completion record MUST drain
        // while actor dispatcher carriers are still available; reversing
        // this order would strand accepted terminal notifications forever.
        vm.nioReadiness().close();
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || !vm.nioReadiness().awaitStopped(
                    remaining, java.util.concurrent.TimeUnit.NANOSECONDS)) {
                throw new IllegalStateException(
                        "Oreslang VM NIO completions did not quiesce before actor shutdown");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "interrupted while draining VM NIO completions", interrupted);
        }

        RuntimeException failure = null;
        try {
            asyncRuntime.close();
        } catch (RuntimeException asyncFailure) {
            failure = asyncFailure;
        }
        try {
            actors.close();
        } catch (RuntimeException actorFailure) {
            if (failure == null) failure = actorFailure;
            else failure.addSuppressed(actorFailure);
        }
        try {
            vm.close();
        } catch (RuntimeException vmFailure) {
            if (failure == null) failure = vmFailure;
            else failure.addSuppressed(vmFailure);
        } finally {
            synchronized (this) {
                linkedCodeUnits.clear();
                linkedImportResolutions.clear();
                sharedCodeImages.close();
            }
            garbageCollector.close();
            output.flush();
        }
        if (failure != null) throw failure;
    }
}
