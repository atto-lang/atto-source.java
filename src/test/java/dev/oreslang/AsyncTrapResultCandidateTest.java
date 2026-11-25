package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

final class AsyncTrapResultCandidateTest {
    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "async-trap-result.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    void futureOwnsOneResultForImmediateAwaitedOptionAndExistingResult() throws Exception {
        String source = """
                async fnc ready(): int { rt cooperate; return 42; }
                async trap! fnc immediate(): int { return 42; }
                trap async fnc later(): int { return await ready(); }
                async trap fnc maybe(bool present): Option<int> {
                  val int n = await ready();
                  if present then return Some(n); fi
                  return None;
                }
                async trap fnc existing(): Result<int, Error> {
                  return Ok(await ready());
                }
                async trap fnc unit(): void { val int n = await ready(); return; }
                pub fnc main(): void {
                  val Future<Result<int, Error>> immediate_future = immediate();
                  val Result<int, Error> a = await immediate_future;
                  val Future<Result<int, Error>> later_future = later();
                  val Result<int, Error> b = await later_future;
                  val Result<Option<int>, Error> c = await maybe(true);
                  val Result<Option<int>, Error> d = await maybe(false);
                  val Result<int, Error> e = await existing();
                  val Result<void, Error> f = await unit();
                  stdio.stdout.write(a.unwrap());
                  stdio.stdout.write(":");
                  stdio.stdout.write(b.unwrap());
                  stdio.stdout.write(":");
                  stdio.stdout.write(c.unwrap().unwrap());
                  stdio.stdout.write(":");
                  stdio.stdout.write(d.unwrap().is_none());
                  stdio.stdout.write(":");
                  stdio.stdout.write(e.unwrap());
                  stdio.stdout.write(":");
                  stdio.stdout.write(f.is_ok());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("42:42:42:true:42:true", run(source));
    }

    @Test
    void bothWrongFutureShapesAreRejected() {
        String source = """
                async trap fnc later(): int { return 42; }
                pub fnc main(): void {
                  val Future<Result<int, Error>> f = later();
                  return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        for (String wrong : new String[]{"Future<Option<int>>", "Result<Future<int>, Error>"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(
                            source.replace("Future<Result<int, Error>>", wrong))));
        }
    }

    @Test
    void panicAfterAwaitDoesNotBecomeErr() {
        String source = """
                async fnc ready(): int { rt cooperate; return 42; }
                async trap fnc later(): int {
                  val int n = await ready();
                  val Option<int> absent = None;
                  return absent.unwrap();
                }
                pub fnc main(): void {
                  val Result<int, Error> r = await later();
                  stdio.stdout.write("unreachable");
                  return;
                }
                """;
        PolyglotException failure = assertThrows(PolyglotException.class, () -> run(source));
        assertTrue(failure.getMessage().contains("Option::unwrap"), failure::getMessage);
    }

    @Test
    void strictSourceGateRemainsUntilTypedGuestThrowLowering() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parseStrictAtto("""
                async trap! fnc later(): int { return 42; }
                pub fnc main(): int { return 0; }
                """));
    }
}
