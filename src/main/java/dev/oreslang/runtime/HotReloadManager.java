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
        } catch (RuntimeException failure) {
            context.close(true);
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

    public synchronized Map<String, Generation> activeGenerations() {
        return Map.copyOf(activeByCodeUnit);
    }

    /** Explicit retirement permits old actors/requests to drain before teardown. */
    public void retire(long generationId) {
        final Generation generation;
        synchronized (this) {
            generation = generations.get(generationId);
            if (generation != null) {
                boolean selected = active.get() == generation
                        || activeByCodeUnit.get(generation.codeUnitId()) == generation;
                if (selected) requireRoutingEpochCapacity();
                generations.remove(generationId, generation);
                boolean removed = active.compareAndSet(generation, null);
                removed |= activeByCodeUnit.remove(generation.codeUnitId(), generation);
                if (removed) ++routingEpoch;
            }
        }
        // Context close can call guest-host callbacks; never do that while
        // holding the supervisor routing monitor.
        if (generation != null) generation.close();
    }

    public synchronized int liveGenerations() { return generations.size(); }

    /** Closed guest contexts must not remain in the manager's active registry. */
    private synchronized void onGenerationClosed(Generation generation) {
        generations.remove(generation.id(), generation);
        boolean selected = activeByCodeUnit.get(generation.codeUnitId()) == generation
                || active.get() == generation;
        if (selected) requireRoutingEpochCapacity();
        boolean removed = activeByCodeUnit.remove(generation.codeUnitId(), generation);
        removed |= active.compareAndSet(generation, null);
        if (removed && !closed) ++routingEpoch;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("hot reload manager is closed");
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        // Snapshot first: each Generation.close() re-enters onGenerationClosed.
        java.util.List<Generation> toClose = java.util.List.copyOf(generations.values());
        generations.clear();
        activeByCodeUnit.clear();
        active.set(null);
        Throwable failure = null;
        for (Generation generation : toClose) {
            try {
                generation.close();
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
        private final AtomicBoolean closed = new AtomicBoolean();

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
        public boolean closed() { return closed.get(); }

        /** Starts the staged generation exactly once. */
        public Value start() {
            if (closed.get()) throw new IllegalStateException("generation is closed");
            if (!started.compareAndSet(false, true)) throw new IllegalStateException("generation already started");
            try {
                return context.eval(source);
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    context.close(true);
                } finally {
                    HotReloadManager manager = owner.get();
                    if (manager != null) {
                        manager.onGenerationClosed(this);
                    }
                }
            }
        }
    }
}
