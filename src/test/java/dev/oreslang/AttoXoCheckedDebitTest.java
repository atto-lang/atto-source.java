package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Executes the unchanged Atto-XO signed debit corpus in JVM Graal guests. */
final class AttoXoCheckedDebitTest {
    @TestFactory
    Stream<DynamicTest> exactSharedCheckedDebitGuests() throws Exception {
        byte[] bytes = Files.readAllBytes(Path.of("contracts/atto-xo-checked-debit-v1/cases.tsv"));
        assertEquals("e6e3394d5d62ec1f8171053b74a3b68389c41d5c797d81c14f561d1ca097a28b",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        var rows = new String(bytes, StandardCharsets.UTF_8).lines().toList();
        assertEquals(7, rows.size());
        var ids = new HashSet<String>();
        int values = 0;
        int faults = 0;
        for (String row : rows) {
            String[] fields = row.split("\\|", -1);
            assertEquals(8, fields.length);
            assertTrue(ids.add(fields[0]));
            if (fields[1].equals("overflow")) {
                faults++;
            } else {
                assertTrue(fields[1].equals("42") || fields[1].equals("43"));
                values++;
            }
        }
        assertEquals(3, values);
        assertEquals(4, faults);
        return rows.stream().map(row -> {
            String[] fields = row.split("\\|", -1);
            return DynamicTest.dynamicTest(fields[0], () -> {
                try (Context context = Context.newBuilder("ores").allowAllAccess(false).build()) {
                    Source guest = Source.newBuilder("ores", fields[7], fields[0] + ".atto").build();
                    if (fields[1].equals("overflow")) {
                        PolyglotException error = assertThrows(PolyglotException.class,
                                () -> context.eval(guest));
                        assertTrue(error.getMessage().contains("ORES_I64_OVERFLOW"), error::getMessage);
                    } else {
                        Value result = context.eval(guest);
                        assertTrue(result.fitsInLong());
                        assertEquals(Long.parseLong(fields[1]), result.asLong());
                    }
                }
            });
        });
    }
}
