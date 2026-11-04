package dev.oreslang;

import dev.oreslang.interop.MixedSourceUnit;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AttoSourceAdmissionTest {
    @TempDir Path temp;

    @Test
    void exactSharedAttoScalarCorpusExecutesAndReturnsFortyTwo() throws Exception {
        Path corpus = Path.of("contracts", "atto-parity-v1", "add.atto").toAbsolutePath();
        assertTrue(Files.isRegularFile(corpus), "shared cross-backend .atto corpus must be present");
        assertEquals("pub fnc main(): int { return 40 + 2; }\n", Files.readString(corpus));
        var result = LinkedProgramRunner.runWithExitStatus(
                corpus, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                new ByteArrayOutputStream(), new ByteArrayOutputStream());
        assertEquals(42, result.exitStatus(), "Java/Graal guest must execute the unmodified shared .atto source");
    }

    @Test
    void attoIsNativeGuestSourceNotJavaHostSource() {
        MixedSourceUnit unit = MixedSourceUnit.parse(
                temp.resolve("cache.atto"),
                "pub fnc identity(int x): int { return x; }");
        assertEquals(MixedSourceUnit.PrimaryLanguage.ORES, unit.primaryLanguage());
        assertTrue(unit.hasOresSource());
        assertFalse(unit.hasJavaSource());
        assertTrue(unit.foreignIslands().isEmpty());
    }

    @Test
    void executesAttoGuestWithExtensionlessAttoImport() throws Exception {
        Path helper = temp.resolve("value.atto");
        Path entry = temp.resolve("main.atto");
        Files.writeString(helper, """
                pub fnc answer(): int {
                  return 42;
                }
                """);
        Files.writeString(entry, """
                import fnc answer from "./value";

                pub fnc main(): void {
                  stdio.stdout.write(answer());
                  return;
                }
                """);
        assertEquals(2, LinkedProgramRunner.validate(entry).units().size());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        var run = LinkedProgramRunner.run(
                entry, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), Set.of(), Map.of(),
                out, new ByteArrayOutputStream());
        assertEquals(2, run.units().size());
        assertEquals("42", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void attoJavaIslandsDoNotBypassMixedSourceCapabilityChecks() throws Exception {
        Path entry = temp.resolve("mixed.atto");
        Files.writeString(entry, """
                pub fnc main(): void { return; }
                java { final class Helper {} }
                """);
        IsolatePolicy onlyHostInterop = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.JAVA_INTEROP);
        SecurityException denied = org.junit.jupiter.api.Assertions.assertThrows(
                SecurityException.class,
                () -> LinkedProgramRunner.run(entry, onlyHostInterop,
                        ExecutionProfile.serverJit(), Set.of(), Map.of(),
                        new ByteArrayOutputStream(), new ByteArrayOutputStream()));
        assertTrue(denied.getMessage().contains("JAVA_SOURCE_INTEROP"));
    }

    @Test
    void importedAttoCannotBypassStrictUtf8Admission() throws Exception {
        Path helper = temp.resolve("value.atto");
        Path entry = temp.resolve("main.atto");
        Files.write(helper, new byte[] {(byte) 0xC0, (byte) 0xAF});
        Files.writeString(entry, """
                import fnc answer from "./value.atto";
                pub fnc main(): void { return; }
                """);
        org.junit.jupiter.api.Assertions.assertThrows(
                java.nio.charset.MalformedInputException.class,
                () -> LinkedProgramRunner.validate(entry, Map.of()));
    }

    @Test
    void explicitAttoImportNeverResolvesToLegacyOresTwin() throws Exception {
        Path helper = temp.resolve("value.atto");
        Path legacy = temp.resolve("value.ores");
        Path entry = temp.resolve("main.atto");
        Files.writeString(helper, "pub fnc answer(): int { return 42; }");
        Files.writeString(legacy, "pub fnc answer(): int { return 17; }");
        Files.writeString(entry, """
                import fnc answer from "./value.atto";
                pub fnc main(): void {
                  stdio.stdout.write(answer());
                  return;
                }
                """);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), Set.of(), Map.of(),
                out, new ByteArrayOutputStream());
        assertEquals("42", out.toString(StandardCharsets.UTF_8));
    }
    @Test
    void extensionlessAttoImportWinsOverLegacySiblingInGuestExecution() throws Exception {
        Path modern = temp.resolve("value.atto");
        Path legacy = temp.resolve("value.ores");
        Path entry = temp.resolve("main.atto");
        Files.writeString(modern, "pub fnc answer(): int { return 42; }");
        Files.writeString(legacy, "pub fnc answer(): int { return 7; }");
        Files.writeString(entry, """
                import fnc answer from "./value";
                pub fnc main(): void {
                  stdio.stdout.write(answer());
                  return;
                }
                """);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        var executed = LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), Set.of(), Map.of(),
                out, new ByteArrayOutputStream());
        assertEquals(2, executed.units().size(), "legacy shadow must not enter linked graph");
        assertEquals("42", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void explicitLegacyImportRemainsOptInEvenWhenCanonicalAttoExists() throws Exception {
        Path modern = temp.resolve("value.atto");
        Path legacy = temp.resolve("value.ores");
        Path entry = temp.resolve("main.atto");
        Files.writeString(modern, "pub fnc answer(): int { return 7; }");
        Files.writeString(legacy, "pub fnc answer(): int { return 42; }");
        Files.writeString(entry, """
                import fnc answer from "./value.ores";
                pub fnc main(): void {
                  stdio.stdout.write(answer());
                  return;
                }
                """);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        var executed = LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), Set.of(), Map.of(),
                out, new ByteArrayOutputStream());
        assertEquals(2, executed.units().size());
        assertEquals("42", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void attoPublicClassConstructorAndMutableReceiverLinkAcrossFiles() throws Exception {
        Path library = temp.resolve("column.atto");
        Path entry = temp.resolve("main-column.atto");
        Files.writeString(library, """
                define pub class Column as
                  pub let int value;
                  pub constructor(int initial) {
                    self.value = initial;
                  }
                  pub fnc advance(mut self)(int delta): int {
                    self.value = self.value + delta;
                    return self.value;
                  }
                end
                """);
        Files.writeString(entry, """
                import class Column from "./column.atto";
                pub fnc main(): void {
                  let mut Column column = new Column(40);
                  stdio.stdout.write(column.advance(2));
                  return;
                }
                """);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), Set.of(), Map.of(),
                out, new ByteArrayOutputStream());
        assertEquals("42", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void attoOwnedIntegerArrayAndOptionCompileThroughGuestPipeline() throws Exception {
        // A narrow slice of the cache's actual source semantics, without
        // transferring the private full cache source into this repository.
        Path library = temp.resolve("tiny-column.atto");
        Path entry = temp.resolve("main-tiny-column.atto");
        Files.writeString(library, """
                define pub class TinyColumn as
                  let mut Array<int> slots = new Array<int>();
                  pub fnc add(mut self)(int value): void {
                    self.slots.add(value);
                    return;
                  }
                  pub peek(): Option<int> {
                    if self.slots.size == 0; then
                      return None;
                    fi
                    return Some(self.slots.get(0));
                  }
                end
                """);
        Files.writeString(entry, """
                import class TinyColumn from "./tiny-column.atto";
                pub fnc main(): void {
                  let mut TinyColumn column = new TinyColumn();
                  column.add(42);
                  stdio.stdout.write(column.peek().unwrap());
                  return;
                }
                """);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), Set.of(), Map.of(),
                out, new ByteArrayOutputStream());
        assertEquals("42", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void symlinkedAttoEntrypointIsRejectedBeforeReading() throws Exception {
        Path actual = temp.resolve("real.atto");
        Path alias = temp.resolve("main.atto");
        Files.writeString(actual, "pub fnc main(): void { return; }");
        try {
            Files.createSymbolicLink(alias, actual.getFileName());
        } catch (java.io.IOException | UnsupportedOperationException | SecurityException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symlinks not available");
            return;
        }
        SecurityException denied = org.junit.jupiter.api.Assertions.assertThrows(
                SecurityException.class, () -> LinkedProgramRunner.validate(alias, Map.of()));
        assertTrue(denied.getMessage().contains("symlinked .atto"));
    }

    @Test
    void symlinkedAttoImportIsRejectedBeforeReading() throws Exception {
        Path actual = temp.resolve("real.atto");
        Path alias = temp.resolve("helper.atto");
        Path entry = temp.resolve("main.atto");
        Files.writeString(actual, "pub fnc answer(): int { return 42; }");
        Files.writeString(entry, """
                import fnc answer from "./helper.atto";
                pub fnc main(): void { return; }
                """);
        try {
            Files.createSymbolicLink(alias, actual.getFileName());
        } catch (java.io.IOException | UnsupportedOperationException | SecurityException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symlinks not available");
            return;
        }
        SecurityException denied = org.junit.jupiter.api.Assertions.assertThrows(
                SecurityException.class, () -> LinkedProgramRunner.validate(entry, Map.of()));
        assertTrue(denied.getMessage().contains("symlinked .atto"));
    }


    @Test
    void attoEntrypointThroughSymlinkedDirectoryIsRejected() throws Exception {
        Path real = temp.resolve("real-source");
        Files.createDirectories(real);
        Files.writeString(real.resolve("entry.atto"), "pub fnc main(): void { return; }");
        Path alias = temp.resolve("alias-source");
        try {
            Files.createSymbolicLink(alias, real.getFileName());
        } catch (java.io.IOException | UnsupportedOperationException | SecurityException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symlinks not available");
            return;
        }
        SecurityException denied = org.junit.jupiter.api.Assertions.assertThrows(
                SecurityException.class,
                () -> LinkedProgramRunner.validate(alias.resolve("entry.atto"), Map.of()));
        assertTrue(denied.getMessage().contains("symlinked .atto"));
    }

    @Test
    void attoImportThroughSymlinkedDirectoryIsRejected() throws Exception {
        Path real = temp.resolve("real-import");
        Files.createDirectories(real);
        Files.writeString(real.resolve("helper.atto"),
                "pub fnc answer(): int { return 42; }");
        Path alias = temp.resolve("alias-import");
        Path entry = temp.resolve("main.atto");
        Files.writeString(entry, """
                import fnc answer from "./alias-import/helper.atto";
                pub fnc main(): void { return; }
                """);
        try {
            Files.createSymbolicLink(alias, real.getFileName());
        } catch (java.io.IOException | UnsupportedOperationException | SecurityException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symlinks not available");
            return;
        }
        SecurityException denied = org.junit.jupiter.api.Assertions.assertThrows(
                SecurityException.class,
                () -> LinkedProgramRunner.validate(entry, Map.of()));
        assertTrue(denied.getMessage().contains("symlinked .atto"));
    }

}
