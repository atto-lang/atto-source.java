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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private record ModuleContractSurface(String digest, Set<String> callableKeys) {
        private ModuleContractSurface {
            callableKeys = Set.copyOf(callableKeys);
        }
    }

    private final IsolatePolicy supervisorPolicy;
    private final IsolatePolicy guestPolicy;
    private final ExecutionProfile executionProfile;
    // Unforgeable manager-local identity, never derived from guest-controlled paths.
    private final Object sharingAuthority = new Object();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicReference<Generation> active = new AtomicReference<>();
    private final Map<String, Generation> activeByCodeUnit = new LinkedHashMap<>();
    private final Map<Long, Generation> generations = new LinkedHashMap<>();
    // Guarded by this manager's monitor. Close is terminal: no later load or
    // activation may repopulate the maps after shutdown has detached generations.
    private boolean managerClosed;

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
        ensureManagerOpen();
        return loadInternal(name, sourceText, false, true, null, null, null);
    }

    private Generation loadInternal(
            String name,
            String sourceText,
            boolean initializeModuleOnly,
            boolean activateImmediately,
            String moduleName,
            String contractName,
            ModuleContractSurface contractSurface) {
        ensureManagerOpen();
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
                    moduleName, contractName, contractSurface);
        }

        try (SharedCodeRegistry.Lease image =
                     SharedCodeRegistry.process().acquire(name, sourceText)) {
            CapabilityChecker.check(image.checkedProgram(), guestPolicy);
            return stage(name, image.sha256(), sourceText, true,
                    initializeModuleOnly, activateImmediately,
                    moduleName, contractName, contractSurface);
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
        ensureManagerOpen();
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

        ModuleContractSurface contractSurface = moduleContractSurface(checked, moduleName);
        return loadInternal(
                codeUnitId,
                sourceText,
                true,
                false,
                moduleName,
                contractName,
                contractSurface);
    }

    /**
     * Basic transactional hot replacement for one code unit. The candidate is
     * fully initialized before it becomes active. If startup fails, the previous
     * generation remains active. A replaced generation remains open for callers
     * pinned to it; the supervisor retires it explicitly after draining users.
     */
    public Generation reloadModule(
            String codeUnitId,
            String moduleName,
            String sourceText,
            String contractName) {
        validateAdmissionInputs(codeUnitId, sourceText);
        Generation candidate = loadModule(
                normalizeCodeUnitId(codeUnitId), moduleName, sourceText, contractName);
        try {
            // Never run guest module initialization while holding the manager
            // monitor. Candidate publication is the final, short synchronized
            // step and stale concurrent upgrades fail without disturbing the winner.
            initializeModule(candidate);
            activateModule(candidate);
            return candidate;
        } catch (RuntimeException | Error failure) {
            try {
                retire(candidate.id());
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /**
     * Initializes a staged module candidate without publishing it. Failed
     * candidates are retired automatically so failed contexts and code-image
     * leases do not count against the live-generation budget.
     */
    public Generation initializeModule(Generation generation) {
        java.util.Objects.requireNonNull(generation, "generation");
        Throwable admissionFailure = null;
        boolean mustInitialize = false;

        synchronized (this) {
            ensureManagerOpen();
            if (generations.get(generation.id()) != generation) {
                throw new IllegalArgumentException("generation is not owned by this hot-reload manager");
            }
            if (!generation.initializeModuleOnly) {
                throw new IllegalArgumentException("generation was not loaded through loadModule");
            }
            if (generation.retiring.get() || generation.closed()) {
                throw new IllegalStateException("cannot initialize a retiring/closed generation");
            }
            if (activeByCodeUnit.get(generation.codeUnitId()) == generation) {
                if (!generation.started() || generation.initializing.get()) {
                    throw new IllegalStateException("active module generation is not fully initialized");
                }
                return generation;
            }
            if (generation.initializing.get()) {
                throw new IllegalStateException("module generation initialization is already in progress");
            }
            if (generation.started()) {
                try {
                    requireUnchangedActivationBase(generation);
                } catch (RuntimeException | Error failure) {
                    admissionFailure = failure;
                }
            } else if (generation.initializing.compareAndSet(false, true)) {
                mustInitialize = true;
                try {
                    requireUnchangedActivationBase(generation);
                } catch (RuntimeException | Error failure) {
                    generation.initializing.set(false);
                    mustInitialize = false;
                    admissionFailure = failure;
                }
            } else {
                throw new IllegalStateException("module generation initialization is already in progress");
            }
        }

        if (admissionFailure != null) {
            retireAfterFailure(generation.id(), admissionFailure);
            rethrow(admissionFailure);
        }
        if (!mustInitialize) return generation;

        try {
            // Guest code executes outside the manager monitor. In particular,
            // callbacks cannot deadlock by calling supervisor/runtime methods
            // while this module's initializer is running.
            generation.startModule();
            return generation;
        } catch (RuntimeException | Error failure) {
            retireAfterFailure(generation.id(), failure);
            throw failure;
        } finally {
            generation.initializing.set(false);
        }
    }

    /**
     * Publishes a successfully initialized module generation as active.
     * Staging and initialization are intentionally separate from publication.
     */
    public Generation activateModule(Generation generation) {
        java.util.Objects.requireNonNull(generation, "generation");
        Throwable activationFailure = null;
        synchronized (this) {
            ensureManagerOpen();
            if (generations.get(generation.id()) != generation) {
                throw new IllegalArgumentException("generation is not owned by this hot-reload manager");
            }
            if (!generation.initializeModuleOnly) {
                throw new IllegalArgumentException("only a module-loaded generation can be activated as a module");
            }
            if (generation.retiring.get() || generation.closed()) {
                throw new IllegalStateException("cannot activate a retiring/closed generation");
            }
            if (generation.initializing.get()) {
                throw new IllegalStateException("cannot activate a module while initialization is in progress");
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
                activationFailure = failure;
            }
            if (activationFailure == null) {
                // Linearization point: all compatibility checks and publication
                // happen under the same monitor.
                activeByCodeUnit.put(generation.codeUnitId(), generation);
                active.set(generation);
                return generation;
            }
        }

        retireAfterFailure(generation.id(), activationFailure);
        rethrow(activationFailure);
        throw new AssertionError("unreachable");
    }

    private void ensureManagerOpen() {
        if (managerClosed) throw new IllegalStateException("hot-reload manager is closed");
    }

    private void retireAfterFailure(long generationId, Throwable failure) {
        try {
            retire(generationId);
        } catch (RuntimeException | Error cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
        if (failure instanceof Error error) throw error;
        throw new IllegalStateException(failure);
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
        ensureReplacementCompatibility(
                current,
                generation.initializeModuleOnly,
                generation.moduleName,
                generation.contractName,
                generation.contractSurface);
    }

    private static void ensureReplacementCompatibility(
            Generation current,
            boolean initializeModuleOnly,
            String moduleName,
            String contractName,
            ModuleContractSurface contractSurface) {
        if (current == null) return;
        if (current.closed() || current.retiring.get()) {
            throw new IllegalStateException(
                    "active generation for '" + current.codeUnitId() + "' is closing/closed; retire it before replacement");
        }
        if (current.initializeModuleOnly != initializeModuleOnly) {
            throw new IllegalStateException(
                    "code unit '" + current.codeUnitId()
                            + "' cannot switch between contract-gated module loading and generic code loading");
        }
        if (!initializeModuleOnly) return;
        if (!java.util.Objects.equals(current.moduleName, moduleName)) {
            throw new IllegalStateException(
                    "hot-loaded module identity changed for code unit '" + current.codeUnitId() + "'");
        }
        if (!java.util.Objects.equals(current.contractName, contractName)) {
            throw new IllegalStateException(
                    "hot-loaded module contract changed for code unit '" + current.codeUnitId()
                            + "'; explicit migration is required");
        }
        if (!java.util.Objects.equals(
                current.contractSurface == null ? null : current.contractSurface.digest(),
                contractSurface == null ? null : contractSurface.digest())) {
            throw new IllegalStateException(
                    "module contract ABI changed for code unit '" + current.codeUnitId()
                            + "'; an ordinary hot reload must preserve the complete declared contract set");
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

    /**
     * Fingerprints the complete declared module-contract set, including the
     * recursively inherited contract surfaces. A hot reload cannot redefine
     * a same-named contract to a weaker signature or drop another declared
     * contract and still pass simply by supplying the same contract name.
     */
    private static ModuleContractSurface moduleContractSurface(Ast.Program program, String moduleName) {
        Ast.ModuleDecl module = program.modules().stream()
                .filter(candidate -> candidate.name().equals(moduleName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "hot-loaded module '" + moduleName + "' was not found"));

        List<Ast.TypeRef> contractRefs = module.contracts().stream()
                .sorted(java.util.Comparator.comparing(HotReloadManager::contractTypeText))
                .toList();
        if (contractRefs.isEmpty()) {
            throw new IllegalArgumentException(
                    "hot-loaded module '" + moduleName + "' must conform to at least one module contract");
        }

        StringBuilder abi = new StringBuilder("ores-module-contract-set-v1\n");
        abi.append("module ").append(moduleName).append('\n');
        Set<String> seenNames = new HashSet<>();
        for (Ast.TypeRef ref : contractRefs) {
            if (ref.inferArguments() || !ref.arguments().isEmpty()) {
                throw new IllegalArgumentException(
                        "generic module-contract instantiations are not supported by runtime hot loading: "
                                + contractTypeText(ref));
            }
            if (!seenNames.add(ref.name())) {
                throw new IllegalArgumentException(
                        "module '" + moduleName + "' repeats contract '" + ref.name() + "'");
            }
            abi.append("conforms ").append(contractTypeText(ref)).append('\n');
        }

        Set<String> visiting = new LinkedHashSet<>();
        Set<String> visited = new HashSet<>();
        Set<String> callableKeys = new HashSet<>();
        for (Ast.TypeRef ref : contractRefs) {
            Ast.InterfaceDecl contract = findModuleContractDeclaration(program, ref.name());
            appendContractClosure(program, contract, abi, visiting, visited, callableKeys);
        }
        return new ModuleContractSurface(digest(abi.toString()), callableKeys);
    }

    private static String contractOwnerModuleName(
            Ast.Program program,
            Ast.InterfaceDecl contract) {
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration == contract) return module.name();
            }
        }
        throw new IllegalArgumentException(
                "module contract '" + contract.name() + "' has no declaration owner");
    }

    private static Ast.InterfaceDecl findModuleContractDeclaration(
            Ast.Program program,
            String contractName) {
        List<Ast.InterfaceDecl> matches = new ArrayList<>();
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.InterfaceDecl iface
                        && iface.moduleContract()
                        && iface.name().equals(contractName)) {
                    matches.add(iface);
                }
            }
        }
        if (matches.size() != 1) {
            throw new IllegalArgumentException(
                    "module contract '" + contractName + "' must resolve to exactly one declaration "
                            + "(found " + matches.size() + ")");
        }
        return matches.getFirst();
    }

    private static void appendContractClosure(
            Ast.Program program,
            Ast.InterfaceDecl contract,
            StringBuilder abi,
            Set<String> visiting,
            Set<String> visited,
            Set<String> callableKeys) {
        String identity = contract.name();
        if (visiting.contains(identity)) {
            throw new IllegalArgumentException(
                    "module contract inheritance cycle involving '" + identity + "'");
        }
        if (!visited.add(identity)) return;
        visiting.add(identity);

        abi.append("contract ").append(contract.visibility()).append(' ')
                .append("declared-in ").append(contractOwnerModuleName(program, contract)).append(' ')
                .append(contract.name()).append('<')
                .append(String.join(",", contract.genericParameters()))
                .append("> extends ");
        contract.parents().stream()
                .map(HotReloadManager::contractTypeText)
                .sorted()
                .forEach(parent -> abi.append(parent).append(','));
        abi.append('\n');

        List<String> memberAbi = new ArrayList<>();
        for (Ast.InterfaceMember member : contract.members()) {
            if (member instanceof Ast.InterfaceFunctionDecl function) {
                StringBuilder signature = new StringBuilder("fn ")
                        .append(function.structural() ? "structural " : "")
                        .append(function.name()).append('<')
                        .append(String.join(",", function.genericParameters()))
                        .append(">(");
                for (Ast.Param parameter : function.parameters()) {
                    signature.append(parameter.structural() ? "structural " : "")
                            .append(contractTypeText(parameter.type()));
                    if (parameter.mutable()) signature.append(" mut");
                    signature.append(',');
                }
                signature.append(")=>").append(contractTypeText(function.returnType()));
                memberAbi.add(signature.toString());
                callableKeys.add(function.name() + "#" + function.parameters().size());
            } else if (member instanceof Ast.InterfaceFieldDecl field) {
                String binding = field.bindingKind() == null
                        ? "readable"
                        : field.bindingKind().name().toLowerCase(java.util.Locale.ROOT);
                memberAbi.add("field " + binding + " " + field.name()
                        + ":" + contractTypeText(field.type()));
            }
        }
        memberAbi.stream().sorted().forEach(member -> abi.append(member).append('\n'));

        List<Ast.TypeRef> parents = contract.parents().stream()
                .sorted(java.util.Comparator.comparing(HotReloadManager::contractTypeText))
                .toList();
        for (Ast.TypeRef parentRef : parents) {
            Ast.InterfaceDecl parent = findModuleContractDeclaration(program, parentRef.name());
            appendContractClosure(program, parent, abi, visiting, visited, callableKeys);
        }
        visiting.remove(identity);
    }

    private static String contractTypeText(Ast.TypeRef ref) {
        if (ref == null) return "<inferred>";
        if (ref.isStringLiteral()) return "'" + ref.stringLiteralValue() + "'";
        StringBuilder type = new StringBuilder(ref.name());
        if (ref.inferArguments()) return type.append("<>").toString();
        if (!ref.arguments().isEmpty()) {
            type.append('<');
            for (Ast.TypeRef argument : ref.arguments()) {
                type.append(contractTypeText(argument)).append(',');
            }
            type.append('>');
        }
        return type.toString();
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
        ensureManagerOpen();
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
                        .approvedForAdversarialSharing(sharingAuthority, codeUnitId, sourceText);
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
    public synchronized void approveGuestCodeSharing(String codeUnitId, String sourceText) {
        ensureManagerOpen();
        if (!guestPolicy.adversarial()) {
            throw new IllegalStateException(
                    "guest code-sharing approval is only required for adversarial guests");
        }
        validateAdmissionInputs(codeUnitId, sourceText);
        SharedCodeRegistry.process().approveForAdversarialSharing(
                supervisorPolicy, sharingAuthority, normalizeCodeUnitId(codeUnitId), sourceText);
    }

    public synchronized void revokeGuestCodeSharing(String codeUnitId, String sourceText) {
        ensureManagerOpen();
        if (!guestPolicy.adversarial()) {
            throw new IllegalStateException(
                    "guest code-sharing revocation is only required for adversarial guests");
        }
        validateAdmissionInputs(codeUnitId, sourceText);
        SharedCodeRegistry.process().revokeForAdversarialSharing(
                supervisorPolicy, sharingAuthority, normalizeCodeUnitId(codeUnitId), sourceText);
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
            ModuleContractSurface contractSurface) {
        ensureGenerationCapacity();
        ensureReplacementCompatibility(
                activeByCodeUnit.get(codeUnitId),
                initializeModuleOnly,
                moduleName,
                contractName,
                contractSurface);

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
                    contractSurface);
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

    /**
     * Starts a legacy code unit without exposing an unstarted candidate.
     * Source execution happens outside the manager monitor, and a concurrent
     * replacement invalidates the candidate rather than overwriting a winner.
     * This publishes runtime selection atomically; it cannot undo guest I/O.
     */
    public Generation loadAndStart(String name, String sourceText) {
        Generation previous;
        Generation candidate;
        synchronized (this) {
            ensureManagerOpen();
            // Enforce raw adversarial input bounds before path normalization can
            // collapse a long attacker-supplied identity to a shorter string.
            validateAdmissionInputs(name, sourceText);
            String codeUnitId = normalizeCodeUnitId(name);
            previous = activeByCodeUnit.get(codeUnitId);
            candidate = loadInternal(
                    codeUnitId, sourceText, false, false, null, null, null);
        }

        try {
            candidate.start();
            synchronized (this) {
                ensureManagerOpen();
                if (generations.get(candidate.id()) != candidate
                        || candidate.retiring.get() || candidate.closed()) {
                    throw new IllegalStateException("generic startup candidate is no longer live");
                }
                if (activeByCodeUnit.get(candidate.codeUnitId()) != previous) {
                    throw new IllegalStateException(
                            "stale generic startup candidate for '" + candidate.codeUnitId()
                                    + "': active generation changed during initialization");
                }
                activeByCodeUnit.put(candidate.codeUnitId(), candidate);
                active.set(candidate);
            }
            return candidate;
        } catch (RuntimeException | Error failure) {
            try {
                retire(candidate.id());
            } catch (RuntimeException | Error cleanupFailure) {
                if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /** Last selected active generation, retained for the single-unit compatibility API. */
    public synchronized Generation active() {
        Generation current = active.get();
        return current == null || current.retiring.get() || current.closed() ? null : current;
    }

    /** Active generation for one independently compiled code unit. */
    public synchronized Generation active(String codeUnitId) {
        Generation current = activeByCodeUnit.get(normalizeCodeUnitId(codeUnitId));
        return current == null || current.retiring.get() || current.closed() ? null : current;
    }

    public synchronized Map<String, Generation> activeGenerations() {
        Map<String, Generation> live = new LinkedHashMap<>();
        activeByCodeUnit.forEach((id, generation) -> {
            if (!generation.retiring.get() && !generation.closed()) {
                live.put(id, generation);
            }
        });
        return Map.copyOf(live);
    }

    /**
     * Explicit retirement permits old actors/requests to drain before teardown.
     * Remove publication/registry references under the manager monitor, then
     * close the context after releasing it: close may wait for an in-flight
     * generation call to finish.
     */
    public void retire(long generationId) {
        Generation generation;
        synchronized (this) {
            generation = generations.remove(generationId);
            if (generation != null) {
                generation.retiring.set(true);
                active.compareAndSet(generation, null);
                activeByCodeUnit.remove(generation.codeUnitId(), generation);
            }
        }
        if (generation != null) generation.close();
    }

    public synchronized int liveGenerations() { return generations.size(); }

    @Override
    public void close() {
        List<Generation> closing;
        synchronized (this) {
            if (managerClosed) return;
            managerClosed = true;
            // Revoke while holding this monitor; no concurrent grant on this
            // manager can race after finalization and retain a stale approval.
            SharedCodeRegistry.process().revokeAllForAuthority(sharingAuthority);
            closing = List.copyOf(generations.values());
            closing.forEach(generation -> generation.retiring.set(true));
            generations.clear();
            activeByCodeUnit.clear();
            active.set(null);
        }

        RuntimeException runtimeFailure = null;
        Error fatalFailure = null;
        for (Generation generation : closing) {
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
        private final String moduleName;
        private final String contractName;
        private final ModuleContractSurface contractSurface;
        // Store only a generation ID, not a Generation reference: an active
        // upgraded generation must not keep every retired predecessor/context
        // alive through a chain of expectedPreviousActive pointers.
        private final long expectedPreviousActiveId;
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean initializing = new AtomicBoolean();
        private final AtomicBoolean retiring = new AtomicBoolean();
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
                long expectedPreviousActiveId,
                String moduleName,
                String contractName,
                ModuleContractSurface contractSurface) {
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
            this.moduleName = moduleName;
            this.contractName = contractName;
            this.contractSurface = contractSurface;
        }

        public long id() { return id; }
        public String codeUnitId() { return codeUnitId; }
        public String sha256() { return sha256; }
        /**
         * Legacy raw-context access remains available to generic code units.
         * Contract-gated module generations must execute only through manager
         * initialization and the generation-scoped public-call surface; leaking
         * their Context would allow callers to parse/evaluate arbitrary source
         * in the same context and bypass the module admission gate.
         */
        public Context context() {
            if (initializeModuleOnly) {
                throw new IllegalStateException(
                        "raw Context access is disabled for contract-gated module generations");
            }
            if (retiring.get() || closed.get()) {
                throw new IllegalStateException("generation is retiring/closed");
            }
            return context;
        }
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
                    if (retiring.get() || closed.get()) {
                        throw new IllegalStateException("generation is retiring/closed");
                    }
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
            if (retiring.get() || closed.get()) throw new IllegalStateException("generation is retiring/closed");
            if (!started.get()) {
                throw new IllegalStateException("generation must be initialized before public invocation");
            }

            String resolvedName = functionName;
            if (initializeModuleOnly) {
                // Contract-gated module generations expose only callables
                // declared by their checked contract set. Do not let the host
                // bypass the declared API by asking for an extra public helper.
                String prefix = moduleName + ".";
                String memberName = functionName.startsWith(prefix)
                        ? functionName.substring(prefix.length())
                        : functionName;
                if (memberName.isBlank() || memberName.contains(".")) {
                    throw new IllegalArgumentException(
                            "module generation '" + moduleName
                                    + "' can invoke only its own contract-declared callables");
                }
                String callableKey = memberName + "#" + arguments.length;
                if (contractSurface == null || !contractSurface.callableKeys().contains(callableKey)) {
                    throw new IllegalArgumentException(
                            "callable '" + memberName + "' with arity " + arguments.length
                                    + " is not declared by module '" + moduleName + "' contracts");
                }
                resolvedName = prefix + memberName;
            }

            Object[] commandArguments = new Object[arguments.length + 2];
            commandArguments[0] = OresEvalRootNode.INVOKE_PUBLIC_COMMAND;
            commandArguments[1] = resolvedName;
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
            if (retiring.get() || closed.get()) {
                throw new IllegalStateException("generation is retiring/closed");
            }
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
            // Reject new invocations immediately, even if close must wait for an
            // already-running generation call to leave the synchronized section.
            retiring.set(true);
            boolean transitioned = false;
            Throwable failure = null;
            synchronized (this) {
                if (closed.compareAndSet(false, true)) {
                    transitioned = true;
                    try {
                        // The generation has stopped admitting guest calls.
                        // Do not force-cancel an apparently idle context:
                        // concurrent Graal teardown must fail visibly rather
                        // than destructively racing active VM carriers.
                        dev.oreslang.OresLanguage.closeManagedContext(context);
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
