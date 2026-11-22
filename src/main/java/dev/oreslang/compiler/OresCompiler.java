package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.BorrowMirAuditV1;

/** Trusted compiler front-end API for build systems and isolate admission. */
public final class OresCompiler {
    private OresCompiler() { }

    /**
     * Opt-in first protected-state MIR slice. This executes the production
     * parser first, then admits only one verified i64 state-root region.
     * It does NOT bypass or relax ordinary TypeChecker/OwnershipChecker
     * admission for general compilation and does not claim native AOT.
     */
    public static ProtectedScalarMirV1.Plan compileProtectedScalarI64(String source) {
        return ProtectedScalarMirV1.lower(Parser.parse(source));
    }

    /**
     * Opt-in source-AST -> actor-owned scalar state MIR. Only the verified
     * single-field/single-critical-method subset may construct the executor.
     * This is NOT general critical lowering, a runnable guest main(), or AOT.
     */
    public static dev.oreslang.runtime.SealedActorScalarMirV1.Plan
            compileSealedActorScalarI64(String source) {
        return dev.oreslang.runtime.SealedActorScalarMirV1.lower(source);
    }

    public static Ast.Program parseAndTypeCheck(String source) {
        return analyze(Parser.parse(source));
    }

    /**
     * Opt-in AST and conservative ownership/effect check ONLY. This returns
     * declaration metadata, never executable bytecode or runtime authority.
     * parseAndTypeCheck continues rejecting singleton source.
     */
    public static java.util.List<Ast.SingletonDecl> checkSingletonDeclarationsOnly(String source) {
        return SingletonCheckOnly.check(Parser.parseSingletonsCheckOnly(source));
    }

    /**
     * Opt-in source compiler bridge for deeply immutable singleton literals.
     * The checked code-unit identity must come from trusted module resolution.
     * This does NOT enable ordinary guest singleton execution, mutable state,
     * or implicit cross-isolate sharing. Normal parseAndTypeCheck stays closed.
     */
    public static SingletonSnapshotCompilerV1.Compiled compileSingletonSnapshotOnly(
            String source, String checkedCodeUnitId) {
        return SingletonSnapshotCompilerV1.compile(source, checkedCodeUnitId);
    }

    /**
     * Inspect member-selection syntax only after normal type/ownership admission.
     * No physical layout or performance conclusion is implied.
     */
    public static TypedLayoutAccessEvidence.Report inspectLayoutAccesses(String source) {
        return TypedLayoutAccessEvidence.inspectSource(source);
    }

    /**
     * Check real AttoLang source (syntax, types and ownership) before
     * inspecting call-site-specialized Option/Result callback effects.
     *
     * V1 is a fail-closed, partial analysis report, NOT source-language pure
     * admission and NOT a generated guest/native executable.
     */
    public static FunctionalCallbackEffectAnalysisV1.Report analyzeFunctionalCallbacksV1(
            String source) {
        return FunctionalCallbackEffectAnalysisV1.analyzeChecked(
                parseAndTypeCheck(source));
    }

    /**
     * Explicit, opt-in Atto v1/v2 callable-body admission through the REAL
     * parser, strict TypeChecker and OwnershipChecker. Never change the
     * unversioned JVM/Truffle source default or accept an unverified import
     * graph under a local-only language-version selection.
     *
     * This returns a checked AST for a trusted one-shot Truffle guest lease;
     * it does not claim general actor/suspension or native-AOT parity.
     */
    public static Ast.Program parseAndTypeCheckVersionedAtto(String source, int version) {
        Ast.Program program = Parser.parseVersionedAtto(source, version);
        if (!program.imports().isEmpty()) {
            throw new IllegalArgumentException(
                    "ATTO_LANGUAGE_VERSION_IMPORT_GRAPH_UNVERIFIED: imported units must be checked under the same version");
        }
        TypeChecker.TypeCheckResult checked =
                TypeChecker.checkTypesWithDiagnostics(program, true);
        OwnershipChecker.check(checked.program(), checked.shortInferredAliases());
        return checked.program();
    }

