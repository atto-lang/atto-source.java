package dev.oreslang.launcher;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Cross-backend signed64 guest-source candidates: runtime execution, not source readback. */
final class SignedI64NativeGuestParityTest {
    private static final String[][] CASES = new String[][] {
                {"40-wide-i64-difference", "// 40: wide-i64-difference\n// Candidate scalar exactness fixture; no compiler-backed execution claimed.\npub fnc main(): int { return 9007199254740993 - 9007199254740951; }\n"},
                {"41-min-i64-successor", "// 41: min-i64-successor\n// Candidate scalar exactness fixture; no compiler-backed execution claimed.\npub fnc main(): int { return (-9223372036854775807 - 1) + 9223372036854775807 + 43; }\n"},
                {"42-max-i64-predecessor", "// 42: max-i64-predecessor\n// Candidate scalar exactness fixture; no compiler-backed execution claimed.\npub fnc main(): int { return 9223372036854775807 - (9223372036854775807 - 42); }\n"},
                {"43-wide-checked-product", "// 43: wide-checked-product\n// Candidate scalar exactness fixture; no compiler-backed execution claimed.\npub fnc main(): int { return 3037000499 * 3037000499 - 9223372030926248959; }\n"},
                {"44-double-negative-product", "// 44: double-negative-product\n// Candidate scalar exactness fixture; no compiler-backed execution claimed.\npub fnc main(): int { return (1 - 43) * -1; }\n"},
                {"45-grouped-negation", "// 45: grouped-negation\n// Candidate scalar exactness fixture; no compiler-backed execution claimed.\npub fnc main(): int { return -(-42); }\n"},
                {"46-named-bitxor", "// 46: named-bitxor\n// Candidate scalar exactness fixture; no compiler-backed execution claimed.\npub fnc main(): int { return Integer.bitXor(40, 2); }\n"},
                {"47-mantissa-precision-product", "// 47: mantissa-precision-product\n// Candidate scalar exactness fixture; no compiler-backed execution claimed.\npub fnc main(): int { return (9007199254740993 - 9007199254740992) * 42; }\n"},
                {"48-max-i64-delta", "// 48: max-i64-delta\n// Candidate scalar exactness fixture; no compiler-backed execution claimed.\npub fnc main(): int { return 9223372036854775807 - 9223372036854775765; }\n"},
                {"49-signed-boundary-product", "// 49: signed-boundary-product\n// Candidate scalar exactness fixture; no compiler-backed execution claimed.\npub fnc main(): int { return (9223372036854775807 - 9223372036854775800) * 6; }\n"}
    };

    private static int runGuest(String source) throws Exception {
        Path exact = Files.createTempFile("java-exact-i64-guest-", ".ores");
        try {
            Files.writeString(exact, source, StandardCharsets.UTF_8);
            TypeChecker.check(Parser.parse(source));
            // AOT *execution mode* on Graal/JVM; this is not a Native Image executable.
            return OresMain.exitStatusForArgs(new String[] {"--mode=aot", exact.toString()});
        } finally {
            Files.deleteIfExists(exact);
        }
    }

    @Test
    void tenSignedI64CandidatesExecuteRealJvmOreslangGuestMain() throws Exception {
        assertEquals(10, CASES.length);
        for (String[] row : CASES) {
            assertEquals(42, runGuest(row[1]), row[0]);
        }
    }

    @Test
    void explicitTypeInferenceAndCastCandidatesMustAgreeWithRuntime() throws Exception {
        assertEquals(42, runGuest("pub fnc main(): int { const int x = 7; return x + 35; }"));
        assertEquals(42, runGuest("pub fnc main(): int { const x = 7 as int; return x + 35; }"));
    }

    @Test
    void implicitNumericNarrowingCannotSilentlyChangeReturnType() {
        // Main's numeric contract: int -> float is a valid assignment widening
        // (see TypeChecker / PatternMatchingHardeningTest), so only a narrowing
        // float -> int return may never be silently accepted.
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(
                "fnc widened(): float { return 7; }")));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(
                "fnc bad(): int { return 7.5; }")));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(
                "fnc bad(): int { val float x = 7.5; return x; }")));
    }
}
