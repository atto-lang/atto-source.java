package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.stdlib.CoreLibrarySources;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Host-side G0 import graph regression; not a source-compiled native FFI proof. */
final class UnsafeLinkedImportAdmissionTest {
    private static Ast.Program benignImport() {
        return Parser.parse("import module safe_api from \"pkg:safe_api\";");
    }

    @Test
    void trustedLookingImportAliasCannotResolveToUnsafeStdlibUnit() {
        Ast.ImportDecl imported = benignImport().imports().getFirst();
        for (String target : new String[] {
                "@std/unsafe", "@std/unsafe.ores", "@std/unsafe/ffi.ores",
                "@ores.std/unsafe/memory.ores", "@std\\unsafe\\native.ores",
                "@std.unsafe.ffi", "@std:unsafe.ffi", "@ores.std:unsafe",
                "@std/./unsafe/ffi", "@std/other/../unsafe/native",
                "@ores.std//unsafe/memory", "@./std/unsafe/ffi",
                "@pkg/../std/unsafe/memory"}) {
            assertTrue(CoreLibrarySources.isUnsafeStdUnitId(target), target);
            SecurityException denied = assertThrows(SecurityException.class, () ->
                    ImportGraph.resolveImportUnitId(
                            "src/main.ores", imported, Set.of(target),
                            Map.of("src/main.ores", Map.of("pkg:safe_api", target))), target);
            assertTrue(denied.getMessage().contains("unsafe standard-library"), target);
        }
    }

    @Test
    void unsafeUnitCannotEnterLinkedProgramWithoutAnExplicitImport() {
        Ast.Program simple = Parser.parse("pub routine main(): void { return; }");
        Map<String, Ast.Program> units = Map.of(
                "src/main.ores", simple, "@std/unsafe/ffi.ores", simple);
        assertThrows(SecurityException.class, () ->
                ImportGraph.resolveDependencies(units, units.keySet()));
        assertThrows(SecurityException.class, () ->
                ImportGraph.validateLinkedImports(units));
    }

    @Test
    void unrelatedModulePathsAndUnitIdsAreNotMistakenForUnsafeStdlib() {
        for (String unit : new String[] {
                "@std/unsafe_utils.ores", "@std/safe.ores",
                "src/unsafe.ores", "@ores.core/numeric.ores",
                "@std/unsafe-math.ores",
                "@std/./unsafe_tools.ores", "@std/other/../safe.ores",
                "@pkg/std/unsafe/memory.ores"}) {
            assertFalse(CoreLibrarySources.isUnsafeStdUnitId(unit), unit);
        }
    }
}
