package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Regression for exact public module-contract imports across source units. */
final class LinkedModuleContractAdmissionTest {
    private static Map<String, String> sources() {
        LinkedHashMap<String, String> units = new LinkedHashMap<>();
        units.put("src/contracts/RouteContract.ores", """
                pub define contract RouteContract as
                  fnc get(String request) => String;
                  fnc post(String request) => String;
                end
                """);
        units.put("src/routes/rest/handlers.ores", """
                define module handlers as
                  pub fnc get(String request): String { return request; }
                  pub fnc post(String request): String { return request; }
                end
                """);
        units.put("src/routes/rest/route.ores", """
                import contract RouteContract from "../../contracts/RouteContract";
                import module handlers from "./handlers";
                define module Route conforms RouteContract as
                  pub fnc get(String request): String { return handlers.get(request); }
                  pub fnc post(String request): String { return handlers.post(request); }
                end
                """);
        return units;
    }

    @Test
    void validatesAcrossRealSourceUnitBoundaries() {
        var compiler = new IncrementalCompiler();
        var result = compiler.compile(sources());
        assertEquals(3, result.units().size());
        assertEquals(3, result.rebuiltUnits().size());
        var second = compiler.compile(sources());
        assertTrue(second.rebuiltUnits().isEmpty());
        assertEquals(3, second.reusedUnits().size());
    }

    @Test
    void importOfPrivateContractIsRejectedBeforeTypeChecking() {
        var units = sources();
        units.put("src/contracts/RouteContract.ores",
                units.get("src/contracts/RouteContract.ores")
                        .replace("pub define contract", "define contract"));
        assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(units));
    }

    @Test
    void importedContractStillRequiresCompleteImplementation() {
        var units = sources();
        units.put("src/routes/rest/route.ores",
                units.get("src/routes/rest/route.ores")
                        .replace("  pub fnc post(String request): String { return handlers.post(request); }",
                                ""));
        assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(units));
    }

    @Test
    void contractAbiChangesRecheckImporter() {
        var compiler = new IncrementalCompiler();
        compiler.compile(sources());
        var revised = sources();
        revised.put("src/contracts/RouteContract.ores",
                revised.get("src/contracts/RouteContract.ores")
                        .replace("end", "  fnc remove(String request) => String;\nend"));
        assertThrows(IllegalArgumentException.class,
                () -> compiler.compile(revised));
        // A failed update must not certify a cache entry for the invalid ABI.
        var restored = compiler.compile(sources());
        assertEquals(3, restored.reusedUnits().size());
    }
}
