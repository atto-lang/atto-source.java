package dev.oreslang.runtime;

import dev.oreslang.ast.AnnotationExpander;
import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.nodes.OresEvalRootNode;
import dev.oreslang.parser.Parser;
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
 * parsed/type/capability checked, then assigned a fresh context. Legacy
 * code-unit loading preserves its active-slot behavior; module loading stages
 * and initializes a candidate before publishing it as active.
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
        return loadInternal(name, sourceText, false, true, null, null, null);
    }

    private Generation loadInternal(
            String name,
            String sourceText,
            boolean initializeModuleOnly,
            boolean activateImmediately,
            String moduleName,
            String contractName,
            String moduleContractDigest) {
        validateAdmissionInputs(name, sourceText);
        ensureGenerationCapacity();
        name = normalizeCodeUnitId(name);
        boolean shared = canShare(name, sourceText);
        if (!shared) {
            // Do not let matching bytes/path attach unapproved adversarial code
            // to the process-wide immutable AST/code-image cache.
            CapabilityChecker.check(
                    OresCompiler.parseAndTypeCheck(sourceText),
                    guestPolicy);
            return stage(name, digest(sourceText), sourceText, false,
                    initializeModuleOnly, activateImmediately,
                    moduleName, contractName, moduleContractDigest);
        }

        try (SharedCodeRegistry.Lease image =
                     SharedCodeRegistry.process().acquire(name, sourceText)) {
            CapabilityChecker.check(image.checkedProgram(), guestPolicy);
            return stage(name, image.sha256(), sourceText, true,
                    initializeModuleOnly, activateImmediately,
                    moduleName, contractName, moduleContractDigest);
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
        validateAdmissionInputs(codeUnitId, sourceText);
        ensureGenerationCapacity();
        requireNonBlank(moduleName, "moduleName");
        requireNonBlank(contractName, "contractName");

        // Inspect the annotation-expanded declaration graph before admission,
        // but leave full type/capability checking to loadInternal so a candidate
        // is not type-checked twice on the normal path.
        Ast.Program checked = AnnotationExpander.expand(Parser.parse(sourceText));
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

        String moduleContractDigest = moduleContractSetDigest(checked, moduleName);
        return loadInternal(
                codeUnitId,
                sourceText,
                true,
                false,
                moduleName,
                contractName,
                moduleContractDigest);
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
        validateAdmissionInputs(codeUnitId, sourceText);
        codeUnitId = normalizeCodeUnitId(codeUnitId);
        Generation previous = activeByCodeUnit.get(codeUnitId);
        Generation previousGlobalActive = active.get();
        Generation candidate = loadModule(codeUnitId, moduleName, sourceText, contractName);
        try {
            initializeModule(candidate);
            activateModule(candidate);
        } catch (RuntimeException | Error failure) {
            // Cleanup must never prevent restoration. A failing Context.close()
            // is secondary to the startup error and must not strand the manager
            // on a failed candidate.
            try {
                retire(candidate.id());
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            } finally {
                if (previous != null && !previous.closed()) {
                    activeByCodeUnit.put(codeUnitId, previous);
                } else {
                    activeByCodeUnit.remove(codeUnitId);
                }
                // The single-unit convenience pointer may have referred to an
                // entirely different code unit before this attempted upgrade.
                // Restore that exact pointer, not merely this code unit's prior
                // generation.
                active.set(previousGlobalActive != null && !previousGlobalActive.closed()
                        ? previousGlobalActive
                        : null);
            }
            throw failure;
        }
        // Do not close the previous context here: actors/requests may still
        // be pinned to its generation. It is no longer active for new work,
        // but stays in the generation registry until the supervisor confirms
        // that all old-generation users have drained and calls retire(id).
        return candidate;
    }

    /**
     * Initializes a staged module candidate without publishing it. Failed
     * candidates are retired automatically so failed contexts and code-image
     * leases do not count against the live-generation budget.
     */
    public synchronized Generation initializeModule(Generation generation) {
        java.util.Objects.requireNonNull(generation, "generation");
        if (generations.get(generation.id()) != generation) {
            throw new IllegalArgumentException("generation is not owned by this hot-reload manager");
        }
        if (!generation.initializeModuleOnly) {
            throw new IllegalArgumentException("generation was not loaded through loadModule");
        }
        if (generation.closed()) {
            throw new IllegalStateException("cannot initialize a closed generation");
        }
        // Repeated supervisor calls must never retire a generation that is
        // already active. Return active generations idempotently.
        if (activeByCodeUnit.get(generation.codeUnitId()) == generation) {
            if (!generation.started()) {
                throw new IllegalStateException("active module generation is not initialized");
            }
            return generation;
        }
        if (generation.started()) {
            try {
                requireUnchangedActivationBase(generation);
                return generation;
            } catch (RuntimeException | Error failure) {
                try {
                    retire(generation.id());
                } catch (RuntimeException | Error cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        }
        try {
            requireUnchangedActivationBase(generation);
            generation.startModule();
            return generation;
        } catch (RuntimeException | Error failure) {
            try {
                retire(generation.id());
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /**
     * Publishes a successfully initialized module generation as active.
     * Staging and initialization are intentionally separate from publication.
     */
    public synchronized Generation activateModule(Generation generation) {
        java.util.Objects.requireNonNull(generation, "generation");
        if (generations.get(generation.id()) != generation) {
            throw new IllegalArgumentException("generation is not owned by this hot-reload manager");
        }
        if (!generation.initializeModuleOnly) {
            throw new IllegalArgumentException("only a module-loaded generation can be activated as a module");
        }
        if (generation.closed()) {
            throw new IllegalStateException("cannot activate a closed generation");
        }
        if (!generation.started()) {
            throw new IllegalStateException("module generation must initialize successfully before activation");
        }
        if (activeByCodeUnit.get(generation.codeUnitId()) == generation) {
            return generation;
        }
        try {
            requireUnchangedActivationBase(generation);
        } catch (RuntimeException | Error failure) {
            try {
                retire(generation.id());
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        activeByCodeUnit.put(generation.codeUnitId(), generation);
        active.set(generation);
        return generation;
    }

    private void requireUnchangedActivationBase(Generation generation) {
        Generation current = activeByCodeUnit.get(generation.codeUnitId());
        long currentId = current == null ? 0L : current.id();
        if (currentId != generation.expectedPreviousActiveId) {
            throw new IllegalStateException(
                    "stale module candidate for '" + generation.codeUnitId()
                            + "': the active generation changed after this candidate was staged");
        }
        if (current != null && current.closed()) {
            throw new IllegalStateException(
                    "active generation for '" + generation.codeUnitId()
                            + "' is closed; retire it before activating a replacement");
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
        java.util.Objects.requireNonNull(unit, "unit");
        validateAdmissionInputs(unit.unitId(), unit.sourceText());
        ensureGenerationCapacity();
        String codeUnitId = normalizeCodeUnitId(unit.unitId());
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
        return stage(
                codeUnitId, unit.sourceDigest(), unit.sourceText(), shared,
                false, true, null, null, null);
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

    private void ensureGenerationCapacity() {
        if (guestPolicy.adversarial()
                && generations.size() >= MAX_ADVERSARIAL_LIVE_GENERATIONS) {
            throw new IllegalStateException(
                    "adversarial hot-load live generation limit exceeded: "
                            + MAX_ADVERSARIAL_LIVE_GENERATIONS);
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
        if (!guestPolicy.adversarial()) {
            throw new IllegalStateException(
                    "guest code-sharing approval is only required for adversarial guests");
        }
        validateAdmissionInputs(codeUnitId, sourceText);
        SharedCodeRegistry.process().approveForAdversarialSharing(
                supervisorPolicy, normalizeCodeUnitId(codeUnitId), sourceText);
    }

    public void revokeGuestCodeSharing(String codeUnitId, String sourceText) {
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
            boolean activateImmediately,
            String moduleName,
            String contractName,
            String moduleContractDigest) {
        ensureGenerationCapacity();

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

            Generation expectedPreviousActive =
                    initializeModuleOnly && !activateImmediately
                            ? activeByCodeUnit.get(codeUnitId)
                            : null;
            long expectedPreviousActiveId =
                    expectedPreviousActive == null ? 0L : expectedPreviousActive.id();
            Generation generation = new Generation(
                    this,
                    id,
                    codeUnitId,
                    sourceDigest,
                    context,
                    source,
                    codeImage,
                    executionProfile,
                    shareCodeImage,
                    initializeModuleOnly,
                    expectedPreviousActiveId,
                    moduleName,
                    contractName,
                    moduleContractDigest);
            generations.put(id, generation);
            if (activateImmediately) {
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
        Generation generation = load(name, sourceText);
        generation.start();
        return generation;
    }

    /** Last selected active generation, retained for the single-unit compatibility API. */
    public synchronized Generation active() { return active.get(); }

    /** Active generation for one independently compiled code unit. */
    public synchronized Generation active(String codeUnitId) {
        return activeByCodeUnit.get(normalizeCodeUnitId(codeUnitId));
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
        private final HotReloadManager owner;
        private final long id;
        private final String codeUnitId;
        private final String sha256;
        private final Context context;
        private final Source source;
        private final SharedCodeRegistry.Lease codeImage;
        // Keep one parsed executable per generation. For unapproved/adversarial
        // source the Source is deliberately cached(false); reparsing on each
        // invocation would create a fresh Evaluator and lose initialized state.
        private Value parsedProgram;
        private final ExecutionProfile executionProfile;
        private final boolean sharedCodeImage;
        private final boolean initializeModuleOnly;
        // Store only a generation ID, not a Generation reference: an active
        // upgraded generation must not keep every retired predecessor/context
        // alive through a chain of expectedPreviousActive pointers.
        private final long expectedPreviousActiveId;
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();

        private Generation(
                HotReloadManager owner,
                long id,
                String codeUnitId,
                String sha256,
                Context context,
                Source source,
                SharedCodeRegistry.Lease codeImage,
                ExecutionProfile executionProfile,
                boolean sharedCodeImage,
                boolean initializeModuleOnly,
                long expectedPreviousActiveId) {
            this.owner = java.util.Objects.requireNonNull(owner, "owner");
            this.id = id;
            this.codeUnitId = codeUnitId;
            this.sha256 = sha256;
            this.context = context;
            this.source = source;
            this.codeImage = codeImage;
            this.executionProfile = executionProfile;
            this.sharedCodeImage = sharedCodeImage;
            this.initializeModuleOnly = initializeModuleOnly;
            this.expectedPreviousActiveId = expectedPreviousActiveId;
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

        /** Starts a legacy code-unit generation exactly once. */
        public Value start() {
            boolean startAttempted = false;
            try {
                synchronized (this) {
                    if (initializeModuleOnly) {
                        throw new IllegalStateException(
                                "module generations must be initialized through HotReloadManager.initializeModule");
                    }
                    if (closed.get()) throw new IllegalStateException("generation is closed");
                    if (started.get()) throw new IllegalStateException("generation already started");
                    startAttempted = true;
                    return startExecution();
                }
            } catch (RuntimeException | Error failure) {
                // Cleanup only a failed first execution, not a repeated-start
                // misuse. The synchronized block has exited before close()
                // retires this generation from its owner, avoiding lock inversion.
                if (startAttempted) {
                    try {
                        close();
                    } catch (RuntimeException | Error cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
                throw failure;
            }
        }

        /**
         * Invokes a public function from this initialized code generation.
         * Keeping invocation generation-scoped lets existing callers finish
         * against an older generation while a replacement is active.
         */
        public synchronized Value invokePublic(String functionName, Object... arguments) {
            requireNonBlank(functionName, "functionName");
            java.util.Objects.requireNonNull(arguments, "arguments");
            if (closed.get()) throw new IllegalStateException("generation is closed");
            if (!started.get()) {
                throw new IllegalStateException("generation must be initialized before public invocation");
            }
            Object[] commandArguments = new Object[arguments.length + 2];
            commandArguments[0] = OresEvalRootNode.INVOKE_PUBLIC_COMMAND;
            commandArguments[1] = functionName;
            System.arraycopy(arguments, 0, commandArguments, 2, arguments.length);
            return parsedProgram().execute(commandArguments);
        }

        private synchronized Value startModule() {
            if (!initializeModuleOnly) {
                throw new IllegalStateException("generation was not loaded through loadModule");
            }
            return startExecution();
        }

        private Value startExecution() {
            if (closed.get()) throw new IllegalStateException("generation is closed");
            if (!started.compareAndSet(false, true)) {
                throw new IllegalStateException("generation already started");
            }
            if (initializeModuleOnly) {
                // parse(...).execute(command) enters the Oreslang root with
                // its supervisor-only init command: link + initialize the
                // module source without invoking the program's main().
                return parsedProgram().execute(OresEvalRootNode.INIT_ONLY_COMMAND);
            }
            // Execute the same parsed handle used for later public calls so
            // its context-local Evaluator/module state is stable even when
            // Graal Source caching is disabled for an adversarial generation.
            return parsedProgram().execute();
        }

        private Value parsedProgram() {
            if (parsedProgram == null) parsedProgram = context.parse(source);
            return parsedProgram;
        }

        @Override
        public void close() {
            boolean transitioned = false;
            Throwable failure = null;
            synchronized (this) {
                if (closed.compareAndSet(false, true)) {
                    transitioned = true;
                    try {
                        context.close(true);
                    } catch (RuntimeException | Error contextFailure) {
                        failure = contextFailure;
                    } finally {
                        if (codeImage != null) {
                            try {
                                codeImage.close();
                            } catch (RuntimeException | Error leaseFailure) {
                                if (failure == null) failure = leaseFailure;
                                else failure.addSuppressed(leaseFailure);
                            }
                        }
                    }
                }
            }

            // Publicly closing a Generation also removes its active slot and
            // registry entry. Do this only after releasing the generation
            // monitor: manager retirement acquires the manager monitor first.
            if (transitioned) {
                try {
                    owner.retire(id);
                } catch (RuntimeException | Error cleanupFailure) {
                    if (failure == null) failure = cleanupFailure;
                    else failure.addSuppressed(cleanupFailure);
                }
            }
            if (failure instanceof Error error) throw error;
            if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
        }
    }
}
