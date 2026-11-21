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
        assertTrue(config.resolveImport(consumer, "./producer").isEmpty(),
                "canonical .atto importers must not implicitly downgrade to .ores");
        assertEquals(legacy.toAbsolutePath().normalize(),
                config.resolveImport(consumer, "./producer.ores").orElseThrow());
    }

    @Test
    void legacyImporterRequiresExplicitExtensionForAmbiguousAttoOresTwins()
            throws Exception {
        Path consumer = workspace.resolve("consumer.ores");
        Path atto = workspace.resolve("producer.atto");
        Path legacy = workspace.resolve("producer.ores");
        Files.writeString(consumer, "pub fnc answer() -> int { return 42; }");
        Files.writeString(atto, "pub fnc value() -> int { return 42; }");
        Files.writeString(legacy, "pub fnc value() -> int { return 7; }");

        OresProjectConfig config = OresProjectConfig.discover(consumer, Map.of());
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> config.resolveImport(consumer, "./producer"));
        assertTrue(denied.getMessage().contains("ambiguous legacy source import"));
        assertEquals(atto.toAbsolutePath().normalize(),
                config.resolveImport(consumer, "./producer.atto").orElseThrow());
        assertEquals(legacy.toAbsolutePath().normalize(),
                config.resolveImport(consumer, "./producer.ores").orElseThrow());
    }

    @Test
    void legacyImporterMayResolveUnambiguousModernOrLegacySource()
            throws Exception {
        Path consumer = workspace.resolve("consumer.ores");
        Path modern = workspace.resolve("modern.atto");
        Path legacy = workspace.resolve("legacy.ores");
        Files.writeString(consumer, "pub fnc answer() -> int { return 42; }");
        Files.writeString(modern, "pub fnc value() -> int { return 42; }");
        Files.writeString(legacy, "pub fnc value() -> int { return 7; }");
        OresProjectConfig config = OresProjectConfig.discover(consumer, Map.of());

        assertEquals(modern.toAbsolutePath().normalize(),
                config.resolveImport(consumer, "./modern").orElseThrow());
        assertEquals(legacy.toAbsolutePath().normalize(),
                config.resolveImport(consumer, "./legacy").orElseThrow());
    }

    @Test
    void outsideRootExistsAndMissingAreDeniedBeforeSuffixProbe() throws Exception {
        Path project = Files.createDirectories(workspace.resolve("project"));
        Path consumer = project.resolve("consumer.atto");
        Path existingOutside = workspace.resolve("outside.atto");
        Files.writeString(consumer, "pub fnc main() -> int { return 42; }");
        Files.writeString(existingOutside, "pub fnc answer() -> int { return 7; }");
        OresProjectConfig config = OresProjectConfig.discover(consumer, Map.of());

        for (String target : new String[] {"../outside", "../missing"}) {
            IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                    () -> config.resolveImport(consumer, target));
            assertTrue(denied.getMessage().contains("outside the project root"),
                    denied::toString);
        }
    }

    @Test
    void inRootSymlinkedAncestorCannotRedirectExtensionlessImport() throws Exception {
        Path project = Files.createDirectories(workspace.resolve("project"));
        Path outside = Files.createDirectories(workspace.resolve("external"));
        Path consumer = project.resolve("consumer.atto");
        Files.writeString(consumer, "pub fnc main() -> int { return 42; }");
        Files.writeString(outside.resolve("dependency.atto"),
                "pub fnc answer() -> int { return 7; }");
        try {
            Files.createSymbolicLink(project.resolve("alias"), outside);
        } catch (UnsupportedOperationException | java.io.IOException
                 | SecurityException unsupported) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "symlinks unavailable on this filesystem");
        }
        OresProjectConfig config = OresProjectConfig.discover(consumer, Map.of());
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> config.resolveImport(consumer, "./alias/dependency"));
        assertTrue(denied.getMessage().contains("symlink alias"), denied::toString);
    }

    @Test
    void canonicalAttoIgnoresIrrelevantLegacySymlinkSibling() throws Exception {
        Path consumer = workspace.resolve("consumer.atto");
        Path modern = workspace.resolve("dependency.atto");
        Path outside = Files.createDirectories(workspace.resolve("outside"));
        Path legacyAlias = workspace.resolve("dependency.ores");
        Files.writeString(consumer, "pub fnc main() -> int { return 42; }");
        Files.writeString(modern, "pub fnc answer() -> int { return 42; }");
        Files.writeString(outside.resolve("dependency.ores"),
                "pub fnc answer() -> int { return 7; }");
        try {
            Files.createSymbolicLink(legacyAlias, outside.resolve("dependency.ores"));
        } catch (UnsupportedOperationException | java.io.IOException
                 | SecurityException unsupported) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "symlinks unavailable on this filesystem");
        }
        OresProjectConfig config = OresProjectConfig.discover(consumer, Map.of());
        assertEquals(modern.toAbsolutePath().normalize(),
                config.resolveImport(consumer, "./dependency").orElseThrow());
    }

    @Test
    void unextendedPhysicalSourceFileCannotBypassCanonicalSuffixSelection()
            throws Exception {
        Path consumer = workspace.resolve("consumer.atto");
        Path noExtension = workspace.resolve("producer");
        Path canonical = workspace.resolve("producer.atto");
        Files.writeString(consumer, "pub fnc main() -> int { return 42; }");
        Files.writeString(noExtension, "pub fnc answer() -> int { return 7; }");
        Files.writeString(canonical, "pub fnc answer() -> int { return 42; }");
        OresProjectConfig config = OresProjectConfig.discover(consumer, Map.of());

        assertEquals(canonical.toAbsolutePath().normalize(),
                config.resolveImport(consumer, "./producer").orElseThrow());
        Files.delete(canonical);
        assertTrue(config.resolveImport(consumer, "./producer").isEmpty(),
                "no-extension file must never be admitted as a native source");
    }

    @Test
    void explicitMixedCaseSourceExtensionsAreRejectedBeforeExistence() throws Exception {
        Path consumer = workspace.resolve("consumer.atto");
        Files.writeString(consumer, "pub fnc main() -> int { return 42; }");
        Files.writeString(workspace.resolve("producer.ATTO"), "pub fnc value() -> int { return 7; }");
        Files.writeString(workspace.resolve("producer.atto"), "pub fnc value() -> int { return 42; }");
        OresProjectConfig config = OresProjectConfig.discover(consumer, Map.of());
        for (String importName : new String[] {
                "./producer.ATTO", "./producer.AtTo", "./missing.ATTO",
                "./producer.ORES", "./missing.JAVA"}) {
            IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                    () -> config.resolveImport(consumer, importName));
            assertTrue(denied.getMessage().contains("noncanonical source extension"),
                    denied::toString);
        }
        assertEquals(workspace.resolve("producer.atto").toAbsolutePath().normalize(),
                config.resolveImport(consumer, "./producer.atto").orElseThrow());
    }

    @Test
    void mixedCaseImporterSuffixCannotChooseCanonicalAttoDialect() throws Exception {
        Path noncanonical = workspace.resolve("consumer.ATTO");
        Path producer = workspace.resolve("producer.atto");
        Files.writeString(noncanonical, "pub fnc main() -> int { return 42; }");
        Files.writeString(producer, "pub fnc value() -> int { return 42; }");
        OresProjectConfig config = OresProjectConfig.discover(noncanonical, Map.of());

        for (Path importer : new Path[] {
                noncanonical, workspace.resolve("missing.OrEs"),
                workspace.resolve("missing.JAVA")}) {
            IllegalArgumentException denied = assertThrows(
                    IllegalArgumentException.class,
                    () -> config.resolveImport(importer, "./producer.atto"));
            assertTrue(denied.getMessage().contains("noncanonical importer source extension"),
                    denied::toString);
        }
    }

    @Test
    void legacyExplicitOresStillWorksButCaseAliasIsRejected() throws Exception {
        Path consumer = workspace.resolve("consumer.ores");
        Path legacy = workspace.resolve("producer.ores");
        Files.writeString(consumer, "pub fnc main() -> int { return 42; }");
        Files.writeString(legacy, "pub fnc value() -> int { return 7; }");
        OresProjectConfig config = OresProjectConfig.discover(consumer, Map.of());
        assertEquals(legacy.toAbsolutePath().normalize(),
                config.resolveImport(consumer, "./producer.ores").orElseThrow());
        assertThrows(IllegalArgumentException.class,
                () -> config.resolveImport(consumer, "./producer.OREs"));
    }

    @Test
    void canonicalAttoImporterDoesNotImplicitlyLoadJavaSource() throws Exception {
        Path consumer = workspace.resolve("consumer.atto");
        Path java = workspace.resolve("producer.java");
        Files.writeString(consumer, "pub fnc answer() -> int { return 42; }");
        Files.writeString(java, "public class Producer {}");
        OresProjectConfig config = OresProjectConfig.discover(consumer, Map.of());

        assertTrue(config.resolveImport(consumer, "./producer").isEmpty());
        assertEquals(java.toAbsolutePath().normalize(),
                config.resolveImport(consumer, "./producer.java").orElseThrow());
    }
}
