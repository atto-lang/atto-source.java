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
        return TreeShaker.shake(parseAndTypeCheck(source), options);
    }

    /**
     * Trusted compile-only GPU boundary, wired through the normal compiler
     * entry point. This artifact is not an executable GPU capability and the
     * guest parser intentionally continues to reject GPU declarations.
     *
     * The restricted source grammar is independently type/effect checked by
     * AcceleratorSourceLowering (only pure finite f32 operations permitted).
     */
    public static dev.oreslang.runtime.accel.DopKernelIR.OpenClArtifact compileGpuSource(
            String source,
            dev.oreslang.runtime.DataOrientedLayout.Plan layout,
            dev.oreslang.runtime.accel.DopKernelIR.GpuTarget deviceProfile) {
        java.util.Objects.requireNonNull(source, "source");
        java.util.Objects.requireNonNull(layout, "layout");
        java.util.Objects.requireNonNull(deviceProfile, "deviceProfile");
        return dev.oreslang.runtime.accel.AcceleratorSourceLowering
                .compileGpu(source, layout).emitOpenCl(deviceProfile);
    }

    /**
     * Trusted compile-only quantum source to typed OpenQASM3 circuit.
     * Explicit simulator or hardware selection is deferred to the caller;
     * this method never submits an execution job or silently simulates one.
     */
    public static dev.oreslang.runtime.accel.QuantumCircuitIR.Circuit compileQuantumSource(
            String source) {
        java.util.Objects.requireNonNull(source, "source");
        return dev.oreslang.runtime.accel.AcceleratorSourceLowering
                .compileQuantum(source).circuit();
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
