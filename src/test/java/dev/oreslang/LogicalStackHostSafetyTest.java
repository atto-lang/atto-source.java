package dev.oreslang;

import java.util.stream.Stream;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The logical stack quota must trip before the host JVM stack for every guest
 * call shape the interpreter lowers differently. A host StackOverflowError is
 * a VM error and fails the carrier; a LogicalStackOverflowException fails only
 * the owning logical stack. Calibration notes: docs/ACTOR_LOGICAL_STACKS.md.
 */
@Timeout(60)
final class LogicalStackHostSafetyTest {
    static Stream<String> shapes() {
        return Stream.of(
                // direct self recursion
                "fnc dive(n: int): int { return dive(n + 1) + 1; }\n"
                        + "pub fnc main(): void { dive(0); return; }",
                // wide arithmetic per frame (most host stack per guest call measured)
                "fnc dive(a: int, b: int, c: int, d: int): int {"
                        + " val x = (a + b) * (c - d) + (a * 2 - b) * (c + d * 3)"
                        + " + ((a + 1) * (b + 2) - (c + 3) * (d + 4));"
                        + " return dive(a + 1, b + x, c, d) + x; }\n"
                        + "pub fnc main(): void { dive(0, 1, 2, 3); return; }",
                // method dispatch on an object receiver
                "define class R as\n  pub fnc dive(n: int): int { return self.dive(n + 1) + 1; }\nend\n"
                        + "pub fnc main(): void { val r = new R(); r.dive(0); return; }",
                // mutual recursion through nested blocks
                "fnc ping(n: int): int { if (n >= 0) { return pong(n + 1) + 1; } fi return 0; }\n"
                        + "fnc pong(n: int): int { if (n < 0) { return 0; } fi return ping(n + 1) + 1; }\n"
                        + "pub fnc main(): void { ping(0); return; }");
    }

    @ParameterizedTest
    @MethodSource("shapes")
    void logicalQuotaTripsBeforeTheHostStack(String program) {
        PolyglotException failure = assertThrows(PolyglotException.class, () -> {
            try (Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).build()) {
                context.eval(Source.newBuilder(OresLanguage.ID, program, "host-safety.ores")
                        .mimeType(OresLanguage.MIME_TYPE).build());
            }
        });
        String message = String.valueOf(failure.getMessage());
        assertTrue(message.contains("StackOverflow: logical stack"), message);
        assertFalse(message.contains("java.lang.StackOverflowError"), message);
    }
}
