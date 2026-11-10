package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Type-position aliases only. Construction through ~kind(...) and Kind.of(...)
 * remains separately gated; these tests must not certify unimplemented factories.
 */
final class TildeAggregateTypeAliasCompilerTest {
    @Test
    void typedTupleAliasesShareTheExistingFiniteShapeAndMetadata() {
        assertEquals(typeOf("Tuple[int, string]"), typeOf("~tuple<int, string>"));
        // Preserve an unprefixed nominal Tuple<A,B> AST: only the type
        // checker may select builtin tuple semantics after class lookup.
        assertEquals(2, typeOf("Tuple<int, string>").arguments().size());
        assertEquals(typeOf("Tuple<align=64, size=2>[int, string]"),
                typeOf("~tuple<align=64, size=2>[int, string]"));
        assertEquals(4, typeOf("Tuple<int, string, align=64, size=2>")
                .arguments().size());

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc main(): void {
                  val a: ~tuple<int, string> = (7, "seven");
                  val b: Tuple<int, string> = (8, "eight");
                  val c: Tuple[int, string] = (9, "nine");
                  val d: ~tuple<align=64, size=2>[int, string] = (10, "ten");
                  val e: Tuple<int, string, align=64, size=2> = (11, "eleven");
                  return;
                }
                """)));
    }

    @Test
    void arrayVectorAndFixedArrayAliasesPreserveOldShapes() {
        assertEquals(typeOf("Array<int>"), typeOf("~array<int>"));
        assertEquals(typeOf("Vector[int]"), typeOf("~vector[int]"));
        assertEquals(typeOf("FixedArray[4 of int]"), typeOf("~fixed_array[4 of int]"));
        assertEquals(typeOf("Vector[...(int, string, bool)]"),
                typeOf("~vector[...(int, string, bool)]"));
        assertEquals(typeOf("Vector<allocator=Arena, align=64>[int]"),
                typeOf("~vector<allocator=Arena, align=64>[int]"));
        assertEquals(typeOf("Vector[int][int]"), typeOf("~vector[int][int]"));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc main(): void {
                  val array: ~array<int> = arr[1, 2, 3];
                  val vector: ~vector[int] = [1, 2, 3];
                  val grouped: ~vector[...(int, string, bool)] =
                      [5, "foo", true, 6, "bar", false];
                  val fixed: ~fixed_array[4 of int] = [1, 2, 3, 4];
                  return;
                }
                """)));
    }

    @Test
    void parserOnlyNominalAliasesDoNotCertifyFactoryOrRuntimeSemantics() {
        // Surface syntax alone: implementation of factories and runtime
        // representations is independently gated by each builtin's contract.
        assertEquals(typeOf("Map<string, bool>"), typeOf("~map<string, bool>"));
        assertEquals(typeOf("Entry<string, bool>"), typeOf("~entry<string, bool>"));
        assertEquals(typeOf("Pair<int, string>"), typeOf("~pair<int, string>"));
        assertEquals(typeOf("Set<int>"), typeOf("~set<int>"));
        assertEquals(typeOf("Record<{name: string, age: int}>"),
                typeOf("~record<{name: string, age: int}>"));
        assertEquals(typeOf("List[int]"), typeOf("~list[int]"));
        assertEquals(typeOf("Slice[int]"), typeOf("~slice[int]"));
    }

    @Test
    void invalidCollectionPoliciesAndShapesStayRejected() {
        IllegalArgumentException finite = assertThrows(IllegalArgumentException.class,
                () -> checkLocal("~vector[int, string, bool]", "[5, \"foo\", true]"));
        assertTrue(finite.getMessage().contains("finite sequence pattern"));

        IllegalArgumentException incomplete = assertThrows(IllegalArgumentException.class,
                () -> checkLocal("~vector[...(int, string, bool)]", "[5, \"foo\"]"));
        assertTrue(incomplete.getMessage().contains("repeating sequence group"));

        IllegalArgumentException runtimeSize = assertThrows(IllegalArgumentException.class,
                () -> checkLocal("~vector<size=4>[int]", "[1, 2, 3, 4]"));
        assertTrue(runtimeSize.getMessage().contains("runtime instance state"));

        IllegalArgumentException mismatchedTuple = assertThrows(IllegalArgumentException.class,
                () -> checkLocal("~tuple<size=3>[int, string]", "(7, \"seven\")"));
        assertTrue(mismatchedTuple.getMessage().contains("fixed sequence arity"));

        IllegalArgumentException mixedShape = assertThrows(IllegalArgumentException.class,
                () -> checkLocal("Tuple<int, string>[int, string]", "(7, \"seven\")"));
        assertTrue(mixedShape.getMessage().contains("cannot mix positional"));
    }

    @Test
    void nominalTupleClassIsNeverRewrittenIntoBuiltinTuple() {
        String declarations = """
                define class Tuple<A, B> as
                  pub val A first;
                  pub val B second;
                end
                fnc accept_nominal(Tuple<int, string> value): void { return; }
                fnc accept_positional(~tuple<int, string> value): void { return; }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(declarations)));

        IllegalArgumentException mismatch = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(
                        declarations + """
                        pub fnc main(): void {
                          val positional: ~tuple<int, string> = (42, "answer");
                          accept_nominal(positional);
                          return;
                        }
                        """)));
        assertTrue(mismatch.getMessage().contains("argument"), mismatch::getMessage);
    }

    @Test
    void explicitCollectionShapesCannotBeHijackedByNominalAliases() {
        // A named generic alias remains available in unshaped type position,
        // but an explicitly shaped intrinsic must retain its original kind.
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                type Tuple<T> = T;
                type Vector<T> = T;
                fnc takes_tuple(~tuple<int, string> value): void { return; }
                fnc takes_vector(~vector[...(int, string, bool)] value): void { return; }
                fnc takes_alias(Tuple<int> value): void { return; }
                fnc takes_vector_alias(Vector<int> value): void { return; }
                pub fnc main(): void {
                  val t: ~tuple<int, string> = (7, "seven");
                  val v: ~vector[...(int, string, bool)] = [7, "seven", true];
                  takes_tuple(t);
                  takes_vector(v);
                  takes_alias(11);
                  takes_vector_alias(12);
                  return;
                }
                """)));
    }

    @Test
    void tildeAliasesAlsoResolveInParametersReturnsAndNestedTypes() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc make_values(): ~array<int> {
                  return arr[1, 2, 3];
                }
                fnc passthrough(~array<int> xs): Array[int] {
                  return xs;
                }
                fnc accept_nested(~array<~array<int>> xs): void {
                  return;
                }
                pub fnc main(): void {
                  val values: Array[int] = passthrough(make_values());
                  return;
                }
                """)));
    }

    @Test
    void existingFixedArrayAndTupleKindRemainIncompatible() {
        IllegalArgumentException mismatch = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                        fnc needs_tuple(~tuple<int, string> item): void { return; }
                        pub fnc main(): void {
                          val fixed: ~fixed_array[int, string] = [42, "answer"];
                          needs_tuple(fixed);
                          return;
                        }
                        """)));
        assertTrue(mismatch.getMessage().contains("argument"), mismatch::getMessage);
    }

    @Test
    void tildeTypeIsAllowlistedAndValueUnaryTildeIsUnchanged() {
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> typeOf("~tupel<int, string>"));
        assertTrue(unknown.getMessage().contains("unknown built-in tilde type"));

        assertDoesNotThrow(() -> Parser.parse("""
                pub fnc main(): void {
                  val value: int = ~1;
                  return;
                }
                """));
    }

    private static void checkLocal(String type, String initializer) {
        TypeChecker.check(Parser.parse("""
                pub fnc main(): void {
                  val value: %s = %s;
                  return;
                }
                """.formatted(type, initializer)));
    }

    // Parser-only extraction: the initializer is deliberately a valid ordinary
    // literal; these assertions do not claim type checking or execution.
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
