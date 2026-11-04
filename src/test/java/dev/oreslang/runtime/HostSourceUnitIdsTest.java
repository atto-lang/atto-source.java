package dev.oreslang.runtime;

import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.config.OresProjectConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * File-based linked execution must not leak physical absolute host paths into
 * the incremental compiler's logical macro/cache/import unit identities.
 * The imported public-macro AST/artifact linker is separately gated.
 */
final class HostSourceUnitIdsTest {
    @TempDir Path temp;

    @Test
    void sourceIdsRemainPortableAcrossIndependentProjectDirectories() throws Exception {
        Path first = Files.createDirectories(temp.resolve("first"));
        Path second = Files.createDirectories(temp.resolve("second"));
        Path a = first.resolve("main.atto");
        Path b = second.resolve("main.atto");
        Files.writeString(a, "pub fnc main() -> int { return 42; }");
        Files.writeString(b, "pub fnc main() -> int { return 42; }");
        assertEquals("project/main.atto",
                HostSourceUnitIds.of(a, OresProjectConfig.discover(a, Map.of())));
        assertEquals("project/main.atto",
                HostSourceUnitIds.of(b, OresProjectConfig.discover(b, Map.of())));
    }

    @Test
    void realLinkedCompilerUsesLogicalIdsForBothAttoUnitsAndTheirImportEdge()
            throws Exception {
        Path producer = temp.resolve("producer.atto");
        Path consumer = temp.resolve("consumer.atto");
        Files.writeString(producer, "pub fnc helper() -> int { return 7; }");
        Files.writeString(consumer, """
                import * as lib from "./producer.atto";
                pub fnc main() -> int { return 42; }
                """);

        IncrementalCompiler.BuildResult build =
                assertDoesNotThrow(() -> LinkedProgramRunner.validate(consumer, Map.of()));
        assertTrue(build.units().containsKey("project/producer.atto"));
        assertTrue(build.units().containsKey("project/consumer.atto"));
        assertEquals(java.util.Set.of("project/producer.atto"),
                build.units().get("project/consumer.atto").dependencies());
        assertTrue(build.units().keySet().stream().noneMatch(s -> s.contains(temp.toString())));
    }

    @Test
    void realLinkedGuestReturnsFortyTwoWithProjectOwnedAttoEntry() throws Exception {
        Path producer = temp.resolve("producer.atto");
        Path consumer = temp.resolve("consumer.atto");
        Files.writeString(producer, "pub fnc helper() -> int { return 7; }");
        Files.writeString(consumer, """
                import * as lib from "./producer.atto";
                pub fnc main() -> int { return 42; }
                """);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        LinkedProgramRunner.RunResult result = LinkedProgramRunner.runWithExitStatus(
                consumer, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                stdout, stderr);
        assertEquals(42, result.exitStatus());
        assertEquals("", stdout.toString(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(result.build().units().containsKey("project/consumer.atto"));
    }

    @Test
    void extensionlessLinkedImportPrefersCanonicalAttoOverLegacyOres() throws Exception {
        Path canonical = temp.resolve("producer.atto");
        Path legacy = temp.resolve("producer.ores");
        Path consumer = temp.resolve("consumer.atto");
        Files.writeString(canonical, "pub fnc helper() -> int { return 7; }");
        Files.writeString(legacy, "pub fnc helper() -> int { return 13; }");
        Files.writeString(consumer, """
                import * as lib from "./producer";
                pub fnc main() -> int { return 42; }
                """);
        IncrementalCompiler.BuildResult build = LinkedProgramRunner.validate(consumer, Map.of());
        assertTrue(build.units().containsKey("project/producer.atto"));
        assertFalse(build.units().containsKey("project/producer.ores"));
        assertEquals(java.util.Set.of("project/producer.atto"),
                build.units().get("project/consumer.atto").dependencies());
    }

    @Test
    void realLinkedAttoImportCannotImplicitlyDowngradeToLegacyOres() throws Exception {
        Path consumer = temp.resolve("consumer.atto");
        Path legacy = temp.resolve("producer.ores");
        Files.writeString(legacy, "pub fnc helper() -> int { return 7; }");
        Files.writeString(consumer, """
                import * as lib from "./producer";
                pub fnc main() -> int { return 42; }
                """);

        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(consumer, Map.of()));
        assertTrue(denied.getMessage().contains("does not resolve"));

        Files.writeString(consumer, """
                import * as lib from "./producer.ores";
                pub fnc main() -> int { return 42; }
                """);
        IncrementalCompiler.BuildResult explicit =
                assertDoesNotThrow(() -> LinkedProgramRunner.validate(consumer, Map.of()));
        assertTrue(explicit.units().containsKey("project/producer.ores"));
    }

    @Test
    void realLinkedLegacyImporterRejectsAmbiguousTwinDeclarations() throws Exception {
        Path consumer = temp.resolve("consumer.ores");
        Files.writeString(temp.resolve("producer.atto"),
                "pub fnc helper() -> int { return 42; }");
        Files.writeString(temp.resolve("producer.ores"),
                "pub fnc helper() -> int { return 7; }");
        Files.writeString(consumer, """
                import * as lib from "./producer";
                pub fnc main() -> int { return 42; }
                """);

        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(consumer, Map.of()));
        assertTrue(denied.getMessage().contains("ambiguous legacy source import"));
    }

