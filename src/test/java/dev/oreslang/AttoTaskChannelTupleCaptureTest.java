package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Named Tuple<T...> channel payloads must be proven recursively Send before
 * independently scheduled tasks can share the synchronized Channel handle.
 * No special permission is given to arbitrary nominal types or Rc payloads.
 */
final class AttoTaskChannelTupleCaptureTest {
    @Test
    void independentTasksCanShareChannelOfSendTuplePayload() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                pub async fnc main(): void {
                  val Channel<Tuple<int, List<int>>> queue =
                      Channel.new<Tuple<int, List<int>>>(4);
                  task scope {
                    let Future<void> writer = task {
                      let List<int> value = [1, 2, 3];
                      writech queue, (7, value);
                      return;
                    };
                    let Future<void> reader = task {
                      const Tuple<int, List<int>> received =
                          await (nb readch queue);
                      return;
                    };
                  }
                  return;
                }
                """));
    }

    @Test
    void rejectsChannelWhoseTupleContainsNonSendRc() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub async fnc main(): void {
                          val Channel<Tuple<int, Rc<int>>> queue =
                              Channel.new<Tuple<int, Rc<int>>>(1);
                          task scope {
                            let Future<void> reader = task {
                              const Tuple<int, Rc<int>> received =
                                  await (nb readch queue);
                              return;
                            };
                          }
                          return;
                        }
                        """));
        assertTrue(failure.getMessage().contains("non-Send"), failure::getMessage);
    }
}
