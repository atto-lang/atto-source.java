package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TaskBoundaryAdmissions;

/** Trusted compiler front-end API for build systems and isolate admission. */
public final class OresCompiler {
    private OresCompiler() { }

    public static Ast.Program parseAndTypeCheck(String source) {
        return analyze(Parser.parse(source));
    }

    /** Runs the complete front-end admission policy on an already parsed program. */
    public static Ast.Program analyze(Ast.Program program) {
        program = TypeChecker.checkTypes(program);
        OwnershipChecker.check(program);
        return program;
    }

    /**
     * Executable frontend analysis carrying sealed exact-identity Task
     * admissions alongside the exact checked program instance.
     */
    public record TaskBoundaryAnalysis(
            Ast.Program program,
            TaskBoundaryAdmissions taskAdmissions) {
        public TaskBoundaryAnalysis {
            java.util.Objects.requireNonNull(program, "program");
            java.util.Objects.requireNonNull(taskAdmissions, "taskAdmissions");
            if (!taskAdmissions.sealed()
                    || !taskAdmissions.executable()) {
                throw new IllegalArgumentException(
                        "Task boundary analysis requires ownership-authorized executable admissions");
            }
            taskAdmissions.requireProgramIdentity(program);
        }
    }

    public static TaskBoundaryAnalysis parseAndAnalyzeTaskBoundaries(
            String source) {
        return analyzeTaskBoundaries(Parser.parse(source));
    }

    public static TaskBoundaryAnalysis analyzeTaskBoundaries(
            Ast.Program program) {
        TypeChecker.TaskTypeCheckResult checked =
                TypeChecker.checkTypesWithTaskAdmissions(program);
        OwnershipChecker.TaskAdmissionSeal ownershipSeal =
                OwnershipChecker.checkForTaskAdmission(checked.program());
        checked.taskAdmissions().authorizeExecution(
                checked.program(), ownershipSeal);
        return new TaskBoundaryAnalysis(
                checked.program(), checked.taskAdmissions());
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
        OwnershipChecker.check(checked.program());
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
