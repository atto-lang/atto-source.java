package dev.oreslang.runtime;

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
    private final UntrustedCodeShareAllowlist untrustedCodeShareAllowlist;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicReference<Generation> active = new AtomicReference<>();
    private final Map<String, Generation> activeByCodeUnit = new LinkedHashMap<>();
    private final Map<Long, Generation> generations = new LinkedHashMap<>();

    public HotReloadManager(
            IsolatePolicy supervisorPolicy,
            ExecutionProfile executionProfile) {
        this(supervisorPolicy, supervisorPolicy, executionProfile, UntrustedCodeShareAllowlist.none());
    }

    public HotReloadManager(
            IsolatePolicy supervisorPolicy,
            IsolatePolicy guestPolicy,
            ExecutionProfile executionProfile) {
        this(supervisorPolicy, guestPolicy, executionProfile, UntrustedCodeShareAllowlist.none());
    }

    public HotReloadManager(
            IsolatePolicy supervisorPolicy,
            IsolatePolicy guestPolicy,
            ExecutionProfile executionProfile,
            UntrustedCodeShareAllowlist untrustedCodeShareAllowlist) {
        this.supervisorPolicy =
                java.util.Objects.requireNonNull(supervisorPolicy);
        this.guestPolicy =
                java.util.Objects.requireNonNull(guestPolicy);
        this.executionProfile =
                java.util.Objects.requireNonNull(executionProfile);
        this.untrustedCodeShareAllowlist =
                java.util.Objects.requireNonNull(untrustedCodeShareAllowlist);
        this.supervisorPolicy.require(
                IsolatePolicy.Capability.HOT_CODE_LOAD,
                "HotReloadManager");
    }

    public static HotReloadManager forUntrustedActors(
            IsolatePolicy supervisorPolicy,
            ExecutionProfile executionProfile) {
        return forUntrustedActors(
                supervisorPolicy, executionProfile, UntrustedCodeShareAllowlist.none());
    }

    public static HotReloadManager forUntrustedActors(
            IsolatePolicy supervisorPolicy,
            ExecutionProfile executionProfile,
            UntrustedCodeShareAllowlist untrustedCodeShareAllowlist) {
        return new HotReloadManager(
                supervisorPolicy,
                IsolatePolicy.untrustedActor(),
                executionProfile,
                untrustedCodeShareAllowlist);
    }

    public IsolatePolicy supervisorPolicy() { return supervisorPolicy; }
    public IsolatePolicy guestPolicy() { return guestPolicy; }

    /**
     * Validates and stages a new generation without executing its entrypoint.
     */
    public synchronized Generation load(String name, String sourceText) {
        validateAdmissionInputs(name, sourceText);
        boolean shared = !guestPolicy.adversarial()
                || SharedCodeRegistry.process()
                        .approvedForAdversarialSharing(name, sourceText);
        if (!shared) {
            // Unapproved adversarial code is intentionally parsed/checked on a
            // private path and cannot attach to a trusted process-wide image.
            CapabilityChecker.check(
                    dev.oreslang.compiler.OresCompiler.parseAndTypeCheck(sourceText),
                    guestPolicy);
            return stage(name, digest(sourceText), sourceText, false);
        }
        try (SharedCodeRegistry.Lease image =
                     SharedCodeRegistry.process().acquire(name, sourceText)) {
            CapabilityChecker.check(image.checkedProgram(), guestPolicy);
            return stage(name, image.sha256(), sourceText, true);
        }
    }

    /**
     * Reuses an incrementally compiled unit. Static compilation work is reused,
     * while capability admission is deliberately repeated for the destination
     * isolate because authority belongs to the runtime policy, not the cache.
     */
    public synchronized Generation load(IncrementalCompiler.CompiledUnit unit) {
        java.util.Objects.requireNonNull(unit, "unit");
        validateAdmissionInputs(unit.unitId(), unit.sourceText());
        boolean shared = !guestPolicy.adversarial()
                || SharedCodeRegistry.process().approvedForAdversarialSharing(
                        unit.unitId(), unit.sourceText());
        if (shared) {
            CapabilityChecker.check(unit.program(), guestPolicy);
        } else {
            CapabilityChecker.check(
                    dev.oreslang.compiler.OresCompiler.parseAndTypeCheck(unit.sourceText()),
                    guestPolicy);
        }
        return stage(unit.unitId(), unit.sourceDigest(), unit.sourceText(), shared);
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

    public void approveGuestCodeSharing(String codeUnitId, String sourceText) {
        if (!guestPolicy.adversarial()) {
            throw new IllegalStateException(
                    "guest code-sharing approval is only required for adversarial guests");
        }
        SharedCodeRegistry.process().approveForAdversarialSharing(
                supervisorPolicy, codeUnitId, sourceText);
    }

    public void revokeGuestCodeSharing(String codeUnitId, String sourceText) {
        if (!guestPolicy.adversarial()) {
            throw new IllegalStateException(
                    "guest code-sharing revocation is only required for adversarial guests");
        }
        SharedCodeRegistry.process().revokeForAdversarialSharing(
                supervisorPolicy, codeUnitId, sourceText);
    }

    private Generation stage(
            String codeUnitId,
            String sourceDigest,
            String sourceText,
            boolean shareCodeImage) {
        if (guestPolicy.adversarial()
                && generations.size() >= MAX_ADVERSARIAL_LIVE_GENERATIONS) {
            throw new IllegalStateException(
                    "adversarial hot-load live generation limit exceeded: "
                            + MAX_ADVERSARIAL_LIVE_GENERATIONS);
        }
        long id = sequence.incrementAndGet();
        Context context =
                guestPolicy.restrictedContextBuilder(executionProfile).build();
        SharedCodeRegistry.Lease codeImage = null;
        try {
            Source source;
            if (shareCodeImage) {
                // One immutable image is reused across eligible contexts.
                codeImage = untrusted
                    ? SharedCodeRegistry.process().acquireUntrusted(
                            codeUnitId, sourceText, untrustedCodeShareAllowlist)
                    : SharedCodeRegistry.process().acquire(codeUnitId, sourceText);
                if (!codeImage.sha256().equals(sourceDigest)) {
                    throw new IllegalArgumentException("compiled code-unit source digest mismatch");
                }
                source = codeImage.source();
            } else {
                // Deliberately unique source identity: OresLanguage.parse cannot
                // attach this adversarial load to a trusted shared AST.
                if (!digest(sourceText).equals(sourceDigest)) {
                    throw new IllegalArgumentException("compiled code-unit source digest mismatch");
                }
                source = Source.newBuilder(dev.oreslang.OresLanguage.ID, sourceText, codeUnitId)
                        .mimeType(dev.oreslang.OresLanguage.MIME_TYPE)
                        .buildLiteral();
            }
            Generation generation = new Generation(
                    id, codeUnitId, sourceDigest, context, source, codeImage,
                    executionProfile, shareCodeImage);
            generations.put(id, generation);
            activeByCodeUnit.put(codeUnitId, generation);
            active.set(generation);
            return generation;
        } catch (RuntimeException | Error failure) {
            try {
                context.close(true);
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            } finally {
                if (codeImage != null) codeImage.close();
            }
            throw failure;
        }
    }

    private Generation stageUnshared(
            String codeUnitId, String sourceDigest, String sourceText) {
        if (guestPolicy.adversarial()
                && generations.size() >= MAX_ADVERSARIAL_LIVE_GENERATIONS) {
            throw new IllegalStateException(
                    "adversarial hot-load live generation limit exceeded: "
                            + MAX_ADVERSARIAL_LIVE_GENERATIONS);
        }
        long id = sequence.incrementAndGet();
        Context context =
                guestPolicy.restrictedContextBuilder(executionProfile).build();
        try {
            // Fail closed for unapproved adversarial source. cached(false)
            // prevents accidental engine-level parse/JIT reuse if the embedding
            // configuration later changes to a shared sandbox engine.
            Source source = Source.newBuilder(
                            dev.oreslang.OresLanguage.ID, sourceText, codeUnitId)
                    .mimeType(dev.oreslang.OresLanguage.MIME_TYPE)
                    .cached(false)
                    .buildLiteral();
            Generation generation = new Generation(
                    id, codeUnitId, sourceDigest, context, source, null, executionProfile);
            generations.put(id, generation);
            activeByCodeUnit.put(codeUnitId, generation);
            active.set(generation);
            return generation;
        } catch (RuntimeException | Error failure) {
            try {
                context.close(true);
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    public synchronized Generation loadAndStart(String name, String sourceText) {
        Generation generation = load(name, sourceText);
        generation.start();
        return generation;
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
        Generation generation = generations.remove(generationId);
        if (generation != null) {
            active.compareAndSet(generation, null);
            activeByCodeUnit.remove(generation.codeUnitId(), generation);
            generation.close();
        }
    }

    public synchronized int liveGenerations() { return generations.size(); }

    @Override
    public synchronized void close() {
        for (Generation generation : generations.values()) generation.close();
        generations.clear();
        activeByCodeUnit.clear();
        active.set(null);
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
        private final Source source;
        private final SharedCodeRegistry.Lease codeImage;
        private final ExecutionProfile executionProfile;
        private final boolean sharedCodeImage;
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();

        private Generation(long id, String codeUnitId, String sha256,
                           Context context, Source source,
                           SharedCodeRegistry.Lease codeImage,
                           ExecutionProfile executionProfile,
                           boolean sharedCodeImage) {
            this.id = id;
            this.codeUnitId = codeUnitId;
            this.sha256 = sha256;
            this.context = context;
            this.source = source;
            this.codeImage = codeImage;
            this.executionProfile = executionProfile;
            this.sharedCodeImage = sharedCodeImage;
        }

        public long id() { return id; }
        public String codeUnitId() { return codeUnitId; }
        public String sha256() { return sha256; }
        public Context context() { return context; }
        public Source source() { return source; }
        public ExecutionProfile executionProfile() { return executionProfile; }
        public boolean sharedCodeImage() { return sharedCodeImage; }
        public boolean started() { return started.get(); }
        public boolean closed() { return closed.get(); }

        /** Starts the staged generation exactly once. */
        public Value start() {
            if (closed.get()) throw new IllegalStateException("generation is closed");
            if (!started.compareAndSet(false, true)) throw new IllegalStateException("generation already started");
            try {
                return context.eval(source);
            } catch (RuntimeException failure) {
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
                    if (codeImage != null) codeImage.close();
                }
            }
        }
    }
}
