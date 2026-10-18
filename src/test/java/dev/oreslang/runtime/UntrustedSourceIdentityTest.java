package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Pure JDK contract: no polyglot Engine, no native isolate, no actor classpath. */
final class UntrustedSourceIdentityTest {
    @Test
    void sourceHashDependsOnUnitAndExactBytes() {
        var a = UntrustedSourceIdentity.inspect("core.ores", "fnc a() {}");
        assertEquals(a, UntrustedSourceIdentity.inspect("core.ores", "fnc a() {}"));
        assertNotEquals(a, UntrustedSourceIdentity.inspect("std.ores", "fnc a() {}"));
        assertNotEquals(a, UntrustedSourceIdentity.inspect("core.ores", "fnc b() {}"));
        assertEquals(10, a.byteLength());
        assertEquals(64, a.sha256().length());
    }

    @Test
    void strictUnicodeSourceAndModuleValidation() {
        String broken = String.valueOf((char) 0xD800);
        assertThrows(IllegalArgumentException.class,
                () -> UntrustedSourceIdentity.inspect("a.ores", broken));
        assertThrows(IllegalArgumentException.class,
                () -> UntrustedSourceIdentity.inspect("a" + broken, "valid"));
        assertThrows(IllegalArgumentException.class,
                () -> UntrustedSourceIdentity.inspect("a" + ((char) 0xDC00), "valid"));
        assertThrows(IllegalArgumentException.class,
                () -> UntrustedSourceIdentity.inspect("a\nname", "valid"));
        assertThrows(IllegalArgumentException.class,
                () -> UntrustedSourceIdentity.inspect("a\u0000name", "valid"));
        assertThrows(IllegalArgumentException.class,
                () -> UntrustedSourceIdentity.inspect("unit" + ((char) 0x202E) + ".ores", "valid"),
                "Unicode right-to-left override may spoof a module identity");
        assertThrows(IllegalArgumentException.class,
                () -> UntrustedSourceIdentity.inspect("unit" + ((char) 0x200B) + ".ores", "valid"),
                "zero-width code-unit formatting makes diagnostics ambiguous");
        assertThrows(IllegalArgumentException.class,
                () -> UntrustedSourceIdentity.inspect("unit" + ((char) 0x2066) + ".ores", "valid"),
                "isolate formatting control must not enter logical IDs");
        assertThrows(IllegalArgumentException.class,
                () -> UntrustedSourceIdentity.inspect("", "valid"));
        assertThrows(IllegalArgumentException.class,
                () -> UntrustedSourceIdentity.inspect("a".repeat(513), "valid"));
        assertEquals(4, UntrustedSourceIdentity.inspect("lib/🎉.ores", "🎉").byteLength());
    }

    @Test
    void sourceLimitIsExactUtf8ByteCount() {
        assertEquals(1_048_576,
                UntrustedSourceIdentity.inspect("a", "x".repeat(1_048_576)).byteLength());
        assertThrows(IllegalArgumentException.class, () ->
                UntrustedSourceIdentity.inspect("a", "x".repeat(1_048_577)));
        assertThrows(IllegalArgumentException.class, () ->
                UntrustedSourceIdentity.inspect("a", "€".repeat(400_000)));
        assertThrows(IllegalArgumentException.class, () ->
                UntrustedSourceIdentity.inspect("a", ""));
    }

    @Test
    void exactHashRoundTripForThousandSources() throws Exception {
        Random random = new Random(1);
        for (int i = 0; i < 1_000; i++) {
            byte[] bytes = new byte[random.nextInt(2048) + 1];
            for (int j = 0; j < bytes.length; j++) {
                bytes[j] = (byte) ('a' + random.nextInt(26));
            }
            String code = new String(bytes, StandardCharsets.UTF_8);
            var image = UntrustedSourceIdentity.inspect("m.ores", code);
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                    image.sha256());
            assertEquals(bytes.length, image.byteLength());
        }
    }

    @Test
    void deterministicUnicodeFuzzMatchesStrictJavaEncoder() throws Exception {
        Random random = new Random(0xB1D1C0DEL);
        char[] alphabet = {'a', '/', '\u20AC', '\u202E', '\uD83C', '\uDF89', '\uD800', '\uDC00'};
        for (int i = 0; i < 5_000; i++) {
            StringBuilder code = new StringBuilder();
            int length = 1 + random.nextInt(12);
            for (int j = 0; j < length; j++) {
                code.append(alphabet[random.nextInt(alphabet.length)]);
            }
            String input = code.toString();
            var encoder = StandardCharsets.UTF_8.newEncoder();
            if (!encoder.canEncode(input)) {
                assertThrows(IllegalArgumentException.class,
                        () -> UntrustedSourceIdentity.inspect("fuzz.ores", input));
            } else {
                var observed = UntrustedSourceIdentity.inspect("fuzz.ores", input);
                byte[] expected = input.getBytes(StandardCharsets.UTF_8);
                assertEquals(expected.length, observed.byteLength());
                assertEquals(HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(expected)), observed.sha256());
            }
        }
    }

    @Test
    void concurrentValidationIsDeterministic() throws Exception {
        try (var workers = Executors.newFixedThreadPool(8)) {
            List<Future<UntrustedSourceIdentity.Validated>> reads = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                reads.add(workers.submit(() ->
                        UntrustedSourceIdentity.inspect("unit.ores", "unchanging")));
            }
            var expected = reads.getFirst().get(10, TimeUnit.SECONDS);
            for (var future : reads) {
                assertEquals(expected, future.get(10, TimeUnit.SECONDS));
            }
        }
    }
}
