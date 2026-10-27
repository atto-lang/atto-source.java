package dev.oreslang;

import dev.oreslang.config.OresProjectConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class ImportPhysicalContainmentTest {
    @TempDir Path temp;

    @Test
    void bareImportRejectsFileSymlinkEscapingConfiguredRoot() throws Exception {
        Path app = temp.resolve("app");
        Files.createDirectories(app);
        Path main = app.resolve("main.ores");
        Files.writeString(main, "pub fnc main(): void { return; }\n");

        Path outside = temp.resolve("outside.ores");
        Files.writeString(outside, "pub fnc secret(): int { return 42; }\n");
        Path alias = app.resolve("secret.ores");
        requireSymlink(alias, outside);

        OresProjectConfig config = OresProjectConfig.discover(main, Map.of());
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> config.resolveImport(main, "secret"));
        assertTrue(failure.getMessage().contains("escapes physical configured search root"),
                failure.getMessage());
    }

    @Test
    void bareImportRejectsDirectorySymlinkEscapingConfiguredRoot() throws Exception {
        Path app = temp.resolve("app-dir");
        Files.createDirectories(app);
        Path main = app.resolve("main.ores");
        Files.writeString(main, "pub fnc main(): void { return; }\n");

        Path outside = temp.resolve("external");
        Files.createDirectories(outside);
        Path target = outside.resolve("secret.ores");
        Files.writeString(target, "pub fnc secret(): int { return 42; }\n");
        requireSymlink(app.resolve("alias"), outside);

        OresProjectConfig config = OresProjectConfig.discover(main, Map.of());
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> config.resolveImport(main, "alias/secret"));
        assertTrue(failure.getMessage().contains("escapes physical configured search root"),
                failure.getMessage());
    }

    @Test
    void inRootAliasUsesCanonicalPhysicalSourceIdentity() throws Exception {
        Path app = temp.resolve("app-alias");
        Files.createDirectories(app);
        Path main = app.resolve("main.ores");
        Files.writeString(main, "pub fnc main(): void { return; }\n");

        Path target = app.resolve("real.ores");
        Files.writeString(target, "pub fnc value(): int { return 42; }\n");
        Path alias = app.resolve("alias.ores");
        requireSymlink(alias, target);

        OresProjectConfig config = OresProjectConfig.discover(main, Map.of());
        assertEquals(target.toRealPath(), config.resolveImport(main, "alias").orElseThrow());
    }

    @Test
    void configuredSymlinkRootRemainsSupportedWhenTargetStaysInsideIt() throws Exception {
        Path app = temp.resolve("consumer");
        Files.createDirectories(app);
        Path main = app.resolve("main.ores");
        Files.writeString(main, "pub fnc main(): void { return; }\n");

        Path physicalRoot = temp.resolve("physical-shared");
        Files.createDirectories(physicalRoot);
        Path shared = physicalRoot.resolve("shared.ores");
        Files.writeString(shared, "pub fnc shared(): int { return 42; }\n");

        Path rootAlias = temp.resolve("shared-root");
        requireSymlink(rootAlias, physicalRoot);

        OresProjectConfig config = OresProjectConfig.discover(
                main,
                Map.of(OresProjectConfig.ENV_ORESLANG_PATH, rootAlias.toString()));
        assertEquals(shared.toRealPath(), config.resolveImport(main, "shared").orElseThrow());
    }

    private static void requireSymlink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | SecurityException unsupported) {
            assumeTrue(false, "symlinks unavailable: " + unsupported);
        } catch (IOException failure) {
            // Windows developer hosts may require an elevated privilege or
            // Developer Mode for symlink creation. Treat only that platform
            // capability failure as an unavailable test facility.
            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                assumeTrue(false, "symlinks unavailable: " + failure);
            }
            throw failure;
        }
    }
}
