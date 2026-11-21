package dev.oreslang;

import dev.oreslang.types.SourceEffectAudit;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class SourceEffectAuditProvenanceTest {
    private static final String SOURCE = """
            pub fnc answer(): int {
                return 42;
            }
            """;

    @Test void sourceReportIsBoundToExactCheckedText() throws Exception {
        var report = SourceEffectAudit.inspect(SOURCE);
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(SOURCE.getBytes(StandardCharsets.UTF_8)));
        assertEquals(expected, report.sourceSha256());
        assertTrue(report.conservativelyEffectFree());
        assertFalse(report.functions().isEmpty());
        assertTrue(report.unsupportedDeclarations().isEmpty());

        var changed = SourceEffectAudit.inspect(SOURCE + "\n");
        assertNotEquals(report.sourceSha256(), changed.sourceSha256(),
                "the source identity must include whitespace");
        assertTrue(changed.conservativelyEffectFree());
    }

    @Test void malformedUtf16CannotCollapseToReplacementByteDigest() throws Exception {
        var method = SourceEffectAudit.class.getDeclaredMethod("sourceDigest", String.class);
        method.setAccessible(true);
        for (String malformed : new String[]{String.valueOf((char) 0xD800),
                String.valueOf((char) 0xDC00), "a" + (char) 0xD800 + "b"}) {
            var error = assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> method.invoke(null, malformed));
            assertInstanceOf(IllegalArgumentException.class, error.getCause());
            assertTrue(error.getCause().getMessage().contains("UTF-16"));
        }
    }

    @Test void clientsCannotInstantiateOrModifyAClaimOfPurity() {
        assertEquals(0, SourceEffectAudit.Report.class.getConstructors().length,
                "public record constructor would allow forging a pure source report");
        for (var constructor : SourceEffectAudit.Report.class.getDeclaredConstructors()) {
            assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        }
        var report = SourceEffectAudit.inspect(SOURCE);
        assertThrows(UnsupportedOperationException.class,
                () -> report.functions().put("forged", Set.of()));
        assertThrows(UnsupportedOperationException.class,
                () -> report.functions().values().iterator().next().add(
                        dev.oreslang.types.EffectContracts.Effect.IO));
        assertThrows(UnsupportedOperationException.class,
                () -> report.unsupportedDeclarations().add("forged"));
    }

    @Test void invalidSourceNeverReceivesAnIdentityBoundCertificate() {
        assertThrows(IllegalArgumentException.class,
                () -> SourceEffectAudit.inspect("pub fnc broken(): int { return nope; }"));
    }
}
