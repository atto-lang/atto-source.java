package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.compiler.IncrementalCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Versioned source hot loader.
 *
 * Loading is side-effect free with respect to guest execution: source is
 * parsed/type/capability checked, then assigned a fresh context. The trusted
 * supervisor explicitly starts the generation after activation.
 *
 * No JNI/FFI or OS dynamic-library loading is required.
 */
public final class HotReloadManager implements AutoCloseable {
    private static final int MAX_ADVERSARIAL_SOURCE_CHARS = 1_048_576;
    private static final int MAX_ADVERSARIAL_CODE_UNIT_ID_CHARS = 512;
    private static final int MAX_ADVERSARIAL_LIVE_GENERATIONS = 8;

    private final IsolatePolicy supervisorPolicy;
    private final IsolatePolicy guestPolicy;
    private final ExecutionProfile executionProfile;
    private final AtomicLong sequence = new AtomicLong();
    private boolean closed;
    // All routing mutations run under this monitor. A monotonic epoch
    // invalidates stale candidates even when a route returns to its old image.
    private long routingEpoch;
    private final AtomicReference<Generation> active = new AtomicReference<>();
    private final Map<String, Generation> activeByCodeUnit = new LinkedHashMap<>();
    // Serving routes have separate publication authority: legacy load() may
    // change activeByCodeUnit without shadowing an already healthy service.
    private final Map<String, Generation> servingByCodeUnit = new LinkedHashMap<>();
    private final Map<Long, Generation> generations = new LinkedHashMap<>();

    public HotReloadManager(
            IsolatePolicy supervisorPolicy,
            ExecutionProfile executionProfile) {
        this(supervisorPolicy, supervisorPolicy, executionProfile);
    }

    public HotReloadManager(
            IsolatePolicy supervisorPolicy,
            IsolatePolicy guestPolicy,
            ExecutionProfile executionProfile) {
        this.supervisorPolicy =
                java.util.Objects.requireNonNull(supervisorPolicy);
        this.guestPolicy =
                java.util.Objects.requireNonNull(guestPolicy);
        this.executionProfile =
                java.util.Objects.requireNonNull(executionProfile);
        this.supervisorPolicy.require(
                IsolatePolicy.Capability.HOT_CODE_LOAD,
                "HotReloadManager");
        // Trusted parents may narrow authority, but must never grant a
        // hot-loaded guest more permission or resource budget than they own.
        if (!this.supervisorPolicy.capabilities().containsAll(this.guestPolicy.capabilities())
                || this.guestPolicy.maxHeapBytes() > this.supervisorPolicy.maxHeapBytes()
                || this.guestPolicy.maxMailboxMessages() > this.supervisorPolicy.maxMailboxMessages()
                || this.guestPolicy.maxWallTime().compareTo(this.supervisorPolicy.maxWallTime()) > 0
                || (this.supervisorPolicy.adversarial() && !this.guestPolicy.adversarial())) {
            throw new SecurityException(
                    "hot-loaded guest policy must be no more permissive than its supervisor");
        }
    }

    public static HotReloadManager forUntrustedActors(
            IsolatePolicy supervisorPolicy,
            ExecutionProfile executionProfile) {
        return new HotReloadManager(
                supervisorPolicy,
                IsolatePolicy.untrustedActor(),
                executionProfile);
    }

    public IsolatePolicy supervisorPolicy() { return supervisorPolicy; }
    public IsolatePolicy guestPolicy() { return guestPolicy; }

    /**
     * Validates and stages a new generation without executing its entrypoint.
     */
    public synchronized Generation load(String name, String sourceText) {
        validateAdmissionInputs(name, sourceText);
        OresCompiler.validateForIsolate(sourceText, guestPolicy);
        return stage(name, digest(sourceText), sourceText, true);
    }

    /**
     * Reuses an incrementally compiled unit. Static compilation work is reused,
     * while capability admission is deliberately repeated for the destination
     * isolate because authority belongs to the runtime policy, not the cache.
     */
    public synchronized Generation load(IncrementalCompiler.CompiledUnit unit) {
        java.util.Objects.requireNonNull(unit, "unit");
        validateAdmissionInputs(unit.unitId(), unit.sourceText());
        // A CompiledUnit is a public record, not an authenticated compiler receipt.
        // Its AST, dependency set, and digest must never authorize unrelated
        // executable source bytes.
        String verifiedDigest = digest(unit.sourceText());
        if (!verifiedDigest.equals(unit.sourceDigest())) {
            throw new SecurityException("compiled hot-load source digest mismatch");
        }
        if (!unit.dependencies().isEmpty()) {
            throw new SecurityException("compiled hot-load requires verified dependency closure");
        }
        // Reject imported source before attempting linker/type resolution: the
        // public dependency set can be forged empty. Closed-world linking needs
        // a separate compiler-private dependency provenance mechanism.
        var parsed = dev.oreslang.parser.Parser.parse(unit.sourceText());
        if (!parsed.imports().isEmpty()) {
            throw new SecurityException("compiled hot-load imports require verified dependency closure");
        }
        OresCompiler.validateForIsolate(unit.sourceText(), guestPolicy);
        return stage(unit.unitId(), verifiedDigest, unit.sourceText(), true);
    }