    @Test
    void legacyOresEntryHasDistinctCanonicalFileIdentity() throws Exception {
        Path source = temp.resolve("legacy.ores");
        Files.writeString(source, "pub fnc main() -> int { return 42; }");
        IncrementalCompiler.BuildResult build = LinkedProgramRunner.validate(source, Map.of());
        assertTrue(build.units().containsKey("project/legacy.ores"));
    }

    @Test
    void absoluteHostPathsRemainForbiddenForUntrustedCompilerSourceMaps()
            throws Exception {
        Path source = temp.resolve("raw.atto");
        String program = "pub fnc main() -> int { return 42; }";
        Files.writeString(source, program);
        assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(Map.of(source.toString(), program)));
    }

    @Test
    void cannotImportFileOutsideAnyConfiguredProjectRoot() throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path outside = temp.resolve("outside.atto");
        Path consumer = project.resolve("consumer.atto");
        Files.writeString(outside, "pub fnc value() -> int { return 7; }");
        Files.writeString(consumer, """
                import * as external from "../outside.atto";
                pub fnc main() -> int { return 42; }
                """);
        assertThrows(IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(consumer, Map.of()));
    }

    @Test
    void cannotAdmitSymlinkSourceAlias() throws Exception {
        Path target = temp.resolve("producer.atto");
        Path alias = temp.resolve("alias.atto");
        Files.writeString(target, "pub fnc main() -> int { return 42; }");
        try {
            Files.createSymbolicLink(alias, target.getFileName());
        } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
            Assumptions.assumeTrue(false, "symlinks unavailable on runner");
        }
        OresProjectConfig project = OresProjectConfig.discover(target, Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> HostSourceUnitIds.of(alias, project));
    }

    @Test
    void noAmbientAbsolutePathBecomesOwnedSourceIdentity() throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path source = project.resolve("main.atto");
        Files.writeString(source, "pub fnc main() -> int { return 42; }");
        OresProjectConfig config = OresProjectConfig.discover(source, Map.of());
        Path notUnderRoot = temp.resolve("unrelated.atto");
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> HostSourceUnitIds.of(notUnderRoot, config));
        assertTrue(denied.getMessage().contains("outside the project root"));
    }

    @Test
    void sourceReplacementBetweenAdmissionAndReadFailsClosed() throws Exception {
        Path source = temp.resolve("mutable.atto");
        Path old = temp.resolve("old-mutable.atto");
        Files.writeString(source, "pub fnc main() -> int { return 42; }");
        OresProjectConfig config = OresProjectConfig.discover(source, Map.of());
        HostSourceUnitIds.Admission admission = new HostSourceUnitIds.Admission();
        String id = admission.id(source, config);
        admission.verifyRead(source, config, id);
        Files.move(source, old);
        Files.writeString(source, "pub fnc main() -> int { return 123456789; }");
        assertThrows(IllegalArgumentException.class,
                () -> admission.verifyRead(source, config, id));
    }

    @Test
    void explicitlyConfiguredExternalRootHasSeparateLogicalIdentity() throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path external = Files.createDirectories(temp.resolve("external"));
        Path source = project.resolve("main.atto");
        Path dependency = external.resolve("dep.atto");
        Files.writeString(source, "pub fnc main() -> int { return 42; }");
        Files.writeString(dependency, "pub fnc helper() -> int { return 7; }");
        OresProjectConfig config = OresProjectConfig.discover(source,
                Map.of(OresProjectConfig.ENV_ORESLANG_PATH, external.toString()));
        assertEquals("project/main.atto", HostSourceUnitIds.of(source, config));
        assertEquals("external/1/dep.atto", HostSourceUnitIds.of(dependency, config));
    }
}
