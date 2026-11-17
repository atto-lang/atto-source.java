package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Actual Java/GraalVM guest execution, not simulated host values. These
 * programs match the semantic cases in platform-conformance
 * contracts/v1/caller-trap-parity. LLVM and .NET must compile the identical
 * .atto source and execute their own backends before claiming parity.
 */
final class CallerTrapThreeBackendV1Test {
    private record Outcome(long value, String stdout) {}

    private static Outcome execute(String source) throws Exception {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        Source guest = Source.newBuilder(OresLanguage.ID, source, "caller-trap-v1.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(stdout).build()) {
            Value result = context.eval(guest);
            assertTrue(result.fitsInLong(), "guest must return signed i64");
            return new Outcome(result.asLong(), stdout.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void successWrapsExactlyOneOption() throws Exception {
        Outcome actual = execute("""
                pub fnc main(): int {
                  val Option<int> value = trap (40 + 2);
                  return value.unwrap();
                }
                """);
        assertEquals(42, actual.value());
        assertEquals("", actual.stdout());
    }

    @Test
    void trapOfResultOkPreservesNestedTagAndPayload() throws Exception {
        Outcome actual = execute("""
                fnc checked_id(int id): Result<int,String> { return Ok(id); }
                pub fnc main(): int {
                  val Option<Result<int,String>> outer = trap checked_id(42);
                  return outer.unwrap().unwrap();
                }
                """);
        assertEquals(42, actual.value());
    }

    @Test
    void resultErrDoesNotBecomeTrappedNone() throws Exception {
        Outcome actual = execute("""
                fnc checked_id(int id): Result<int,String> {
                  if id < 0 then return Err("invalid"); fi
                  return Ok(id);
                }
                pub fnc main(): int {
                  val Option<Result<int,String>> outer = trap checked_id(-1);
                  if outer.is_some() then
                    if outer.unwrap().is_err() then return 7; fi
                  fi
                  return 0;
                }
                """);
        assertEquals(7, actual.value());
    }

    @Test
    void nestedSomeNoneIsNotFlattened() throws Exception {
        Outcome actual = execute("""
                fnc missing(): Option<int> { return None; }
                pub fnc main(): int {
                  val Option<Option<int>> nested = trap missing();
                  if nested.is_some() then
                    if nested.unwrap().is_none() then return 13; fi
                  fi
                  return 0;
                }
                """);
        assertEquals(13, actual.value());
    }

    @Test
    void evaluatedOnceWithRealStdioSideEffect() throws Exception {
        Outcome actual = execute("""
                fnc checked(): int {
                  stdio.stdout.write("once");
                  return 42;
                }
                pub fnc main(): int {
                  val Option<int> value = trap checked();
                  return value.unwrap();
                }
                """);
        assertEquals(42, actual.value());
        assertEquals("once", actual.stdout(), "operand must execute exactly once");
    }

    @Test
    void ordinaryCheckedCastFailureBecomesNone() throws Exception {
        Outcome actual = execute("""
                define class Animal as
                end
                define class Dog extends Animal as
                end
                define class Cat extends Animal as
                end
                fnc as_dog(Animal value): Dog { return value as Dog; }
                pub fnc main(): int {
                  val Option<Dog> outcome = trap as_dog(new Cat());
                  if outcome.is_none() then return 9; fi
                  return 0;
                }
                """);
        assertEquals(9, actual.value());
    }

    @Test
    void unwrapNonePanicBypassesTrap() {
        String source = """
                pub fnc main(): int {
                  val Option<int> missing = None;
                  val Option<int> ignored = trap missing.unwrap();
                  return 0;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        PolyglotException failure = assertThrows(PolyglotException.class, () -> execute(source));
        assertTrue(failure.getMessage().contains("Option::unwrap"));
    }

    @Test
    void anOptionCannotSilentlyBecomeBareI64() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                pub fnc main(): int {
                  val int bad = trap (40 + 2);
                  return bad;
                }
                """));
    }
}
