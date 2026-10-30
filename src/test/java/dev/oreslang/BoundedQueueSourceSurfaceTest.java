package dev.oreslang;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

/**
 * Source-surface contract used by rx-oreslang-channels BoundedQueue<T>.
 *
 * <p>This does not duplicate the library implementation. It pins the language
 * features that the userland queue requires: generic Channel<T> fields,
 * Result-returning generic methods, immediate probes, and a suspending
 * priority select that races cancellation/close reads against write
 * admission.</p>
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
                  val Channel<bool> closed = Channel.new<bool>(1);
                  let int generation;

                  constructor(int capacity) {
                    self.items = Channel.new<T>(capacity);
                    self.generation = 1;
                  }

                  pub try_push(T value): Result<bool, String> {
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

                    do select first {
                      when readch cancellation: const ignored {
                        ticket.restore_cancelled();
                        return Err("cancelled");
                      }
                      when readch self.closed: const ignored {
                        try writech self.closed, true;
                        return Err("closed");
                      }
                      when writech self.items, value: {
                        return Ok(true);
                      }
                    }
                  }

                  pub pop_blocking(): Option<T> {
                    do select first {
                      when readch self.items: val value {
                        return Some(value);
                      }
                      when readch self.closed: const ignored {
                        try writech self.closed, true;
                        return None;
                      }
                    }
                  }
                end
                """));

        assertDoesNotThrow(() -> OwnershipChecker.check(program));
    }
}
