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
    void outOfRootPathIsRejectedBeforeCheckingWhetherItExists() throws Exception {
        Path project = Files.createDirectory(temp.resolve("project"));
        Path entry = project.resolve("main.atto");
        Files.writeString(entry, "pub fnc answer() -> int { return 42; }");
        OresProjectConfig config = OresProjectConfig.discover(entry, Map.of());
        Path absentOutside = temp.resolve("not-present.atto");
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> HostSourceUnitIds.of(absentOutside, config));
        assertTrue(denied.getMessage().contains("outside the project root"),
                denied::toString);
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

    @Test
    void linkedImportCannotEscapeProjectRootThroughRelativeTraversal() throws Exception {
        Path project = temp.resolve("project");
        Files.createDirectories(project);
        Path consumer = project.resolve("consumer.atto");
        Path outside = temp.resolve("outside.atto");
        Files.writeString(consumer, """
                import * as external from "../outside.atto";
                pub fnc main() -> int { return 42; }
                """);
        Files.writeString(outside, "pub fnc value() -> int { return 7; }");
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(consumer, Map.of()));
        assertTrue(denied.getMessage().contains("outside the project root"),
                denied::toString);
    }

    @Test
    void linkedImportCannotSmuggleSymlinkAliasAcrossMacroSourceUnits() throws Exception {
        Path target = temp.resolve("producer.atto");
        Path alias = temp.resolve("alias.atto");
        Path consumer = temp.resolve("consumer.atto");
        Files.writeString(target, """
                define pub macro literal(Const<int> value) -> Syntax<Expr> {
                    return quote(value);
                }
                """);
        Files.writeString(consumer, """
                import * as lib from "./alias.atto";
                pub fnc main() -> int { return lib.literal!(const 42); }
                """);
        try {
            Files.createSymbolicLink(alias, target.getFileName());
        } catch (UnsupportedOperationException | java.io.IOException
                 | SecurityException unsupported) {
            Assumptions.assumeTrue(false, "symlinks unavailable on this runner");
        }
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(consumer, Map.of()));
        assertTrue(denied.getMessage().contains("symlink"),
                denied::toString);
    }

    @Test
    void linkedImportRejectsHardlinkedPublicMacroSource() throws Exception {
        Path producer = temp.resolve("producer.atto");
        Path alias = temp.resolve("alias.atto");
        Path consumer = temp.resolve("consumer.atto");
        Files.writeString(producer, """
                define pub macro literal(Const<int> value) -> Syntax<Expr> {
                    return quote(value);
                }
                """);
        try {
            Files.createLink(alias, producer);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unsupported) {
            Assumptions.assumeTrue(false, "hardlinks unavailable on this runner");
        }
        Files.writeString(consumer, """
                import * as first from "./producer.atto";
                import * as second from "./alias.atto";
                pub fnc main() -> int { return first.literal!(const 42); }
                """);
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(consumer, Map.of()));
        assertTrue(denied.getMessage().contains("hardlink")
                        || denied.getMessage().contains("alias"),
                denied::toString);
    }

    @Test
    void hardlinkOutsideImportClosureFailsClosedOnUnixFileSystems() throws Exception {
        Path project = temp.resolve("project");
        Path outside = temp.resolve("outside");
        Files.createDirectories(project);
        Files.createDirectories(outside);
        Path original = outside.resolve("source.atto");
        Path alias = project.resolve("alias.atto");
        Files.writeString(original, "pub fnc answer() -> int { return 42; }");
        try {
            Files.createLink(alias, original);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unsupported) {
            Assumptions.assumeTrue(false, "hardlinks unavailable on this runner");
        }
        try {
            Files.getAttribute(alias, "unix:nlink");
        } catch (UnsupportedOperationException unsupported) {
            Assumptions.assumeTrue(false,
                    "no Unix hardlink count; per-build file-key checks remain active");
        }
        OresProjectConfig projectConfig = OresProjectConfig.discover(alias, Map.of());
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> HostSourceUnitIds.of(alias, projectConfig));
        assertTrue(denied.getMessage().contains("hardlink"), denied::toString);
    }

    @Test
    void fileIdentityRegistryIsIdempotentAcrossRepeatedImports() throws Exception {
        Path source = temp.resolve("single.atto");
        Files.writeString(source, "pub fnc answer() -> int { return 42; }");
        OresProjectConfig projectConfig = OresProjectConfig.discover(source, Map.of());
        HostSourceUnitIds.Admission admission = new HostSourceUnitIds.Admission();
        assertEquals("project/single.atto", admission.id(source, projectConfig));
        assertEquals("project/single.atto",
                admission.id(source.toAbsolutePath().normalize(), projectConfig));
    }

    @Test
    void externalEnvironmentRootCannotGrantCompileTimeMacroAuthority() throws Exception {
        Path project = temp.resolve("project");
        Path external = temp.resolve("ambient");
        Files.createDirectories(project);
        Files.createDirectories(external);
        Path consumer = project.resolve("consumer.atto");
        Path producer = external.resolve("producer.atto");
        Files.writeString(consumer, """
                import * as lib from "producer";
                pub fnc main() -> int {
                    return lib.literal!(const 40 + 2);
                }
                """);
        Files.writeString(producer, """
                define pub macro literal(Const<int> value) -> Syntax<Expr> {
                    return quote(value);
                }
                """);
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(consumer,
                        Map.of(OresProjectConfig.ENV_ORESLANG_PATH, external.toString())));
        assertTrue(denied.getMessage().contains("verified source ownership"),
                denied::toString);
    }

    @Test
    void sourceReplacementAfterAdmissionCannotReuseOriginalLogicalIdentity() throws Exception {
        Path original = temp.resolve("replace.atto");
        Path oldInode = temp.resolve("old-replace.atto");
        Files.writeString(original, "pub fnc answer() -> int { return 42; }");
        OresProjectConfig project = OresProjectConfig.discover(original, Map.of());
        HostSourceUnitIds.Admission admission = new HostSourceUnitIds.Admission();
        String id = admission.id(original, project);
        assertDoesNotThrow(() -> admission.verifyRead(original, project, id));

        // Retain the original file object so the OS cannot immediately reuse
        // its inode/file key when a replacement appears under the same name.
        Files.move(original, oldInode);
        Files.writeString(original, "pub fnc answer() -> int { return 123456789; }");
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> admission.verifyRead(original, project, id));
        assertTrue(denied.getMessage().contains("changed between admission"),
                denied::toString);
    }

    @Test
    void inPlaceSourceEditAfterAdmissionIsAlsoRejected() throws Exception {
        Path original = temp.resolve("mutated.atto");
        Files.writeString(original, "pub fnc answer() -> int { return 42; }");
        OresProjectConfig project = OresProjectConfig.discover(original, Map.of());
        HostSourceUnitIds.Admission admission = new HostSourceUnitIds.Admission();
        String id = admission.id(original, project);

        Files.writeString(original, "pub fnc answer() -> int { return 42123456789; }");
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> admission.verifyRead(original, project, id));
        assertTrue(denied.getMessage().contains("changed between admission"),
                denied::toString);
    }

    @Test
    void externalEnvironmentRootCanStillSupplyOrdinaryRuntimeDeclarations() throws Exception {
        Path project = temp.resolve("project");
        Path external = temp.resolve("ambient");
        Files.createDirectories(project);
        Files.createDirectories(external);
        Path consumer = project.resolve("consumer.atto");
        Path producer = external.resolve("producer.atto");
        Files.writeString(consumer, """
                import * as lib from "producer";
                pub fnc main() -> int { return 42; }
                """);
        Files.writeString(producer, "pub fnc helper() -> int { return 7; }");
        IncrementalCompiler.BuildResult result = assertDoesNotThrow(
                () -> LinkedProgramRunner.validate(consumer,
                        Map.of(OresProjectConfig.ENV_ORESLANG_PATH, external.toString())));
        assertTrue(result.units().containsKey("project/consumer.atto"));
        assertTrue(result.units().containsKey("external/1/producer.atto"));
    }

    @Test
    void filesystemUnitNamesMustMatchLogicalUnicodePolicy() throws Exception {
        Path valid = temp.resolve("emoji-🙂.atto");
        Files.writeString(valid, "pub fnc answer() -> int { return 42; }");
        OresProjectConfig config = OresProjectConfig.discover(valid, Map.of());
        assertEquals("project/emoji-🙂.atto", HostSourceUnitIds.of(valid, config));

        Path colon;
        try {
            colon = temp.resolve("colon:name.atto");
            Files.writeString(colon, "pub fnc answer() -> int { return 42; }");
        } catch (java.nio.file.InvalidPathException | java.io.IOException
                 | SecurityException unsupported) {
            Assumptions.assumeTrue(false, "filename unsupported by host filesystem");
            return;
        }
        assertThrows(IllegalArgumentException.class, () -> HostSourceUnitIds.of(colon, config));
    }
}
