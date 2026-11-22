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

    @Test
    void allThreeCoreImportNamesShareOneSealedSourceUnit() throws Exception {
        for (String module : new String[] {"slice", "error"}) {
            String canonical = CoreLibrarySources.oresCoreSource("ores.core/" + module);
            String unit = CoreLibrarySources.oresCoreUnitId("ores.core/" + module);
            for (String path : new String[] {
                    "core/" + module, "atto.core/" + module,
                    "ores.core/" + module, "atto.core/" + module + ".atto"
            }) {
                assertTrue(CoreLibrarySources.isOresCoreImport(path));
                assertEquals(canonical, CoreLibrarySources.oresCoreSource(path));
                assertEquals(unit, CoreLibrarySources.oresCoreUnitId(path));
            }
            assertThrows(IllegalArgumentException.class, () ->
                    CoreLibrarySources.oresCoreSource("core/../" + module));
            assertThrows(IllegalArgumentException.class, () ->
                    CoreLibrarySources.oresCoreSource("atto.core/" + module + ".ores"));
        }
    }

    @Test
    void copyableArraySliceOperationsExecuteThroughPublicImports() throws Exception {
        Path guest = temp.resolve("slice-guest.atto");
        Files.writeString(guest, """
                import module slice from "atto.core/slice";
                import module error from "core/error";
                pub fnc main(): int {
                  let mut Array<int> values = new Array<int>();
                  values.add(0);
                  values.add(20);
                  values.add(30);
                  values.add(40);
                  if slice.valid_index(-1, 4); do return 24; fi
                  if !slice.valid_index(3, 4); do return 25; fi
                  if !slice.valid_signed_index(-1, 4); do return 26; fi
                  if slice.valid_signed_index(-5, 4); do return 27; fi
                  if !slice.is_empty(0); do return 28; fi
                  if slice.is_empty(-1); do return 29; fi
                  if !slice.can_split_at(4, 4); do return 30; fi
                  if slice.can_split_at(4, 5); do return 31; fi
                  if !slice.valid_range(1, 3, 4); do return 1; fi
                  if slice.valid_range(3, 2, 4); do return 2; fi
                  if !slice.valid_endpoints(1, 4, 4); do return 3; fi
                  if !slice.get_copy_int(values, 1, 2, -1).is_some(); do return 4; fi
                  if slice.get_copy_int(values, 1, 2, -1).unwrap_or(-1) != 30; do return 5; fi
                  if !slice.get_copy_int(values, 0, 4, 0).is_some(); do return 6; fi
                  if slice.get_copy_int(values, 0, 4, 0).unwrap_or(-1) != 0; do return 7; fi
                  if !slice.get_copy_int(values, 4, 0, 0).is_none(); do return 8; fi
                  if !slice.get_copy_int(values, 1, 2, -3).is_none(); do return 9; fi
                  if !slice.get_copy_int(values, 3, 2, 0).is_none(); do return 10; fi
                  if slice.last_copy_int(values, 1, 2).unwrap_or(-1) != 30; do return 11; fi
                  if !slice.first_copy_int(values, 4, 0).is_none(); do return 12; fi
                  if slice.subslice_offset(1, 3, 4, 1, 2).unwrap_or(-1) != 2; do return 13; fi
                  if !slice.subslice_offset(1, 3, 4, 2, 2).is_none(); do return 14; fi
                  if !slice.valid_reverse_window(2, -1, 3, 4); do return 15; fi
                  if slice.valid_reverse_window(2, -2, 3, 4); do return 16; fi
                  if !slice.valid_reverse_window(0, -9223372036854775808, 1, 4); do return 17; fi
                  if slice.valid_reverse_window(0, -9223372036854775808, 2, 4); do return 18; fi
                  if error.code_group(3001).unwrap_or(-1) != 3000; do return 19; fi
                  if !error.code_group(3090).is_some(); do return 20; fi
                  if !error.code_group(3091).is_none(); do return 21; fi
                  val Option<int> before = slice.get_copy_int(values, 0, 4, 0);
                  values.set(0, 99);
                  if before.unwrap_or(-1) != 0; do return 22; fi
                  if slice.get_copy_int(values, 0, 4, 0).unwrap_or(-1) != 99; do return 23; fi
                  return 42;
                }
                """);
        assertDoesNotThrow(() -> LinkedProgramRunner.validate(guest, Map.of()));
        var outcome = LinkedProgramRunner.runWithExitStatus(
                guest, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                new ByteArrayOutputStream(), new ByteArrayOutputStream());
        assertEquals(42, outcome.exitStatus());
    }

    @Test
    void copyHelperDoesNotAcceptNoncopyElements() throws Exception {
        Path guest = temp.resolve("not-copy.atto");
        Files.writeString(guest, """
                import module slice from "core/slice";
                pub fnc main(): int {
                  val Array<String> values = new Array<String>();
                  return slice.get_copy_int(values, 0, 0, 0).unwrap_or(42);
                }
                """);
        assertThrows(IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(guest, Map.of()));
    }
}
