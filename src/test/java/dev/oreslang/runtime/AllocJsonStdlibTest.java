package dev.oreslang.runtime;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.interop.MixedSourceUnit;
import dev.oreslang.stdlib.AllocLibrarySources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AllocJsonStdlibTest {
    @TempDir Path temp;

    @Test
    void jsonDescriptorIsPackagedPureOreslang() throws Exception {
        String source = AllocLibrarySources.source("alloc/json");
        MixedSourceUnit parsed = MixedSourceUnit.parse(
                AllocLibrarySources.unitId("alloc/json"),
                AllocLibrarySources.fileName("alloc/json"),
                source);

        assertTrue(parsed.hasOresSource());
        assertFalse(parsed.hasJavaSource());
        assertTrue(source.contains("JsonValue"));
        assertEquals("@alloc/json.ores", AllocLibrarySources.unitId("alloc/json"));
    }

    @Test
    void reservedAllocJsonCannotBeShadowedAndEmptyJsonMapExecutes() throws Exception {
        Path project = temp.resolve("shadow");
        Files.createDirectories(project.resolve("alloc"));
        Files.writeString(project.resolve("alloc/json.ores"), """
                define pub class NotJsonValue as
                end
                """);

        Path entry = project.resolve("main.ores");
        Files.writeString(entry, """
                import class JsonValue from "alloc/json";

                pub routine main(): void {
                  val Map<string, JsonValue> values =
                      new Map<string, JsonValue>();
                  stdio.println(values.size());
                  return;
                }
                """);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        var build = LinkedProgramRunner.run(
                entry,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                out,
                new ByteArrayOutputStream());

        assertEquals("0", out.toString(StandardCharsets.UTF_8).trim());
        assertTrue(build.units().containsKey("@alloc/json.ores"));
        assertFalse(build.units().containsKey(
                project.resolve("alloc/json.ores").toAbsolutePath().normalize()
                        .toString().replace('\\', '/')));
    }

    @Test
    void exactAllocImportEnablesJsonMapOwnershipRecoveryProof() {
        String source = """
                import class JsonValue from "alloc/json";

                fnc stage(JsonValue item): void {
                  val Map<string, JsonValue> values =
                      new Map<string, JsonValue>();
                  try {
                    values["x"] = item;
                  } catch (err) {
                    rt take item;
                  }
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void jsonMapRequiresExactAllocImport() {
        String source = """
                fnc stage(JsonValue item): void {
                  val Map<string, JsonValue> values =
                      new Map<string, JsonValue>();
                  return;
                }
                """;

        assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void importedJsonValueIsOpaqueAndNotDirectlyConstructible() throws Exception {
        Path entry = temp.resolve("opaque.ores");
        Files.writeString(entry, """
                import class JsonValue from "alloc/json";

                pub routine main(): void {
                  val JsonValue bad = new JsonValue();
                  return;
                }
                """);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(entry));
        assertTrue(failure.getMessage().contains("opaque alloc/json value"),
                failure.getMessage());
    }

    @Test
    void jsonMapRequiresStringOnlyKeys() throws Exception {
        Path entry = temp.resolve("symbol-key.ores");
        Files.writeString(entry, """
                import class JsonValue from "alloc/json";

                pub routine main(): void {
                  val Map<Symbol, JsonValue> bad =
                      new Map<Symbol, JsonValue>();
                  return;
                }
                """);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(entry));
        assertTrue(failure.getMessage().contains("String-only key domain"),
                failure.getMessage());
    }

    @Test
    void unknownAllocModuleFailsClosedBeforeProjectResolution() throws Exception {
        Path project = temp.resolve("unknown");
        Files.createDirectories(project.resolve("alloc"));
        Files.writeString(project.resolve("alloc/not-json.ores"), """
                define pub class JsonValue as
                end
                """);
        Path entry = project.resolve("main.ores");
        Files.writeString(entry, """
                import class JsonValue from "alloc/not-json";
                pub routine main(): void { return; }
                """);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(entry));
        assertTrue(failure.getMessage().contains("unknown Oreslang alloc module"),
                failure.getMessage());
    }
}