    private void validateAdmissionInputs(
            String codeUnitId,
            String sourceText) {
        requireOpen();
        java.util.Objects.requireNonNull(codeUnitId, "codeUnitId");
        java.util.Objects.requireNonNull(sourceText, "sourceText");
        if (codeUnitId.isBlank()) {
            throw new IllegalArgumentException("codeUnitId cannot be blank");
        }
        if (!guestPolicy.adversarial()) return;
        if (codeUnitId.length() > MAX_ADVERSARIAL_CODE_UNIT_ID_CHARS) {
            throw new IllegalArgumentException(
                    "adversarial hot-load codeUnitId exceeds maximum length "
                            + MAX_ADVERSARIAL_CODE_UNIT_ID_CHARS);
        }
        if (sourceText.length() > MAX_ADVERSARIAL_SOURCE_CHARS) {
            throw new IllegalArgumentException(
                    "adversarial hot-load source exceeds maximum character count "
                            + MAX_ADVERSARIAL_SOURCE_CHARS);
        }
    }

    private Generation stage(String codeUnitId, String sourceDigest, String sourceText, boolean publish) {
        requireOpen();
        if (guestPolicy.adversarial()
                && generations.size() >= MAX_ADVERSARIAL_LIVE_GENERATIONS) {
            throw new IllegalStateException(
                    "adversarial hot-load live generation limit exceeded: "
                            + MAX_ADVERSARIAL_LIVE_GENERATIONS);
        }
        long id = sequence.updateAndGet(current -> {
            if (current == Long.MAX_VALUE) {
                throw new IllegalStateException("hot-reload generation IDs exhausted");
            }
            return current + 1;
        });
        Context context =
                guestPolicy.restrictedContextBuilder(executionProfile).build();
        try {
            Source source = Source.newBuilder(OresLanguage.ID, sourceText, codeUnitId)
                    .mimeType(OresLanguage.MIME_TYPE)
                    .buildLiteral();
            Generation generation = new Generation(this, id, codeUnitId, sourceDigest, context, source, executionProfile);
            if (publish) requireRoutingEpochCapacity();
            generations.put(id, generation);
            if (publish) {
                ++routingEpoch;
                activeByCodeUnit.put(codeUnitId, generation);
                active.set(generation);
            }
            return generation;
        } catch (RuntimeException | Error failure) {
            // Context/source construction can fail with Error (including
            // allocation failure). Preserve the root cause if cleanup fails.
            try {
                context.close(true);
            } catch (RuntimeException | Error cleanupFailure) {
                if (failure != cleanupFailure) failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /**
     * Transactional generic source replacement. No serving route changes until
     * successful guest initialization. Candidate initialization runs outside
     * the routing monitor, then an epoch check rejects stale publication.
     * This is NOT actor mailbox migration or a physical-quiescence guarantee.
     */
    public Generation reloadAndStart(String name, String sourceText) {
        final Generation candidate;
        final long expectedEpoch;
        synchronized (this) {
            validateAdmissionInputs(name, sourceText);
            OresCompiler.validateForIsolate(sourceText, guestPolicy);
            candidate = stage(name, digest(sourceText), sourceText, false);
            expectedEpoch = routingEpoch;
        }
        try {
            candidate.start();
            synchronized (this) {
                requireOpen();
                if (routingEpoch != expectedEpoch
                        || generations.get(candidate.id()) != candidate
                        || candidate.closed()) {
                    throw new IllegalStateException("stale hot-reload candidate");
                }
                requireRoutingEpochCapacity();
                ++routingEpoch;
                activeByCodeUnit.put(name, candidate);
                servingByCodeUnit.put(name, candidate);
                active.set(candidate);
                return candidate;
            }
        } catch (RuntimeException | Error failure) {
            try {
                retire(candidate.id());
            } catch (RuntimeException | Error cleanupFailure) {
                if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /** Existing convenience API now preserves the last serving generation on failure. */
    public Generation loadAndStart(String name, String sourceText) {
        return reloadAndStart(name, sourceText);
    }

    private void requireRoutingEpochCapacity() {
        if (routingEpoch == Long.MAX_VALUE) {
            throw new IllegalStateException("hot-reload routing epoch exhausted");
        }
    }

    /** Last generation staged, retained for compatibility with the single-unit API. */
    public Generation active() { return active.get(); }

    /** Active generation for one independently compiled code unit. */
    public synchronized Generation active(String codeUnitId) {
        return activeByCodeUnit.get(codeUnitId);
    }

    /**
     * Single supervisor-owned serving route, independent of the legacy
     * active() staging pointer. Only successful reloadAndStart() publication
     * enters this registry. Initialization success is NOT a health/ABI or
     * actor-quiescence certificate.
     */
    public synchronized Generation serving(String codeUnitId) {
        Generation selected = servingByCodeUnit.get(codeUnitId);
        return selected != null && selected.initialized() && !selected.closed()
                ? selected : null;
    }

    public synchronized Map<String, Generation> activeGenerations() {
        return Map.copyOf(activeByCodeUnit);
    }

    /**
     * Retire only an idle generation. Reservation and retirement are serialized
     * with start() under manager -> generation lock order; a concurrent guest
     * entrypoint cannot be invalidated by an unchecked Context.close(true).
     * This protects manager-owned startup only, not guest-created async Tasks
     * or external callers holding the exposed Context reference.
     */
    public void retire(long generationId) {
        final Generation generation;
        synchronized (this) {
            generation = generations.get(generationId);
            if (generation != null) {
                boolean selected = active.get() == generation
                        || activeByCodeUnit.get(generation.codeUnitId()) == generation
                        || servingByCodeUnit.get(generation.codeUnitId()) == generation;
                if (selected) requireRoutingEpochCapacity();
                if (!generation.beginClose()) return;
                generations.remove(generationId, generation);
                boolean removed = active.compareAndSet(generation, null);
                removed |= activeByCodeUnit.remove(generation.codeUnitId(), generation);
                removed |= servingByCodeUnit.remove(generation.codeUnitId(), generation);
                if (removed) ++routingEpoch;
            }
        }
        // Do not run context cleanup or user unwind callbacks under the
        // supervisor monitor; the generation is already fenced from starts.
        if (generation != null) generation.finishClose();
    }

    public synchronized int liveGenerations() { return generations.size(); }

    /** Closed guest contexts must not remain in the manager's active registry. */
    private synchronized void onGenerationClosed(Generation generation) {
        boolean selected = activeByCodeUnit.get(generation.codeUnitId()) == generation
                || servingByCodeUnit.get(generation.codeUnitId()) == generation
                || active.get() == generation;
        // Never mutate registry selection before checking the epoch fence.
        if (selected && !closed) requireRoutingEpochCapacity();
        generations.remove(generation.id(), generation);
        boolean removed = activeByCodeUnit.remove(generation.codeUnitId(), generation);
        removed |= servingByCodeUnit.remove(generation.codeUnitId(), generation);
        removed |= active.compareAndSet(generation, null);
        if (removed && !closed) ++routingEpoch;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("hot reload manager is closed");
        }
    }

    @Override
    public void close() {
        final java.util.List<Generation> toClose;
        synchronized (this) {
            if (closed) return;
            // All starts must acquire the manager monitor, so checking every
            // execution lease here is an atomic precondition. If any candidate
            // still executes, leave the manager and all registrations intact.
            toClose = java.util.List.copyOf(generations.values());
            for (Generation generation : toClose) {
                generation.requireIdleForClose();
            }
            for (Generation generation : toClose) {
                generation.beginClose();
            }
            // Seal admission and detach routes atomically. Context cleanup is
            // performed later, outside the supervisor monitor.
            closed = true;
            generations.clear();
            activeByCodeUnit.clear();
            servingByCodeUnit.clear();
            active.set(null);
        }
        Throwable failure = null;
        for (Generation generation : toClose) {
            try {
                generation.finishClose();
            } catch (RuntimeException | Error closingFailure) {
                if (failure == null) {
                    failure = closingFailure;
                } else {
                    failure.addSuppressed(closingFailure);
                }
            }
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
    }

    private static String digest(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static final class Generation implements AutoCloseable {
        // A retained retired Generation must not pin the manager (and all its other contexts).
        private final java.lang.ref.WeakReference<HotReloadManager> owner;
        private final long id;
        private final String codeUnitId;
        private final String sha256;
        private final Context context;
        private final Source source;
        private final ExecutionProfile executionProfile;
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean initialized = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        // True only while the synchronous manager-owned Context.eval call is
        // executing. It is NOT a complete Task/continuation/FFI quiescence ledger.
        private boolean executing;
        // Scoped host entry/operation pins. These protect callers which use
        // pinOperation() for the *entire* physical operation. An external user
        // of context() without a pin, or a detached async continuation, remains
        // outside this proof.
        private int operationPins;

        private Generation(HotReloadManager owner, long id, String codeUnitId, String sha256, Context context, Source source, ExecutionProfile executionProfile) {
            this.owner = new java.lang.ref.WeakReference<>(owner);
            this.id = id;
            this.codeUnitId = codeUnitId;
            this.sha256 = sha256;
            this.context = context;
            this.source = source;
            this.executionProfile = executionProfile;
        }

        public long id() { return id; }
        public String codeUnitId() { return codeUnitId; }
        public String sha256() { return sha256; }
        public Context context() { return context; }
        public Source source() { return source; }
        public ExecutionProfile executionProfile() { return executionProfile; }
        public boolean started() { return started.get(); }
        /** Only true after entrypoint returns without an exception. */
        public boolean initialized() { return initialized.get(); }
        public boolean closed() { return closed.get(); }

        /**
         * Host-owned physical operation lease for an initialized generation.
         * Hold this across every guest invocation, callback, or external
         * operation whose completion depends on this code image. No guest
         * authorization is conferred, and this does not capture suspended Tasks
         * or legacy unpinned uses of context() automatically.
         */
        public final class OperationLease implements AutoCloseable {
            private final AtomicBoolean released = new AtomicBoolean();

            private OperationLease() { }

            @Override public void close() {
                synchronized (Generation.this) {
                    if (!released.compareAndSet(false, true)) return;
                    if (operationPins <= 0) {
                        throw new IllegalStateException("hot-load operation pin underflow");
                    }
                    --operationPins;
                }
            }
        }

        /** Linearizable admission against retire(), close() and shutdown(). */
        public OperationLease pinOperation() {
            HotReloadManager manager = owner.get();
            if (manager == null) {
                throw new IllegalStateException("generation owner no longer available");
            }
            synchronized (manager) {
                manager.requireOpen();
                if (manager.generations.get(id) != this) {
                    throw new IllegalStateException("generation not registered");
                }
                synchronized (this) {
                    if (closed.get() || !initialized.get() || executing) {
                        throw new IllegalStateException(
                                "operation requires an idle initialized live generation");
                    }
                    if (operationPins == Integer.MAX_VALUE) {
                        throw new IllegalStateException(
                                "hot-load operation pin budget exhausted");
                    }
                    ++operationPins;
                    return new OperationLease();
                }
            }
        }

        /** Starts once, reserving a lifecycle lease before entering Graal. */
        public Value start() {
            HotReloadManager manager = owner.get();
            if (manager == null) {
                throw new IllegalStateException("generation owner is no longer available");
            }
            // manager -> generation lock order is identical to retire/close.
            synchronized (manager) {
                manager.requireOpen();
                if (manager.generations.get(id) != this) {
                    throw new IllegalStateException("generation has been retired");
                }
                synchronized (this) {
                    if (closed.get()) {
                        throw new IllegalStateException("generation is closed");
                    }
                    if (!started.compareAndSet(false, true)) {
                        throw new IllegalStateException("generation already started");
                    }
                    executing = true;
                }
            }
            Throwable failure = null;
            try {
                Value value = context.eval(source);
                initialized.set(true);
                return value;
            } catch (RuntimeException | Error evaluationFailure) {
                failure = evaluationFailure;
                throw evaluationFailure;
            } finally {
                synchronized (this) {
                    executing = false;
                }
                if (failure != null) {
                    try {
                        close();
                    } catch (RuntimeException | Error cleanupFailure) {
                        if (cleanupFailure != failure) {
                            failure.addSuppressed(cleanupFailure);
                        }
                    }
                }
            }
        }

        private synchronized void requireIdleForClose() {
            if (executing || operationPins != 0) {
                throw new IllegalStateException(
                        "cannot retire generation " + id
                                + " while entrypoint or scoped guest operation is active");
            }
        }

        /** Called only under the manager monitor or when owner is gone. */
        private synchronized boolean beginClose() {
            if (closed.get()) return false;
            requireIdleForClose();
            closed.set(true);
            return true;
        }

        /** Context cleanup is NEVER called under the manager routing monitor. */
        private void finishClose() {
            context.close(true);
        }

        @Override
        public void close() {
            HotReloadManager manager = owner.get();
            if (manager != null) {
                synchronized (manager) {
                    // An exhausted publication epoch must reject close before
                    // marking a selected generation terminal; otherwise the
                    // registry could retain an unusable closed route.
                    if (!closed.get() && !manager.closed
                            && (manager.active.get() == this
                            || manager.activeByCodeUnit.get(codeUnitId) == this
                            || manager.servingByCodeUnit.get(codeUnitId) == this)) {
                        manager.requireRoutingEpochCapacity();
                    }
                    if (!beginClose()) return;
                    // Detach before cleanup. An in-flight start cannot race
                    // because it also requires this monitor.
                    manager.onGenerationClosed(this);
                }
            } else if (!beginClose()) {
                return;
            }
            finishClose();
        }
    }
}
