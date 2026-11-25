package dev.oreslang;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.Test;

/**
 * Source-surface contract used by rx-oreslang-channels BoundedQueue<T>.
 *
 * <p>This does not duplicate the library implementation. It pins the language
 * features that the userland queue requires: generic Channel<T> fields,
 * Result-returning generic methods, immediate probes, and a suspending
 * priority select that races cancellation against write admission, plus
 * source-visible Channel.close()/is_closed() terminal handling.</p>
 */
final class BoundedQueueSourceSurfaceTest {

    @Test
    void genericTicketedQueueSurfaceTypeChecksAndPassesOwnership() {
        var program = TypeChecker.check(Parser.parse("""
                define class QueueTicket as
                  val Channel<bool> cancelled = Channel.new<bool>(1);
                  val int generation;

                  constructor(int generation) {
                    self.generation = generation;
                  }

                  pub cancellation_signal(): Channel<bool> {
                    return self.cancelled;
                  }

                  pub restore_cancelled(): void {
                    try writech self.cancelled, true;
                    return;
                  }
                end

                define class QueueSlice<T> as
                  val Channel<T> items;
                  let int generation;

                  constructor(int capacity) {
                    self.items = Channel.new<T>(capacity);
                    self.generation = 1;
                  }

                  pub try_push(T value): Result<bool, String> {
                    if self.items.is_closed(); then
                      return Err("closed");
                    fi
                    val bool admitted = try writech self.items, value;
                    if admitted; then
                      return Ok(true);
                    fi
                    return Err("full");
                  }

                  pub push_blocking(
                      T value,
                      QueueTicket ticket
                  ): Result<bool, String> {
                    val Channel<bool> cancellation =
                        ticket.cancellation_signal();

                    try {
                      do select first {
                        when readch cancellation: const ignored {
                          ticket.restore_cancelled();
                          return Err("cancelled");
                        }
                        when writech self.items, value: {
                          return Ok(true);
                        }
                      }
                    } catch (err) {
                      if self.items.is_closed(); then
                        return Err("closed");
                      fi
                      throw err;
                    }
                  }

                  pub pop_blocking(): Option<T> {
                    try {
                      return Some(readch self.items);
                    } catch (err) {
                      if self.items.is_closed(); then
                        return None;
                      fi
                      throw err;
                    }
                  }

                  pub close(): void {
                    self.generation = self.generation + 1;
                    self.items.close();
                    return;
                  }
                end
                """));

        assertDoesNotThrow(() -> OwnershipChecker.check(program));
    }

    @Test
    void actualChannelCloseFailsUncommittedWriteAndPreservesDrain() throws Exception {
        String source = """
                async fnc pending_push(Channel<int> channel): String {
                  try {
                    do select first {
                      when writech channel, 2: {
                        return "written";
                      }
                    }
                  } catch (err) {
                    if channel.is_closed(); then
                      return "closed";
                    fi
                    throw err;
                  }
                  return "unreachable";
                }

                pub async fnc main(): void {
                  val Channel<int> channel = Channel.new<int>(1);
                  writech channel, 1;
                  val Future<String> pending = pending_push(channel);

                  channel.close();

                  stdio.stdout.write(await pending);
                  stdio.stdout.write("|");
                  stdio.stdout.write(readch channel);
                  stdio.stdout.write("|");

                  try {
                    readch channel;
                    stdio.stdout.write("unexpected");
                  } catch (err) {
                    if channel.is_closed(); then
                      stdio.stdout.write("drained");
                    else
                      throw err;
                    fi
                  }
                  return;
                }
                """;

        assertEquals("closed|1|drained", SelectNestedReturnContractTest.run(source));
    }

    @Test
    void queueAndTicketCapabilitiesCannotCrossActorCallableBoundary() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class QueueTicket as
                  val Channel<bool> cancelled = Channel.new<bool>(1);
                end

                pub actor fnc leak_ticket(QueueTicket ticket): void {
                  return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class QueueSlice<T> as
                  val Channel<T> items;

                  constructor(int capacity) {
                    self.items = Channel.new<T>(capacity);
                  }
                end

                pub actor fnc leak_queue(QueueSlice<int> queue): void {
                  return;
                }
                """)));
    }
}
