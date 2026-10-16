package dev.oreslang.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class OresProjectConfigRegressionTest {
    @TempDir Path temp;

    @Test
    void oresPathsPreservesLeadingTrailingSpacesAsLiteralDirectoryNames() throws Exception {
        Path project = temp.resolve("consumer");
        Path spaced = temp.resolve(" source directory ");
        Path other = temp.resolve("other");
        Files.createDirectories(project);
        Files.createDirectories(spaced);
        Files.createDirectories(other);
        Files.writeString(spaced.resolve("answer.ores"), "pub fnc answer(): int { return 42; }\n");
        Path manifest = project.resolve(OresProjectConfig.PACKAGE_MANIFEST_NAME);
        Files.writeString(manifest, "schema_version = \"1\"\n[source]\nroots = []\n");

        OresProjectConfig config = OresProjectConfig.load(manifest,
                Map.of(OresProjectConfig.ENV_ORES_PATHS, ":" + spaced + "::" + other + ":"));

        assertEquals(List.of(spaced, other), config.environmentPaths());
        assertEquals(spaced.resolve("answer.ores").toRealPath(),
                config.resolveImport(project.resolve("main.ores"), "answer").orElseThrow());
    }

    @Test
    void packageContextRejectsMalformedImportedAliasesWithStableErrors() throws Exception {
        Path project = temp.resolve("consumer");
        Files.createDirectories(project);
        Path manifest = project.resolve(OresProjectConfig.PACKAGE_MANIFEST_NAME);
        Files.writeString(manifest, "schema_version = \"1\"\n[dependencies]\ntools = \"acme/tools\"\n");
        OresProjectConfig config = OresProjectConfig.load(manifest, Map.of());
        Path source = project.resolve("importer.ores");
        for (String input : new String[]{null, "", "tools/src/a", "pkg:", "pkg:tools",
                "pkg:/src/a", "pkg:tools/"}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> config.forImportedPackage(source, input), String.valueOf(input));
            assertTrue(error.getMessage().contains("expected pkg:"), String.valueOf(input));
        }
        assertThrows(IllegalArgumentException.class,
                () -> config.forImportedPackage(null, "pkg:tools/src/a"));
    }

    @Test
    void validPackageImportEntersDependencyProjectWithoutUsingConsumerRoot() throws Exception {
        Path project = temp.resolve("consumer");
        Path dependency = project.resolve("zed_modules/acme/tools");
        Files.createDirectories(dependency.resolve("src"));
        Files.writeString(project.resolve(OresProjectConfig.PACKAGE_MANIFEST_NAME),
                "schema_version = \"1\"\n[dependencies]\ntools = \"acme/tools\"\n");
        Files.writeString(dependency.resolve(OresProjectConfig.PACKAGE_MANIFEST_NAME),
                "schema_version = \"1\"\n[package]\nname = \"tools\"\n");
        Path source = dependency.resolve("src/a.ores");
        Files.writeString(source, "pub fnc a(): int { return 1; }\n");
        OresProjectConfig consumer = OresProjectConfig.discover(project.resolve("main.ores"), Map.of());
        OresProjectConfig imported = consumer.forImportedPackage(source, "pkg:tools/src/a");
        assertEquals(dependency.toRealPath(), imported.projectRoot().toRealPath());
        assertEquals("tools", imported.projectName().orElseThrow());
        OresProjectConfig windowsSeparators = consumer.forImportedPackage(source, "pkg:tools\\src\\a");
        assertEquals(dependency.toRealPath(), windowsSeparators.projectRoot().toRealPath());
        assertEquals(source.toRealPath(),
                imported.resolveImport(source, "+/src/a").orElseThrow());
        IllegalArgumentException retiredMarker = assertThrows(IllegalArgumentException.class,
                () -> imported.resolveImport(source, "@/src/a"));
        assertTrue(retiredMarker.getMessage().contains("invalid import prefix"));
    }
}
