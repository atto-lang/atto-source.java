package dev.oreslang.runtime;

import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.config.OresProjectConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host paths are only admitted by the trusted loader. The compiler continues
 * to reject absolute macro source IDs in ordinary untrusted source maps.
 */
final class HostSourceUnitIdsTest {
    @TempDir Path temp;

    @Test
    void mapsProjectFilesToStableLogicalIdsWithoutHostPathExposure() throws Exception {
        Path source = temp.resolve("main.atto");
        Files.writeString(source, "pub fnc answer() -> int { return 1; }");
        OresProjectConfig config = OresProjectConfig.discover(source, Map.of());

        assertEquals("project/main.atto", HostSourceUnitIds.of(source, config));
        assertEquals("project/main.atto",
                HostSourceUnitIds.of(source.toAbsolutePath().normalize(), config));
        assertFalse(HostSourceUnitIds.of(source, config).contains(temp.toString()));
    }

    @Test
    void refusesUnconfiguredExternalAbsolutePaths() throws Exception {
        Path project = temp.resolve("project");
        Path outside = temp.resolve("outside");
        Files.createDirectories(project);
        Files.createDirectories(outside);
        Path entry = project.resolve("main.atto");
        Path target = outside.resolve("producer.atto");
        Files.writeString(entry, "pub fnc answer() -> int { return 1; }");
        Files.writeString(target, "pub fnc value() -> int { return 7; }");
        OresProjectConfig config = OresProjectConfig.discover(entry, Map.of());

        assertThrows(IllegalArgumentException.class, () -> HostSourceUnitIds.of(target, config));
    }

    @Test
    void explicitlyConfiguredExternalRootsHaveDeterministicDisjointPrefix() throws Exception {
        Path project = temp.resolve("project");
        Path external = temp.resolve("externals");
        Files.createDirectories(project);
        Files.createDirectories(external);
        Path entry = project.resolve("main.atto");
        Path target = external.resolve("producer.atto");
        Files.writeString(entry, "pub fnc answer() -> int { return 1; }");
        Files.writeString(target, "pub fnc value() -> int { return 7; }");

        OresProjectConfig config = OresProjectConfig.discover(entry,
                Map.of(OresProjectConfig.ENV_ORESLANG_PATH, external.toString()));
        assertEquals("project/main.atto", HostSourceUnitIds.of(entry, config));
        assertEquals("external/1/producer.atto", HostSourceUnitIds.of(target, config));
    }

    @Test
    void symlinkAliasesOfSourcesAreRejectedBeforeCompilerAdmission() throws Exception {
        Path target = temp.resolve("target.atto");
        Path alias = temp.resolve("alias.atto");
        Files.writeString(target, "pub fnc answer() -> int { return 1; }");
        try {
            Files.createSymbolicLink(alias, target.getFileName());
        } catch (UnsupportedOperationException | java.io.IOException
                 | SecurityException unsupported) {
            Assumptions.assumeTrue(false, "symlinks unavailable on this runner");
        }
        OresProjectConfig config = OresProjectConfig.discover(target, Map.of());
        assertThrows(IllegalArgumentException.class, () -> HostSourceUnitIds.of(alias, config));
        assertEquals("project/target.atto", HostSourceUnitIds.of(target, config));
    }

    @Test
    void ordinaryIncrementalCompilerStillRejectsHostAbsoluteUnitIds() throws Exception {
        Path source = temp.resolve("main.atto");
        String program = "pub fnc answer() -> int { return 1; }";
        Files.writeString(source, program);
        assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(Map.of(source.toString(), program)));
    }

    @Test
    void linkedFilesystemValidationExpandsImportedMacroWithLogicalIds() throws Exception {
        Path producer = temp.resolve("producer.atto");
        Path consumer = temp.resolve("consumer.atto");
        Files.writeString(producer, """
                define pub macro literal(Const<int> value) -> Syntax<Expr> {
                    return quote(value);
                }
                """);
        Files.writeString(consumer, """
                import * as lib from "./producer.atto";
                pub fnc main() -> int {
                    return lib.literal!(const 40 + 2);
                }
                """);

        IncrementalCompiler.BuildResult result =
                assertDoesNotThrow(() -> LinkedProgramRunner.validate(consumer, Map.of()));
        assertTrue(result.units().containsKey("project/producer.atto"));
        assertTrue(result.units().containsKey("project/consumer.atto"));
        assertEquals(java.util.Set.of("project/producer.atto"),
                result.units().get("project/consumer.atto").dependencies());
    }

    @Test
    void linkedGuestExecutionReturnsFortyTwoAfterCompileTimeMacroErasure() throws Exception {
        Path producer = temp.resolve("producer.atto");
        Path consumer = temp.resolve("consumer.atto");
        Files.writeString(producer, """
                define pub macro literal(Const<int> value) -> Syntax<Expr> {
                    return quote(value);
                }
                """);
        Files.writeString(consumer, """
                import * as lib from "./producer.atto";
                pub fnc main() -> int {
                    return lib.literal!(const 40 + 2);
                }
                """);
        LinkedProgramRunner.RunResult result = LinkedProgramRunner.runWithExitStatus(
                consumer, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                new ByteArrayOutputStream(), new ByteArrayOutputStream());
        assertEquals(42, result.exitStatus());
        assertTrue(result.build().units().containsKey("project/consumer.atto"));
    }
}
