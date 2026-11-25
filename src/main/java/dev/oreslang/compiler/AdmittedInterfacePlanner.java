package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Trusted, local-only bridge from compiler-issued admission tokens to the
 * otherwise purely structural interface/SCC invalidation plan.
 *
 * This does not admit linked imports or authorize executable-object cache
 * reuse. The next multi-unit linker must issue equivalent checker tokens
 * only after the entire SCC has passed cross-file type/ownership checks.
 */
public final class AdmittedInterfacePlanner {
    private AdmittedInterfacePlanner() { }

    public static InterfaceSccPlanner.Plan planLocal(
            Map<String, OresCompiler.CheckedProgram> admitted,
            Map<String, ExportInterfaceCodec.Header> environmentByUnit) {
        Objects.requireNonNull(admitted, "admitted");
        Objects.requireNonNull(environmentByUnit, "environmentByUnit");
        if (!admitted.keySet().equals(environmentByUnit.keySet())) {
            throw new IllegalArgumentException("checker tokens and compiler environments differ");
        }
        Map<String, Ast.Program> programs = new LinkedHashMap<>();
        Map<String, Map<String, String>> noExternalImports = new LinkedHashMap<>();
        for (Map.Entry<String, OresCompiler.CheckedProgram> source : admitted.entrySet()) {
            OresCompiler.CheckedProgram token =
                    Objects.requireNonNull(source.getValue(), "compiler-issued checked token");
            if (!token.program().imports().isEmpty()) {
                throw new IllegalArgumentException("unlinked checked token cannot import providers");
            }
            programs.put(source.getKey(), token.program());
            noExternalImports.put(source.getKey(), Map.of());
        }
        return InterfaceSccPlanner.fromCheckedPrograms(
                programs, environmentByUnit, Map.of(), noExternalImports);
    }
}
