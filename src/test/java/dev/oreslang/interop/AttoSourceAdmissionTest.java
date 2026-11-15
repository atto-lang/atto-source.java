package dev.oreslang.interop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class AttoSourceAdmissionTest {
    @Test
    void canonicalAttoFileUsesLanguageFrontend() {
        MixedSourceUnit unit = MixedSourceUnit.parse(
                "/pkg/main.atto", "main.atto", "pub fnc main() => void { return; }");
        assertEquals(MixedSourceUnit.PrimaryLanguage.ORES, unit.primaryLanguage());
        assertTrue(unit.hasOresSource());
        assertFalse(unit.hasJavaSource());
        assertEquals("/pkg/main.atto", unit.unitId());
    }

    @Test
    void legacyOresRemainsSupported() {
        MixedSourceUnit unit = MixedSourceUnit.parse(
                "/pkg/main.ores", "main.ores", "pub fnc main() => void { return; }");
        assertEquals(MixedSourceUnit.PrimaryLanguage.ORES, unit.primaryLanguage());
    }

    @Test
    void foreignExtensionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                MixedSourceUnit.parse("/pkg/main.py", "main.py", "not Oreslang"));
    }
}
