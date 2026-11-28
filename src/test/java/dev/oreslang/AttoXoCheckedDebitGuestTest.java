package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Exact Atto-XO guest source parity with Rust #644 and JS #195, on the JVM. */
final class AttoXoCheckedDebitGuestTest {
    @Test
    void exactSharedGuestsExecuteWithCheckedIntermediateArithmetic() throws Exception {
        byte[] bytes = Files.readAllBytes(Path.of("contracts/atto-xo-checked-debit-v1/cases.tsv"));
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.UTF_8));
        assertEquals("6831dbd0c18eeaa2078fa5f3e324d0f88b40771e",
                HexFormat.of().formatHex(digest.digest(bytes)));
        String[] rows = new String(bytes, StandardCharsets.UTF_8).split("\n", -1);
        assertEquals(8, rows.length);
        assertEquals("", rows[7]);
        Set<String> ids = new HashSet<>();
        int values = 0;
        int overflows = 0;
        for (int i = 0; i < 7; i++) {
            String[] fields = rows[i].split("\\|", -1);
            assertEquals(8, fields.length);
            String id = fields[0];
            assertTrue(ids.add(id), id);
            Source source = Source.newBuilder("ores", fields[7], id + ".atto").build();
            try (Context context = Context.newBuilder("ores").allowAllAccess(false).build()) {
                if ("overflow".equals(fields[1])) {
                    PolyglotException error = assertThrows(PolyglotException.class,
                            () -> context.eval(source), id);
                    assertTrue(error.getMessage().contains("ORES_I64_OVERFLOW"),
                            () -> id + ": " + error.getMessage());
                    overflows++;
                } else {
                    long expected = Long.parseLong(fields[1]);
                    assertEquals(expected, context.eval(source).asLong(), id);
                    values++;
                }
            }
        }
        assertEquals(3, values);
        assertEquals(4, overflows);
    }
}
