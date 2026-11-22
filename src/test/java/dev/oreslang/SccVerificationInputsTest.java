package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.ExportInterfaceCodec;
import dev.oreslang.compiler.SccVerificationInputs;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class SccVerificationInputsTest {
    private static final ExportInterfaceCodec.Header ENV =
            new ExportInterfaceCodec.Header("", "compiler21", "x86_64", "strict");

    private static Ast.ImportDecl imported(String path) {
        return new Ast.ImportDecl(Ast.ImportKind.FUNCTION,
                List.of("work"), false, null, path);
    }

    private static Map<String, Ast.Program> units() {
        return Map.of(
                "a.ores", new Ast.Program(List.of(imported("./b.ores")), List.of()),
                "b.ores", new Ast.Program(List.of()));
    }

    private static Map<String, ExportInterfaceCodec.Header> headers() {
        return Map.of("a.ores", ENV, "b.ores", ENV);
    }

    @Test void nestedProviderOverridesAndSourceIdsCannotChangeAfterSnapshot() {
        var sources = new LinkedHashMap<>(units());
        var environments = new LinkedHashMap<>(headers());
        var binding = new LinkedHashMap<String,String>();
        binding.put("./b.ores", "b.ores");
        var overrides = new LinkedHashMap<String,Map<String,String>>();
        overrides.put("a.ores", binding);

        var admittedInputs = SccVerificationInputs.snapshot(
                sources, environments, overrides);
        sources.clear();
        environments.clear();
        overrides.clear();
        binding.clear();

        assertEquals(2, admittedInputs.sources().size());
        assertEquals(2, admittedInputs.environments().size());
        assertEquals("b.ores",
                admittedInputs.overrides().get("a.ores").get("./b.ores"));
        assertThrows(UnsupportedOperationException.class,
                () -> admittedInputs.sources().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> admittedInputs.overrides().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> admittedInputs.overrides().get("a.ores").clear());
    }

    @Test void rejectEmptyMissingOrForeignCompilerEnvironments() {
        assertThrows(IllegalArgumentException.class,
                () -> SccVerificationInputs.snapshot(Map.of(),Map.of(),Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> SccVerificationInputs.snapshot(units(),
                        Map.of("a.ores", ENV),Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> SccVerificationInputs.snapshot(units(),
                        Map.of("a.ores", ENV,"b.ores",ENV,"extra",ENV),Map.of()));
    }

    @Test void rejectDanglingOrAliasedTargetIdentitiesBeforeTypeChecking() {
        assertThrows(IllegalArgumentException.class,
                () -> SccVerificationInputs.snapshot(units(),headers(),
                        Map.of("ghost.ores",Map.of())));
        assertThrows(IllegalArgumentException.class,
                () -> SccVerificationInputs.snapshot(units(),headers(),
                        Map.of("a.ores",Map.of("./missing.ores","b.ores"))));
        assertThrows(IllegalArgumentException.class,
                () -> SccVerificationInputs.snapshot(units(),headers(),
                        Map.of("a.ores",Map.of("./b.ores","ghost.ores"))));
        assertThrows(IllegalArgumentException.class,
                () -> SccVerificationInputs.snapshot(units(),headers(),
                        Map.of("a.ores",Map.of("./b.ores","dir/../b.ores"))));
    }

    @Test void importResourceBudgetIsEnforcedBeforeCompilerAdmission() {
        var oversizedImports = new ArrayList<Ast.ImportDecl>();
        for (int i=0;i<4097;i++) oversizedImports.add(imported("./b.ores"));
        var huge = Map.of("a.ores",new Ast.Program(oversizedImports,List.of()),
                "b.ores",new Ast.Program(List.<Ast.ModuleDecl>of()));
        assertThrows(IllegalArgumentException.class,
                () -> SccVerificationInputs.snapshot(huge,headers(),Map.of()));
    }
}
