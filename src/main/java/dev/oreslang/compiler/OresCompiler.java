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
     * Opt-in, closed-world namespace actor runtime lowering. The source
     * program must already be published as one immutable VM CodeImage.
     * This does NOT loosen normal TypeChecker admission for arbitrary
     * external mtd bodies: NamespaceActorIngressV1 validates the exact
     * private init/receive AST and emits a runtime-owned executor directly.
     */
    public static NamespaceActorIngressV1.Plan compileNamespaceActorIngressV1(
            dev.oreslang.runtime.SharedCodeImageStore.CodeImage image,
            String packageIdentity, String actorName) {
        return NamespaceActorIngressV1.compile(image, packageIdentity, actorName);
    }
    /**
     * Opt-in V2 multi-method actor bundle. Unmodified broker ADT receive
     * declarations remain fail-closed until typed ADT routing and runtime
     * activation grants are implemented.
     */
    public static NamespaceActorIngressV2.Plan compileNamespaceActorIngressV2(
            dev.oreslang.runtime.SharedCodeImageStore.CodeImage image,
            String packageIdentity, String actorName) {
        return NamespaceActorIngressV2.compile(image, packageIdentity, actorName);
    }
    /**
     * Opt-in V3 exhaustive nominal enum/ActorMail dispatch. General
     * TypeChecker and arbitrary userland enum execution remain fail-closed.
     * No primitive variant grants privileged actor activation.
     */
    public static NamespaceActorIngressV3.Plan compileNamespaceActorIngressV3(
            dev.oreslang.runtime.SharedCodeImageStore.CodeImage image,
            String packageIdentity, String actorName) {
        return NamespaceActorIngressV3.compile(image, packageIdentity, actorName);
    }



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
