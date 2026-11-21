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
 * Loading is side-effect free with respect to guest execution. For trusted
 * code the host validates before creating a Context. For UNTRUSTED code the
 * trusted host checks bounded source metadata only and routes parsing,
 * linking and capability admission through the single shared guest isolate.
 * The supervisor explicitly starts the generation after activation.
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
    // Lazily created to preserve reject-before-isolate-allocation semantics.
    // One HotReloadManager owns one app-wide UNTRUSTED pool; concurrent owners
    // fail closed under SharedUntrustedIsolatePool's singleton admission.
    private SharedUntrustedIsolatePool untrustedPool;
    private boolean ownsUntrustedPool = true;
    private boolean closed;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicReference<Generation> active = new AtomicReference<>();
    private final Map<String, Generation> activeByCodeUnit = new LinkedHashMap<>();
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
    }

    public static HotReloadManager forUntrustedActors(
            IsolatePolicy supervisorPolicy,
            ExecutionProfile executionProfile) {
        return new HotReloadManager(
                supervisorPolicy,
                IsolatePolicy.untrustedActor(),
                executionProfile);
    }

    /**
     * Reuses the application's ONE existing untrusted Engine. The caller owns
     * the pool; this manager closes only Contexts/generations it created.
     * No second Engine can be constructed by this factory.
     */
    public static HotReloadManager forUntrustedActors(
            IsolatePolicy supervisorPolicy,
            ExecutionProfile executionProfile,
            SharedUntrustedIsolatePool sharedPool) {
        HotReloadManager manager = forUntrustedActors(supervisorPolicy, executionProfile);
        manager.untrustedPool = java.util.Objects.requireNonNull(sharedPool, "sharedPool");
        manager.ownsUntrustedPool = false;
        return manager;
    }

    public IsolatePolicy supervisorPolicy() { return supervisorPolicy; }
    public IsolatePolicy guestPolicy() { return guestPolicy; }

    /**
     * Validates and stages a new generation without executing its entrypoint.
     */
    public synchronized Generation load(String name, String sourceText) {
        if (closed) throw new IllegalStateException("hot loader is closed");
        validateAdmissionInputs(name, sourceText);
        // NEVER parse or type-check attacker-supplied source in the trusted
        // bootstrap JVM. The isolated Context will parse/link/capability-check
        // when Generation.start() evaluates the source. Keep only cheap input
        // length/identity admission here, before allocating the guest Engine.
        if (!guestPolicy.adversarial()) {
            OresCompiler.validateForIsolate(sourceText, guestPolicy);
        }
        return stage(name, digest(sourceText), sourceText);
    }

    /**
     * Reuses an incrementally compiled unit. Static compilation work is reused,
     * while capability admission is deliberately repeated for the destination
     * isolate because authority belongs to the runtime policy, not the cache.
     */
    public synchronized Generation load(IncrementalCompiler.CompiledUnit unit) {
        if (closed) throw new IllegalStateException("hot loader is closed");
        java.util.Objects.requireNonNull(unit, "unit");
        validateAdmissionInputs(unit.unitId(), unit.sourceText());
        if (guestPolicy.adversarial()) {
            // A trusted compiler cache/AST is NOT an untrusted isolate proof.
            // Restage the original bounded source and verify its digest here;
            // isolated parsing and type checking occur on guest evaluation.
            return stage(unit.unitId(), digest(unit.sourceText()), unit.sourceText());
        }
        CapabilityChecker.check(unit.program(), guestPolicy);
        return stage(unit.unitId(), unit.sourceDigest(), unit.sourceText());
    }

    private void validateAdmissionInputs(
            String codeUnitId,
            String sourceText) {
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

    private Generation stage(String codeUnitId, String sourceDigest, String sourceText) {
        if (guestPolicy.adversarial()
                && generations.size() >= MAX_ADVERSARIAL_LIVE_GENERATIONS) {
            throw new IllegalStateException(
                    "adversarial hot-load live generation limit exceeded: "
                            + MAX_ADVERSARIAL_LIVE_GENERATIONS);
        }
        long id = sequence.incrementAndGet();
        SharedUntrustedIsolatePool.Group group = null;
        Context context;
        if (guestPolicy.adversarial()) {
            // The isolated Engine must outlive all hot-loaded code generations.
            // Per-generation contexts share ONE physical isolate heap/GC/JIT.
            // No standalone restrictedContextBuilder() fallback is permitted.
            if (untrustedPool == null) {
                untrustedPool = new SharedUntrustedIsolatePool(
                        256L * 1024 * 1024, MAX_ADVERSARIAL_LIVE_GENERATIONS);
            }
            group = untrustedPool.createGroup();
            context = group.contextForSupervisor();
        } else {
            context = guestPolicy.restrictedContextBuilder(executionProfile).build();
        }
        try {
            Source source = Source.newBuilder(OresLanguage.ID, sourceText, codeUnitId)
                    .mimeType(OresLanguage.MIME_TYPE)
                    .buildLiteral();
            Generation generation = new Generation(
                    id, codeUnitId, sourceDigest, context, source,
                    group == null ? executionProfile : ExecutionProfile.serverHybrid(),
                    group);
            generations.put(id, generation);
            activeByCodeUnit.put(codeUnitId, generation);
            active.set(generation);
            return generation;
        } catch (RuntimeException | Error failure) {
            try {
                if (group != null) group.close();
                else context.close(true);
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    public synchronized Generation loadAndStart(String name, String sourceText) {
        Generation generation = load(name, sourceText);
        try {
            generation.start();
            return generation;
        } catch (RuntimeException | Error failure) {
            // Do not let rejected user source accumulate generations after
            // their Contexts have already been physically retired. A failed
            // Context close instead retains a tombstone via retire().
            try {
                retire(generation.id());
            } catch (RuntimeException | Error cleanup) {
                failure.addSuppressed(new IllegalStateException(
                        "failed generation retirement was not confirmed"));
            }
            throw failure;
        }
    }

    /** Last generation staged, retained for compatibility with the single-unit API. */
    public Generation active() { return active.get(); }

    /** Active generation for one independently compiled code unit. */
    public synchronized Generation active(String codeUnitId) {
        return activeByCodeUnit.get(codeUnitId);
    }

    public synchronized Map<String, Generation> activeGenerations() {
        return Map.copyOf(activeByCodeUnit);
    }

    /** Explicit retirement permits old actors/requests to drain before teardown. */
    public synchronized void retire(long generationId) {
        Generation generation = generations.get(generationId);
        if (generation != null) {
            // The generation becomes undiscoverable immediately, but its
            // resource reservation stays counted until Context.close(true)
            // actually succeeds. Failed teardown is a fenced tombstone.
            active.compareAndSet(generation, null);
            activeByCodeUnit.remove(generation.codeUnitId(), generation);
            generation.close();
            generations.remove(generationId, generation);
        }
    }

    public synchronized int liveGenerations() { return generations.size(); }

    @Override
    public synchronized void close() {
        closed = true;
        RuntimeException failure = null;
        for (var entry : new java.util.ArrayList<>(generations.entrySet())) {
            try {
                entry.getValue().close();
                generations.remove(entry.getKey(), entry.getValue());
            } catch (RuntimeException error) {
                // An unsuccessful close stays reserved, even if guest code
                // was already fenced from all supervisor APIs.
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        activeByCodeUnit.clear();
        active.set(null);
        if (untrustedPool != null && ownsUntrustedPool) {
            try {
                untrustedPool.close();
                untrustedPool = null;
            } catch (RuntimeException error) {
                // An unsuccessful isolate close must not be marked as fully
                // released. SharedUntrustedIsolatePool keeps its admission
                // slot fenced until the underlying Engine really closes.
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
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
        private final long id;
        private final String codeUnitId;
        private final String sha256;
        private final Context context;
        private final SharedUntrustedIsolatePool.Group group;
        private final Source source;
        private final ExecutionProfile executionProfile;
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final VerifiedCloseFence closeFence;

        private Generation(long id, String codeUnitId, String sha256,
                           Context context, Source source,
                           ExecutionProfile executionProfile,
                           SharedUntrustedIsolatePool.Group group) {
            this.id = id;
            this.codeUnitId = codeUnitId;
            this.sha256 = sha256;
            this.context = context;
            this.group = group;
            this.source = source;
            this.executionProfile = executionProfile;
            this.closeFence = new VerifiedCloseFence(() -> {
                if (this.group != null) this.group.close();
                else this.context.close(true);
            });
        }

        public long id() { return id; }
        public String codeUnitId() { return codeUnitId; }
        public String sha256() { return sha256; }
        public Context context() { return context; }
        public Source source() { return source; }
        public ExecutionProfile executionProfile() { return executionProfile; }
        public boolean started() { return started.get(); }
        public boolean closed() { return closed.get(); }

        /** Starts the staged generation exactly once.
         * An untrusted guest may return only a void main result here: never
         * export a guest Value or exception object to the trusted JVM.
         */
        public Value start() {
            if (closed.get()) throw new IllegalStateException("generation is closed");
            if (!started.compareAndSet(false, true)) throw new IllegalStateException("generation already started");
            if (group != null) {
                try (GuestAdmissionDeadline admission = new GuestAdmissionDeadline(
                        java.time.Duration.ofSeconds(30), group::close)) {
                    // Untrusted actor hot-loading must not execute arbitrary
                    // top-level main under the long Context CPU quota.
                    // Parse/link/static initialization is also time bounded:
                    // an infinite initializer triggers Context.close(true)
                    // from outside the guest execution monitor.
                    Value parsed = context.parse(source);
                    parsed.execute(dev.oreslang.nodes.OresEvalRootNode.LINK_ONLY_COMMAND);
                    parsed.execute(dev.oreslang.nodes.OresEvalRootNode.INIT_ONLY_COMMAND);
                    if (!admission.commitIfLive()) {
                        throw new IllegalStateException("untrusted hot-load admission deadline expired");
                    }
                    return null;
                } catch (RuntimeException guestFailure) {
                    try { close(); } catch (RuntimeException ignored) {
                        // Keep the original guest error and its host-reachable
                        // cause chain out of the supervisor API entirely.
                    }
                    throw new IllegalStateException(
                            "untrusted generation failed or isolated Context was revoked");
                }
            }
            try {
                return context.eval(source);
            } catch (RuntimeException failure) {
                close();
                throw failure;
            }
        }

        @Override
        public void close() {
            closed.set(true);
            closeFence.awaitConfirmedClose("hot generation Context");
        }
    }
}
