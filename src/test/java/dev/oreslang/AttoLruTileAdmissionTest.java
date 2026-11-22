package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A source-local, independently authored AoSoA guest probe.
 *
 * This is intentionally not a copy of the private lru-cache.atto library.
 * It checks the minimal linked-guest features needed by its payload layer,
 * and must not be reported as full cache execution or AOT memory proof.
 */
final class AttoLruTileAdmissionTest {
    @TempDir Path temp;

    @Test
    void linkedAttoTiledPayloadKeepsTailSlotsDistinctAcrossWidths() throws Exception {
        Path library = temp.resolve("small-tiled-column.atto");
        Path entry = temp.resolve("main-tiled-column.atto");
        Files.writeString(library, """
                define pub class SmallTiledColumn as
                  val int width;
                  let mut Array<int> cells = new Array<int>();

                  pub constructor(int tileWidth) {
                    self.width = tileWidth;
                    let int tileCount = (65 + tileWidth - 1) / tileWidth;
                    for (let i = 0; i < tileCount * tileWidth * 3; i++) {
                      self.cells.add(0);
                    }
                  }

                  offset(int slot, int field) -> int {
                    let int tile = slot / self.width;
                    let int lane = slot - tile * self.width;
                    return tile * (self.width * 3) + field * self.width + lane;
                  }

                  pub fnc store(mut self)(int slot, int key, int value) -> void {
                    self.cells.set(self.offset(slot, 0), key);
                    self.cells.set(self.offset(slot, 1), value);
                    self.cells.set(self.offset(slot, 2), 1);
                    return;
                  }

                  pub fnc read(int slot) -> int {
                    if self.cells.get(self.offset(slot, 2)) == 0; then return -1; fi
                    return self.cells.get(self.offset(slot, 1));
                  }

                  pub fnc erase(mut self)(int slot) -> void {
                    self.cells.set(self.offset(slot, 0), 0);
                    self.cells.set(self.offset(slot, 1), 0);
                    self.cells.set(self.offset(slot, 2), 0);
                    return;
                  }
                end
                """);
        Files.writeString(entry, """
                import class SmallTiledColumn from "./small-tiled-column.atto";

                fnc exercise(int width) -> bool {
                  let SmallTiledColumn column = new SmallTiledColumn(width);
                  column.store(0, 10, 20);
                  column.store(1, 12, -7);
                  column.store(64, 11, 22);
                  let int before = column.read(1);
                  if column.read(0) != 20; then return false; fi
                  if before != -7 || column.read(64) != 22; then return false; fi
                  column.erase(1);
                  if column.read(1) != -1; then return false; fi
                  if column.read(0) != 20 || column.read(64) != 22; then return false; fi
                  column.store(1, 13, 30);
                  if column.read(1) != 30 || column.read(64) != 22; then return false; fi
                  if before != -7; then return false; fi
                  return true;
                }

                pub fnc main(): void {
                  if !exercise(1) || !exercise(4) || !exercise(8) ||
                     !exercise(16) || !exercise(32) || !exercise(64); then
                    stdio.stdout.write(0);
                    return;
                  fi
                  stdio.stdout.write(42);
                  return;
                }
                """);
        // Separate source-linking and execution observations. This single
        // linked-guest check does not establish an independent AOT link step.
        assertEquals(2, LinkedProgramRunner.validate(entry).units().size());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        var run = LinkedProgramRunner.run(
                entry, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), Set.of(), Map.of(), out, err);
        assertEquals(2, run.units().size());
        assertEquals("42", out.toString(StandardCharsets.UTF_8));
        assertEquals("", err.toString(StandardCharsets.UTF_8));
    }
}
