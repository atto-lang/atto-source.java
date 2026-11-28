package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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
    @Timeout(20)
    void logSevenSampleIdentityMatchesThePublishedCode() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(LOG_007_EXACT.getBytes(StandardCharsets.UTF_8));
        assertEquals("26d17ee717f453a1c6d08d53a14e05b262cfe3cbc98c919b0c907f3dea5fe175",
                HexFormat.of().formatHex(digest));
        // HTML publishing intentionally strips exactly one final newline.
        // Any edit must invalidate the public/private provenance pair.
        assertFalse(LOG_007_EXACT.endsWith("\n"), "Canonical snippet unexpectedly includes a newline");
        assertEquals(3, LOG_007_EXACT.lines().count(), "Published snippet line count changed");
        assertNotEquals(HexFormat.of().formatHex(digest),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(LOG_007_EXACT.replace("x * 2", "x * 3").getBytes(StandardCharsets.UTF_8))));
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(LOG_007_EXACT)));
    }

    @Test
    @Timeout(45)
    void logSevenSampleExecutesThroughRealGuestRuntime() {
        // Execute actual guest calls, including the zero and negative paths.
        // This proves only the JVM/Truffle profile, not LLVM or native guest AOT.
        String program = LOG_007_EXACT + "\n" + """
                pub fnc main(): void {
                    stdio.stdout.write(calculate(21));
                    stdio.stdout.write("|");
                    stdio.stdout.write(calculate(0));
                    stdio.stdout.write("|");
                    stdio.stdout.write(calculate(-7));
                    return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
        Source source = Source.newBuilder(OresLanguage.ID, program, "log-007-published.ores")
                .mimeType(OresLanguage.MIME_TYPE).buildLiteral();
        // Fresh contexts guard against accidental reliance on compiler-global,
        // runtime-global or prior guest execution state.
        for (int iteration = 0; iteration < 2; iteration++) {
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false)
                    .out(stdout)
                    .build()) {
                assertDoesNotThrow(() -> context.eval(source), "guest execution " + iteration);
            }
            assertEquals("42|0|-14", stdout.toString(StandardCharsets.UTF_8),
                    "fresh context execution " + iteration);
        }
    }
}
