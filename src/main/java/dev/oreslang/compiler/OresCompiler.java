package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.AsyncTransferProvenance;
import dev.oreslang.types.AsyncSemanticProvenance;

/** Trusted compiler front-end API for build systems and isolate admission. */
public final class OresCompiler {
    private OresCompiler() { }

    public static Ast.Program parseAndTypeCheck(String source) {
        return analyze(Parser.parse(source));
    }

    /**
     * Combined semantic + ownership admission for current async call lowering.
     *
     * <p>The TypeChecker runs first and proves every declared async boundary is
     * concrete owned task-safe data. OwnershipChecker then proves the exact
     * argument transfers for each statically resolved async call. The returned
     * provenance is still not a lifetime-release witness: runtime lowering must
     * retain transferred ownership until TaskScope.getDone() physical
     * quiescence.</p>
     */
    public enum AsyncReleaseBarrier {
        /**
         * Transferred ownership may be released only after the runtime has
         * observed physical TaskScope descendant quiescence via getDone().
         */
        TASK_SCOPE_GET_DONE
    }

    public record AsyncAdmissionResult(
            Ast.Program program,
            java.util.List<String> warnings,
            AsyncTransferProvenance asyncTransfers,
            AsyncReleaseBarrier releaseBarrier) {
        public AsyncAdmissionResult {
            warnings = java.util.List.copyOf(warnings);
            java.util.Objects.requireNonNull(
                    asyncTransfers, "asyncTransfers");
            java.util.Objects.requireNonNull(
                    releaseBarrier, "releaseBarrier");
        }
    }

    public static AsyncAdmissionResult analyzeForAsyncLowering(
            Ast.Program program) {
        TypeChecker.TypeCheckResult checked =
                TypeChecker.checkTypesWithDiagnostics(program);
        OwnershipChecker.CheckResult owned =
                OwnershipChecker.checkWithAsyncTransferProvenance(
                        checked.program(), checked.shortInferredAliases());

        AsyncSemanticProvenance semantic = checked.asyncSemantic();
        AsyncTransferProvenance transfers = owned.asyncTransfers();
        requireAsyncProofJoin(semantic, transfers);

        return new AsyncAdmissionResult(
                checked.program(),
                checked.warnings(),
                transfers,
                AsyncReleaseBarrier.TASK_SCOPE_GET_DONE);
    }

    /**
     * Package-private so the linked/incremental compiler can enforce the same
     * exact-call semantic + ownership join before leasing prechecked ASTs to
     * the Truffle runtime.
     */
    static void requireAsyncProofJoin(
            AsyncSemanticProvenance semantic,
            AsyncTransferProvenance transfers) {
        AsyncSemanticProvenance.requireExactOwnershipJoin(
                semantic, transfers);
    }

    /** Runs the complete front-end admission policy on an already parsed program. */
    public static Ast.Program analyze(Ast.Program program) {
        return analyzeForAsyncLowering(program).program();
    }

    /** Compiler diagnostics are returned explicitly instead of written to shared stderr. */
    public record AnalysisResult(Ast.Program program, java.util.List<String> warnings) {
        public AnalysisResult { warnings = java.util.List.copyOf(warnings); }
    }

    public static AnalysisResult parseAndTypeCheckWithDiagnostics(String source) {
        return analyzeWithDiagnostics(Parser.parse(source));
    }

    public static AnalysisResult analyzeWithDiagnostics(Ast.Program program) {
        AsyncAdmissionResult checked = analyzeForAsyncLowering(program);
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
