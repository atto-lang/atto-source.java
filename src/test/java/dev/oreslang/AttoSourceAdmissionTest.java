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
    void extensionlessAttoImportSelectsCanonicalFileOverLegacyTwin() throws Exception {
        Path canonical = temp.resolve("answer.atto");
        Path legacy = temp.resolve("answer.ores");
        Path entry = temp.resolve("preferred.atto");
        Files.writeString(canonical, "pub fnc answer(): int { return 42; }");
        Files.writeString(legacy, "pub fnc answer(): int { return 17; }");
        Files.writeString(entry, """
                import fnc answer from "./answer";
                pub fnc main(): void {
                  stdio.stdout.write(answer());
                  return;
                }
                """);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        var result = LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), Set.of(), Map.of(),
                out, new ByteArrayOutputStream());
        assertEquals(2, result.units().size());
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
                  let Column column = new Column(40);
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
                  let TinyColumn column = new TinyColumn();
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

    @Test
    void attoSourceOnUnsupportedFilesystemFailsClosed() throws Exception {
        // ZipFS provides ordinary DirectoryStream, not provider-pinned
        // SecureDirectoryStream; a path preflight is insufficient.
        Path archive = temp.resolve("source.zip");
        java.net.URI uri = java.net.URI.create("jar:" + archive.toUri());
        try (java.nio.file.FileSystem zip = java.nio.file.FileSystems.newFileSystem(
                uri, Map.of("create", "true"))) {
            Path entry = zip.getPath("/main.atto");
            Files.writeString(entry, "pub fnc main(): void { return; }");
            org.junit.jupiter.api.Assertions.assertThrows(
                    SecurityException.class,
                    () -> dev.oreslang.runtime.BoundedSourceReader.readUtf8(entry));
        } catch (java.nio.file.ProviderNotFoundException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "ZipFS provider unavailable");
        }
    }

    @Test
    void regularAttoFileStillAdmitsStrictUtf8ThroughPinnedDirectory() throws Exception {
        Path entry = temp.resolve("utf8.atto");
        String source = "pub fnc main(): void { return; } // café";
        Files.writeString(entry, source, StandardCharsets.UTF_8);
        var decoded = dev.oreslang.runtime.BoundedSourceReader.readWithByteCount(entry);
        assertEquals(source, decoded.text());
        assertEquals(source.getBytes(StandardCharsets.UTF_8).length, decoded.byteCount());
    }

}
