package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(25)
final class DoChannelDiscardRuntimeTest {
    private static String run(String source) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder("ores")
                .allowAllAccess(true)
                .out(output)
                .build()) {
            context.eval(Source.newBuilder("ores", source, "do-channel-discard.ores").build());
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    void blockingDiscardedReadConsumesExactlyOneMessage() throws Exception {
        assertEquals("2", run("""
                pub routine main(): void {
                  val Channel<int> input = Channel.new<int>(2);
                  do writech input, 1;
                  do writech input, 2;
                  do readch input;
                  stdio.stdout.write(readch input);
                  return;
                }
                """));
    }

    @Test
    void blockingDiscardedReadCooperatesWithPendingRendezvousWriter() throws Exception {
        assertEquals("ok", run("""
                pub async routine main(): void {
                  val Channel<int> input = Channel.new<int>(0);
                  val Future<void> pending = nb writech input, 99;
                  do readch input;
                  await pending;
                  stdio.stdout.write("ok");
                  return;
                }
                """));
    }

    @Test
    void actorScopedDiscardedNonblockingReadConsumesOneBufferedMessage() throws Exception {
        assertEquals("2", run("""
                actor fnc worker(): void {
                  val Channel<int> input = Channel.new<int>(2);
                  do writech input, 1;
                  do writech input, 2;
                  do nb readch input;
                  stdio.stdout.write(readch input);
                  return;
                }
                pub routine main(): void {
                  worker();
                  return;
                }
                """));
    }

    @Test
    void actorScopedDiscardedNonblockingWriteRetainsPayloadUntilDelivery() throws Exception {
        assertEquals("9", run("""
                actor fnc worker(): void {
                  val Channel<int> input = Channel.new<int>(1);
                  do nb writech input, 9;
                  stdio.stdout.write(readch input);
                  return;
                }
                pub routine main(): void {
                  worker();
                  return;
                }
                """));
    }

    @Test
    void discardedNonblockingWriteMovesOwnedPayloadBeforeNextStatement() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub let int value = 7;
                        end
                        actor fnc bad(): void {
                          val Channel<Box> output = Channel.new<Box>(1);
                          val Box item = new Box();
                          do nb writech output, item;
                          stdio.stdout.write(item.value);
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().contains("moved"), error.getMessage());
    }

    @Test
    void abandonedChannelFutureCannotBeDetachedInOrdinaryFunction() {
        for (String op : new String[]{"do nb readch ch;", "do nb writech ch, 1;"}) {
            var error = assertThrows(IllegalArgumentException.class, () ->
                    TypeChecker.check(Parser.parse("""
                    fnc bad(): void {
                      val Channel<int> ch = Channel.new<int>(1);
                      %s
                      return;
                    }
                    """.formatted(op))));
            assertTrue(error.getMessage().contains("actor execution domain"), error.getMessage());
        }
    }
}
