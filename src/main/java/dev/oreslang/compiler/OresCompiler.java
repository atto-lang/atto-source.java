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

    /**
     * Minted only by OresCompiler after Oreslang type and ownership admission.
     * Unlike AnalysisResult (a presentation value), this class has no public
     * constructor and must not be decoded or synthesized from cache contents.
     *
     * Currently local-only: a cross-file admitted snapshot must instead be
     * constructed by the future trusted SCC linker after linked resolution.
     */
    public static final class CheckedProgram {
        private final Ast.Program program;
        private final java.util.Map<String, TypeChecker.PublicNominalOrigin> nominalOrigins;
        private final java.util.List<String> warnings;

        private CheckedProgram(TypeChecker.CheckedPublicNominals verified) {
            this.program = verified.program();
            this.nominalOrigins = java.util.Map.copyOf(verified.resolvedNames());
            this.warnings = java.util.List.copyOf(verified.warnings());
        }

        public Ast.Program program() { return program; }
        public java.util.Map<String, TypeChecker.PublicNominalOrigin> nominalOrigins() {
            return nominalOrigins;
        }
        public java.util.List<String> warnings() { return warnings; }
    }

    /** Local, no-import front-end admission with an unforgeable checked token. */
    public static CheckedProgram admitLocal(Ast.Program program) {
        return new CheckedProgram(TypeChecker.checkTypesAndSnapshotPublicNominals(program));
    }

    public static CheckedProgram parseAndAdmitLocal(String source) {
        return admitLocal(Parser.parse(source));
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
