package dev.oreslang;

import dev.oreslang.types.SourceEffectAudit;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression gate: observationally pure results are bound to source bytes,
 * not an unqualified callable-name spelling or stale analysis result.
 */
final class SourceEffectSourceIdentityTest {
    private static String digest(String source) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(source.getBytes(StandardCharsets.UTF_8)));
    }

    @Test void sourceDigestTracksTheExactAdmittedSource() throws Exception {
        String first = "pub fnc result(): int { return 42; }";
        String second = "pub fnc result(): int { return 43; }";
        var a = SourceEffectAudit.inspect(first);
        var b = SourceEffectAudit.inspect(second);
        assertTrue(a.conservativelyEffectFree());
        assertTrue(b.conservativelyEffectFree());
        assertEquals(digest(first), a.sourceSha256());
        assertEquals(digest(second), b.sourceSha256());
        assertNotEquals(a.sourceSha256(), b.sourceSha256());
    }

    @Test void malformedUtf16DoesNotReceiveSourceIdentity() {
        String malformed = "pub fnc result(): int { return 42; } // " + (char) 0xD800;
        assertThrows(IllegalArgumentException.class,
                () -> SourceEffectAudit.inspect(malformed));
    }

    @Test void typeErrorNeverReceivesPureAdmission() {
        assertThrows(IllegalArgumentException.class, () ->
                SourceEffectAudit.inspect("pub fnc result(): int { return unknown; }"));
    }
}
