package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.OwnershipChecker;

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

    public static Ast.Program parseAndTypeCheck(String source) {
        return analyze(Parser.parse(source));
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

    /** Compiler diagnostics are returned explicitly instead of written to shared stderr. */
    public record AnalysisResult(Ast.Program program, java.util.List<String> warnings) {
        public AnalysisResult { warnings = java.util.List.copyOf(warnings); }
    }

    public static AnalysisResult parseAndTypeCheckWithDiagnostics(String source) {
        return analyzeWithDiagnostics(Parser.parse(source));
    }

    public static AnalysisResult analyzeWithDiagnostics(Ast.Program program) {
        TypeChecker.TypeCheckResult checked = TypeChecker.checkTypesWithDiagnostics(program);
        OwnershipChecker.check(checked.program(), checked.shortInferredAliases());
        return new AnalysisResult(checked.program(), checked.warnings());
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
