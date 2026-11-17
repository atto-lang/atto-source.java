package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 'do' discards a channel operation's value, not its suspension obligation.
 * The pending source execution must resume on the scheduler, without parking
 * an actor/root carrier, and the channel must commit exactly one operation.
 */
@Timeout(15)
final class DoChannelSuspensionLanguageTest {
    @Test
    void doReadchConsumesAndDiscardsExactlyOneValue() throws Exception {
        assertEquals("22", run("""
                pub async routine main(): void {
                  val Channel<int> ch = Channel.new<int>(2);
                  do writech ch, 11;
                  do writech(ch, 22);
                  do readch(ch);
                  stdio.stdout.write(readch ch);
                  return;
                }
                """));
    }

    @Test
    void doWritechSuspendsUnderBackpressureAndResumes() throws Exception {
        assertEquals("false|41|42", run("""
                async fnc produce(Channel<int> data, Channel<bool> ready): void {
                  do writech data, 41;
                  val bool admitted = try writech data, 99;
                  do writech ready, admitted;
                  do writech data, 42;
                  return;
                }
                pub async routine main(): void {
                  val Channel<int> data = Channel.new<int>(1);
                  val Channel<bool> ready = Channel.new<bool>(1);
                  val Future<void> producer = produce(data, ready);
                  val bool extra = readch ready;
                  val int first = readch data;
                  val int second = readch data;
                  await producer;
                  stdio.stdout.write(extra);
                  stdio.stdout.write("|");
                  stdio.stdout.write(first);
                  stdio.stdout.write("|");
                  stdio.stdout.write(second);
                  return;
                }
                """));
    }

    @Test
    void doReadchSuspendsOnZeroCapacityChannelAndDiscardsValue() throws Exception {
        assertEquals("done", run("""
                async fnc produce(Channel<int> data, Channel<int> ready): void {
                  do writech ready, 1;
                  do writech data, 17;
                  return;
                }
                pub async routine main(): void {
                  val Channel<int> data = Channel.new<int>(0);
                  val Channel<int> ready = Channel.new<int>(1);
                  val Future<void> producer = produce(data, ready);
                  do readch ready;
                  do readch data;
                  await producer;
                  stdio.stdout.write("done");
                  return;
                }
                """));
    }

    @Test
    void doNbCannotSilentlyDetachPendingFuture() {
        for (String operation : new String[]{
                "do nb readch ch;", "do nb writech ch, 42;"}) {
            String source = """
                    pub routine main(): void {
                      val Channel<int> ch = Channel.new<int>(1);
                      %s
                      return;
                    }
                    """.formatted(operation);
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class, () -> Parser.parse(source));
            assertTrue(failure.getMessage().contains("discard a pending Future"),
                    failure::getMessage);
        }
    }

    @Test
    void doChannelStillRequiresStaticallyTypedChannel() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub routine main(): void {
                          do readch 4;
                          return;
                        }
                        """));
        assertTrue(failure.getMessage().contains("channel"),
                failure::getMessage);
    }

    @Test
    void numericShiftOperatorsRemainNumeric() throws Exception {
        assertEquals("8|4", run("""
                pub routine main(): void {
                  val int a = 1 << 3;
                  val int b = a >> 1;
                  stdio.stdout.write(a);
                  stdio.stdout.write("|");
                  stdio.stdout.write(b);
                  return;
                }
                """));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, program,
                            "do-channel-suspension.ores")
                    .mimeType(OresLanguage.MIME_TYPE).buildLiteral());
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
