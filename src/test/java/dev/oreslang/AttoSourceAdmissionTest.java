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
    void attoPublicClassConstructorAndMutableReceiverLinkAcrossFiles() throws Exception {
        Path library = temp.resolve("column.atto");
        Path entry = temp.resolve("main-column.atto");
        Files.writeString(library, """
                pub define class Column as
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
                pub define class TinyColumn as
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

}
