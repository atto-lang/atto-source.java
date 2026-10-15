package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Public engineering-log evidence check. Snippet text remains minimal and
 * non-secret; tests run in this private compiler repository, not the public
 * marketing site's Actions runner.
 *
 * Source: https://ores-truffle-oreslang.github.io/logs/7/
 * Catalog id: log-007-sample-02
 */
final class EngineeringLogPublishedSnippetTest {
    private static final String LOG_007_EXACT = """
            pub fnc calculate(int x) : int {
                return x * 2;
            }
            """.stripTrailing();

    @Test
    void logSevenSampleIdentityMatchesThePublishedCode() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(LOG_007_EXACT.getBytes(StandardCharsets.UTF_8));
        assertEquals("26d17ee717f453a1c6d08d53a14e05b262cfe3cbc98c919b0c907f3dea5fe175",
                HexFormat.of().formatHex(digest));
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(LOG_007_EXACT)));
    }

    @Test
    void logSevenSampleExecutesThroughRealGuestRuntime() {
        String program = LOG_007_EXACT + "\n" + """
                pub fnc main(): void {
                    stdio.stdout.write(calculate(21));
                    return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "log-007-published.ores")
                .mimeType(OresLanguage.MIME_TYPE).buildLiteral();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(stdout)
                .build()) {
            assertDoesNotThrow(() -> context.eval(source));
        }
        assertEquals("42", stdout.toString(StandardCharsets.UTF_8));
    }
}
