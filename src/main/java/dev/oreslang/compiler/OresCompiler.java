package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TaskAdmissionProof;
import dev.oreslang.types.TaskSemanticAdmission;

/** Trusted compiler front-end API for build systems and isolate admission. */
public final class OresCompiler {
    private OresCompiler() { }

    public record ExecutionAnalysis(
            Ast.Program program,
            java.util.List<String> warnings,
            TaskAdmissionProof taskAdmissions) {
        public ExecutionAnalysis {
            warnings = java.util.List.copyOf(warnings);
            if (program == null || taskAdmissions == null) {
                throw new IllegalArgumentException(
                        "execution analysis requires program and Task proof");
            }
            if (taskAdmissions.releaseBarrier()
                    != TaskAdmissionProof.ReleaseBarrier.PHYSICAL_TASK_SCOPE_DONE) {
                throw new IllegalArgumentException(
                        "executable Task proof has the wrong release barrier");
            }
        }
    }

    public static ExecutionAnalysis parseAndProveForExecution(String source) {
        return analyzeForExecution(Parser.parse(source));
    }

    public static ExecutionAnalysis analyzeForExecution(Ast.Program program) {
        TypeChecker.TypeCheckResult checked =
                TypeChecker.checkTypesWithDiagnostics(program);
        OwnershipChecker.CheckResult ownership =
                OwnershipChecker.checkWithTaskTransferProvenance(checked.program());
        TaskSemanticAdmission semantic =
                TypeChecker.checkTaskTransferProvenance(
                        checked.program(), ownership.taskTransfers());
        TaskAdmissionProof proof =
                TaskAdmissionProof.combine(ownership.taskTransfers(), semantic);
        return new ExecutionAnalysis(checked.program(), checked.warnings(), proof);
    }

    public static Ast.Program parseAndTypeCheck(String source) {
        return analyzeForExecution(Parser.parse(source)).program();
    }

    /** Runs the complete front-end admission policy on an already parsed program. */
    public static Ast.Program analyze(Ast.Program program) {
        return analyzeForExecution(program).program();
    }

    /** Compiler diagnostics are returned explicitly instead of written to shared stderr. */
    public record AnalysisResult(Ast.Program program, java.util.List<String> warnings) {
        public AnalysisResult { warnings = java.util.List.copyOf(warnings); }
    }

    public static AnalysisResult parseAndTypeCheckWithDiagnostics(String source) {
        return analyzeWithDiagnostics(Parser.parse(source));
    }

    public static AnalysisResult analyzeWithDiagnostics(Ast.Program program) {
        ExecutionAnalysis checked = analyzeForExecution(program);
        return new AnalysisResult(checked.program(), checked.warnings());
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
