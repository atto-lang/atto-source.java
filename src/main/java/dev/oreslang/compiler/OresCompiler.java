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
        return analyzeLinked(program, java.util.List.of());
    }

    /** Check an imported-contract-aware code unit without merging runtime modules. */
    public static Ast.Program analyzeLinked(
            Ast.Program program, java.util.List<TypeChecker.LinkedContract> verifiedContracts) {
        program = TypeChecker.checkTypes(program, verifiedContracts);
        OwnershipChecker.check(program);
        return program;
    }

    /*
     * Context.parse() invokes the Truffle language parser again after the
     * linked host has already checked every imported contract and the borrow
     * rules. Share that exact verified AST ONLY during one synchronous host
     * parse call; never install mutable compiler state in an actor context,
     * VM-global cache, or carrier-thread-local at runtime.
     */
    private record LinkedSource(String codeUnitId, String source, Ast.Program checked) { }
    private static final ThreadLocal<LinkedSource> HOST_LINKED_SOURCE = new ThreadLocal<>();

    public static final class LinkedGuestParseScope implements AutoCloseable {
        private final LinkedSource prior;
        private final Thread owner = Thread.currentThread();
        private boolean closed;

        private LinkedGuestParseScope(LinkedSource prior) {
            this.prior = prior;
        }

        @Override public void close() {
            if (closed || Thread.currentThread() != owner) {
                throw new IllegalStateException("linked guest parse scope closed incorrectly");
            }
            closed = true;
            if (prior == null) HOST_LINKED_SOURCE.remove();
            else HOST_LINKED_SOURCE.set(prior);
        }
    }

    /** Host-only, fail-closed bridge from a verified build unit to Truffle parsing. */
    public static LinkedGuestParseScope linkVerifiedGuestParse(
            String codeUnitId, String source, Ast.Program checked) {
        if (codeUnitId == null || codeUnitId.isBlank()
                || source == null || checked == null) {
            throw new IllegalArgumentException("linked source identity and checked AST are required");
        }
        LinkedSource previous = HOST_LINKED_SOURCE.get();
        HOST_LINKED_SOURCE.set(new LinkedSource(codeUnitId, source, checked));
        return new LinkedGuestParseScope(previous);
    }

    /** Null means ordinary standalone parsing; any active mismatched lease is fatal. */
    public static Ast.Program takeVerifiedGuestProgram(String codeUnitId, String source) {
        LinkedSource snapshot = HOST_LINKED_SOURCE.get();
        if (snapshot == null) return null;
        if (!snapshot.codeUnitId().equals(codeUnitId) || !snapshot.source().equals(source)) {
            throw new SecurityException("Truffle parse source differs from verified linked compilation");
        }
        return snapshot.checked();
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
