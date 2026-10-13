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

    /** Runs the complete front-end admission policy on an already parsed program. */
    public static Ast.Program analyze(Ast.Program program) {
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
        Ast.Program checked = parseAndTypeCheck(source);
        // TreeShaker currently clones/reconstructs expressions. That invalidates
        // identity-keyed compile-time call bindings. Until it emits a rebased
        // checked binding plan, do NOT publish an optimized image with enum
        // same-arity overloads whose targets could no longer be proven.
        for (Ast.ModuleDecl module : checked.modules()) {
            java.util.Set<String> slots = new java.util.HashSet<>();
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.FunctionDecl fn) {
                    String slot = fn.name() + "#" + fn.parameters().size();
                    if (!slots.add(slot)) {
                        throw new IllegalArgumentException(
                                "tree-shaken builds with same-arity nominal enum overloads "
                                        + "require rebinding callable IDs after AST rewriting");
                    }
                }
            }
        }
        return TreeShaker.shake(checked, options);
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
