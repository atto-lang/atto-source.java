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
    void nativeImageMetadataIncludesAllocOresResources() throws Exception {
        String path =
                "META-INF/native-image/dev.oreslang/oreslang/reachability-metadata.json";
        try (var input =
                AllocJsonStdlibTest.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) {
                throw new AssertionError("missing Native Image reachability metadata");
            }
            String metadata =
                    new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(metadata.contains("dev/oreslang/alloc/*.ores"));
            assertTrue(metadata.contains("dev/oreslang/alloc/**/*.ores"));
        }
    }

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
                pub define class NotJsonValue as
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
    void aliasedAllocJsonImportDoesNotUnlockCanonicalJsonValueMapDomain() {
        String source = """
                import class JsonValue as J from "alloc/json";

                fnc stage(J item): void {
                  val Map<string, J> values =
                      new Map<string, J>();
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
    void sourceJsonFactoriesConstructAndInsertMoveOnlyValues() throws Exception {
        Path entry = temp.resolve("constructors.ores");
        Files.writeString(entry, """
                import class JsonValue from "alloc/json";
                import class Json from "alloc/json";

                pub routine main(): void {
                  val Map<string, JsonValue> values =
                      new Map<string, JsonValue>();
                  val JsonValue name = Json.of("Ada");
                  values["name"] = name;
                  values.put("enabled", Json.of(true));
                  values.put("count", Json.of(7));
                  values.put("missing", Json.null);
                  stdio.println(values.size());
                  return;
                }
                """);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                entry,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                out,
                new ByteArrayOutputStream());

        assertEquals("4", out.toString(StandardCharsets.UTF_8).trim());
    }

    @Test
    void sourceJsonArrayAndObjectConstructorsAreOwnedAndTyped() throws Exception {
        Path entry = temp.resolve("aggregate-constructors.ores");
        Files.writeString(entry, """
                import class JsonValue from "alloc/json";
                import class Json from "alloc/json";

                pub routine main(): void {
                  val List<JsonValue> items = [
                    Json.of("a"),
                    Json.of(2),
                    Json.null
                  ];
                  val JsonValue array = Json.array(items);

                  val Map<string, JsonValue> fields =
                      new Map<string, JsonValue>();
                  fields["items"] = array;
                  val JsonValue object = Json.object(fields);

                  val Map<string, JsonValue> envelope =
                      new Map<string, JsonValue>();
                  envelope["payload"] = object;
                  stdio.println(envelope.size());
                  return;
                }
                """);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                entry,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                out,
                new ByteArrayOutputStream());

        assertEquals("1", out.toString(StandardCharsets.UTF_8).trim());
    }

    @Test
    void sourceJsonParseProducesOwnedCanonicalJsonValue() throws Exception {
        Path entry = temp.resolve("parse.ores");
        Files.writeString(entry, """
                import class JsonValue from "alloc/json";
                import class Json from "alloc/json";

                pub routine main(): void {
                  val JsonValue parsed =
                      Json.parse("{\\\"name\\\":\\\"Ada\\\",\\\"n\\\":9007199254740993}");
                  stdio.println(Json.stringify(parsed));
                  return;
                }
                """);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                entry,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                out,
                new ByteArrayOutputStream());

        assertEquals(
                "{\"name\":\"Ada\",\"n\":9007199254740993}",
                out.toString(StandardCharsets.UTF_8).trim());
    }

    @Test
    void typedDecodeConstructsClosedStructAndRecordWithoutReflection()
            throws Exception {
        Path entry = temp.resolve("decode-closed-shapes.ores");
        Files.writeString(entry, """
                import class JsonValue from "alloc/json";
                import class Json from "alloc/json";

                pub routine main(): void {
                  const struct{id: int, name: String} asStruct =
                    Json.decode<struct{id: int, name: String}>(
                      "{\\\"id\\\":7,\\\"name\\\":\\\"Ada\\\"}");
                  const record{id: int, name: String} asRecord =
                    Json.decode<record{id: int, name: String}>(
                      "{\\\"id\\\":8,\\\"name\\\":\\\"Grace\\\"}");
                  stdio.println(asStruct.id);
                  stdio.println(asStruct.name);
                  stdio.println(asRecord.id);
                  stdio.println(asRecord.name);
                  return;
                }
                """);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                entry,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                out,
                new ByteArrayOutputStream());

        assertEquals(
                "7" + System.lineSeparator()
                        + "Ada" + System.lineSeparator()
                        + "8" + System.lineSeparator()
                        + "Grace",
                out.toString(StandardCharsets.UTF_8).trim());
    }

    @Test
    void typedDecodeRejectsMissingUnknownAndWrongFieldTypes() throws Exception {
        for (String json : new String[]{
                "{\"id\":1}",
                "{\"id\":1,\"name\":\"Ada\",\"extra\":2}",
                "{\"id\":\"wrong\",\"name\":\"Ada\"}"
        }) {
            Path entry = temp.resolve(
                    "decode-bad-" + Math.abs(json.hashCode()) + ".ores");
            Files.writeString(entry, """
                    import class JsonValue from "alloc/json";
                    import class Json from "alloc/json";

                    pub routine main(): void {
                      const record{id: int, name: String} value =
                        Json.decode<record{id: int, name: String}>("%s");
                      stdio.println(value.id);
                      return;
                    }
                    """.formatted(json.replace("\\", "\\\\").replace("\"", "\\\"")));

            assertThrows(
                    RuntimeException.class,
                    () -> LinkedProgramRunner.run(
                            entry,
                            IsolatePolicy.developer(),
                            ExecutionProfile.serverJit(),
                            new ByteArrayOutputStream(),
                            new ByteArrayOutputStream()),
                    json);
        }
    }

    @Test
    void typedDecodeClassTargetFailsClosedUntilFactoryCodecExists() {
        String source = """
                import class JsonValue from "alloc/json";
                import class Json from "alloc/json";

                define class User as
                  id: int;
                end

                fnc decode_user(): User {
                  return Json.decode<User>("{\\\"id\\\":1}");
                }
                """;

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(
                failure.getMessage().contains("constructor/factory codec"),
                failure.getMessage());
    }

    @Test
    void typedDecodeRequiresExplicitTargetAndStringInput() {
        assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        import class JsonValue from "alloc/json";
                        import class Json from "alloc/json";

                        fnc bad(): void {
                          Json.decode("{}");
                          return;
                        }
                        """));

        assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        import class JsonValue from "alloc/json";
                        import class Json from "alloc/json";

                        fnc bad(): int {
                          return Json.decode<int>(7);
                        }
                        """));
    }

    @Test
    void sourceStringifyIsStableAndNonConsuming() throws Exception {
        Path entry = temp.resolve("stringify.ores");
        Files.writeString(entry, """
                import class JsonValue from "alloc/json";
                import class Json from "alloc/json";

                pub routine main(): void {
                  val Map<string, JsonValue> fields =
                      new Map<string, JsonValue>();
                  fields.put("name", Json.of("Ada"));
                  fields.put("enabled", Json.of(true));
                  fields.put("count", Json.of(7));
                  fields.put("missing", Json.null);

                  stdio.println(Json.stringify(fields));

                  // Stringify is a read boundary, not an ownership transfer.
                  fields.put("after", Json.of("still-owned"));
                  stdio.println(fields.size());

                  val List<JsonValue> items = [
                    Json.of("a"),
                    Json.of(2),
                    Json.null
                  ];
                  val JsonValue array = Json.array(items);
                  stdio.println(Json.stringify(array));
                  return;
                }
                """);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                entry,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                out,
                new ByteArrayOutputStream());

        assertEquals(
                "{\"name\":\"Ada\",\"enabled\":true,\"count\":7,\"missing\":null}"
                        + System.lineSeparator()
                        + "5"
                        + System.lineSeparator()
                        + "[\"a\",2,null]",
                out.toString(StandardCharsets.UTF_8).trim());
    }

    @Test
    void rejectedIndexedJsonWriteRestoresMoveOnlyCandidateInCatch() throws Exception {
        Path entry = temp.resolve("indexed-rollback.ores");
        Files.writeString(entry, """
                import class JsonValue from "alloc/json";
                import class Json from "alloc/json";

                pub routine main(): void {
                  val Map<string, JsonValue> values =
                      new Map<string, JsonValue>();
                  val JsonValue item = Json.of("kept");

                  try {
                    values["\\uD800"] = item;
                  } catch (err) {
                    values["restored"] = item;
                  }

                  stdio.println(values.size());
                  return;
                }
                """);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                entry,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                out,
                new ByteArrayOutputStream());

        assertEquals("1", out.toString(StandardCharsets.UTF_8).trim());
    }

    @Test
    void rejectedNamedPutRestoresMoveOnlyCandidateInCatch() throws Exception {
        Path entry = temp.resolve("named-put-rollback.ores");
        Files.writeString(entry, """
                import class JsonValue from "alloc/json";
                import class Json from "alloc/json";

                pub routine main(): void {
                  val Map<string, JsonValue> values =
                      new Map<string, JsonValue>();
                  val JsonValue item = Json.of("kept");

                  try {
                    values.put("\\uD800", item);
                  } catch (err) {
                    values.put("restored", item);
                  }

                  stdio.println(values.size());
                  return;
                }
                """);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                entry,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                out,
                new ByteArrayOutputStream());

        assertEquals("1", out.toString(StandardCharsets.UTF_8).trim());
    }

    @Test
    void quotaRejectedJsonWriteRestoresNamedOwnerInsideCatch() throws Exception {
        Path entry = temp.resolve("quota-rollback.ores");
        String oversizedKey = "x".repeat(OresKeyedMap.MAX_TEXT_KEY_BYTES + 1);
        Files.writeString(entry, """
                import class JsonValue from "alloc/json";
                import class Json from "alloc/json";

                pub routine main(): void {
                  val Map<string, JsonValue> values =
                      new Map<string, JsonValue>();
                  val JsonValue candidate = Json.of("kept");
                  try {
                    values["%s"] = candidate;
                  } catch (err) {
                    values["restored"] = candidate;
                  }
                  stdio.println(values.size());
                  return;
                }
                """.formatted(oversizedKey));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                entry,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                out,
                new ByteArrayOutputStream());

        assertEquals("1", out.toString(StandardCharsets.UTF_8).trim());
    }

    @Test
    void committedJsonMapWriteConsumesNamedOwner() throws Exception {
        Path entry = temp.resolve("committed-move.ores");
        Files.writeString(entry, """
                import class JsonValue from "alloc/json";
                import class Json from "alloc/json";

                pub routine main(): void {
                  val Map<string, JsonValue> values =
                      new Map<string, JsonValue>();
                  val JsonValue candidate = Json.of("moved");
                  values.put("first", candidate);
                  values.put("second", candidate);
                  return;
                }
                """);

        assertThrows(
                IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(entry));
    }

    @Test
    void jsonObjectConsumesSourceMapSoGuestCyclesCannotBeFormed() throws Exception {
        Path entry = temp.resolve("cycle-prevention.ores");
        Files.writeString(entry, """
                import class JsonValue from "alloc/json";
                import class Json from "alloc/json";

                pub routine main(): void {
                  val Map<string, JsonValue> fields =
                      new Map<string, JsonValue>();
                  val JsonValue object = Json.object(fields);
                  fields["self"] = object;
                  return;
                }
                """);

        assertThrows(
                IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(entry));
    }

    @Test
    void jsonNamespaceCannotBeConstructed() throws Exception {
        Path entry = temp.resolve("json-namespace-new.ores");
        Files.writeString(entry, """
                import class JsonValue from "alloc/json";
                import class Json from "alloc/json";

                pub routine main(): void {
                  val Json bad = new Json();
                  return;
                }
                """);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(entry));
        assertTrue(failure.getMessage().contains("cannot be instantiated"),
                failure.getMessage());
    }

    @Test
    void linkedClassAliasUsesOnlyItsLocalBinding() throws Exception {
        Path library = temp.resolve("alias-library.ores");
        Files.writeString(library, """
                pub define class Widget as
                end
                """);

        Path accepted = temp.resolve("alias-accepted.ores");
        Files.writeString(accepted, """
                import class Widget as W from "./alias-library.ores";

                pub routine main(): void {
                  val W value = new W();
                  return;
                }
                """);
        assertDoesNotThrow(() -> LinkedProgramRunner.validate(accepted));

        Path leaked = temp.resolve("alias-source-name-leak.ores");
        Files.writeString(leaked, """
                import class Widget as W from "./alias-library.ores";

                pub routine main(): void {
                  val Widget bad = new Widget();
                  return;
                }
                """);
        assertThrows(
                IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(leaked));
    }

    @Test
    void aliasedAllocJsonValueRemainsOpaque() throws Exception {
        Path entry = temp.resolve("jsonvalue-alias-opaque.ores");
        Files.writeString(entry, """
                import class JsonValue as J from "alloc/json";

                pub routine main(): void {
                  val J bad = new J();
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
    void aliasedJsonNamespaceRemainsUnconstructible() throws Exception {
        Path entry = temp.resolve("json-alias-opaque.ores");
        Files.writeString(entry, """
                import class Json as J from "alloc/json";

                pub routine main(): void {
                  val J bad = new J();
                  return;
                }
                """);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(entry));
        assertTrue(failure.getMessage().contains(
                        "compiler-owned construction namespace"),
                failure.getMessage());
    }

    @Test
    void allocJsonWildcardNamespaceImportFailsClosed() throws Exception {
        Path entry = temp.resolve("json-wildcard.ores");
        Files.writeString(entry, """
                import * as json_ns from "alloc/json";
                pub routine main(): void { return; }
                """);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(entry));
        assertTrue(failure.getMessage().contains(
                        "alloc/json currently exposes only"),
                failure.getMessage());
    }

    @Test
    void jsonFactoryAliasDoesNotAcquireCompilerAuthority() throws Exception {
        Path entry = temp.resolve("factory-alias.ores");
        Files.writeString(entry, """
                import class JsonValue from "alloc/json";
                import class Json as J from "alloc/json";

                pub routine main(): void {
                  val JsonValue value = J.of("no");
                  return;
                }
                """);

        assertThrows(
                IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(entry));
    }

    @Test
    void jsonFloatFactoryFailsClosedUntilExactDecimalCarrierExists() throws Exception {
        Path entry = temp.resolve("float-fail-closed.ores");
        Files.writeString(entry, """
                import class JsonValue from "alloc/json";
                import class Json from "alloc/json";

                pub routine main(): void {
                  val JsonValue value = Json.of(1.25);
                  return;
                }
                """);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(entry));
        assertTrue(failure.getMessage().contains("exact decimal"),
                failure.getMessage());
    }

    @Test
    void unknownAllocModuleFailsClosedBeforeProjectResolution() throws Exception {
        Path project = temp.resolve("unknown");
        Files.createDirectories(project.resolve("alloc"));
        Files.writeString(project.resolve("alloc/not-json.ores"), """
                pub define class JsonValue as
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
