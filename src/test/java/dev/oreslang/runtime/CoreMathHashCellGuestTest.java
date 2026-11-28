package dev.oreslang.runtime;

import dev.oreslang.stdlib.CoreLibrarySources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

final class CoreMathHashCellGuestTest {
    @TempDir Path temp;
    @Test
    void freestandingSourcesAreRegisteredWithoutLegacyAliases() throws Exception {
        for (String name : new String[]{"math", "hash", "cell"}) {
            assertTrue(CoreLibrarySources.packagedCorePaths().contains(name + ".atto"));
            String code = CoreLibrarySources.oresCoreSource("ores.core/" + name);
            assertTrue(code.contains("define module " + name + " as"));
            assertFalse(code.contains("java:"));
            assertEquals(name + ".atto", CoreLibrarySources.oresCoreFileName("ores.core/" + name));
            assertThrows(IllegalArgumentException.class,
                    () -> CoreLibrarySources.oresCoreSource("ores.core/" + name + ".ores"));
            assertThrows(IllegalArgumentException.class,
                    () -> CoreLibrarySources.oresCoreSource("ores.core/../" + name));
        }
    }
    @Test
    void linkedAttoGuestMustExecuteAllThreeModules() throws Exception {
        Path main = temp.resolve("main.atto");
        Files.writeString(main, """
                import module math from "ores.core/math";
                import module hash from "ores.core/hash";
                import module cell from "ores.core/cell";
                pub fnc main(): int {
                  if math.min_int(5, 3) != 3; do return 1; fi
                  if math.max_int(-5, 3) != 3; do return 2; fi
                  if math.signum_int(-20) != -1; do return 3; fi
                  if math.signum_int(0) != 0; do return 4; fi
                  if math.contains_closed_int(6, 1, 5); do return 5; fi
                  if !math.contains_closed_int(5, 1, 5); do return 6; fi
                  if hash.initial_state() != 17; do return 7; fi
                  if hash.step_byte(17, 42) != 569; do return 8; fi
                  if hash.two_bytes(42, 7) != 17646; do return 9; fi
                  if hash.step_byte(-1, 5) != -1; do return 10; fi
                  if hash.step_byte(17, 256) != -1; do return 11; fi
                  if hash.from_bool(true) != 528; do return 12; fi
                  if !cell.can_borrow_read(2, false); do return 13; fi
                  if cell.can_borrow_read(2, true); do return 14; fi
                  if cell.can_borrow_write(1, false); do return 15; fi
                  if !cell.can_borrow_write(0, false); do return 16; fi
                  if cell.readers_after_acquire(65535, false) != -1; do return 17; fi
                  if cell.readers_after_acquire(3, false) != 4; do return 18; fi
                  if cell.readers_after_release(0, false) != -1; do return 19; fi
                  if cell.readers_after_release(3, false) != 2; do return 20; fi
                  return 42;
                }
                """);
        assertDoesNotThrow(() -> LinkedProgramRunner.validate(main));
        var execution = LinkedProgramRunner.runWithExitStatus(main,
                IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                new ByteArrayOutputStream(), new ByteArrayOutputStream());
        assertEquals(42, execution.exitStatus());
    }
}
