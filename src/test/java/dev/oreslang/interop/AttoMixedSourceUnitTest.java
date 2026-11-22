package dev.oreslang.interop;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class AttoMixedSourceUnitTest {
    @Test
    void attoSourceUsesLanguageViewAndCanonicalFilesystemIdentity() {
        Path source = Path.of("src/main.atto");
        MixedSourceUnit unit = MixedSourceUnit.parse(source, "pub fnc main(): void { return; }");
        assertEquals(MixedSourceUnit.PrimaryLanguage.ORES, unit.primaryLanguage());
        assertTrue(unit.hasOresSource());
        assertFalse(unit.hasJavaSource());
        assertEquals(source.toAbsolutePath().normalize().toString().replace('\\', '/'), unit.unitId());
        assertTrue(unit.oresSource().contains("pub fnc main"));
    }

    @Test
    void rejectsUnsupportedExtensionInsteadOfTreatingItAsJava() {
        assertThrows(IllegalArgumentException.class,
                () -> MixedSourceUnit.parse("id", "main.txt", "pub fnc main(): void { return; }"));
    }

    @Test
    void legacyOresExtensionRemainsExplicitlyCompatible() {
        MixedSourceUnit unit = MixedSourceUnit.parse("id", "main.ores", "pub fnc main(): void { return; }");
        assertEquals(MixedSourceUnit.PrimaryLanguage.ORES, unit.primaryLanguage());
    }
}
