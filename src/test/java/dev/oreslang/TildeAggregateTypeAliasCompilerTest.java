package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Migration gate: the old ~kind type aliases are removed alongside ~kind(...)
 * values. A tilde now marks a structural view, never a collection family.
 *
 * This class is parser conformance only, NOT guest/AOT factory certification.
 */
final class TildeAggregateTypeAliasCompilerTest {
    private static final List<String> REMOVED_KINDS = List.of(
            "tuple", "array", "vector", "map", "entry", "pair",
            "set", "record", "list", "slice", "fixed_array");

    @Test
    void everyRetiredCollectionTypeAliasFailsClosed() {
        for (String kind : REMOVED_KINDS) {
            for (String syntax : List.of(
                    "~" + kind + "<int>",
                    "~ " + kind + "<int>",
                    "~" + kind + "[int]")) {
                assertThrows(IllegalArgumentException.class,
                        () -> typeOf(syntax), syntax);
            }
        }
    }

    @Test
    void parameterReturnAndNestedAliasPositionsCannotBypassRemoval() {
        for (String declaration : List.of(
                "fnc f(~tuple<int, string> p): void { return; }",
                "fnc f(): ~array<int> { return arr[1]; }",
                "fnc f(Array<~array<int>> xs): void { return; }",
                "fnc f(~vector[...(int, string, bool)] xs): void { return; }",
                "fnc f(): void { val x: ~map<string, int> = 1; return; }",
                "fnc f(): void { val x: ~fixed_array[4 of int] = [1,2,3,4]; return; }")) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(declaration), declaration);
        }
    }

    @Test
    void canonicalNominalTypeNamesAndGenericArgumentsRemainAvailable() {
        assertEquals("Tuple", typeOf("Tuple<int, string>").name());
        assertEquals(2, typeOf("Tuple<int, string>").arguments().size());
        assertEquals("Array", typeOf("Array<int>").name());
        assertEquals("Vector", typeOf("Vector[int]").name());
        assertEquals("Map", typeOf("Map<string, bool>").name());
        assertEquals("Entry", typeOf("Entry<string, bool>").name());
        assertEquals("Pair", typeOf("Pair<int, string>").name());
        assertEquals("Set", typeOf("Set<int>").name());
        assertEquals("List", typeOf("List[int]").name());
        assertEquals("Slice", typeOf("Slice[int]").name());

        // A nominal Tuple lookup must not be rewritten to the removed tilde
        // type alias solely because its identifier matches a builtin name.
        assertEquals("Tuple", typeOf("Tuple<int, string>").name());
    }

    @Test
    void removedAliasCannotMasqueradeAsOrdinaryUnaryTilde() {
        assertThrows(IllegalArgumentException.class,
                () -> typeOf("~tupel<int, string>"));
        assertDoesNotThrow(() -> Parser.parse("""
                pub fnc main(): void {
                    val value: int = ~1;
                    val grouped = ~(value);
                    return;
                }
                """));
    }

    @Test
    void structuralTildeRemainsDistinctFromAggregateTypeAliases() {
        assertDoesNotThrow(() -> Parser.parse("""
                define class Point as
                    pub val int x;
                    pub val int y;
                end
                fnc inspect(~Point point): void { return; }
                """));
        assertDoesNotThrow(() -> Parser.parse("""
                pub fnc main(): void {
                    val value = Tuple.of(1, "one");
                    val array = Array.of(1, 2);
                    return;
                }
                """));
    }

    private static Ast.TypeRef typeOf(String type) {
        Ast.Program program = Parser.parse("""
                pub fnc main(): void {
                  val value: %s = 0;
                  return;
                }
                """.formatted(type));
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl decl : module.declarations()) {
                if (!(decl instanceof Ast.FunctionDecl fn)) continue;
                for (Ast.Stmt stmt : fn.body()) {
                    if (stmt instanceof Ast.BindingStmt binding
                            && binding.name().equals("value")) {
                        return binding.declaredType();
                    }
                }
            }
        }
        throw new AssertionError("failed to find declared aggregate type");
    }
}
