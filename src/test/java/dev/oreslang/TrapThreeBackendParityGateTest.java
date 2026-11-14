package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Real Java/Graal guest oracle; not evidence of LLVM or .NET parity. */
final class TrapThreeBackendParityGateTest {
    private static String fixture() throws Exception {
        try (InputStream input = TrapThreeBackendParityGateTest.class.getResourceAsStream(
                "/trap-result-option/three-backend-boundary.atto")) {
            assertNotNull(input, "parity guest fixture must be packaged");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void callerTrapPreservesResultAndCatchesOnlyOrdinaryGuestFailure() throws Exception {
        String source = fixture();
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        Source guest = Source.newBuilder(OresLanguage.ID, source, "three-backend-boundary.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            Value result = context.eval(guest);
            assertTrue(result.fitsInLong(), "guest main() must return int");
            assertEquals(7L, result.asLong(),
                    "trap must add Option, preserve Result Err, and catch ordinary cast failure");
        }
    }

    @Test
    void anOptionResultCannotBePassedToABareResultParameter() {
        String source = """
                fnc checked_id(int id): Result<int, String> { return Ok(id); }
                fnc consume(Result<int, String> value): void { return; }
                pub fnc main(): int {
                  val Option<Result<int, String>> wrapped = trap checked_id(5);
                  consume(wrapped);
                  return 0;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source),
                "no implicit Option<Result> to Result conversion");
    }

    @Test
    void anUnwrappedResultCannotBeAssignedToOptionResultWithoutTrap() {
        String source = """
                fnc checked_id(int id): Result<int, String> { return Ok(id); }
                pub fnc main(): int {
                  val Option<Result<int, String>> wrapped = checked_id(5);
                  return 0;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source),
                "only trap adds the outer Option");
    }
}
