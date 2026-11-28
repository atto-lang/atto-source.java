package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Real source-level channel operations must suspend a SourceTask, not block a
 * scheduler carrier or disguise a full bounded channel as an unbounded queue.
 */
@Timeout(15)
final class SourceChannelCooperativeBackpressureTest {
    @Test
    void suspendedWriterResumesAfterAnotherTaskDrainsBufferedChannel() throws Exception {
        assertEquals("false|41|42", run("""
                async fnc produce(Channel<int> data, Channel<bool> ready): void {
                  writech data, 41;
                  val bool admitted = try writech data, 99;
                  writech ready, admitted;
                  writech data, 42;
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
    void suspendedReaderResumesAfterRendezvousWriteWithoutParking() throws Exception {
        assertEquals("17", run("""
                async fnc consume(Channel<int> data, Channel<int> started): int {
                  writech started, 1;
                  return readch data;
                }

                pub async routine main(): void {
                  val Channel<int> data = Channel.new<int>(0);
                  val Channel<int> started = Channel.new<int>(1);
                  val Future<int> reader = consume(data, started);
                  val int marker = readch started;
                  writech data, 17;
                  stdio.stdout.write(await reader);
                  return;
                }
                """));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, program,
                    "source-channel-cooperative-backpressure.ores")
                    .mimeType(OresLanguage.MIME_TYPE).buildLiteral());
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
