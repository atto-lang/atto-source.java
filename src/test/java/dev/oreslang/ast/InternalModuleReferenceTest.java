package dev.oreslang.ast;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class InternalModuleReferenceTest {
    @Test
    void internalModuleReferenceRoundTripsUnicodeLosslessly() {
        String module = "pkg.模块.α";
        String encoded = Ast.internalModuleReference(module);

        assertTrue(encoded.startsWith("$module$"));
        assertNotEquals(module, encoded);
        assertEquals(module, Ast.internalModuleReferenceTarget(encoded));
        assertNull(Ast.internalModuleReferenceTarget(module));
    }

    @Test
    void malformedInternalModuleReferencesFailClosed() {
        for (String malformed : new String[] {
                "$module$",
                "$module$0",
                "$module$zz",
                "$module$ff"
        }) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> Ast.internalModuleReferenceTarget(malformed),
                    malformed);
            assertTrue(failure.getMessage().contains(
                    "malformed internal module reference"), failure::toString);
        }
    }

    @Test
    void internalReferenceCannotEncodeBlankModule() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Ast.internalModuleReference(""));
    }
}