    /**
     * Run the real Parser -> TypeChecker -> OwnershipChecker only once while
     * collecting a bounded, fail-closed MIR shadow event stream.
     *
     * This does not run the Rust oracle or certify compiled guest execution.
     * The ordinary production compilation entry point remains unchanged.
     */
    public static BorrowMirAuditV1.Report analyzeBorrowMirV1(String source) {
        TypeChecker.TypeCheckResult checked =
                TypeChecker.checkTypesWithDiagnostics(Parser.parse(source));
        return OwnershipChecker.checkWithBorrowMirAuditV1(
                checked.program(), checked.shortInferredAliases());
    }

    /**
     * Explicit AttoLang strict-v1 *front-end* admission for AOT build pipelines.
     * It emits a checked AST, not native code or a guest-AOT proof. The caller
     * must still use an AOT backend and verify a program-specific executable.
     */
    public static Ast.Program parseAndTypeCheckStrictAtto(String source) {
        Ast.Program program = Parser.parseStrictAtto(source);
        // Profile evidence must cover the complete compilation closure.
        // The single-unit API cannot validate imported source transitively,
        // so fail closed rather than linking a legacy-checked dependency.
        if (!program.imports().isEmpty()) {
            throw new IllegalArgumentException(
                    "ATTO_STRICT_IMPORT_GRAPH_UNVERIFIED: every imported unit must use the same source profile");
        }
        TypeChecker.TypeCheckResult checked =
                TypeChecker.checkTypesWithDiagnostics(program, true);
        OwnershipChecker.check(checked.program(), checked.shortInferredAliases());
        return checked.program();
    }

    /**
     * Validate a complete source graph under the strict profile. Every source
     * unit, including import targets, must be supplied and checked; unresolved
     * external imports fail closed. Returns linked checked AST units, not an
     * emitted native program or proof of AOT execution.
     */
    public static IncrementalCompiler.BuildResult parseAndTypeCheckStrictAttoGraph(
            java.util.Map<String, String> sources,
            java.util.Map<String, java.util.Map<String, String>> importResolutions) {
        return new IncrementalCompiler().compileStrictAtto(sources, importResolutions);
    }

    /** Strict source-profile front end for closed-world build optimization. */
    public static TreeShaker.Result compileForBuildStrictAtto(
            String source, BuildOptions options) {
        return TreeShaker.shake(parseAndTypeCheckStrictAtto(source), options);
    }

    /** Runs the complete front-end admission policy on an already parsed program. */
    public static Ast.Program analyze(Ast.Program program) {
        TypeChecker.TypeCheckResult checked = TypeChecker.checkTypesWithDiagnostics(program);
        OwnershipChecker.check(checked.program(), checked.shortInferredAliases());
        return checked.program();
    }

    /** Imported module contracts participate in type proofs, not runtime declarations. */
    public static Ast.Program analyze(
            Ast.Program program, java.util.Map<String, Ast.InterfaceDecl> importedContracts) {
        TypeChecker.TypeCheckResult checked =
                TypeChecker.checkTypesWithLinkedMetadataWithDiagnostics(
                        program, java.util.Map.of(), java.util.Map.of(), importedContracts);
        OwnershipChecker.check(checked.program(), checked.shortInferredAliases());
        return checked.program();
    }

    /**
     * Minted only by OresCompiler after Oreslang type and ownership admission.
     * Unlike AnalysisResult (a presentation value), this class has no public
     * constructor and must not be decoded or synthesized from cache contents.
     *
     * Currently local-only: a cross-file admitted snapshot must instead be
     * constructed by the future trusted SCC linker after linked resolution.
     */
    public static final class CheckedProgram {
        private final Ast.Program program;
        private final java.util.Map<String, TypeChecker.PublicNominalOrigin> nominalOrigins;
        private final java.util.List<String> warnings;

