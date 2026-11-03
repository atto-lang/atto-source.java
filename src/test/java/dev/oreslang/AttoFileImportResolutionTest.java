package dev.oreslang;

import dev.oreslang.config.OresProjectConfig;
import dev.oreslang.interop.MixedSourceUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Filesystem-level .atto migration checks without claiming guest execution. */
final class AttoFileImportResolutionTest {
    @TempDir Path workspace;

    @Test
    void canonicalAttoFileLoadsAsNativeSourceWithoutJavaInterop() {
        String source = """
                define pub macro literal(Const<int> value) -> Syntax<Expr> {
                    return quote(value);
                }
                """;
        MixedSourceUnit unit = MixedSourceUnit.parse("producer.atto", "producer.atto", source);
        assertEquals(MixedSourceUnit.PrimaryLanguage.ORES, unit.primaryLanguage());
        assertTrue(unit.hasOresSource());
        assertEquals(source, unit.oresSource());
        assertFalse(unit.hasJavaSource());
    }

    @Test
    void extensionlessFilesystemImportChoosesAttoBeforeLegacyOres() throws Exception {
        Path consumer = workspace.resolve("consumer.atto");
        Path atto = workspace.resolve("producer.atto");
        Path legacy = workspace.resolve("producer.ores");
        Files.writeString(consumer, "pub fnc answer() -> int { return 42; }");
        Files.writeString(atto, "pub fnc value() -> int { return 42; }");
        Files.writeString(legacy, "pub fnc value() -> int { return 7; }");

        OresProjectConfig config = OresProjectConfig.discover(consumer, Map.of());
        assertEquals(atto.toAbsolutePath().normalize(),
                config.resolveImport(consumer, "./producer").orElseThrow());
        assertEquals(legacy.toAbsolutePath().normalize(),
                config.resolveImport(consumer, "./producer.ores").orElseThrow());
    }

    @Test
    void missingExplicitAttoDoesNotSilentlyDowngradeToOres() throws Exception {
        Path consumer = workspace.resolve("consumer.atto");
        Path legacy = workspace.resolve("producer.ores");
        Files.writeString(consumer, "pub fnc answer() -> int { return 42; }");
        Files.writeString(legacy, "pub fnc value() -> int { return 7; }");

        OresProjectConfig config = OresProjectConfig.discover(consumer, Map.of());
        assertTrue(config.resolveImport(consumer, "./producer.atto").isEmpty());
        assertEquals(legacy.toAbsolutePath().normalize(),
                config.resolveImport(consumer, "./producer").orElseThrow());
    }
}
