package dev.oreslang;

import dev.oreslang.config.OresProjectConfig;
import dev.oreslang.interop.MixedSourceUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

final class MixedSourceAttoExtensionTest {
    @TempDir Path temp;

    @Test
    void attoFileIsAdmittedAsNativeOresSource() {
        String source = "pub routine main(): void { return; }";
        MixedSourceUnit unit = MixedSourceUnit.parse(temp.resolve("main.atto"), source);
        assertEquals(MixedSourceUnit.PrimaryLanguage.ORES, unit.primaryLanguage());
        assertTrue(unit.hasOresSource());
        assertEquals(source, unit.oresSource());
    }

    @Test
    void extensionlessImportsPreferAttoAndExplicitAttoImportsResolve() throws Exception {
        Path main = temp.resolve("main.atto");
        Path atto = temp.resolve("helper.atto");
        Path legacy = temp.resolve("helper.ores");
        Files.writeString(main, "pub routine main(): void { return; }");
        Files.writeString(atto, "pub routine helper(): int { return 42; }");
        Files.writeString(legacy, "pub routine helper(): int { return 7; }");
        OresProjectConfig config = OresProjectConfig.discover(main, Map.of());
        assertEquals(atto.toAbsolutePath().normalize(), config.resolveImport(main, "./helper").orElseThrow());
        assertEquals(atto.toAbsolutePath().normalize(), config.resolveImport(main, "./helper.atto").orElseThrow());
        assertEquals(legacy.toAbsolutePath().normalize(), config.resolveImport(main, "./helper.ores").orElseThrow());
    }

    @Test
    void legacyOresSourceRemainsAdmitted() {
        String source = "pub routine main(): void { return; }";
        MixedSourceUnit unit = MixedSourceUnit.parse(temp.resolve("main.ores"), source);
        assertEquals(MixedSourceUnit.PrimaryLanguage.ORES, unit.primaryLanguage());
        assertTrue(unit.hasOresSource());
        assertEquals(source, unit.oresSource());
    }

    @Test
    void unknownExtensionsFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> MixedSourceUnit.parse(temp.resolve("main.txt"), "pub routine main(): void { return; }"));
    }
}