        private CheckedProgram(TypeChecker.CheckedPublicNominals verified) {
            this.program = verified.program();
            this.nominalOrigins = java.util.Map.copyOf(verified.resolvedNames());
            this.warnings = java.util.List.copyOf(verified.warnings());
        }

        public Ast.Program program() { return program; }
        public java.util.Map<String, TypeChecker.PublicNominalOrigin> nominalOrigins() {
            return nominalOrigins;
        }
        public java.util.List<String> warnings() { return warnings; }
    }

    /** Local, no-import front-end admission with an unforgeable checked token. */
    public static CheckedProgram admitLocal(Ast.Program program) {
        return new CheckedProgram(TypeChecker.checkTypesAndSnapshotPublicNominals(program));
    }

    public static CheckedProgram parseAndAdmitLocal(String source) {
        return admitLocal(Parser.parse(source));
    }

    /**
     * Trusted source-text entry point for the restricted VerifiedScc v0
     * semantic checker. Parsing is deliberately done inside the compiler,
     * before immutable AST preflight, declaration staging and all-unit
     * type/ownership verification. This does not perform runtime linking
     * or grant permission to reuse native compiled objects.
     */
    public static VerifiedSccAdmission.VerifiedGraph parseAndVerifySccSources(
            java.util.Map<String, String> sources,
            java.util.Map<String, ExportInterfaceCodec.Header> environments,
            java.util.Map<String, java.util.Map<String, String>> importOverrides) {
        java.util.Objects.requireNonNull(sources, "source text map");
        java.util.Map<String, String> frozen =
                java.util.Map.copyOf(sources);
        java.util.Map<String, Ast.Program> parsed = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, String> source : frozen.entrySet()) {
            parsed.put(source.getKey(), Parser.parse(source.getValue()));
        }
        return VerifiedSccAdmission.verifyGraph(
                parsed, environments, importOverrides);
    }

    /** Compiler diagnostics are returned explicitly instead of written to shared stderr. */
    public record AnalysisResult(Ast.Program program, java.util.List<String> warnings) {
        public AnalysisResult { warnings = java.util.List.copyOf(warnings); }
    }

    public static AnalysisResult parseAndTypeCheckWithDiagnostics(String source) {
        return analyzeWithDiagnostics(Parser.parse(source));
    }

    public static AnalysisResult analyzeWithDiagnostics(Ast.Program program) {
        TypeChecker.TypeCheckResult checked = TypeChecker.checkTypesWithDiagnostics(program);
        OwnershipChecker.OwnershipCheckResult owned =
                OwnershipChecker.checkWithDiagnostics(checked.program(), checked.shortInferredAliases());
        java.util.List<String> warnings = new java.util.ArrayList<>(checked.warnings());
        warnings.addAll(owned.warnings());
        return new AnalysisResult(checked.program(), warnings);
    }

    /**
     * Opt-in RT primitive subset: perform the full existing type/ownership check
     * before admitting any source-level resource bounds or lowering to Java 8.
     * This is not a verified no-heap runtime or WCET proof. Consumers must run
     * the independent exact-classfile audit and target qualification gates.
     */
    public static RealtimePrimitiveCompiler.Artifact compileRealtimePrimitive(
            String source, String entry, int arity,
            RealtimePrimitiveCompiler.Budget budget, java.nio.file.Path outputDirectory)
            throws java.io.IOException {
        Ast.Program checked = parseAndTypeCheck(source);
        return RealtimePrimitiveCompiler.compile(checked, entry, arity, budget, outputDirectory);
    }

    /**
     * Opt-in compiler-only AOT metadata closure for explicit, public,
     * non-actor, non-generic source classes and closed scalar public fields.
     *
     * Runs the regular parser, type checker and ownership checker first.
     * Returns immutable descriptor bytes, NOT an AttoLang guest value,
     * executable code, GraalVM image, or type_of<T>() intrinsic.
     *
     * Unsupported type shapes, private roots and missing symbols reject.
     */
    public static byte[] compileAotReflectionTableV1(
            String source, String packageIdentityClaim,
            java.util.List<String> explicitQualifiedRoots) {
        Ast.Program checked = parseAndTypeCheck(source);
        return AotReflectionSourceRootsV1.lowerChecked(
                checked, packageIdentityClaim, explicitQualifiedRoots).bytes();
    }

    /**
     * Immutable compiler-side result of an independently parsed, closed
     * type_of<Nominal>() query. No guest runtime lookup or invocation is exposed.
     */
    public record StaticTypeOfQueryV1(
            String qualifiedType, String typeIdHex, byte[] descriptorTable) {
        public StaticTypeOfQueryV1 {
            java.util.Objects.requireNonNull(qualifiedType, "qualified type");
            java.util.Objects.requireNonNull(typeIdHex, "type ID");
            descriptorTable = java.util.Objects.requireNonNull(
                    descriptorTable, "descriptor table").clone();
        }
        @Override public byte[] descriptorTable() { return descriptorTable.clone(); }
    }

    /**
     * Opt-in static type_of<Nominal>() compiler metadata bootstrap.
     *
     * This method checks real source declarations with the production Parser,
     * TypeChecker and OwnershipChecker. The standalone query is parsed as a
     * real generic CallExpr, then bound to a single public class from that
     * checked program through AotReflectionSourceRootsV1. Its name cannot be
     * shadowed by a user-declared same-name callable.
     *
     * The caller's package identity is a CLAIM, not authenticated provenance.
     * A separate build admission gate must bind it to a reviewed manifest,
     * exact source tree and compiler artifact before any parity promotion.
     *
     * THIS DOES NOT make type_of<T>() valid inside an AttoLang guest function,
     * lower guest bytecode, or prove native execution. Those are separate gates.
     */
    public static StaticTypeOfQueryV1 compileStaticTypeOfQueryV1(
            String sourceDeclarations, String packageIdentityClaim,
            String staticQuery) {
        Ast.Program checked = parseAndTypeCheck(sourceDeclarations);
        for (Ast.ModuleDecl module : checked.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.FunctionDecl function
                        && function.name().equals("type_of")) {
                    throw new IllegalArgumentException(
                            "static type_of intrinsic conflicts with a user-declared function");
                }
            }
        }
        Ast.TypeRef nominal = Parser.parseStaticTypeOfQueryV1(staticQuery);
        String qualified = "__root__." + nominal.name();
        byte[] encoded = AotReflectionSourceRootsV1.lowerChecked(
                checked, packageIdentityClaim, java.util.List.of(qualified)).bytes();
        AotReflectionTableV1Reader.Table table = AotReflectionTableV1Reader.decode(encoded);
        if (table.types().size() != 1 ||
                !table.types().getFirst().resolvedSymbol().equals(qualified)) {
            throw new IllegalStateException("static type_of descriptor identity mismatch");
        }
        return new StaticTypeOfQueryV1(
                qualified, table.types().getFirst().typeIdHex(), encoded);
    }

    /**
     * Compiler-only typed MIR from the strict single-return descriptor subset.
     * This does not admit type_of<T>() in ordinary guest compilation and does
     * not emit JVM bytecode, native objects or runnable AttoLang binaries.
     */
    public static AotReflectionTypedMirV1.Plan lowerAotReflectionTypedMirV1(
            String guestSource, String packageIdentityClaim) {
        return AotReflectionTypedMirV1.lowerSource(guestSource, packageIdentityClaim);
    }

    /**
     * Compile a bounded JSON/MessagePack scalar decoding schema from the same
     * explicit, source-checked nominal descriptor used by static type_of.
     *
     * Decoding returns immutable scalar slots only: it does NOT construct
     * guest objects, reflect on host classes, or prove Native Image execution.
     * Field aliases/defaults/nested values are rejected until a versioned
     * compiler-generated schema and constructor ABI exist.
     */
    public static AotScalarWireV1.Schema compileAotScalarWireSchemaV1(
            String sourceDeclarations, String packageIdentityClaim,
            String staticTypeQuery) {
        return AotScalarWireV1.fromCheckedQuery(
                compileStaticTypeOfQueryV1(
                        sourceDeclarations, packageIdentityClaim, staticTypeQuery));
    }

    /**
     * Emit a real Java 8 classfile for the explicitly admitted, bounded
     * AttoLang primitive subset. The source profile and ownership pass are
     * enforced BEFORE code generation. This is an executable bytecode build
     * artifact, not a Graal Native Image or no-JIT certificate.
     */
    public static RealtimePrimitiveCompiler.Artifact compileRealtimePrimitiveStrictAtto(
            String source, String entry, int arity,
            RealtimePrimitiveCompiler.Budget budget, java.nio.file.Path outputDirectory)
            throws java.io.IOException {
        Ast.Program checked = parseAndTypeCheckStrictAtto(source);
        return RealtimePrimitiveCompiler.compile(checked, entry, arity, budget, outputDirectory);
    }

    /**
     * Conservative checked-AST actor callback preflight. This proves only
     * scalar syntax/effect exclusion, not actor heap or hard-RT execution.
     * The checked AST is produced here, never supplied by the caller.
     */
    public static RealtimeActorSourceEffects.Report analyzeRealtimeActorCallback(
            String source, String actorName, String callbackName, int arity,
            RealtimeActorSourceEffects.Budget budget) {
        Ast.Program checked = parseAndTypeCheck(source);
        return RealtimeActorSourceEffects.analyzeChecked(
                checked, actorName, callbackName, arity, budget);
    }

    /**
     * Experimental public interface emission. Static analysis and ownership
     * admission are mandatory before extracting metadata. This does not execute
     * guest code, persist a cache, or authorize a decoded external interface.
     */
    public static ExportInterfaceCodec.InterfaceFile emitPublicInterface(
            String source, ExportInterfaceCodec.Header header) {
        return ExportInterfaceCollector.collect(parseAndTypeCheck(source), header);
    }

    /**
     * Parse and type-check the complete source first, then perform closed-world
     * build optimization. Type errors in code that later becomes unreachable
     * are still reported; tree shaking is an optimization, not conditional
     * compilation that hides invalid source.
     */
    // FULL_GUEST_AOT_TODO (platform-conformance #321): This source-graph
    // optimization returns AST/tree-shake metadata, not guest-specific
    // executable methods. Preserve this path for hybrid/interpreter use.
    // A strict AOT path must lower a finite resolved graph (imports, generic
    // specializations, class/actor dispatch, macro output, init/drop and
    // suspension/exception continuations) to executable guest code before
    // native-image, and fail closed on unknown targets with no fallback.
    // ZERO_TRACING_GC_TODO: Native Image of the Truffle evaluator, even with
    // Epsilon and zero observed collections, does not show zero managed
    // allocations or deterministic reclamation of guest graph objects.
    // Every actor-owned native arena needs checked 32-bit region offsets,
    // generation fences and unwind/restart/cancel/FFI pin quiescence proofs.
    public static TreeShaker.Result compileForBuild(String source, BuildOptions options) {
        return TreeShaker.shake(parseAndTypeCheck(source), options);
    }

    /**
     * Performs syntax, type, and language-capability admission without
     * executing guest code.
     */
    public static Ast.Program validateForIsolate(String source, IsolatePolicy policy) {
        Ast.Program program = parseAndTypeCheck(source);
        CapabilityChecker.check(program, policy);
        return program;
    }
}
