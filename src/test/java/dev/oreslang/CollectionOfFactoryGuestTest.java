package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration of canonical .of lowering from Java #2735 onto tilde-removal
 * Java #3140. The compiler-owned aggregate AST/runtime must be preserved;
 * removed tilde construction cannot regain parser admission, and neither
 * host Java collections nor dynamic member fallback can replace guest values.
 * Authored tests are NOT verified execution evidence until real JUnit runs.
 */
final class CollectionOfFactoryGuestTest {
    private static String program(String body) {
        return "pub fnc main(): void {\n" + body + "\nreturn;\n}\n";
    }

    private static String guestStdout(String text) {
        Source source = assertDoesNotThrow(() -> Source.newBuilder(
                OresLanguage.ID, text, "collection-factory-of.atto")
                .mimeType(OresLanguage.MIME_TYPE).build());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertDoesNotThrow(() -> {
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).out(out).build()) {
                context.eval(source);
            }
        });
        return out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    void mapOfUsesTheSameTypedGuestStorageForAllKeyDomains() {
        String text = program("""
              const counts = Map.of<string, int>("alpha" => 1, "beta" => 2);
              const flags = Map.of<Symbol, bool>(#ready => true);
              const more = Map.of<Symbol, bool>(#ready => true, #done => false);
              stdio.println(counts.size);
              stdio.println(flags.size);
              stdio.println(more.size);
              """);
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(text));
        assertEquals("2\n1\n2\n", guestStdout(text));
    }

    @Test
    void mapOfAcceptsDirectContextAndTypedEmpty() {
        String text = program("""
              const Map<string, int> contextual = Map.of("alpha" => 1);
              const Map<Symbol, bool> empty = Map.of();
              const typed = Map.of<string, int>();
              stdio.println(contextual.size);
              stdio.println(empty.size);
              stdio.println(typed.size);
              """);
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(text));
        assertEquals("1\n0\n0\n", guestStdout(text));
    }

    @Test
    void arrayTupleAndRecordFactoriesReuseExistingAggregateShapes() {
        String text = program("""
              const values = Array.of(1, 2, 3);
              const shorthand = arr[1, 2, 3];
              const tuple = Tuple.of(7, true);
              const record = Record.of(ready: true);
              stdio.println(values[0]);
              stdio.println(shorthand[0]);
              stdio.println(tuple[0]);
              stdio.println(record.ready);
              """);
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(text));
        assertEquals("1\n1\n7\ntrue\n", guestStdout(text));
    }

    @Test
    void missingOrInvalidMapWitnessesAreNotDynamicMaps() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program("const m = Map.of();")));
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program("const m = Map.of(\"key\" => 1);")));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(program("const m = Map.of<string>(\"key\" => 1);")));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(program("const m = Map.of<string, int>(\"key\": 1);")));
    }

    @Test
    void unsupportedFactoryTypeWitnessesAndMapSpreadsFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(program("const a = Array.of<int>(1, 2);")));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(program("const t = Tuple.of<int>(1);")));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(program("const m = Map.of<string,int>(...source);")));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(program("const r = Record.of<int>(x: 1);")));
    }
    private static Ast.Expr firstFactoryInitializer(String source) {
        Ast.Program program = Parser.parse(source);
        Ast.FunctionDecl function = program.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(declaration -> declaration instanceof Ast.FunctionDecl)
                .map(declaration -> (Ast.FunctionDecl) declaration)
                .findFirst().orElseThrow();
        return function.body().stream()
                .filter(statement -> statement instanceof Ast.BindingStmt)
                .map(statement -> ((Ast.BindingStmt) statement).initializer())
                .reduce((first, later) -> later).orElseThrow();
    }

    @Test
    void canonicalFactoriesLowerDirectlyToExistingCheckedAggregateAst() {
        assertInstanceOf(Ast.TupleExpr.class, firstFactoryInitializer(
                program("const t = Tuple.of(1, true);")));
        Ast.ListExpr array = assertInstanceOf(Ast.ListExpr.class,
                firstFactoryInitializer(program("const a = Array.of(1, 2);")));
        assertTrue(array.explicitArrayConstructor(),
                "Array.of must preserve explicit-array typing; never silently infer Tuple");
        Ast.ListExpr oldArray = assertInstanceOf(Ast.ListExpr.class,
                firstFactoryInitializer(program("const a = arr[1, 2];")));
        assertTrue(oldArray.explicitArrayConstructor());
        assertInstanceOf(Ast.ObjectExpr.class, firstFactoryInitializer(
                program("const r = Record.of(ready: true);")));
        Ast.MapLiteralExpr map = assertInstanceOf(Ast.MapLiteralExpr.class,
                firstFactoryInitializer(program("const m = Map.of<string,int>(\"x\" => 1);")));
        assertNotNull(map.mapType(), "typed Map.of needs a compile-time K/V witness");
        assertEquals("Map", map.mapType().name());
        assertEquals(2, map.mapType().arguments().size());
    }

    @Test
    void nominalOrLexicalShadowingNeverHijacksOrdinaryOfCalls() {
        for (String source : new String[]{
                "define class Tuple as end\n" +
                    program("const t = Tuple.of(1, 2);"),
                "import class NamedTuple as Tuple from \"./named-tuple.ores\";\n" +
                    program("const t = Tuple.of(1, 2);"),
                "import types {Tuple} from \"./some-types.ores\";\n" +
                    program("const t = Tuple.of(1, 2);"),
                program("const Tuple = 1; const t = Tuple.of(1, 2);"),
                "pub fnc build<Tuple>(): void { const t = Tuple.of(1, 2); return; }",
                "define class Map as end\n" +
                    program("const m = Map.of(\"x\", 1);"),
                program("const Array = 1; const t = Array.of(1, 2);"),
                // Lexically later names still cannot retroactively grant the
                // compiler ownership of a user's nominal factory.
                program("const t = Tuple.of(1, 2);")
                        + "define class Tuple as end\n",
                program("const r = Record.of(ready: true);")
                        + "define class Record as end\n"
        }) {
            assertInstanceOf(Ast.CallExpr.class,
                    firstFactoryInitializer(source), source);
        }
    }

    @Test
    void explicitArrayFactoryCannotSatisfyTupleReturnType() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        fnc incorrectly_return_tuple(): Tuple[int, int] {
                            return Array.of(1, 2);
                        }
                        """));
        assertTrue(failure.getMessage().contains("return value")
                        || failure.getMessage().contains("Tuple"),
                "Array.of must not be silently reclassified as a Tuple: "
                        + failure.getMessage());
    }

    @Test
    void emptyArraysRequireAnExplicitlyCheckedElementType() {
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(program("const a = Array.of();")));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(program("const a = ~array();")));
        // Zero-arity products and closed records have well-defined shapes.
        assertInstanceOf(Ast.TupleExpr.class,
                firstFactoryInitializer(program("const t = Tuple.of();")));
        assertInstanceOf(Ast.ObjectExpr.class,
                firstFactoryInitializer(program("const r = Record.of();")));
    }

    @Test
    void unimplementedFactoriesCannotFallThroughToHostCollections() {
        for (String kind : new String[]{"Entry", "Pair", "List", "Vector", "Set"}) {
            IllegalArgumentException problem = assertThrows(
                    IllegalArgumentException.class,
                    () -> Parser.parse(program("const x = " + kind + ".of(1, 2);")),
                    kind);
            assertTrue(problem.getMessage().contains("unsupported: " + kind + ".of"),
                    kind + ": " + problem.getMessage());
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(program("const f = " + kind + ".of;")),
                    kind + " method-value fallback");
        }
        // A user-declared class of the same name is an ordinary name,
        // never the compiler-owned pending standard-library type.
        assertInstanceOf(Ast.CallExpr.class, firstFactoryInitializer(
                "define class Pair as end\n" +
                        program("const p = Pair.of(1, 2);")));
    }

    @Test
    void mapOfCannotPublishDuplicateStringOrSymbolKeys() {
        for (String constructor : new String[]{
                "Map.of<string,int>(\"dup\" => 1, \"dup\" => 2)",
                "Map.of<Symbol,bool>(#ready => true, #ready => false)"
        }) {
            String text = program("const m = " + constructor + ";");
            assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(text));
            Source guest = assertDoesNotThrow(() ->
                    Source.newBuilder(OresLanguage.ID, text, "duplicate-of.atto")
                            .mimeType(OresLanguage.MIME_TYPE).build());
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).build()) {
                RuntimeException failure = assertThrows(RuntimeException.class,
                        () -> context.eval(guest));
                assertTrue(String.valueOf(failure.getMessage())
                                .contains("duplicate logical key"),
                        constructor + ": " + failure.getMessage());
            }
        }
    }

    @Test
    void mapOfRejectsWrongKeyValueAndOversizedConstruction() {
        for (String constructor : new String[]{
                "Map.of<int,int>(1 => 1)",
                "Map.of<string,int>(\"x\" => true)",
                "Map.of<string,int>(1 => 1)"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(
                            program("const m = " + constructor + ";")),
                    constructor);
        }
        StringBuilder oversized = new StringBuilder(
                "pub fnc main(): void { const m = Map.of<string,int>(");
        for (int i = 0; i <= 4096; i++) {
            if (i != 0) oversized.append(", ");
            oversized.append("\"k").append(i).append("\" => ").append(i);
        }
        oversized.append("); return; }");
        IllegalArgumentException problem = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse(oversized.toString()));
        assertTrue(problem.getMessage().contains("4096 entries"),
                problem.getMessage());
    }

    @Test
    void ordinaryLiteralImmediatePostfixMatchesNamedFactoryGuest() {
        String source = program("""
                stdio.println(arr[11, 23][1]);
                stdio.println(Array.of(11, 23)[1]);
                stdio.println(tuple(31, true)[0]);
                stdio.println(Tuple.of(31, true)[0]);
                """);
        assertInstanceOf(Ast.IndexExpr.class, firstFactoryInitializer(
                program("const n = arr[11, 23][1];")));
        assertInstanceOf(Ast.IndexExpr.class, firstFactoryInitializer(
                program("const n = tuple(31, true)[0];")));
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("23\n23\n31\n31\n", guestStdout(source));
    }

    @Test
    void removedTildeConstructorHeadsFailButUnaryAndStructuralAreIndependent() {
        assertInstanceOf(Ast.UnaryExpr.class, firstFactoryInitializer(
                program("const flags = 7; const x = ~flags;")));
        for (String obsolete : new String[]{
                "~array(1, 2)", "~ array(1, 2)", "~tuple(1, true)",
                "~ tuple(1, true)", "~map<string,int>(\"key\" => 1)",
                "~<Map<string,int>>()", "~record(ready: true)"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(program("const x = " + obsolete + ";")), obsolete);
        }
    }

    @Test
    void spreadsCannotPiggybackOnTupleAndArrayFactories() {
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(program("const t = Tuple.of(...source);")));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(program("const a = Array.of(...source);")));
    }

}
