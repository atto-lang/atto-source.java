package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class TypedOpenMapKeysTest {
    private static final String MAP = """
            define class MyMap extends Map<string, unknown> as
              key fieldX: Option<string>;
              key fieldY: Option<bool>;

              on_put(string key, unknown value): void {
                stdio.stdout.write("P");
                return;
              }
              on_get(string key): void {
                stdio.stdout.write("G");
                return;
              }
              on_remove(string key): void {
                stdio.stdout.write("R");
                return;
              }
            end
            """;

    @Test
    void mapKeysAreSchemaNotFields() {
        Ast.ClassDecl klass = (Ast.ClassDecl) Parser.parse(MAP)
                .modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(decl -> decl instanceof Ast.ClassDecl)
                .findFirst().orElseThrow();

        assertEquals(0, klass.fields().size());
        assertEquals(2, klass.mapKeys().size());
        assertEquals("fieldX", klass.mapKeys().getFirst().name());
        assertEquals("Option", klass.mapKeys().getFirst().valueType().name());
    }

    @Test
    void staticallyChecksLiteralKeysAndAcceptsUnknownKeys() {
        TypeChecker.check(Parser.parse(MAP + """
                fnc valid(): void {
                  val m = new MyMap();
                  m.put("fieldX", "hello");
                  m["fieldY"] = true;
                  m.put("extra", 123);
                  val Option<string> x = m.get("fieldX");
                  val Option<bool> y = m["fieldY"];
                  val Option<unknown> z = m.get("extra");
                  return;
                }
                """));
    }

    @Test
    void rejectsIncorrectLiteralKeyValues() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(MAP + """
                fnc invalid(): void {
                  val m = new MyMap();
                  m.put("fieldX", 123);
                  return;
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(MAP + """
                fnc invalid(): void {
                  val m = new MyMap();
                  m["fieldY"] = "nope";
                  return;
                }
                """)));
    }

    @Test
    void rejectsKeysOutsideMapAndDuplicateKeys() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Wrong as
                  key fieldX: Option<string>;
                end
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Bad extends Map<string, unknown> as
                  key fieldX: Option<string>;
                  key fieldX: Option<bool>;
                end
                """)));
    }

    @Test
    void unknownReadRequiresExplicitNarrowing() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(MAP + """
                fnc invalid(): void {
                  val m = new MyMap();
                  val string surprise = m.get("something").unwrap();
                  return;
                }
                """)));
    }

    @Test
    void rejectsHooksWithWrongSignature() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Bad extends Map<string, unknown> as
                  key fieldX: Option<string>;
                  on_put(string key): void { return; }
                end
                """)));
    }

    @Test
    void mapMutationsRequireAnExclusiveMutableReceiver() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(MAP + """
                        fnc invalid(&MyMap map): void {
                          map.put("fieldX", "wrong");
                          return;
                        }
                        """)));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(MAP + """
                        fnc invalid(&MyMap map): void {
                          map.remove("fieldX");
                          return;
                        }
                        """)));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(MAP + """
                        fnc invalid(&MyMap map): void {
                          map["fieldX"] = "wrong";
                          return;
                        }
                        """)));
        TypeChecker.check(Parser.parse(MAP + """
                fnc valid(&mut MyMap map): void {
                  map.put("fieldX", "hello");
                  map.remove("fieldX");
                  return;
                }
                """));
    }

    @Test
    void rejectsBorrowEscapeThroughPutAndBrackets() {
        IllegalArgumentException put = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(MAP + """
                        fnc invalid(): void {
                          val m = new MyMap();
                          val int x = 7;
                          m.put("extra", &x);
                          return;
                        }
                        """)));
        assertTrue(put.getMessage().contains("cannot store borrowed references"));

        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(MAP + """
                        fnc invalid(): void {
                          val m = new MyMap();
                          val int x = 7;
                          m["extra"] = &x;
                          return;
                        }
                        """)));

        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(MAP + """
                        fnc invalid(): void {
                          val m = new MyMap();
                          val int x = 7;
                          m.put("extra", Some(&x));
                          return;
                        }
                        """)));
    }

    @Test
    void rejectsUnverifiableSchemasAndGenericMapInheritance() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Bad extends Map<string, unknown> as
                  key value: Option<Future<int>>;
                end
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Bad<T> extends Map<string, unknown> as
                  key value: Option<string>;
                end
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Base extends Map<string, unknown> as
                  key value: Option<string>;
                end
                define class Child extends Base as
                end
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  val m = new Map<string, unknown>();
                  return;
                }
                """)));
    }

    @Test
    void rejectsVoidPayloadsAndWrongNestedPayloads() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(MAP + """
                fnc empty(): void { return; }
                fnc invalid(): void {
                  val m = new MyMap();
                  m.put("extra", empty());
                  return;
                }
                """)));
        TypeChecker.check(Parser.parse("""
                define class MyMap extends Map<string, unknown> as
                  key items: Option<Array<string>>;
                end
                fnc valid(): void {
                  val m = new MyMap();
                  val items = new Array<string>();
                  items.add("hello");
                  m.put("items", items);
                  return;
                }
                """));
    }

    @Test
    void ownershipRejectsAliasMutationAfterMapInsertion() {
        String program = """
                define class MyMap extends Map<string, unknown> as
                  key items: Option<Array<string>>;
                end
                fnc invalid(): void {
                  val m = new MyMap();
                  val items = new Array<unknown>();
                  items.add("hello");
                  val string key = "items";
                  m.put(key, items);
                  items.set(0, 42);
                  return;
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(program)));
        assertTrue(failure.getMessage().contains("moved value 'items'"));
    }

    @Test
    void rejectedDynamicWriteDoesNotInvokePutHook() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        String program = MAP + """
                pub routine main(): void {
                  val m = new MyMap();
                  val string key = "fieldY";
                  m.put(key, 7);
                  return;
                }
                """;
        TypeChecker.check(Parser.parse(program));
        Source source = Source.newBuilder(OresLanguage.ID, program, "map-hook-reject.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            assertThrows(PolyglotException.class, () -> context.eval(source));
        }
        assertEquals("", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void bracketAndMethodAccessRunHooksAndReturnOptions() throws Exception {
        String actual = execute(MAP + """
                pub routine main(): void {
                  val m = new MyMap();
                  m.put("fieldX", "hello");
                  m["fieldY"] = true;
                  stdio.stdout.write(m.get("fieldX").unwrap());
                  stdio.stdout.write(m["fieldY"].unwrap());
                  m.remove("fieldX");
                  stdio.stdout.write(m.get("fieldX").is_none());
                  return;
                }
                """);
        assertEquals("PPGhelloGtrueRGtrue", actual);
    }

    @Test
    void nonCopyMapReadsAreRejectedWhileRemoveTransfersOwnership() throws Exception {
        String schema = """
                define class OwnedMap extends Map<string, unknown> as
                  key items: Option<Array<string>>;
                end
                """;
        IllegalArgumentException forbiddenMethod = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(schema + """
                        fnc invalid(): void {
                          val m = new OwnedMap();
                          m.get("items");
                          return;
                        }
                        """)));
        assertTrue(forbiddenMethod.getMessage().contains("non-Copy"));

        IllegalArgumentException forbiddenIndex = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(schema + """
                        fnc invalid(): void {
                          val m = new OwnedMap();
                          m["items"];
                          return;
                        }
                        """)));
        assertTrue(forbiddenIndex.getMessage().contains("non-Copy"));

        assertEquals("true", execute(schema + """
                pub routine main(): void {
                  val m = new OwnedMap();
                  val items = new Array<string>();
                  items.add("hello");
                  m.put("items", items);
                  stdio.stdout.write(m.remove("items").is_some());
                  return;
                }
                """));
    }

    @Test
    void overwriteRequiresExplicitTransferForOwnedValues() throws Exception {
        String schema = """
                define class OwnedMap extends Map<string, unknown> as
                  key items: Option<Array<string>>;
                  key label: Option<string>;
                end
                """;
        String rejected = schema + """
                pub routine main(): void {
                  val m = new OwnedMap();
                  val first = new Array<string>();
                  first.add("first");
                  m.put("items", first);
                  val second = new Array<string>();
                  second.add("second");
                  m.put("items", second);
                  return;
                }
                """;
        TypeChecker.check(Parser.parse(rejected));
        Source source = Source.newBuilder(OresLanguage.ID, rejected, "map-owned-replace.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            PolyglotException failure = assertThrows(
                    PolyglotException.class, () -> context.eval(source));
            assertTrue(failure.getMessage().contains("cannot overwrite a non-Copy owned value"));
        }

        assertEquals("next", execute(schema + """
                pub routine main(): void {
                  val m = new OwnedMap();
                  m.put("label", "first");
                  m.put("label", "next");
                  stdio.stdout.write(m.get("label").unwrap());
                  return;
                }
                """));
    }

    @Test
    void openKeyCannotExposeMoveOnlyValueThroughDynamicGet() throws Exception {
        String program = """
                define class OwnedMap extends Map<string, unknown> as
                end
                pub routine main(): void {
                  val m = new OwnedMap();
                  val items = new Array<string>();
                  items.add("hello");
                  m.put("extra", items);
                  m.get("extra");
                  return;
                }
                """;
        TypeChecker.check(Parser.parse(program));
        Source source = Source.newBuilder(OresLanguage.ID, program, "map-noncopy-read.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            PolyglotException failure = assertThrows(
                    PolyglotException.class, () -> context.eval(source));
            assertTrue(failure.getMessage().contains("move-only"));
        }
    }

    @Test
    void dynamicKeyCannotSmuggleWrongValue() throws Exception {
        String program = MAP + """
                pub routine main(): void {
                  val m = new MyMap();
                  val string k = "fieldX";
                  m.put(k, 42);
                  return;
                }
                """;
        TypeChecker.check(Parser.parse(program));
        Source src = Source.newBuilder(OresLanguage.ID, program, "typed-map-reject.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).build()) {
            PolyglotException failure = assertThrows(PolyglotException.class, () -> context.eval(src));
            assertTrue(failure.getMessage().contains("Map key 'fieldX' expects"));
        }
    }

    private static String execute(String program) throws Exception {
        TypeChecker.check(Parser.parse(program));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source src = Source.newBuilder(OresLanguage.ID, program, "typed-map-keys.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(src);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
