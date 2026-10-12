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

    // Linked source signatures are bound to a concrete unit identity and only
    // installed by the trusted linked-runner in a synchronous admission scope.
    // No unlinked or dynamic call gains a borrow contract by default.
    private static final ThreadLocal<java.util.Map<String, java.util.Map<String, Ast.FunctionDecl>>>
            LINKED_IMPORT_SIGNATURES = new ThreadLocal<>();

    public static final class LinkedImportScope implements AutoCloseable {
        private final java.util.Map<String, java.util.Map<String, Ast.FunctionDecl>> previous;
        private LinkedImportScope(java.util.Map<String, java.util.Map<String, Ast.FunctionDecl>> previous) {
            this.previous = previous;
        }
        @Override public void close() {
            if (previous == null) LINKED_IMPORT_SIGNATURES.remove();
            else LINKED_IMPORT_SIGNATURES.set(previous);
        }
    }

    /** Trusted host-only, lexical linked-source admission scope. */
    public static LinkedImportScope installVerifiedLinkedImports(
            java.util.Map<String, java.util.Map<String, Ast.FunctionDecl>> signatures) {
        var previous = LINKED_IMPORT_SIGNATURES.get();
        LINKED_IMPORT_SIGNATURES.set(java.util.Map.copyOf(signatures));
        return new LinkedImportScope(previous);
    }

    public static Ast.Program parseAndTypeCheck(String source) {
        return analyze(Parser.parse(source));
    }

    /** Called with the canonical source-unit identity by the language parser. */
    public static Ast.Program parseAndTypeCheck(String source, String unitId) {
        var scoped = LINKED_IMPORT_SIGNATURES.get();
        return analyze(Parser.parse(source),
                scoped == null ? java.util.Map.of() : scoped.getOrDefault(unitId, java.util.Map.of()));
    }

    public static Ast.Program parseAndTypeCheck(
            String source, java.util.Map<String, Ast.FunctionDecl> importedSignatures) {
        return analyze(Parser.parse(source), importedSignatures);
    }

    /** Runs the complete front-end admission policy on an already parsed program. */
    public static Ast.Program analyze(Ast.Program program) {
        return analyze(program, java.util.Map.of());
    }

    /** Linked source signature entrypoint, still fail-closed for unverified calls. */
    public static Ast.Program analyze(
            Ast.Program program, java.util.Map<String, Ast.FunctionDecl> importedSignatures) {
        program = TypeChecker.checkTypes(program);
        OwnershipChecker.check(program, importedSignatures);
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
