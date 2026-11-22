package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import dev.oreslang.stdlib.CoreLibrarySources;
import dev.oreslang.types.TypeChecker;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** Compiler-owned .atto source and real guest checks, not a Java port of the helpers. */
final class CoreSliceErrorGuestTest {
    @TempDir Path temp;

    @Test
    void bothFreestandingModulesArePackagedAndChecked() throws Exception {
        for (String module : new String[] {"slice", "error"}) {
            String source = CoreLibrarySources.oresCoreSource("ores.core/" + module);
            assertEquals(module + ".atto",
                    CoreLibrarySources.oresCoreFileName("ores.core/" + module));
            assertTrue(CoreLibrarySources.packagedCorePaths().contains(module + ".atto"));
            assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(source)), module);
        }
    }

    @Test
    void realGuestRunsBoundaryAndCatalogHelpers() throws Exception {
        Path guest = temp.resolve("main.atto");
        Files.writeString(guest, """
                import module slice from "ores.core/slice";
                import module error from "ores.core/error";
                pub fnc main(): int {
                  if slice.normalize_index(-1, 5) == 4 &&
                     slice.normalize_index(-5, 5) == 0 &&
                     slice.normalize_index(-6, 5) == -1 &&
                     slice.normalize_index(0, 0) == -1 &&
                     slice.normalize_bound(-5, 5) == 0 &&
                     slice.normalize_bound(5, 5) == 5 &&
                     slice.normalize_bound(-6, 5) == -1 &&
                     slice.normalize_bound(0, 0) == 0 &&
                     slice.range_len(-2, 5, 5) == 2 &&
                     slice.range_len(4, 1, 5) == 0 &&
                     slice.range_len(0, 6, 5) == -1 &&
                     slice.valid_window(5, 0, 5) &&
                     !slice.valid_window(5, 1, 5) &&
                     slice.valid_strided_window(0, 2, 3, 5) &&
                     !slice.valid_strided_window(1, 2, 3, 5) &&
                     !slice.valid_strided_window(0, -1, 3, 5) &&
                     error.is_known_core_code(0) &&
                     error.is_known_core_code(3090) &&
                     error.is_known_core_code(9014) &&
                     !error.is_known_core_code(9999) &&
                     error.is_bounds_code(1008) &&
                     !error.is_bounds_code(1009) &&
                     error.is_retry_advisory_code(5001) &&
                     !error.is_retry_advisory_code(5002) &&
                     error.is_actor_api_code(2012) &&
                     !error.is_actor_api_code(1009) &&
                     error.valid_message_length(1024, 1024) &&
                     !error.valid_message_length(1025, 1024); do
                    return 42;
                  fi
                  return 1;
                }
                """);
        assertDoesNotThrow(() -> LinkedProgramRunner.validate(guest, Map.of()));
        var outcome = LinkedProgramRunner.runWithExitStatus(
                guest, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                new ByteArrayOutputStream(), new ByteArrayOutputStream());
        assertEquals(42, outcome.exitStatus());
    }

    @Test
    void importedHelperRejectsIncorrectGuestArgumentType() throws Exception {
        Path invalid = temp.resolve("invalid.atto");
        Files.writeString(invalid, """
                import module slice from "ores.core/slice";
                pub fnc main(): int { return slice.normalize_index("bad", 5); }
                """);
        assertThrows(IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(invalid, Map.of()));
    }
}
