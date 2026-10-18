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
