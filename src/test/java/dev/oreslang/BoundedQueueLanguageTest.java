package dev.oreslang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.oreslang.compiler.OresCompiler;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
final class BoundedQueueLanguageTest {

    @Test
    void localAsyncProducersShareOneQueueAndPreservePendingWriterOrder()
            throws Exception {
        assertEquals("0|#admitted|1|#admitted|2", run("""
                async fnc push_one(
                    BoundedQueue<int> queue,
                    QueueTicket ticket,
                    int value
                ): QueuePush<int> {
                  return await queue.push_async(value, ticket);
                }

                pub async fnc main(): void {
                  val BoundedQueue<int> queue = BoundedQueue.new<int>(1, 8);

                  val QueueTicket seed_ticket = queue.new_ticket();
                  val QueuePush<int> seed = queue.try_push(0, seed_ticket);

                  val QueueTicket first_ticket = queue.new_ticket();
                  val QueueTicket second_ticket = queue.new_ticket();
                  val Future<QueuePush<int>> first =
                      push_one(queue, first_ticket, 1);
                  val Future<QueuePush<int>> second =
                      push_one(queue, second_ticket, 2);

                  val QueuePop<int> seed_pop = queue.try_pop();
                  stdio.stdout.write(seed_pop.take_value().unwrap());
                  stdio.stdout.write("|");

                  val QueuePush<int> first_result = await first;
                  stdio.stdout.write(first_result.status);
                  stdio.stdout.write("|");

                  val QueuePop<int> first_pop = queue.try_pop();
                  stdio.stdout.write(first_pop.take_value().unwrap());
                  stdio.stdout.write("|");

                  val QueuePush<int> second_result = await second;
                  stdio.stdout.write(second_result.status);
                  stdio.stdout.write("|");

                  val QueuePop<int> second_pop = queue.try_pop();
                  stdio.stdout.write(second_pop.take_value().unwrap());
                  return;
                }
                """));
    }

    @Test
    void cancellingOlderProducerReturnsItsPayloadAndLaterProducerAdvances()
            throws Exception {
        assertEquals(
                "true|#cancelled|11|#admitted|12",
                run("""
                async fnc push_one(
                    BoundedQueue<int> queue,
                    QueueTicket ticket,
                    int value
                ): QueuePush<int> {
                  return await queue.push_async(value, ticket);
                }

                pub async fnc main(): void {
                  val BoundedQueue<int> queue = BoundedQueue.new<int>(1, 8);
                  val QueuePush<int> seed =
                      queue.try_push(10, queue.new_ticket());

                  val QueueTicket older = queue.new_ticket();
                  val QueueTicket later = queue.new_ticket();
                  val Future<QueuePush<int>> older_push =
                      push_one(queue, older, 11);
                  val Future<QueuePush<int>> later_push =
                      push_one(queue, later, 12);

                  stdio.stdout.write(older.cancel());
                  stdio.stdout.write("|");

                  val QueuePop<int> seed_pop = queue.try_pop();
                  val int ignored = seed_pop.take_value().unwrap();

                  val QueuePush<int> cancelled = await older_push;
                  stdio.stdout.write(cancelled.status);
                  stdio.stdout.write("|");
                  stdio.stdout.write(cancelled.take_value().unwrap());
                  stdio.stdout.write("|");

                  val QueuePush<int> admitted = await later_push;
                  stdio.stdout.write(admitted.status);
                  stdio.stdout.write("|");

                  val QueuePop<int> delivered = queue.try_pop();
                  stdio.stdout.write(delivered.take_value().unwrap());
                  return;
                }
                """));
    }

    @Test
    void fullReturnsOwnershipOfMoveOnlyPayload() throws Exception {
        assertEquals("2|1", run("""
                define class Box as
                  val int id;

                  constructor(int id) {
                    self.id = id;
                  }
                end

                pub fnc main(): void {
                  val BoundedQueue<Box> queue = BoundedQueue.new<Box>(1, 4);

                  val QueuePush<Box> first =
                      queue.try_push(new Box(1), queue.new_ticket());

                  val Box second = new Box(2);
                  val QueuePush<Box> rejected =
                      queue.try_push(second, queue.new_ticket());

                  val Box recovered = rejected.take_value().unwrap();
                  stdio.stdout.write(recovered.id);
                  stdio.stdout.write("|");

                  val QueuePop<Box> popped = queue.try_pop();
                  val Box original = popped.take_value().unwrap();
                  stdio.stdout.write(original.id);
                  return;
                }
                """));
    }

    @Test
    void closeInvalidatesUnclaimedTicketAndDrainsCommittedValue()
            throws Exception {
        assertEquals("#stale_ticket|22|#closed_drained", run("""
                pub fnc main(): void {
                  val BoundedQueue<int> queue = BoundedQueue.new<int>(1, 4);
                  val QueuePush<int> first =
                      queue.try_push(22, queue.new_ticket());
                  val QueueTicket stale = queue.new_ticket();

                  queue.close();

                  val QueuePush<int> rejected = queue.try_push(23, stale);
                  stdio.stdout.write(rejected.status);
                  stdio.stdout.write("|");

                  val QueuePop<int> value = queue.try_pop();
                  stdio.stdout.write(value.take_value().unwrap());
                  stdio.stdout.write("|");

                  val QueuePop<int> terminal = queue.try_pop();
                  stdio.stdout.write(terminal.status);
                  return;
                }
                """));
    }

    @Test
    void queueCapabilitiesCannotCrossActorCallableBoundaries() {
        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("""
                        pub actor fnc leak(BoundedQueue<int> queue): void {
                          return;
                        }
                        """));

        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("""
                        pub actor fnc leak(QueueTicket ticket): void {
                          return;
                        }
                        """));

        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("""
                        pub actor fnc leak(QueuePush<int> result): void {
                          return;
                        }
                        """));
    }

    @Test
    void moveOnlyRejectedPayloadMustBeRecoveredFromResultNotOriginalBinding() {
        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("""
                        define class Box as
                          val int id;
                          constructor(int id) { self.id = id; }
                        end

                        fnc bad(): int {
                          val BoundedQueue<Box> queue = BoundedQueue.new<Box>(1);
                          val QueuePush<Box> first =
                              queue.try_push(new Box(1), queue.new_ticket());
                          val Box second = new Box(2);
                          val QueuePush<Box> rejected =
                              queue.try_push(second, queue.new_ticket());
                          return second.id;
                        }
                        """));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                        OresLanguage.ID,
                        program,
                        "bounded-queue-language.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
