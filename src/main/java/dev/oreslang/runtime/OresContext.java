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
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
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
    // A failed physical shutdown must never let Truffle/actor heaps disappear
    // merely because the host dropped the context wrapper. This quarantine is
    // released only after every mandatory retirement gate has succeeded.
    private static final java.util.Set<OresContext> QUARANTINED_CONTEXTS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
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
    private final java.util.Set<VmNioTcpListener> vmNioListeners = new java.util.HashSet<>();
    private final java.util.Set<VmNioTcpConnection> vmNioOutboundConnections =
            new java.util.HashSet<>();
    private final java.util.Set<OresFuture<?>> vmNioConnecting = new java.util.HashSet<>();
    private final Object nioLifecycle = new Object();
    private boolean nioContextClosed;
    private int nioOpening;
    private static final int MAX_NIO_LISTENERS_PER_CONTEXT = 4;
    private static final int MAX_NIO_OUTBOUND_CONNECTIONS_PER_CONTEXT = 64;
    private static final int MAX_NIO_OPERATIONS_PER_OUTBOUND_CONNECTION = 16;

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

    public void requireNioOwner(VmNioTcpConnection connection, String operation) {
        synchronized (nioLifecycle) {
            if (nioContextClosed || !vmNioOutboundConnections.contains(connection))
                throw new SecurityException(
                        "foreign or closed outbound nonblocking TCP connection: " + operation);
        }
        requireCapability(IsolatePolicy.Capability.NETWORK, operation);
    }

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
            vmNioListeners.removeIf(VmNioTcpListener::isTerminated);
            if (vmNioListeners.size() + nioOpening >= MAX_NIO_LISTENERS_PER_CONTEXT)
                throw new RejectedExecutionException("TCP listener capacity exhausted");
            ++nioOpening;
        }
        VmNioTcpListener listener;
        try {
            listener = VmNioTcpListener.loopback(
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
            if (nioContextClosed) {
                listener.close();
                vmNioListeners.add(listener);
                nioLifecycle.notifyAll();
                throw new IllegalStateException("TCP context closed during listener admission");
            }
            vmNioListeners.add(listener);
            nioLifecycle.notifyAll();
        }
        return listener;
    }

    public OresFuture<VmNioTcpConnection> connectNioIpv4(String host, int port) {
        if (ActorRuntime.inActorExecution())
            throw new SecurityException("nonblocking TCP connect is supervisor-only");
        if (port < 1 || port > 65535)
            return OresFuture.failed(new IllegalArgumentException("invalid TCP connect port"));

        final java.net.InetAddress address;
        try {
            address = strictIpv4Literal(host);
        } catch (IllegalArgumentException invalidHost) {
            return OresFuture.failed(invalidHost);
        }
        requirePermission(RuntimePermissions.Permission.NET, host + ":" + port,
                "network.connect_async");

        synchronized (nioLifecycle) {
            if (nioContextClosed)
                return OresFuture.failed(new IllegalStateException(
                        "cannot connect TCP in closed context"));
            vmNioOutboundConnections.removeIf(VmNioTcpConnection::isTerminated);
            if (vmNioOutboundConnections.size() + vmNioConnecting.size()
                    >= MAX_NIO_OUTBOUND_CONNECTIONS_PER_CONTEXT)
                return OresFuture.failed(new RejectedExecutionException(
                        "outbound TCP connection capacity exhausted"));
            ++nioOpening;
        }

        final OresFuture<java.nio.channels.SocketChannel> raw;
        try {
            raw = vm.nioReadiness().connectAsync(
                    new java.net.InetSocketAddress(address, port));
        } catch (RuntimeException | Error failure) {
            synchronized (nioLifecycle) {
                --nioOpening;
                nioLifecycle.notifyAll();
            }
            throw failure;
        }

        final OresFuture<VmNioTcpConnection> exposed =
                new OresFuture<>(() -> raw.cancel(false), () -> { });
        synchronized (nioLifecycle) {
            if (nioContextClosed) {
                exposed.cancel(false);
            }
            vmNioConnecting.add(exposed);
        }

        raw.whenCompleteRuntime((socket, failure) -> {
            VmNioTcpConnection connection = null;
            try {
                if (raw.isCancelled()) {
                    if (!exposed.isCancelled()) exposed.cancel(false);
                    return;
                }
                if (failure != null) {
                    exposed.failFromRuntime(failure);
                    return;
                }
                synchronized (nioLifecycle) {
                    if (!nioContextClosed && !exposed.isDone()) {
                        connection = new VmNioTcpConnection(
                                vm.nioReadiness(),
                                socket,
                                MAX_NIO_OPERATIONS_PER_OUTBOUND_CONNECTION,
                                () -> {
                                    synchronized (nioLifecycle) {
                                        vmNioOutboundConnections.removeIf(
                                                VmNioTcpConnection::isTerminated);
                                        nioLifecycle.notifyAll();
                                    }
                                });
                        vmNioOutboundConnections.add(connection);
                    }
                }
                if (connection == null) {
                    try { socket.close(); } catch (java.io.IOException ignored) { }
                    if (!exposed.isDone())
                        exposed.failFromRuntime(new IllegalStateException(
                                "TCP context closed during connect completion"));
                } else if (!exposed.completeFromRuntime(connection)) {
                    connection.close();
                }
            } finally {
                synchronized (nioLifecycle) {
                    vmNioConnecting.remove(exposed);
                    --nioOpening;
                    if (nioOpening < 0)
                        throw new IllegalStateException("NIO admission counter underflow");
                    nioLifecycle.notifyAll();
                }
            }
        });
        return exposed;
    }

    private static java.net.InetAddress strictIpv4Literal(String host) {
        if (host == null) throw new IllegalArgumentException("TCP host cannot be null");
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4)
            throw new IllegalArgumentException(
                    "network.connect_async requires a numeric IPv4 literal until async DNS is installed");
        byte[] bytes = new byte[4];
        for (int i = 0; i < parts.length; ++i) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 3
                    || !part.chars().allMatch(Character::isDigit))
                throw new IllegalArgumentException(
                        "network.connect_async requires a canonical numeric IPv4 literal");
            int value;
            try { value = Integer.parseInt(part); }
            catch (NumberFormatException malformed) {
                throw new IllegalArgumentException("invalid IPv4 literal", malformed);
            }
            if (value < 0 || value > 255)
                throw new IllegalArgumentException("IPv4 octet outside 0..255");
            if (part.length() > 1 && part.charAt(0) == '0')
                throw new IllegalArgumentException(
                        "IPv4 octets with leading zeroes are not canonical");
            bytes[i] = (byte) value;
        }
        try {
            return java.net.InetAddress.getByAddress(bytes);
        } catch (java.net.UnknownHostException impossible) {
            throw new IllegalStateException("four-byte IPv4 address rejected", impossible);
        }
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
                    || host instanceof CompletionStage<?>
                    || host instanceof VmNioTcpListener
                    || host instanceof VmNioTcpListener.Connection
                    || host instanceof VmNioTcpConnection) {
                return host;
            }
        }
        return value;
    }

    public OresContext(OresLanguage language, TruffleLanguage.Env env) {
        long contextPerf = CorePerf.start();
        this.language = language;
        this.env = env;
        this.input = new BufferedReader(new InputStreamReader(env.in(), StandardCharsets.UTF_8));
        this.output = new PrintWriter(new OutputStreamWriter(env.out(), StandardCharsets.UTF_8), true);
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
            ActorRuntime.DispatcherConfig actorSettings =
                    ActorRuntime.DispatcherConfig.defaults();
            OresCarrierBudget budget = vm.carrierBudget();
            if (budget != null) {
                actorSettings = new ActorRuntime.DispatcherConfig(
                        budget.privateMaximum(),
                        budget.sharedMaximum(),
                        actorSettings.throughput(),
                        actorSettings.maxActors());
            }
            actors = new ActorRuntime(
                    isolatePolicy,
                    actorSettings,
                    this::executeActorTurn,
                    vm::executeControl,
                    vm::executePhysicalStop,
                    budget,
                    vm.carrierPermitPool());
            // Accepted readiness completions reuse actor carrier capacity.
            // A one-shot helper may bootstrap a cold actor carrier off-selector;
            // guest code and Future continuations still run only on the owning scheduler.
            actors.bindNioCompletionCapacityHook(
                    vm.nioReadiness()::retryStagedNotifications);
            vm.nioReadiness().bindVmCompletionExecutor(
                    actors::executeHostIoCompletion, true);
            async = new AsyncRuntime(this::executeAsyncTurn);
            gc = new RuntimeGarbageCollector();
            // Install BEFORE publishing this actor runtime to any guest.
            // The arena-release gate runs before actorExitHook and is notified
            // by the final physical worker/resource-loan receipt.
            actors.setNativeIoLoanRetirementGate(nativeIo::actorPhysicallyRetired);
            nativeIo.setActorLoanRetiredListener(actors::onNativeIoLoanRetired);
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

    /**
     * Two-phase physical context retirement. Separate sealing/revocation from
     * the physical joins, so an actor holding a socket loan cannot make the
     * context wait for a worker before any cancellation has been requested.
     *
     * Each phase is independently attempted on failure; neither actor nor
     * native ownership may be reclaimed without BOTH joins succeeding.
     * Package access permits exercising this exact production sequence in
     * deterministic unit tests without constructing a Truffle environment.
     */
    static void joinActorsAndNativeIo(ActorRuntime actors, AsyncNativeIo nativeIo) {
        java.util.Objects.requireNonNull(actors);
        java.util.Objects.requireNonNull(nativeIo);
        RuntimeException failure = null;
        try {
            actors.sealShutdownAdmission();
        } catch (RuntimeException sealFailure) {
            failure = sealFailure;
        }
        try {
            nativeIo.requestShutdown();
        } catch (RuntimeException revokeFailure) {
            if (failure == null) failure = revokeFailure;
            else if (failure != revokeFailure) failure.addSuppressed(revokeFailure);
        }
        try {
            actors.close();
        } catch (RuntimeException actorFailure) {
            if (failure == null) failure = actorFailure;
            else if (failure != actorFailure) failure.addSuppressed(actorFailure);
        }
        try {
            nativeIo.close();
        } catch (RuntimeException ioFailure) {
            if (failure == null) failure = ioFailure;
            else if (failure != ioFailure) failure.addSuppressed(ioFailure);
        }
        if (failure != null) {
            throw new IllegalStateException(
                    "Oreslang actor and native I/O retirement incomplete", failure);
        }
    }

    @Override
    public void close() {
        if (OresScheduler.current() != null
                || OresVM.onNioWorker()
                || ActorRuntime.inActorExecution()
                || ActorRuntime.inActorGroupMailmanExecution()) {
            throw new IllegalStateException(
                    "OresContext shutdown requires an external trusted host thread");
        }

        // Hold the entire actor, scheduler, Truffle code image and GC graph
        // strongly reachable until NIO, actor and native physical joins succeed.
        QUARANTINED_CONTEXTS.add(this);

        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        java.util.Set<OresFuture<?>> cancellingConnects;
        java.util.Set<VmNioTcpConnection> closingOutbound;
        java.util.Set<VmNioTcpListener> closingVmNio;
        synchronized (nioLifecycle) {
            nioContextClosed = true;
            cancellingConnects = java.util.Set.copyOf(vmNioConnecting);
            closingOutbound = java.util.Set.copyOf(vmNioOutboundConnections);
        }
        for (OresFuture<?> pending : cancellingConnects) pending.cancel(false);
        for (VmNioTcpConnection connection : closingOutbound) connection.close();

        synchronized (nioLifecycle) {
            while (nioOpening > 0) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    output.flush();
                    throw new IllegalStateException(
                            "Oreslang TCP admission failed to quiesce");
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(nioLifecycle, left);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    output.flush();
                    throw new IllegalStateException(
                            "interrupted while fencing Oreslang TCP admission",
                            interrupted);
                }
            }
            closingVmNio = java.util.Set.copyOf(vmNioListeners);
        }
        for (VmNioTcpListener listener : closingVmNio) listener.close();

        // NIO completion delivery is bound to actor carriers. Drain it before
        // actor/native retirement so an accepted completion is never stranded.
        vm.nioReadiness().close();
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || !vm.nioReadiness().awaitStopped(
                    remaining, TimeUnit.NANOSECONDS)) {
                output.flush();
                throw new IllegalStateException(
                        "Oreslang VM NIO completions did not quiesce before actor shutdown");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            output.flush();
            throw new IllegalStateException(
                    "interrupted while draining VM NIO completions", interrupted);
        }
        synchronized (nioLifecycle) {
            vmNioListeners.removeIf(VmNioTcpListener::isTerminated);
            vmNioOutboundConnections.removeIf(VmNioTcpConnection::isTerminated);
            if (!vmNioConnecting.isEmpty() || !vmNioOutboundConnections.isEmpty()) {
                output.flush();
                throw new IllegalStateException(
                        "Oreslang outbound TCP resources did not physically retire");
            }
        }

        try {
            joinActorsAndNativeIo(actors, nativeIo);
        } catch (RuntimeException incomplete) {
            output.flush();
            throw new IllegalStateException(
                    "Oreslang context cleanup incomplete; physical state quarantined",
                    incomplete);
        }
        RuntimeException failure = null;
        try {
            http.close();
        } catch (RuntimeException httpFailure) {
            failure = httpFailure;
        }
        try {
            asyncRuntime.close();
        } catch (RuntimeException asyncFailure) {
            if (failure == null) failure = asyncFailure;
            else if (failure != asyncFailure) failure.addSuppressed(asyncFailure);
        }
        try {
            vm.close();
        } catch (RuntimeException vmFailure) {
            if (failure == null) failure = vmFailure;
            else if (failure != vmFailure) failure.addSuppressed(vmFailure);
        }
        if (failure != null) {
            output.flush();
            throw new IllegalStateException(
                    "Oreslang context auxiliary shutdown incomplete; context quarantined",
                    failure);
        }

        synchronized (this) {
            linkedCodeUnits.clear();
            linkedImportResolutions.clear();
            sharedCodeImages.close();
        }
        garbageCollector.close();
        output.flush();
        QUARANTINED_CONTEXTS.remove(this);
    }
}
