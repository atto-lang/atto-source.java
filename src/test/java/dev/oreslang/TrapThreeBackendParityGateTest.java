package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Real Java/Graal guest oracle; not evidence of LLVM or .NET parity. */
final class TrapThreeBackendParityGateTest {
    private static String fixture(String name) throws Exception {
        try (InputStream input = TrapThreeBackendParityGateTest.class.getResourceAsStream(
                "/trap-result-option/" + name)) {
            assertNotNull(input, "parity guest fixture must be packaged: " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void callerTrapPreservesResultDataWithoutFlattening() throws Exception {
        String source = fixture("three-backend-boundary.atto");
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        Source guest = Source.newBuilder(OresLanguage.ID, source, "three-backend-boundary.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            Value result = context.eval(guest);
            assertTrue(result.fitsInLong(), "guest main() must return int");
            assertEquals(7L, result.asLong(),
                    "trap over a Result keeps exactly one Result layer and preserves its Err");
        }
    }


    @Test
    void samePureTrapIntGuestAsLlvmAndNetMustReturn42() throws Exception {
        String source = fixture("trap-int-native42.atto");
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        Source guest = Source.newBuilder(OresLanguage.ID, source, "trap-int-native42.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            Value result = context.eval(guest);
            assertTrue(result.fitsInLong(), "guest must return signed int");
            assertEquals(42L, result.asLong(), "same stage-one trap int guest on all backends");
        }
    }

    @Test
    void ordinaryCheckedCastFailureIsOuterNone() throws Exception {
        String source = fixture("ordinary-cast-failure.atto");
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        Source guest = Source.newBuilder(OresLanguage.ID, source, "ordinary-cast-failure.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            Value result = context.eval(guest);
            assertTrue(result.fitsInLong(), "guest main() must return int");
            assertEquals(7L, result.asLong(),
                    "only ordinary recoverable checked-cast failure is caught as None");
        }
    }

    @Test
    void panickingUnwrapBypassesCallerSideTrap() throws Exception {
        String source = """
                pub fnc main(): int {
                  val Option<int> missing = None;
                  val Result<int, Error> value = trap missing.unwrap();
                  return 7;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        Source guest = Source.newBuilder(OresLanguage.ID, source, "trap-panic-bypass.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            PolyglotException error = assertThrows(PolyglotException.class,
                    () -> context.eval(guest));
            assertTrue(error.getMessage().contains("Option::unwrap"),
                    "panic must never become None from trap");
        }
    }

    @Test
    void aTrappedResultCarriesTheWiderErrorTypeAndCannotNarrowImplicitly() {
        String source = """
                fnc checked_id(int id): Result<int, String> { return Ok(id); }
                fnc consume(Result<int, String> value): void { return; }
                pub fnc main(): int {
                  val Result<int, String | Error> wrapped = trap checked_id(5);
                  consume(wrapped);
                  return 0;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source),
                "Result<int, String | Error> does not narrow to Result<int, String> implicitly");
    }

    @Test
    void trapNeverWrapsAResultInASecondResult() {
        String source = """
                fnc checked_id(int id): Result<int, String> { return Ok(id); }
                pub fnc main(): int {
                  val Result<Result<int, String>, Error> wrapped = trap checked_id(5);
                  return 0;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source),
                "trap over a Result yields one Result, never Result<Result<..>, Error>");
    }
}
