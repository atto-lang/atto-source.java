package dev.oreslang;

import dev.oreslang.compiler.ScalarEffectIrV1;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

final class ScalarEffectIrV1Test {
    private static final String GOLDEN =
        "{\"schema_version\":1,\"functions\":[{\"name\":\"answer\",\"operations\":["
        + "{\"kind\":\"compute\",\"units\":1},"
        + "{\"kind\":\"compute\",\"units\":1},"
        + "{\"kind\":\"compute\",\"units\":1}]}]}";
    private static final String SHA256 =
        "8ee1adbf6faef704c1eb6d2b2075c3b2d723097037f646f9e6e518a69d005463";

    @Test void matchesRustCompilerAnalyzerAndNativeGolden() throws Exception {
        String source = "fnc answer(): int { return 40 + 2; }";
        String actual = ScalarEffectIrV1.fromSource(source);
        assertEquals(GOLDEN, actual);
        assertEquals(SHA256, HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                        actual.getBytes(StandardCharsets.UTF_8))));
    }

    @Test void countsEveryScalarOperationAndSortsFunctions() {
        String source = "fnc zebra(): int { return (1 + 2) * (3 - 4); } "
                + "fnc alpha(): int { return 42; }";
        String json = ScalarEffectIrV1.fromSource(source);
        assertTrue(json.indexOf("\"alpha\"") < json.indexOf("\"zebra\""));
        assertEquals(8, json.split("compute", -1).length - 1);
    }

    @Test void rejectsModelOmissionAndUnsupportedSyntax() {
        String[] rejected = {
            "fnc answer(int x): int { return x + 1; }",
            "fnc helper(): int { return 5; } fnc answer(): int { return helper(); }",
            "fnc answer(): int { return -42; }",
            "fnc answer(): int { return 1; return 2; }",
            "fnc answer(): int { let int x = 1; return x; }",
            "fnc main(): int { return 42; }"
        };
        for (String source : rejected) {
            assertThrows(RuntimeException.class, () -> ScalarEffectIrV1.fromSource(source),
                source);
        }
    }

    @Test void boundedDeepNestingFailsClosed() {
        String source = "fnc answer(): int { return 1" + "+1".repeat(200) + "; }";
        assertThrows(RuntimeException.class, () -> ScalarEffectIrV1.fromSource(source));
    }
}
