package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.OwnershipChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class TypedMapClosedSurfaceLanguageTest {
    @Test
    void ownershipCheckerRestoresTerminalIndexedMoveOnlyMapCandidateOnCatchEdge() {
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

        assertDoesNotThrow(() ->
                OwnershipChecker.check(Parser.parse(source)));
    }

    @Test
    void ownershipCheckerRestoresTerminalNamedPutCandidateOnCatchEdge() {
        String source = """
                import class JsonValue from "alloc/json";

                fnc stage(JsonValue item): void {
                  val Map<string, JsonValue> values =
                      new Map<string, JsonValue>();

                  try {
                    const Option<JsonValue> previous =
                        values.put("x", item);
                  } catch (err) {
                    rt take item;
                  }
                  return;
                }
                """;

        assertDoesNotThrow(() ->
                OwnershipChecker.check(Parser.parse(source)));
    }

    @Test
    void ownershipCheckerKeepsTerminalCandidateMovedAfterNormalTryExit() {
        String source = """
                import class JsonValue from "alloc/json";

                fnc stage(JsonValue item): void {
                  val Map<string, JsonValue> values =
                      new Map<string, JsonValue>();

                  try {
                    values["x"] = item;
                  } catch (err) {
                  }

                  rt take item;
                  return;
                }
                """;

        assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(source)));
    }

    @Test
    void ownershipCheckerDoesNotRestoreMoveWhenMapWriteIsNotTerminal() {
        String source = """
                import class JsonValue from "alloc/json";

                fnc stage(JsonValue item): void {
                  val Map<string, JsonValue> values =
                      new Map<string, JsonValue>();

                  try {
                    values["x"] = item;
                    stdio.println("after commit");
                  } catch (err) {
                    rt take item;
                  }
                  return;
                }
                """;

        assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(source)));
    }

    @Test
    void outerCatchKeepsInnerCommittedRecoverableMapMoveConservative() {
        String source = """
                import class JsonValue from "alloc/json";

                fnc stage(JsonValue item): void {
                  val Map<string, JsonValue> values =
                      new Map<string, JsonValue>();

                  try {
                    try {
                      values["x"] = item;
                    } catch (inner) {
                    }
                  } catch (outer) {
                    rt take item;
                  }
                  return;
                }
                """;

        assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(source)));
    }

    @Test
    void ownershipCheckerDoesNotGrantRollbackToUserClassNamedJsonValue() {
        String source = """
                define class JsonValue as
                end

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

        assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(source)));
    }

    @Test
    void ownershipCheckerFailsClosedOnTypedMapMutatorArity() {
        String source = """
                import class JsonValue from "alloc/json";

                pub fnc main(): void {
                  val Map<string, JsonValue> values =
                      new Map<string, JsonValue>();
                  values.put("x");
                  return;
                }
                """;

        assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(source)));
    }

    @Test
    void stringAndNominalSymbolKeysExecuteWithoutCoercion() {
        String source = """
                pub fnc main(): void {
                  val Map<string | Symbol, int> values =
                      new Map<string | Symbol, int>();
                  values["ready"] = 11;
                  values[#ready] = 22;
                  stdio.println(values["ready"]);
                  stdio.println(values[#ready]);
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source program = assertDoesNotThrow(() ->
                Source.newBuilder(
                                OresLanguage.ID,
                                source,
                                "typed-map-string-symbol.ores")
                        .mimeType(OresLanguage.MIME_TYPE)
                        .build());
        assertDoesNotThrow(() -> {
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false)
                    .out(output)
                    .build()) {
                context.eval(program);
            }
        });

        assertEquals(
                "11\n22\n",
                output.toString(StandardCharsets.UTF_8)
                        .replace("\r\n", "\n"));
    }

    @Test
    void suspendableIndexedWritePreservesRhsFirstAssignmentOrder() {
        String source = """
                async fnc key(): string {
                  stdio.println("key");
                  return "answer";
                }

                async fnc value(): int {
                  stdio.println("value");
                  return 42;
                }

                pub async fnc main(): void {
                  val Map<string, int> values = new Map<string, int>();
                  values[await key()] = await value();
                  stdio.println(values["answer"]);
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source program = assertDoesNotThrow(() ->
                Source.newBuilder(
                                OresLanguage.ID,
                                source,
                                "typed-map-indexed-write-order.ores")
                        .mimeType(OresLanguage.MIME_TYPE)
                        .build());
        assertDoesNotThrow(() -> {
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false)
                    .out(output)
                    .build()) {
                context.eval(program);
            }
        });

        assertEquals(
                "value\nkey\n42\n",
                output.toString(StandardCharsets.UTF_8)
                        .replace("\r\n", "\n"));
    }

    @Test
    void suspendableConstructionAndIndexedWriteUseSameRuntimeContract() {
        String source = """
                async fnc key(): string {
                  return "answer";
                }

                async fnc value(): int {
                  return 42;
                }

                pub async fnc main(): void {
                  val Map<string, int> values = new Map<string, int>();
                  values[await key()] = await value();
                  stdio.println(values["answer"]);
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source program = assertDoesNotThrow(() ->
                Source.newBuilder(
                                OresLanguage.ID,
                                source,
                                "typed-map-suspendable-indexed-write.ores")
                        .mimeType(OresLanguage.MIME_TYPE)
                        .build());
        assertDoesNotThrow(() -> {
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false)
                    .out(output)
                    .build()) {
                context.eval(program);
            }
        });

        assertEquals(
                "42\n",
                output.toString(StandardCharsets.UTF_8)
                        .replace("\r\n", "\n"));
    }

    @Test
    void closedMapTypeRequiresSupportedExplicitDomains() {
        for (String source : new String[] {
                """
                pub fnc main(): void {
                  val Map<int, int> bad = new Map<int, int>();
                  return;
                }
                """,
                """
                pub fnc main(): void {
                  val Map<string, float> bad = new Map<string, float>();
                  return;
                }
                """,
                """
                pub fnc main(): void {
                  val Map<string> bad = new Map<string>();
                  return;
                }
                """,
                """
                pub fnc main(): void {
                  val Map<> bad = new Map<>();
                  return;
                }
                """
        }) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)));
        }
    }

    @Test
    void indexedAccessPreservesExactKeyAndValueTypes() {
        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("""
                        pub fnc main(): void {
                          val Map<string, int> values = new Map<string, int>();
                          values[7] = 1;
                          return;
                        }
                        """));

        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("""
                        pub fnc main(): void {
                          val Map<string, int> values = new Map<string, int>();
                          values["x"] = "wrong";
                          return;
                        }
                        """));

        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("""
                        pub fnc main(): void {
                          val Map<Symbol, bool> values = new Map<Symbol, bool>();
                          const bool bad = values["ready"];
                          return;
                        }
                        """));
    }

    @Test
    void mutationRequiresDirectMutableOwner() {
        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("""
                        pub fnc main(): void {
                          const Map<string, int> values =
                              new Map<string, int>();
                          values["x"] = 1;
                          return;
                        }
                        """));

        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("""
                        pub fnc main(): void {
                          let Map<string, int> values =
                              new Map<string, int>();
                          values["x"] = 1;
                          return;
                        }
                        """));

        assertDoesNotThrow(() ->
                OresCompiler.parseAndTypeCheck("""
                        pub fnc main(): void {
                          let mut Map<string, int> values =
                              new Map<string, int>();
                          values["x"] = 1;
                          const int x = values["x"];
                          return;
                        }
                        """));

        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("""
                        pub fnc main(): void {
                          let mut Map<string, int> values =
                              new Map<string, int>();
                          values = new Map<string, int>();
                          return;
                        }
                        """));

        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("""
                        pub fnc main(): void {
                          val Map<string, int> values =
                              new Map<string, int>();
                          (rt borrow mut values)["x"] = 1;
                          return;
                        }
                        """));
    }

    @Test
    void aliasesNestedStorageCallableAbiAndClosuresFailClosed() {
        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("""
                        pub fnc main(): void {
                          val Map<string, int> first =
                              new Map<string, int>();
                          val Map<string, int> alias = first;
                          return;
                        }
                        """));

        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("""
                        pub fnc main(): void {
                          val Map<string, int> values =
                              new Map<string, int>();
                          const pair = tuple(values, 1);
                          return;
                        }
                        """));

        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("""
                        fnc consume(Map<string, int> values): void {
                          return;
                        }
                        pub fnc main(): void { return; }
                        """));

        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("""
                        fnc make(): Map<string, int> {
                          val Map<string, int> values =
                              new Map<string, int>();
                          return values;
                        }
                        pub fnc main(): void { return; }
                        """));

        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("""
                        pub fnc main(): void {
                          val Map<string, int> values =
                              new Map<string, int>();
                          values["x"] = 1;
                          const callback = || -> {
                            return values["x"];
                          };
                          stdio.println(callback());
                          return;
                        }
                        """));
    }

    @Test
    void mapCannotHideInFieldsOrModuleState() {
        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("""
                        define class Holder as
                          val Map<string, int> values =
                              new Map<string, int>();
                        end
                        pub fnc main(): void { return; }
                        """));

        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("""
                        define module state
                          val Map<string, int> values =
                              new Map<string, int>();
                        end
                        pub fnc main(): void { return; }
                        """));
    }

    @Test
    void userDeclarationsCannotShadowBuiltinMapType() {
        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("""
                        define class Map as
                        end
                        pub fnc main(): void { return; }
                        """));

        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("""
                        type Map<T> = T;
                        pub fnc main(): void { return; }
                        """));

        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("""
                        fnc identity<Map>(Map value): Map {
                          return value;
                        }
                        pub fnc main(): void { return; }
                        """));

        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("""
                        define class Holder<Map> as
                        end
                        pub fnc main(): void { return; }
                        """));
    }
    @Test
    void genericCallsAndTransientAggregatesCannotSmuggleMapOwnership() {
        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("""
                        fnc sink<T>(T value): void {
                          return;
                        }
                        pub fnc main(): void {
                          val Map<string, int> values =
                              new Map<string, int>();
                          sink(values);
                          return;
                        }
                        """));

        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("""
                        pub fnc main(): void {
                          tuple(new Map<string, int>(), 1);
                          return;
                        }
                        """));

        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("""
                        pub fnc main(): void {
                          [new Map<string, int>()];
                          return;
                        }
                        """));

        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("""
                        pub fnc main(): void {
                          infer struct{values: new Map<string, int>()};
                          return;
                        }
                        """));

        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("""
                        pub fnc main(): void {
                          Some(new Map<string, int>());
                          return;
                        }
                        """));
    }

    @Test
    void genericObjectSpecializationCannotHideMapStorage() {
        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("""
                        define class Box<T> as
                          pub val T value;
                        end

                        pub fnc main(): void {
                          new Box<Map<string, int>>(
                              new Map<string, int>());
                          return;
                        }
                        """));
    }


    @Test
    void actorExecutionCannotAllocateLocalMapYet() {
        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("""
                        actor fnc work(): void {
                          val Map<string, int> values =
                              new Map<string, int>();
                          values["x"] = 1;
                          return;
                        }
                        """));
    }


    @Test
    void aliasesCannotEraseRuntimeMapDescriptors() {
        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("""
                        type Key = string;
                        pub fnc main(): void {
                          val Map<Key, int> values = new Map<Key, int>();
                          return;
                        }
                        """));

        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("""
                        type Value = int;
                        pub fnc main(): void {
                          val Map<string, Value> values =
                              new Map<string, Value>();
                          return;
                        }
                        """));

        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("""
                        type LocalMap = Map<string, int>;
                        pub fnc main(): void {
                          val LocalMap values = new Map<string, int>();
                          return;
                        }
                        """));
    }

    @Test
    void lexicalClosureSnapshotCannotSilentlyRetainUncapturedMap() {
        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("""
                        pub fnc main(): void {
                          val Map<string, int> values =
                              new Map<string, int>();
                          const callback = || -> {
                            return 7;
                          };
                          stdio.println(callback());
                          return;
                        }
                        """));

        assertDoesNotThrow(() ->
                OresCompiler.parseAndTypeCheck("""
                        pub fnc main(): void {
                          val Map<string, int> values =
                              new Map<string, int>();
                          const callback = nlex || -> {
                            return 7;
                          };
                          stdio.println(callback());
                          return;
                        }
                        """));
    }


    @Test
    void readOnlyMapMembersPreservePresenceAndKeyTyping() {
        String source = """
                pub fnc main(): void {
                  val Map<string, int> values = new Map<string, int>();
                  values["answer"] = 42;
                  stdio.println(values.size);
                  stdio.println(values.has("answer"));
                  stdio.println(values.has("missing"));
                  stdio.println(values.get("answer").unwrap());
                  stdio.println(values.get("missing").is_none());
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source program = assertDoesNotThrow(() ->
                Source.newBuilder(
                                OresLanguage.ID,
                                source,
                                "typed-map-read-members.ores")
                        .mimeType(OresLanguage.MIME_TYPE)
                        .build());
        assertDoesNotThrow(() -> {
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false)
                    .out(output)
                    .build()) {
                context.eval(program);
            }
        });

        assertEquals(
                "1\ntrue\nfalse\n42\ntrue\n",
                output.toString(StandardCharsets.UTF_8)
                        .replace("\r\n", "\n"));

        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("""
                        pub fnc main(): void {
                          val Map<string, int> values =
                              new Map<string, int>();
                          values.has(#wrong);
                          return;
                        }
                        """));

        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("""
                        pub fnc main(): void {
                          const Map<string, int> values =
                              new Map<string, int>();
                          values.take("x");
                          return;
                        }
                        """));
    }

    @Test
    void putReturnsPreviousValueAndRequiresDirectOwnerAuthority() {
        String source = """
                pub fnc main(): void {
                  val Map<string, int> values = new Map<string, int>();
                  stdio.println(values.put("a", 10).is_none());
                  stdio.println(values.put("a", 20).unwrap());
                  stdio.println(values["a"]);
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source program = assertDoesNotThrow(() ->
                Source.newBuilder(
                                OresLanguage.ID,
                                source,
                                "typed-map-put.ores")
                        .mimeType(OresLanguage.MIME_TYPE)
                        .build());
        assertDoesNotThrow(() -> {
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false)
                    .out(output)
                    .build()) {
                context.eval(program);
            }
        });
        assertEquals(
                "true\n10\n20\n",
                output.toString(StandardCharsets.UTF_8)
                        .replace("\r\n", "\n"));

        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("""
                        pub fnc main(): void {
                          const Map<string, int> values =
                              new Map<string, int>();
                          values.put("x", 1);
                          return;
                        }
                        """));

        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("""
                        pub fnc main(): void {
                          val Map<string, int> values =
                              new Map<string, int>();
                          const writer = values.put;
                          return;
                        }
                        """));

        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("""
                        pub fnc main(): void {
                          val Map<string, int> values =
                              new Map<string, int>();
                          (rt borrow mut values).put("x", 1);
                          return;
                        }
                        """));

        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("""
                        pub fnc main(): void {
                          val Map<string, int> values =
                              new Map<string, int>();
                          values.put("x", "wrong");
                          return;
                        }
                        """));
    }

    @Test
    void takeAndRemoveRequireOwnerAuthorityAndPreserveAbsence() {
        String source = """
                pub fnc main(): void {
                  val Map<string, int> values = new Map<string, int>();
                  values["a"] = 10;
                  values["b"] = 20;
                  stdio.println(values.take("a").unwrap());
                  stdio.println(values.take("a").is_none());
                  stdio.println(values.remove("b"));
                  stdio.println(values.remove("b"));
                  stdio.println(values.size);
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source program = assertDoesNotThrow(() ->
                Source.newBuilder(
                                OresLanguage.ID,
                                source,
                                "typed-map-take-remove.ores")
                        .mimeType(OresLanguage.MIME_TYPE)
                        .build());
        assertDoesNotThrow(() -> {
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false)
                    .out(output)
                    .build()) {
                context.eval(program);
            }
        });
        assertEquals(
                "10\ntrue\ntrue\nfalse\n0\n",
                output.toString(StandardCharsets.UTF_8)
                        .replace("\r\n", "\n"));

        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("""
                        pub fnc main(): void {
                          let Map<string, int> values =
                              new Map<string, int>();
                          values.remove("x");
                          return;
                        }
                        """));

        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("""
                        pub fnc main(): void {
                          val Map<string, int> values =
                              new Map<string, int>();
                          const remover = values.remove;
                          return;
                        }
                        """));

        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("""
                        pub fnc main(): void {
                          val Map<string, int> values =
                              new Map<string, int>();
                          (rt borrow mut values).take("x");
                          return;
                        }
                        """));
    }


    @Test
    void suspendableMapMembersPreserveOwnerAuthorityAndEvaluateKeysOnce() {
        String source = """
                async fnc key(string value): string {
                  stdio.println(value);
                  return value;
                }

                pub async fnc main(): void {
                  val Map<string, int> values = new Map<string, int>();
                  values["a"] = 10;
                  values["b"] = 20;

                  stdio.println(values.has(await key("a")));
                  stdio.println(values.get(await key("a")).unwrap());
                  stdio.println(values.take(await key("a")).unwrap());
                  stdio.println(values.take(await key("a")).is_none());
                  stdio.println(values.remove(await key("b")));
                  stdio.println(values.remove(await key("b")));
                  stdio.println(values.size);
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source program = assertDoesNotThrow(() ->
                Source.newBuilder(
                                OresLanguage.ID,
                                source,
                                "typed-map-suspendable-members.ores")
                        .mimeType(OresLanguage.MIME_TYPE)
                        .build());
        assertDoesNotThrow(() -> {
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false)
                    .out(output)
                    .build()) {
                context.eval(program);
            }
        });

        assertEquals(
                "a\ntrue\na\n10\na\n10\na\ntrue\nb\ntrue\nb\nfalse\n0\n",
                output.toString(StandardCharsets.UTF_8)
                        .replace("\r\n", "\n"));
    }


    @Test
    void suspendablePutEvaluatesKeyAndValueOnceInOrder() {
        String source = """
                async fnc key(string value): string {
                  stdio.println(value);
                  return value;
                }

                async fnc number(int value): int {
                  stdio.println(value);
                  return value;
                }

                pub async fnc main(): void {
                  val Map<string, int> values = new Map<string, int>();
                  stdio.println(
                      values.put(await key("a"), await number(10)).is_none());
                  stdio.println(
                      values.put(await key("a"), await number(20)).unwrap());
                  stdio.println(values["a"]);
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source program = assertDoesNotThrow(() ->
                Source.newBuilder(
                                OresLanguage.ID,
                                source,
                                "typed-map-suspendable-put.ores")
                        .mimeType(OresLanguage.MIME_TYPE)
                        .build());
        assertDoesNotThrow(() -> {
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false)
                    .out(output)
                    .build()) {
                context.eval(program);
            }
        });

        assertEquals(
                "a\n10\ntrue\na\n20\n10\n20\n",
                output.toString(StandardCharsets.UTF_8)
                        .replace("\r\n", "\n"));
    }


}
