package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.imports.ImportRules;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

final class UnsafeImportAdmissionTest {
    private Ast.ImportDecl imported(String path) {
        return new Ast.ImportDecl(Ast.ImportKind.MODULE, List.of("ffi"), false, null, path);
    }

    @Test void rejectsNativePathsBeforeResolverAndExplicitRedirects() {
        for (String path : List.of("std/unsafe/ffi", "ores.std/unsafe", "atto.std.unsafe.ffi",
                "atto::std::unsafe::ffi", "std::unsafe::ffi", "./atto.std/unsafe",
                "atto.std/other/../unsafe/ffi", "std/unsafe/../safe", "atto.std/unsafe/../safe",
                "atto::std/unsafe/../safe", "ores:std/unsafe/../safe",
                "atto::std/../safe", "ores:std/../safe", "atto/std/../safe", "ores/std/../safe")) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> ImportGraph.resolveImportUnitId("main.atto", imported(path), Set.of(path)));
            assertTrue(error.getMessage().contains("E_UNSAFE_DOMAIN"), error::getMessage);
        }
        var error = assertThrows(IllegalArgumentException.class,
                () -> ImportGraph.resolveImportUnitId("main.atto", imported("safe"), Set.of("std/unsafe/ffi"),
                        Map.of("main.atto", Map.of("safe", "std/unsafe/ffi"))));
        assertTrue(error.getMessage().contains("E_UNSAFE_DOMAIN"), error::getMessage);
        for (String path : List.of("std/unsafe_tools", "atto.std:unsafe-math", "pkg/std/unsafe", "atto.std/safe"))
            assertFalse(ImportRules.isReservedUnsafeNativePath(path), path);
    }
}
