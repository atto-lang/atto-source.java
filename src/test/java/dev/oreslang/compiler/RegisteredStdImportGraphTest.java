package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.stdlib.CoreLibrarySources;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A reserved compiler library is a closed identity, not an ambient project
 * source or an arbitrary caller-selected import resolution. The trusted
 * LinkedProgramRunner must still provide the actual packaged source bytes.
 */
final class RegisteredStdImportGraphTest {
    private static final String APP = "app.atto";
    private static final String HTTP = "@std/http.atto";
    private static final String ATTACKER = "attacker.atto";
    private static final Set<String> SUPPLIED = Set.of(APP, HTTP, ATTACKER);

    private static Ast.ImportDecl parse(String source) {
        return Parser.parse(source + "\n"
                + "pub fnc main(): void { return; }").imports().getFirst();
    }

    private static String resolve(Ast.ImportDecl importDecl, Map<String, String> map,
                                  Set<String> available) {
        return ImportGraph.resolveImportUnitId(APP, importDecl, available,
                Map.of(APP, map));
    }

    @Test
    void stdHttpResolvesOnlyToRegisteredIdentity() {
        Ast.ImportDecl http = parse("import * as http from \"std.http\";");
        assertEquals(HTTP, CoreLibrarySources.requiredRegisteredBuiltinUnitId("std.http"));
        assertEquals(HTTP, resolve(http, Map.of("std.http", HTTP), SUPPLIED));
        assertEquals(HTTP, CoreLibrarySources.requiredRegisteredBuiltinUnitId("std/http.atto"));
        assertEquals(HTTP, CoreLibrarySources.requiredRegisteredBuiltinUnitId("std/http"));
    }

    @Test
    void rejectsMissingMapRatherThanTrustingAnAmbientFilename() {
        Ast.ImportDecl http = parse("import * as http from \"std.http\";");
        var error = assertThrows(IllegalArgumentException.class,
                () -> resolve(http, Map.of(), SUPPLIED));
        assertTrue(error.getMessage().contains("authenticated source-unit resolution"),
                error.getMessage());
    }

    @Test
    void rejectsSpoofedMappedTargetEvenWhenItExists() {
        Ast.ImportDecl http = parse("import * as http from \"std.http\";");
        var error = assertThrows(IllegalArgumentException.class,
                () -> resolve(http, Map.of("std.http", ATTACKER), SUPPLIED));
        assertTrue(error.getMessage().contains("cannot be redirected"),
                error.getMessage());
    }

    @Test
    void rejectsMissingRegisteredSourceUnit() {
        Ast.ImportDecl http = parse("import * as http from \"std.http\";");
        var error = assertThrows(IllegalArgumentException.class,
                () -> resolve(http, Map.of("std.http", HTTP), Set.of(APP)));
        assertTrue(error.getMessage().contains("source unit is missing"), error.getMessage());
    }

    @Test
    void unknownReservedRootsAlwaysFailClosed() {
        for (String bad : new String[] {
                "std.unknown", "std.http3", "std", "alloc.arena",
                "core.result", "std/http.ores", "std/unregistered"
        }) {
            Ast.ImportDecl invalid = parse("import * as x from \"" + bad + "\";");
            assertThrows(IllegalArgumentException.class,
                    () -> resolve(invalid, Map.of(bad, ATTACKER), SUPPLIED), bad);
        }
        assertNull(CoreLibrarySources.requiredRegisteredBuiltinUnitId("./local.atto"));
    }

    @Test
    void existingCoreRegistrationsPreserveTheirExactIdentity() {
        assertEquals("@ores.core/numeric.ores",
                CoreLibrarySources.requiredRegisteredBuiltinUnitId("ores.core/numeric"));
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.requiredRegisteredBuiltinUnitId("ores.core/missing"));
    }
}
