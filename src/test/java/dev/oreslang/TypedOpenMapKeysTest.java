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
