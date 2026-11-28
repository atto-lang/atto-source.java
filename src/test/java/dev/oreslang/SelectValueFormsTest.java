package dev.oreslang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.oreslang.compiler.OresCompiler;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Static select in value position (atto-spec ch. 6):
 * {@code select { ... }} registers its arms and returns {@code Future<T>};
 * {@code await select { ... }} waits for the first arm to commit and
 * evaluates to that arm's value {@code T}; {@code try select { ... }} probes
 * once and yields {@code Option<T>}. {@code do select { ... }} stays the
 * procedural statement. There is no {@code nb select} value form.
 */
@Timeout(60)
final class SelectValueFormsTest {

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, program, "select-value.ores")
                    .mimeType(OresLanguage.MIME_TYPE).buildLiteral());
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    void awaitSelectEvaluatesTheWinningArm() throws Exception {
        assertEquals("40", run("""
                pub async routine main(): void {
                  val Channel<int> a = Channel.new<int>(1);
                  val Channel<int> b = Channel.new<int>(1);
                  writech b, 4;
                  val int got = await select {
                    when readch a: int x -> x + 1,
                    when readch b: int y -> y * 10
                  };
                  stdio.stdout.write(got);
                  return;
                }
                """));
    }

    @Test
    void awaitSelectSuspendsUntilAWriterArrives() throws Exception {
        // Capacity 0: nothing is ready when the select registers, so the task
        // suspends until the producer's rendezvous write commits one arm.
        assertEquals("109", run("""
                async fnc produce(Channel<int> c): void {
                  writech c, 9;
                  return;
                }

                pub async routine main(): void {
                  val Channel<int> a = Channel.new<int>(0);
                  val Channel<int> b = Channel.new<int>(0);
                  val Future<void> producer = produce(b);
                  val int got = await select {
                    when readch a: int x -> x,
                    when readch b: int y -> y + 100
                  };
                  await producer;
                  stdio.stdout.write(got);
                  return;
                }
                """));
    }

    @Test
    void selectReturnsAFutureOfTheWinningArm() throws Exception {
        assertEquals("42", run("""
                pub async routine main(): void {
                  val Channel<int> a = Channel.new<int>(1);
                  val Future<int> pending = select { when readch a: int x -> x * 2 };
                  writech a, 21;
                  stdio.stdout.write(await pending);
                  return;
                }
                """));
    }

    @Test
    void trySelectProbesOnceWithoutRegistering() throws Exception {
        assertEquals("-1|5", run("""
                pub async routine main(): void {
                  val Channel<int> a = Channel.new<int>(1);
                  val Option<int> missed = try select { when readch a: int x -> x };
                  writech a, 5;
                  val Option<int> hit = try select { when readch a: int x -> x };
                  stdio.stdout.write(missed.unwrap_or(-1));
                  stdio.stdout.write("|");
                  stdio.stdout.write(hit.unwrap_or(-1));
                  return;
                }
                """));
    }

    @Test
    void writeArmsAndDefaultProduceValues() throws Exception {
        assertEquals("0|1|3", run("""
                pub async routine main(): void {
                  val Channel<int> full = Channel.new<int>(1);
                  writech full, 1;
                  val int blocked = await select {
                    when writech full, 2: -> 1,
                    default: -> 0
                  };
                  val Channel<int> room = Channel.new<int>(1);
                  val int sent = await select {
                    when writech room, 3: -> 1,
                    default: -> 0
                  };
                  stdio.stdout.write(blocked);
                  stdio.stdout.write("|");
                  stdio.stdout.write(sent);
                  stdio.stdout.write("|");
                  stdio.stdout.write(readch room);
                  return;
                }
                """));
    }

    @Test
    void bothFormsWorkInsideAnActorTurn() throws Exception {
        assertEquals("11|22|", run("""
                define actor Relay as
                  let Channel<int> feed;

                  on_start(): void {
                    self.feed = Channel.new<int>(4);
                    return;
                  }

                  receive(ActorMail<int> mail): void {
                    writech self.feed, mail.value;
                    val int doubled = await select { when readch self.feed: int v -> v * 11 };
                    stdio.stdout.write(doubled);
                    stdio.stdout.write("|");
                    if mail.value == 2 then
                      self.end();
                    fi
                    return;
                  }
                end

                pub async routine main(): void {
                  val relay = spawn Relay();
                  await relay.get_ready();
                  relay.send(1);
                  relay.send(2);
                  await relay.get_done();
                  return;
                }
                """));
    }

    private static void rejected(String source, String fragment) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(String.valueOf(failure.getMessage()).contains(fragment), failure::getMessage);
    }

    @Test
    void misuseFailsClosed() {
        rejected("""
                pub async routine main(): void {
                  val Channel<int> a = Channel.new<int>(1);
                  val Future<int> f = nb select { when readch a: int x -> x };
                  return;
                }
                """, "is not a value form");
        // A discarded select Future could still consume a message.
        rejected("""
                pub async routine main(): void {
                  val Channel<int> a = Channel.new<int>(1);
                  select { when readch a: int x -> x };
                  return;
                }
                """, "a select with expression arms is a Future<T>");
        rejected("""
                pub async routine main(): void {
                  val Channel<int> a = Channel.new<int>(1);
                  val int r = await select {
                    when readch a: int x -> x,
                    default: -> "none"
                  };
                  return;
                }
                """, "select arm");
        rejected("""
                pub async routine main(): void {
                  val Channel<int> a = Channel.new<int>(1);
                  val r = await select { when readch a: int x -> stdio.stdout.write(x) };
                  return;
                }
                """, "must produce a value");
        rejected("""
                pub async routine main(): void {
                  val Channel<int> a = Channel.new<int>(1);
                  val Future<String> f = select { when readch a: int x -> x };
                  return;
                }
                """, "");
    }

    @Test
    void aFutureArmValueCannotSuspend() {
        PolyglotException failure = assertThrows(PolyglotException.class, () -> run("""
                pub async routine main(): void {
                  val Channel<int> a = Channel.new<int>(1);
                  val Future<int> f = select { when readch a: int x -> readch a };
                  return;
                }
                """));
        assertTrue(failure.getMessage().contains("cannot suspend"), failure::getMessage);
    }
}
