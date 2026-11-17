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
     * Trusted host linkage handoff. IncrementalCompiler has already checked
     * every imported declaration and its ownership rules; Truffle must execute
     * that exact AST rather than re-typecheck it without the linked signatures.
     * This is scoped to the parsing carrier and fails closed on source drift.
     */
    private static final ThreadLocal<MapSourceScope> LINKED_PARSE = new ThreadLocal<>();

    private record MapSourceScope(java.util.Map<String, IncrementalCompiler.CompiledUnit> units,
                                  Thread owner) { }

    public static final class LinkedParseScope implements AutoCloseable {
        private final MapSourceScope state;
        private boolean closed;

        private LinkedParseScope(MapSourceScope state) { this.state = state; }

        @Override public void close() {
            if (closed) return;
            if (Thread.currentThread() != state.owner() || LINKED_PARSE.get() != state) {
                throw new SecurityException("linked parser scope closed outside owning compilation carrier");
            }
            LINKED_PARSE.remove();
            closed = true;
        }
    }

    public static LinkedParseScope beginLinkedParse(IncrementalCompiler.BuildResult checked) {
        java.util.Objects.requireNonNull(checked, "checked link build");
        if (!IncrementalCompiler.isCheckedBuild(checked)) {
            throw new SecurityException(
                    "linked parse requires an exact compiler-issued, fully validated build result");
        }
        if (LINKED_PARSE.get() != null) throw new IllegalStateException(
                "nested linked parse scopes are prohibited");
        MapSourceScope state = new MapSourceScope(java.util.Map.copyOf(checked.units()),
                Thread.currentThread());
        LINKED_PARSE.set(state);
        return new LinkedParseScope(state);
    }

    /** Returns null outside the trusted linked-loader parse scope. */
    public static Ast.Program linkedProgramOrNull(String codeUnitId, String sourceText) {
        MapSourceScope active = LINKED_PARSE.get();
        if (active == null) return null;
        if (Thread.currentThread() != active.owner()) throw new SecurityException(
                "linked source parser moved across compilation carriers");
        IncrementalCompiler.CompiledUnit unit = active.units().get(codeUnitId);
        if (unit == null) return null;
        if (!unit.sourceText().equals(sourceText)) throw new SecurityException(
                "linked source bytes changed after type/ownership validation: " + codeUnitId);
        return unit.program();
    }

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
