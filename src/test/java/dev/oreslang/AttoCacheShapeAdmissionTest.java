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
 * Actual linked .atto guest source, not a host-side model.
 * Exercises the LRU's array, loop, constructor, mutable receiver and Option
 * syntax without exporting the private lru-cache.atto source to this repo.
 * This test needs stepful CI and successful guest output before it is evidence.
 */
final class AttoCacheShapeAdmissionTest {
    @TempDir Path temp;

    @Test
    void integerColumnsBoundedLoopsAndOptionLinkAsAttoGuest() throws Exception {
        Path library = temp.resolve("int-columns.atto");
        Path entry = temp.resolve("main.atto");
        Files.writeString(library, """
                define pub class IntColumns as
                  val int capacity;
                  let mut Array<int> keys = new Array<int>();
                  let mut Array<int> values = new Array<int>();
                  let int newest = -1;

                  pub constructor(int capacity) {
                    self.capacity = capacity;
                  }

                  pub fnc initialize(mut self)() -> bool {
                    if self.capacity < 1; then return false; fi
                    for (let i = 0; i < self.capacity; i++) {
                      self.keys.add(0);
                      self.values.add(0);
                    }
                    return self.keys.size == self.capacity;
                  }

                  pub fnc put(mut self)(int slot, int key, int value) -> bool {
                    if slot < 0 || slot >= self.capacity; then return false; fi
                    self.keys.set(slot, key);
                    self.values.set(slot, value);
                    self.newest = slot;
                    return true;
                  }

                  pub peek(int key) -> Option<int> {
                    for (let i = 0; i < self.capacity; i++) {
                      if self.keys.get(i) == key; then
                        return Some(self.values.get(i));
                      fi
                    }
                    return None;
                  }

                  pub latest() -> Option<int> {
                    if self.newest == -1; then return None; fi
                    return Some(self.values.get(self.newest));
                  }
                end
                """);
        Files.writeString(entry, """
                import class IntColumns from "./int-columns.atto";
                pub fnc main(): void {
                  let mut IntColumns columns = new IntColumns(3);
                  if !columns.initialize(); then return; fi
                  columns.put(0, 10, 40);
                  columns.put(1, 20, 42);
                  if !columns.peek(999).is_none(); then return; fi
                  stdio.stdout.write(columns.latest().unwrap());
                  return;
                }
                """);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                entry, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), Set.of(), Map.of(),
                stdout, new ByteArrayOutputStream());
        assertEquals("42", stdout.toString(StandardCharsets.UTF_8));
    }
}
