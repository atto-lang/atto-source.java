package dev.oreslang.runtime;

import dev.oreslang.interop.MixedSourceUnit;
import dev.oreslang.stdlib.CoreLibrarySources;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class NativeCoreLibraryTest {

    @Test
    void packagedStdModuleIsOreslangSourceAndRunsWithoutJavaInterop() throws Exception {
        String source = CoreLibrarySources.source("std/core");
        MixedSourceUnit unit = MixedSourceUnit.parse(
                CoreLibrarySources.unitId("std/core"),
                CoreLibrarySources.fileName("std/core"),
                source);

        assertTrue(unit.hasOresSource());
        assertFalse(unit.hasJavaSource(),
                "packaged std modules must not contain java/do-java islands");
        assertFalse(source.contains("java:"),
                "packaged std modules must not import Java host classes");

        Path entry = Files.createTempFile("ores-native-core-", ".ores");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            Files.writeString(entry, """
                    import module core from "std/core";

                    pub routine main(): void {
                      stdio.println(core.clamp_int(15, 0, 10));
                      return;
                    }
                    """);

            var build = LinkedProgramRunner.run(
                    entry,
                    IsolatePolicy.developer(),
                    ExecutionProfile.serverJit(),
                    out,
                    err);

            assertTrue(build.units().containsKey("@std/core.ores"),
                    "std/core must be linked as an Oreslang source unit");
            assertTrue(
                    out.toString(StandardCharsets.UTF_8).contains("10"),
                    () -> "expected Oreslang stdlib output, stderr="
                            + err.toString(StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(entry);
        }
    }


    @Test
    void everyPackagedStdModuleIsPureOreslangAndHasOnlyStdDependencies() throws Exception {
        Path root = Path.of("src/main/resources/dev/oreslang/std");
        assertTrue(Files.isDirectory(root), "packaged stdlib source root must exist");

        try (var stream = Files.walk(root)) {
            List<Path> modules = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".ores"))
                    .sorted()
                    .toList();
            assertFalse(modules.isEmpty(), "native-first stdlib must contain Oreslang modules");

            for (Path module : modules) {
                String source = Files.readString(module);
                String relative = root.relativize(module).toString().replace('\\', '/');
                String importPath = "std/" + relative.substring(0, relative.length() - ".ores".length());
                MixedSourceUnit unit = MixedSourceUnit.parse(
                        CoreLibrarySources.unitId(importPath),
                        module.getFileName().toString(),
                        source);

                assertFalse(unit.hasJavaSource(),
                        () -> relative + " contains Java source islands");
                assertFalse(source.contains("java:"),
                        () -> relative + " contains a java: host import");

                var program = dev.oreslang.parser.Parser.parse(unit.oresSource());
                for (var imported : program.imports()) {
                    assertTrue(
                            CoreLibrarySources.isCoreImport(imported.path()),
                            () -> relative + " depends on non-std module " + imported.path());
                }
            }
        }
    }

    @Test
    void coreImportPathsFailClosedOnTraversalAndUnknownModules() {
        assertThrows(
                IllegalArgumentException.class,
                () -> CoreLibrarySources.unitId("std/../secret"));
        assertThrows(
                IllegalArgumentException.class,
                () -> CoreLibrarySources.unitId("std//core"));
        assertThrows(
                IllegalArgumentException.class,
                () -> CoreLibrarySources.unitId("std/"));
        assertThrows(
                IllegalArgumentException.class,
                () -> CoreLibrarySources.source("std/not-a-real-module"));
    }
}
