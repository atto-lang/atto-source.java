package dev.oreslang.runtime;

import dev.oreslang.interop.MixedSourceUnit;
import dev.oreslang.stdlib.PortableLibrarySources;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

final class NativeAllocLibraryTest {

    @Test
    void packagedAllocJsonIsPureOreslangSource() throws Exception {
        String source = PortableLibrarySources.source("alloc/json");
        MixedSourceUnit unit = MixedSourceUnit.parse(
                PortableLibrarySources.unitId("alloc/json"),
                PortableLibrarySources.fileName("alloc/json"),
                source);

        assertEquals(
                PortableLibrarySources.Tier.ALLOC,
                PortableLibrarySources.tier("alloc/json"));
        assertEquals("@alloc/json.ores",
                PortableLibrarySources.unitId("alloc/json"));
        assertTrue(unit.hasOresSource());
        assertFalse(unit.hasJavaSource());
        assertFalse(source.contains("java:"));
        assertTrue(source.contains("define module json"));
    }

    @Test
    void everyPackagedAllocModuleIsExplicitlyRegisteredAndPureOreslang()
            throws Exception {
        Path root = Path.of("alloc");
        assertTrue(Files.isDirectory(root),
                "packaged alloc source root must exist");

        try (var stream = Files.walk(root)) {
            List<Path> modules = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".ores"))
                    .sorted()
                    .toList();
            assertFalse(modules.isEmpty());

            Set<String> actual = modules.stream()
                    .map(path -> root.relativize(path)
                            .toString().replace((char) 92, '/'))
                    .collect(Collectors.toSet());
            assertEquals(
                    actual,
                    PortableLibrarySources.packagedSourcePaths(
                            PortableLibrarySources.Tier.ALLOC));

            for (Path module : modules) {
                String source = Files.readString(module);
                String relative = root.relativize(module)
                        .toString().replace('\\', '/');
                String importPath = "alloc/"
                        + relative.substring(
                                0,
                                relative.length() - ".ores".length());
                MixedSourceUnit unit = MixedSourceUnit.parse(
                        PortableLibrarySources.unitId(importPath),
                        module.getFileName().toString(),
                        source);
                assertFalse(unit.hasJavaSource());
                assertFalse(source.contains("java:"));

                var program =
                        dev.oreslang.parser.Parser.parse(unit.oresSource());
                for (var imported : program.imports()) {
                    assertTrue(
                            PortableLibrarySources.mayImport(
                                    PortableLibrarySources.Tier.ALLOC,
                                    imported.path()),
                            () -> relative
                                    + " depends on forbidden module "
                                    + imported.path());
                }
            }
        }
    }

    @Test
    void linkedProgramResolvesAllocJsonAsReservedCompilerUnit()
            throws Exception {
        Path entry = Files.createTempFile("ores-alloc-json-", ".ores");
        try {
            Files.writeString(entry, """
                    import module json from "alloc/json";

                    pub routine main(): void {
                      return;
                    }
                    """);

            var build = LinkedProgramRunner.validate(entry);
            assertTrue(build.units().containsKey("@alloc/json.ores"));
        } finally {
            Files.deleteIfExists(entry);
        }
    }

    @Test
    void allocJsonCannotBeShadowedByApplicationSource() throws Exception {
        Path project = Files.createTempDirectory("ores-alloc-shadow-");
        Path alloc = Files.createDirectories(project.resolve("alloc"));
        Path entry = project.resolve("main.ores");
        try {
            // This filesystem file is intentionally invalid. If project
            // resolution wins over the reserved namespace, validation fails.
            Files.writeString(alloc.resolve("json.ores"), "not valid oreslang");
            Files.writeString(entry, """
                    import module json from "alloc/json";
                    pub routine main(): void { return; }
                    """);

            var build = LinkedProgramRunner.validate(entry);
            assertTrue(build.units().containsKey("@alloc/json.ores"));
            assertFalse(build.units().containsKey(
                    alloc.resolve("json.ores").toAbsolutePath()
                            .normalize().toString().replace('\\', '/')));
        } finally {
            Files.deleteIfExists(entry);
            Files.deleteIfExists(alloc.resolve("json.ores"));
            Files.deleteIfExists(alloc);
            Files.deleteIfExists(project);
        }
    }

    @Test
    void unknownAllocImportNeverFallsBackToApplicationFilesystem()
            throws Exception {
        Path project = Files.createTempDirectory("ores-alloc-unknown-");
        Path alloc = Files.createDirectories(project.resolve("alloc"));
        Path entry = project.resolve("main.ores");
        try {
            Files.writeString(alloc.resolve("phantom.ores"), """
                    define module phantom as
                    end
                    """);
            Files.writeString(entry, """
                    import module phantom from "alloc/phantom";
                    pub routine main(): void { return; }
                    """);

            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> LinkedProgramRunner.validate(entry));
            assertTrue(
                    failure.getMessage().contains(
                            "unknown Oreslang alloc-library module"),
                    failure::getMessage);
        } finally {
            Files.deleteIfExists(entry);
            Files.deleteIfExists(alloc.resolve("phantom.ores"));
            Files.deleteIfExists(alloc);
            Files.deleteIfExists(project);
        }
    }

    @Test
    void allocImportPathsAndDependencyTierFailClosed() throws Exception {
        for (String invalid : List.of(
                "alloc/../secret",
                "alloc/./json",
                "alloc/a/../json",
                "alloc//json",
                "alloc/",
                "alloc/\\json",
                "alloc/json?x",
                "alloc/%2e%2e/json",
                "alloc/one//two",
                "alloc/json" + (char) 0,
                "alloc/" + "verylongsegment".repeat(400),
                "alloc/" + "nested/".repeat(33) + "json")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> PortableLibrarySources.unitId(invalid),
                    invalid);
        }

        assertEquals(
                PortableLibrarySources.unitId("alloc/json"),
                PortableLibrarySources.unitId("alloc/json.ores"));
        assertEquals(
                PortableLibrarySources.source("alloc/json"),
                PortableLibrarySources.source("alloc/json.ores"));

        assertTrue(PortableLibrarySources.mayImport(
                PortableLibrarySources.Tier.ALLOC,
                "alloc/json"));
        assertFalse(PortableLibrarySources.mayImport(
                PortableLibrarySources.Tier.ALLOC,
                "std/core"));
        assertFalse(PortableLibrarySources.mayImport(
                PortableLibrarySources.Tier.ALLOC,
                "./project"));
        assertFalse(PortableLibrarySources.mayImport(
                PortableLibrarySources.Tier.STD,
                "alloc/json"));
    }

    @Test
    void strictIsolateCanValidateAllocJsonWithoutHostCapabilities()
            throws Exception {
        Path entry = Files.createTempFile("ores-alloc-strict-", ".ores");
        try {
            Files.writeString(entry, """
                    import module json from "alloc/json";
                    pub routine main(): void { return; }
                    """);

            var build = LinkedProgramRunner.validate(
                    entry,
                    Map.of(),
                    IsolatePolicy.strictFaas(),
                    PermissionCheckMode.COMPILE);
            assertTrue(build.units().containsKey("@alloc/json.ores"));
        } finally {
            Files.deleteIfExists(entry);
        }
    }
}
