package dev.oreslang;

import org.graalvm.polyglot.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.io.ByteArrayOutputStream;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
final class ConcurrencyDeadlineLanguageTest {
    @Test void ownedReadTimeoutIsCatchableAndChannelRemainsUsable() {
        String program = """
                pub async fnc main(): void {
                  val Channel<int> channel = Channel.new<int>(1);
                  val Future<int> read = nb readch channel;
                  try {
                    await read.with_timeout(30);
                  } catch (err) {
                    stdio.stdout.write("timeout:");
                  }
                  writech channel, 42;
                  stdio.stdout.write(readch channel);
                  return;
                }
                """;
        var output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).out(output).build()) {
            context.eval(OresLanguage.ID, program);
        }
        assertEquals("timeout:42", output.toString());
    }

    @Test void observationTimeoutDoesNotCancelOriginalRead() {
        String program = """
                pub async fnc main(): void {
                  val Channel<int> channel = Channel.new<int>(0);
                  val Future<int> read = nb readch channel;
                  try {
                    await read.wait_timeout(30);
                  } catch (err) {
                    stdio.stdout.write("timeout:");
                  }
                  val Future<void> write = nb writech channel, 42;
                  await write;
                  stdio.stdout.write(await read);
                  return;
                }
                """;
        var output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).out(output).build()) {
            context.eval(OresLanguage.ID, program);
        }
        assertEquals("timeout:42", output.toString());
    }

    @Test void rootDeadlineBoundsAProgramWithNoExplicitTimeout() {
        try (Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false)
                .arguments(OresLanguage.ID, new String[]{"--ores-capabilities=STDOUT", "--ores-max-wall-ms=80"}).build()) {
            var failure = assertThrows(PolyglotException.class, () -> context.eval(OresLanguage.ID, """
                    pub async fnc main(): void {
                      val Channel<int> channel = Channel.new<int>(0);
                      val value = readch channel;
                      stdio.stdout.write(value);
                      return;
                    }
                    """));
            assertTrue(failure.getMessage().contains("deadline expired"), failure.getMessage());
        }
    }
}
