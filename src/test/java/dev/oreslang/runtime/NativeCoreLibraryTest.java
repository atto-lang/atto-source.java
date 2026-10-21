package dev.oreslang.runtime;

import dev.oreslang.interop.MixedSourceUnit;
import dev.oreslang.stdlib.CoreLibrarySources;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
    void everyPackagedModuleIsPureOreslangAndUsesOnlyBundledDependencies() throws Exception {
        Path root = Path.of("stdlib");
        assertTrue(Files.isDirectory(root), "packaged stdlib source root must exist");

        try (var stream = Files.walk(root)) {
            List<Path> modules = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".ores"))
                    .sorted()
                    .toList();
            assertFalse(modules.isEmpty(), "native-first stdlib must contain Oreslang modules");
            Set<String> actual = modules.stream()
                    .map(path -> root.relativize(path).toString().replace((char) 92, '/'))
                    .collect(Collectors.toSet());
            assertEquals(actual, CoreLibrarySources.packagedSourcePaths(),
                    "every packaged .ores file must be explicitly registered and vice versa");

            for (Path module : modules) {
                String source = Files.readString(module);
                String relative = root.relativize(module).toString().replace('\\', '/');
                String importPath =
                        CoreLibrarySources.importPathForPackagedSource(relative);
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
                            CoreLibrarySources.isBundledImport(imported.path()),
                            () -> relative + " depends on non-bundled module "
                                    + imported.path());
                    if (CoreLibrarySources.isCoreImport(importPath)) {
                        assertFalse(
                                CoreLibrarySources.isAllocImport(imported.path()),
                                () -> relative
                                        + " leaks allocator-backed alloc/* into std/*: "
                                        + imported.path());
                    }
                }
            }
        }
    }


    @Test
    void bundledCoreCannotBeOverriddenByApplicationSourceRoot() throws Exception {
        Path project = Files.createTempDirectory("ores-stdlib-shadow-");
        Path std = Files.createDirectories(project.resolve("std"));
        Path entry = project.resolve("main.ores");
        try {
            Files.writeString(std.resolve("core.ores"), """
                    define module core
                      pub fnc min_int(int left, int right): int {
                        return 999;
                      }
                    end
                    """);
            Files.writeString(entry, """
                    import module core from "std/core";
                    pub routine main(): void {
                        stdio.println(core.min_int(9, 4));
                        return;
                    }
                    """);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            var build = LinkedProgramRunner.run(
                    entry, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                    out, new ByteArrayOutputStream());
            assertTrue(build.units().containsKey("@std/core.ores"));
            assertEquals("4", out.toString(StandardCharsets.UTF_8).trim(),
                    "application filesystem must never shadow std/core");
        } finally {
            Files.deleteIfExists(entry);
            Files.deleteIfExists(std.resolve("core.ores"));
            Files.deleteIfExists(std);
            Files.deleteIfExists(project);
        }
    }

    @Test
    void unknownCoreImportNeverFallsBackToApplicationFilesystem() throws Exception {
        Path project = Files.createTempDirectory("ores-stdlib-unknown-");
        Path std = Files.createDirectories(project.resolve("std"));
        Path entry = project.resolve("main.ores");
        try {
            Files.writeString(std.resolve("phantom.ores"), """
                    define module phantom
                    end
                    """);
            Files.writeString(entry, """
                    import module phantom from "std/phantom";
                    pub routine main(): void { return; }
                    """);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> LinkedProgramRunner.validate(entry));
            assertTrue(
                    failure.getMessage().contains(
                            "unknown Oreslang bundled-library module"));
        } finally {
            Files.deleteIfExists(entry);
            Files.deleteIfExists(std.resolve("phantom.ores"));
            Files.deleteIfExists(std);
            Files.deleteIfExists(project);
        }
    }

    @Test
    void allocJsonIsReservedBundledModuleAndCannotBeShadowed() throws Exception {
        assertTrue(CoreLibrarySources.isAllocImport("alloc/json"));
        assertTrue(CoreLibrarySources.isBundledImport("alloc/json"));
        assertFalse(CoreLibrarySources.isCoreImport("alloc/json"));
        assertEquals("@alloc/json.ores", CoreLibrarySources.unitId("alloc/json"));
        assertEquals("json.ores", CoreLibrarySources.fileName("alloc/json"));
        assertEquals(
                "alloc/json",
                CoreLibrarySources.importPathForPackagedSource(
                        "alloc/json.ores"));
        assertTrue(
                CoreLibrarySources.source("alloc/json")
                        .contains("define module json"));

        Path project = Files.createTempDirectory("ores-alloc-json-shadow-");
        Path alloc = Files.createDirectories(project.resolve("alloc"));
        Path entry = project.resolve("main.ores");
        try {
            Files.writeString(alloc.resolve("json.ores"), """
                    define module json as
                      pub fnc attacker(): int { return 999; }
                    end
                    """);
            Files.writeString(entry, """
                    import module json from "alloc/json";
                    pub routine main(): void { return; }
                    """);

            var build = LinkedProgramRunner.validate(entry);
            assertTrue(
                    build.units().containsKey("@alloc/json.ores"),
                    "allocator-tier JSON must resolve to its packaged unit");
            assertFalse(
                    build.units().containsKey(
                            alloc.resolve("json.ores")
                                    .toAbsolutePath()
                                    .normalize()
                                    .toString()
                                    .replace('\\', '/')),
                    "application source roots must never shadow alloc/json");
        } finally {
            Files.deleteIfExists(entry);
            Files.deleteIfExists(alloc.resolve("json.ores"));
            Files.deleteIfExists(alloc);
            Files.deleteIfExists(project);
        }
    }

    @Test
    void strictIsolateMayValidateTrustedStdlibWithoutJavaCapability() throws Exception {
        Path entry = Files.createTempFile("ores-stdlib-strict-", ".ores");
        try {
            Files.writeString(entry, """
                    import module core from "std/core";
                    pub routine main(): void {
                        stdio.println(core.max_int(9, 4));
                        return;
                    }
                    """);
            var build = LinkedProgramRunner.validate(
                    entry, Map.of(), IsolatePolicy.strictFaas(), PermissionCheckMode.COMPILE);
            assertTrue(build.units().containsKey("@std/core.ores"));
        } finally {
            Files.deleteIfExists(entry);
        }
    }

    @Test
    void validationUsesExactPackagedStdlibWithoutHostInterop() throws Exception {
        Path entry = Files.createTempFile("ores-no-java-", ".ores");
        try {
            Files.writeString(entry, """
                    import module core from "std/core";
                    pub routine main(): void {
                      stdio.println(core.min_int(9, 4));
                      return;
                    }
                    """);
            var build = LinkedProgramRunner.validate(entry);
            assertTrue(build.units().containsKey("@std/core.ores"));
        } finally {
            Files.deleteIfExists(entry);
        }
    }

    @Test
    void coreImportPathsFailClosedOnTraversalAndUnknownModules() throws Exception {
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
                () -> CoreLibrarySources.unitId("alloc/../secret"));
        assertThrows(
                IllegalArgumentException.class,
                () -> CoreLibrarySources.unitId("alloc//json"));
        assertThrows(
                IllegalArgumentException.class,
                () -> CoreLibrarySources.unitId("alloc/"));

        for (String invalid : List.of(
                "std/../secret", "std/./core", "std/a/../core",
                "std//core", "std/", "std/\\\\core",
                "std/core?x", "std/%2e%2e/core", "std/one//two",
                "std/core" + (char) 0,
                "std/" + "verylongsegment".repeat(400),
                "std/" + "nested/".repeat(33) + "core",
                "alloc/../secret", "alloc/./json", "alloc/a/../json",
                "alloc//json", "alloc/", "alloc/\\\\json",
                "alloc/json?x", "alloc/%2e%2e/json", "alloc/one//two",
                "alloc/json" + (char) 0,
                "alloc/" + "verylongsegment".repeat(400),
                "alloc/" + "nested/".repeat(33) + "json")) {
            assertThrows(IllegalArgumentException.class,
                    () -> CoreLibrarySources.unitId(invalid), invalid);
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> CoreLibrarySources.source("std/not-a-real-module"));
        assertEquals(CoreLibrarySources.unitId("std/core"),
                CoreLibrarySources.unitId("std/core.ores"));
        assertEquals(CoreLibrarySources.source("std/core"),
                CoreLibrarySources.source("std/core.ores"));
        assertTrue(CoreLibrarySources.source("std/testing").contains("define module testing"));
        assertTrue(CoreLibrarySources.source("alloc/json").contains("define module json"));
        assertEquals(
                CoreLibrarySources.unitId("alloc/json"),
                CoreLibrarySources.unitId("alloc/json.ores"));
        assertEquals(
                CoreLibrarySources.source("alloc/json"),
                CoreLibrarySources.source("alloc/json.ores"));
        assertFalse(CoreLibrarySources.packagedSourcePaths().contains("phantom.ores"));
    }
}
