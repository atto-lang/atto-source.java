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
        return analyze(program, java.util.Map.of());
    }

    /** Linked-file admission with explicitly resolved public module contracts. */
    public static Ast.Program analyze(
            Ast.Program program, java.util.Map<String, Ast.InterfaceDecl> importedContracts) {
        program = TypeChecker.checkTypes(program, importedContracts);
        OwnershipChecker.check(program);
        return program;
    }

    /**
     * A thread-confined, source-exact admission scope for GraalVM's second parse
     * after IncrementalCompiler has already validated the entire import graph.
     * Never make imported contracts process-global or accept unbound guest text.
     */
    private record LinkedParse(String id, String source, java.util.Map<String, Ast.InterfaceDecl> contracts) { }
    private static final ThreadLocal<LinkedParse> LINKED_PARSE = new ThreadLocal<>();

    public static final class LinkedParseScope implements AutoCloseable {
        private final Thread owner = Thread.currentThread();
        private boolean closed;
        private LinkedParseScope() { }
        @Override public void close() {
            if (closed || Thread.currentThread() != owner) {
                throw new IllegalStateException("linked parse scope closed on wrong thread or twice");
            }
            LINKED_PARSE.remove();
            closed = true;
        }
    }

    /** Trusted host bridge: only exact pre-linked source units may use contracts. */
    public static LinkedParseScope openLinkedParse(
            String sourceId,
            String text,
            java.util.Map<String, Ast.InterfaceDecl> importedContracts) {
        if (sourceId == null || sourceId.isBlank() || text == null || importedContracts == null
                || LINKED_PARSE.get() != null) {
            throw new IllegalArgumentException("invalid or nested linked parse scope");
        }
        LINKED_PARSE.set(new LinkedParse(sourceId, text, java.util.Map.copyOf(importedContracts)));
        return new LinkedParseScope();
    }

    public static Ast.Program parseForRuntime(String source, String sourceId) {
        LinkedParse scope = LINKED_PARSE.get();
        if (scope == null) return parseAndTypeCheck(source);
        if (!scope.id().equals(sourceId) || !scope.source().equals(source)) {
            throw new IllegalArgumentException("linked Oreslang parse source identity mismatch");
        }
        return analyze(Parser.parse(source), scope.contracts());
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
