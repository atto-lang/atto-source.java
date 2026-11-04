package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fifty byte-identical scalar sources shared with LLVM, Wasm, Cranelift and
 * .NET. These are compiler typecheck results, NOT a Graal native guest receipt.
 */
final class SharedScalarFiftySourceTest {
    private static final String CORPUS = "contracts/parity/scalar50/v1.tsv";
    private static final String BLOB = "a948dbaf594916a6ee07f65d49a0cf5ae398ca7b";

    @Test
    void allFiftyCanonicalProgramsPassJavaCompilerAdmission() throws Exception {
        final byte[] bytes = Files.readAllBytes(Path.of(CORPUS));
        assertTrue(bytes.length > 0 && bytes.length < 65536);
        final MessageDigest digest = MessageDigest.getInstance("SHA-1");
        digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
        digest.update(bytes);
        assertEquals(BLOB, HexFormat.of().formatHex(digest.digest()),
                "shared scalar50 corpus differs from the five-backend snapshot");
        final String text = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(text.endsWith("\n"));
        assertFalse(text.contains("\r"));
        int count = 0;
        final Set<String> seen = new HashSet<>();
        for (final String row : text.split("\n")) {
            if (row.isEmpty() || row.startsWith("#")) continue;
            final String[] columns = row.split("\t", -1);
            assertEquals(3, columns.length, row);
            count++;
            final String id = "scalar-" + String.format(java.util.Locale.ROOT, "%03d", count);
            assertEquals(id, columns[0]);
            assertTrue(seen.add(columns[0]), "duplicate case ID");
            assertMatchesPositiveNativeExitValue(columns[1]);
            assertTrue(columns[2].startsWith("pub fnc main(): int { return "), id);
            assertTrue(columns[2].endsWith("; }"), id);
            assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(columns[2]), id);
        }
        assertEquals(50, count, "incomplete cross-backend source corpus");
    }

    private static void assertMatchesPositiveNativeExitValue(final String literal) {
        assertTrue(literal.matches("[1-9][0-9]*"), "noncanonical scalar expectation");
        final long value = Long.parseLong(literal);
        assertTrue(value < 128, "out-of-range native exit code");
    }
}
