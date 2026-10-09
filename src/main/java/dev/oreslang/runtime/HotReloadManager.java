package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.nodes.OresEvalRootNode;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
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
 * Trusted/private contexts share one process code image and Graal code cache.
 * Adversarial contexts are private by default and may reuse a process-owned
 * immutable image only after an exact supervisor approval.
 */
public final class HotReloadManager implements AutoCloseable {
    private static final int MAX_ADVERSARIAL_SOURCE_CHARS = 1_048_576;
    private static final int MAX_ADVERSARIAL_CODE_UNIT_ID_CHARS = 512;
    private static final int MAX_ADVERSARIAL_LIVE_GENERATIONS = 8;

    private final IsolatePolicy supervisorPolicy;
    private final IsolatePolicy guestPolicy;
    private final ExecutionProfile executionProfile;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicReference<Generation> active = new AtomicReference<>();
    private final Map<String, Generation> activeByCodeUnit = new LinkedHashMap<>();
    private final Map<Long, Generation> generations = new LinkedHashMap<>();
    private volatile boolean managerClosed;

    public HotReloadManager(
            IsolatePolicy supervisorPolicy,
            ExecutionProfile executionProfile) {
        this(supervisorPolicy, supervisorPolicy, executionProfile);
    }

    public HotReloadManager(
            IsolatePolicy supervisorPolicy,
            IsolatePolicy guestPolicy,
            ExecutionProfile executionProfile) {
        this.supervisorPolicy = java.util.Objects.requireNonNull(supervisorPolicy);
        this.guestPolicy = java.util.Objects.requireNonNull(guestPolicy);
        this.executionProfile = java.util.Objects.requireNonNull(executionProfile);
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

    public IsolatePolicy supervisorPolicy() { return supervisorPolicy; }
    public IsolatePolicy guestPolicy() { return guestPolicy; }

    /**
     * Validates and stages a new generation without executing its entrypoint.
     */
    public synchronized Generation load(String name, String sourceText) {
        requireOpen();
        return loadInternal(name, sourceText, false, true);
    }

    private Generation loadInternal(
            String name,
            String sourceText,
            boolean initializeModuleOnly,
            boolean publish) {
        requireOpen();
        validateAdmissionInputs(name, sourceText);
        name = normalizeCodeUnitId(name);
        boolean shared = canShare(name, sourceText);
        if (!shared) {
            // Do not let matching bytes/path attach unapproved adversarial code
            // to the process-wide immutable AST/code-image cache.
            CapabilityChecker.check(
                    dev.oreslang.compiler.OresCompiler.parseAndTypeCheck(sourceText),
                    guestPolicy);
            return stage(name, digest(sourceText), sourceText, false, initializeModuleOnly, publish);
        }

        try (SharedCodeRegistry.Lease image =
                     SharedCodeRegistry.process().acquire(name, sourceText)) {
            CapabilityChecker.check(image.checkedProgram(), guestPolicy);
            return stage(name, image.sha256(), sourceText, true, initializeModuleOnly, publish);
        }
    }

    /**
     * Loads a named module and requires that its source explicitly declares
     * conformance to the requested module contract. Static type checking still
     * performs the full structural contract check; this gate makes the dynamic
     * loader's expected contract explicit as well.
     *
     * <p>Canonical source syntax is:
     * {@code define module Name conforms Contract as ... end}.</p>
     */
    public synchronized Generation loadModule(
            String codeUnitId,
            String moduleName,
            String sourceText,
            String contractName) {
        return loadModuleInternal(codeUnitId, moduleName, sourceText, contractName, true);
    }

    private Generation loadModuleInternal(
            String codeUnitId,
            String moduleName,
            String sourceText,
            String contractName,
            boolean publish) {
        requireOpen();
        validateAdmissionInputs(codeUnitId, sourceText);
        requireNonBlank(moduleName, "moduleName");
        requireNonBlank(contractName, "contractName");

        Ast.Program checked = OresCompiler.parseAndTypeCheck(sourceText);
        requireModuleContract(checked, moduleName, contractName);

        // This first loader version has no source-graph linker. Accepting imports
        // here would type-check opaque imports but initialize with no linked
        // dependency graph, so reject them rather than load a partial program.
        if (!checked.imports().isEmpty()) {
            throw new IllegalArgumentException(
                    "hot-loaded modules with imports require linked-code-unit loading, which is not enabled yet");
        }

        // Do not initialize unrelated named modules as a side effect of loading
        // one module. Module contracts may live in the synthetic root module.
        List<Ast.ModuleDecl> namedModules = checked.modules().stream()
                .filter(candidate -> !candidate.name().equals(dev.oreslang.parser.Parser.ROOT_MODULE))
                .toList();
        if (namedModules.size() != 1 || !namedModules.getFirst().name().equals(moduleName)) {
            throw new IllegalArgumentException(
                    "hot-loaded source must contain exactly one named module: '" + moduleName + "'");
        }

        Ast.ModuleDecl rootModule = checked.modules().stream()
                .filter(candidate -> candidate.name().equals(dev.oreslang.parser.Parser.ROOT_MODULE))
                .findFirst()
                .orElse(null);
        if (rootModule != null && rootModule.declarations().stream().anyMatch(declaration ->
                !(declaration instanceof Ast.InterfaceDecl iface && iface.moduleContract()))) {
            throw new IllegalArgumentException(
                    "hot-loaded source root scope may contain only module contract declarations");
        }

        return loadInternal(codeUnitId, sourceText, true, publish);
    }

    /**
     * Basic transactional hot replacement for one code unit. The candidate is
     * fully initialized before it becomes active. If startup fails, the previous
     * generation remains active. A replaced generation remains open for callers
     * pinned to it; the supervisor retires it explicitly after draining users.
     */
    public synchronized Generation reloadModule(
            String codeUnitId,
            String moduleName,
            String sourceText,
            String contractName) {
        requireOpen();
        String normalizedId = normalizeCodeUnitId(codeUnitId);
        // The previous generation remains pinned until explicit retirement.
        // The replacement candidate must not enter any active lookup before
        // successful initialization (including during re-entrant callbacks).
        Generation candidate = loadModuleInternal(
                normalizedId, moduleName, sourceText, contractName, false);
        try {
            candidate.start();
        } catch (RuntimeException | Error failure) {
            discardFailedCandidate(candidate, failure);
            throw failure;
        }
        activeByCodeUnit.put(normalizedId, candidate);
        active.set(candidate);
        return candidate;
    }

    private void discardFailedCandidate(Generation candidate, Throwable failure) {
        try {
            retire(candidate.id());
        } catch (RuntimeException | Error cleanupFailure) {
            if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
        }
    }

    private static void requireModuleContract(
            Ast.Program program,
            String moduleName,
            String contractName) {
        Ast.ModuleDecl module = program.modules().stream()
                .filter(candidate -> candidate.name().equals(moduleName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "hot-loaded module '" + moduleName + "' was not found"));

        Ast.TypeRef matched = module.contracts().stream()
                .filter(ref -> ref.name().equals(contractName))
                .findFirst()
                .orElse(null);
        if (matched == null) {
            throw new IllegalArgumentException(
                    "hot-loaded module '" + moduleName
                            + "' must declare 'conforms " + contractName + "'");
        }
        if (matched.inferArguments() || !matched.arguments().isEmpty()) {
            throw new IllegalArgumentException(
                    "generic module contracts are not supported by the name-only hot-load API: "
                            + contractName);
        }
    }

    private static void requireNonBlank(String value, String parameter) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(parameter + " cannot be blank");
        }
    }

    /**
     * Reuses an incrementally compiled unit. Static compilation work is reused
     * only when this destination is allowed to join the shared image.
     */
    public synchronized Generation load(IncrementalCompiler.CompiledUnit unit) {
        requireOpen();
        java.util.Objects.requireNonNull(unit, "unit");
        String codeUnitId = normalizeCodeUnitId(unit.unitId());
        validateAdmissionInputs(codeUnitId, unit.sourceText());
        boolean shared = canShare(codeUnitId, unit.sourceText());

        if (shared) {
            CapabilityChecker.check(unit.program(), guestPolicy);
        } else {
            // Never trust a compiled-unit object from another trust domain as
            // the admission artifact for unapproved adversarial execution.
            CapabilityChecker.check(
                    dev.oreslang.compiler.OresCompiler.parseAndTypeCheck(unit.sourceText()),
                    guestPolicy);
        }
        return stage(codeUnitId, unit.sourceDigest(), unit.sourceText(), shared, false, true);
    }

    private static String normalizeCodeUnitId(String codeUnitId) {
        if (codeUnitId == null || codeUnitId.isBlank()) {
            throw new IllegalArgumentException("codeUnitId cannot be blank");
        }
        try {
            String normalized = java.nio.file.Path.of(codeUnitId.replace('\\', '/'))
                    .normalize().toString().replace('\\', '/');
            if (normalized.isBlank() || normalized.equals(".")) {
                throw new IllegalArgumentException("codeUnitId must identify a code unit");
            }
            return normalized;
        } catch (java.nio.file.InvalidPathException invalidPath) {
            throw new IllegalArgumentException("invalid codeUnitId", invalidPath);
        }
    }

    private boolean canShare(String codeUnitId, String sourceText) {
        return !guestPolicy.adversarial()
                || SharedCodeRegistry.process()
                        .approvedForAdversarialSharing(codeUnitId, sourceText);
    }

    private void validateAdmissionInputs(String codeUnitId, String sourceText) {
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

    /**
     * Supervisor-only exact grant. Approval is bound to code-unit identity and
     * SHA-256 and is rejected unless the code also passes the untrusted policy.
     */
    public void approveGuestCodeSharing(String codeUnitId, String sourceText) {
        requireOpen();
        if (!guestPolicy.adversarial()) {
            throw new IllegalStateException(
                    "guest code-sharing approval is only required for adversarial guests");
        }
        validateAdmissionInputs(codeUnitId, sourceText);
        SharedCodeRegistry.process().approveForAdversarialSharing(
                supervisorPolicy, normalizeCodeUnitId(codeUnitId), sourceText);
    }

    public void revokeGuestCodeSharing(String codeUnitId, String sourceText) {
        requireOpen();
        if (!guestPolicy.adversarial()) {
            throw new IllegalStateException(
                    "guest code-sharing revocation is only required for adversarial guests");
        }
        validateAdmissionInputs(codeUnitId, sourceText);
        SharedCodeRegistry.process().revokeForAdversarialSharing(
                supervisorPolicy, normalizeCodeUnitId(codeUnitId), sourceText);
    }

    private Generation stage(
            String codeUnitId,
            String sourceDigest,
            String sourceText,
            boolean shareCodeImage,
            boolean initializeModuleOnly,
            boolean publish) {
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
                codeImage = SharedCodeRegistry.process().acquire(codeUnitId, sourceText);
                if (!codeImage.sha256().equals(sourceDigest)) {
                    throw new IllegalArgumentException(
                            "compiled code-unit source digest mismatch");
                }
                source = codeImage.source();
            } else {
                if (!digest(sourceText).equals(sourceDigest)) {
                    throw new IllegalArgumentException(
                            "compiled code-unit source digest mismatch");
                }
                // cached(false) is defense in depth: even if a future embedder
                // accidentally gives adversarial contexts a shared Engine,
                // unapproved source still cannot enter that Engine's code cache.
                source = Source.newBuilder(
                                dev.oreslang.OresLanguage.ID,
                                sourceText,
                                codeUnitId)
                        .mimeType(dev.oreslang.OresLanguage.MIME_TYPE)
                        .cached(false)
                        .buildLiteral();
            }

            Generation generation = new Generation(
                    id,
                    codeUnitId,
                    sourceDigest,
                    context,
                    source,
                    codeImage,
                    executionProfile,
                    shareCodeImage,
                    initializeModuleOnly);
            generations.put(id, generation);
            if (publish) {
                activeByCodeUnit.put(codeUnitId, generation);
                active.set(generation);
            }
            return generation;
        } catch (RuntimeException | Error failure) {
            try {
                context.close(true);
            } catch (RuntimeException | Error closeFailure) {
                failure.addSuppressed(closeFailure);
            } finally {
                if (codeImage != null) {
                    try {
                        codeImage.close();
                    } catch (RuntimeException | Error leaseFailure) {
                        failure.addSuppressed(leaseFailure);
                    }
                }
            }
            throw failure;
        }
    }

    public synchronized Generation loadAndStart(String name, String sourceText) {
        requireOpen();
        Generation candidate = loadInternal(name, sourceText, false, false);
        try {
            candidate.start();
        } catch (RuntimeException | Error failure) {
            discardFailedCandidate(candidate, failure);
            throw failure;
        }
        activeByCodeUnit.put(candidate.codeUnitId(), candidate);
        active.set(candidate);
        return candidate;
    }

    /** Last generation staged, retained for compatibility with the single-unit API. */
    public synchronized Generation active() {
        Generation current = active.get();
        return current != null && current.closed() ? null : current;
    }

    /** Active generation for one independently compiled code unit. */
    public synchronized Generation active(String codeUnitId) {
        Generation current = activeByCodeUnit.get(normalizeCodeUnitId(codeUnitId));
        return current != null && current.closed() ? null : current;
    }

    public synchronized Map<String, Generation> activeGenerations() {
        Map<String, Generation> live = new LinkedHashMap<>();
        activeByCodeUnit.forEach((id, generation) -> {
            if (!generation.closed()) live.put(id, generation);
        });
        return Map.copyOf(live);
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
        if (managerClosed) return;
        managerClosed = true;
        RuntimeException runtimeFailure = null;
        Error fatalFailure = null;
        try {
            for (Generation generation : List.copyOf(generations.values())) {
                try {
                    generation.close();
                } catch (RuntimeException failure) {
                    if (runtimeFailure == null) runtimeFailure = failure;
                    else runtimeFailure.addSuppressed(failure);
                } catch (Error failure) {
                    if (fatalFailure == null) fatalFailure = failure;
                    else fatalFailure.addSuppressed(failure);
                }
            }
        } finally {
            generations.clear();
            activeByCodeUnit.clear();
            active.set(null);
        }
        if (fatalFailure != null) {
            if (runtimeFailure != null) fatalFailure.addSuppressed(runtimeFailure);
            throw fatalFailure;
        }
        if (runtimeFailure != null) throw runtimeFailure;
    }

    private void requireOpen() {
        if (managerClosed) {
            throw new IllegalStateException("hot-reload manager is closed");
        }
    }

    private static String digest(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
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
        private final boolean initializeModuleOnly;
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();

        private Generation(
                long id,
                String codeUnitId,
                String sha256,
                Context context,
                Source source,
                SharedCodeRegistry.Lease codeImage,
                ExecutionProfile executionProfile,
                boolean sharedCodeImage,
                boolean initializeModuleOnly) {
            this.id = id;
            this.codeUnitId = codeUnitId;
            this.sha256 = sha256;
            this.context = context;
            this.source = source;
            this.codeImage = codeImage;
            this.executionProfile = executionProfile;
            this.sharedCodeImage = sharedCodeImage;
            this.initializeModuleOnly = initializeModuleOnly;
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
        public synchronized Value start() {
            if (closed.get()) throw new IllegalStateException("generation is closed");
            if (!started.compareAndSet(false, true)) {
                throw new IllegalStateException("generation already started");
            }
            try {
                if (initializeModuleOnly) {
                    // parse(...).execute(command) enters the Oreslang root with
                    // its supervisor-only init command: link + initialize the
                    // module source without invoking the program's main().
                    return context.parse(source).execute(OresEvalRootNode.INIT_ONLY_COMMAND);
                }
                return context.eval(source);
            } catch (RuntimeException | Error failure) {
                try {
                    close();
                } catch (RuntimeException | Error cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        }

        @Override
        public synchronized void close() {
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
