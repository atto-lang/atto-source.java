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

    public static Ast.Program parseAndTypeCheck(String source) {
        return analyze(Parser.parse(source));
    }

    /**
     * Opt-in, STOP-only source supervisor compilation. This does not change
     * general program admission, which still rejects unlowered on_panic hooks.
     * The result is a compiler-validated CONTROL policy, not an actor method.
     */
    public static SourceSupervisorLowering.StopOnlySupervisor compileStopOnlySupervisor(
            String source) {
        return SourceSupervisorLowering.compile(source);
    }

    /** Runs the complete front-end admission policy on an already parsed program. */
    public static Ast.Program analyze(Ast.Program program) {
        rejectUnloweredSupervisors(program);
        program = TypeChecker.checkTypes(program);
        OwnershipChecker.check(program);
        return program;
    }

    /** Compiler diagnostics are returned explicitly instead of written to shared stderr. */
    public record AnalysisResult(Ast.Program program, java.util.List<String> warnings) {
        public AnalysisResult { warnings = java.util.List.copyOf(warnings); }
    }

    public static AnalysisResult parseAndTypeCheckWithDiagnostics(String source) {
        return analyzeWithDiagnostics(Parser.parse(source));
    }

    public static AnalysisResult analyzeWithDiagnostics(Ast.Program program) {
        rejectUnloweredSupervisors(program);
        TypeChecker.TypeCheckResult checked = TypeChecker.checkTypesWithDiagnostics(program);
        OwnershipChecker.check(checked.program());
        return new AnalysisResult(checked.program(), checked.warnings());
    }

    /**
     * Fail closed: a declaration entering the AST does NOT imply its guest
     * lifecycle hook can execute. A source supervisor requires generated
     * CONTROL delivery, effect checking and separate logical context. Until
     * those exist, admitting it would silently turn on_panic into an ordinary
     * method and violate the native/Java shared supervision contract.
     */
    private static void rejectUnloweredSupervisors(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.SupervisorDecl supervisor) {
                    throw new IllegalArgumentException(
                            "source supervisor '" + supervisor.name()
                                    + "' requires compiler-lowered CONTROL turns; "
                                    + "define supervisor / on_panic is parsed but not executable yet");
                }
            }
        }
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
