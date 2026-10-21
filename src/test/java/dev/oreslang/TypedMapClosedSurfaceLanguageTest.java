package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
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


}
