package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A declaration's canonical Tuple[T1, T2] return type must agree with
 * independent positional tuple literals. Canonical Tuple.of() guest lowering is
 * separately stacked in Java #2735; these tests must not certify it.
 * Authored tests are NOT execution evidence until a real runner executes them.
 */
final class TupleReturnTypeContractTest {
    @Test
    void canonicalBracketReturnRunsAsCompiledGuest() throws Exception {
        String program = readFixture("atto/tuple-returns/canonical-return.atto");
        var typed = TypeChecker.check(Parser.parse(program));
        assertDoesNotThrow(() -> OwnershipChecker.check(typed));
        assertEquals("alpha|beta", run(program));
    }

    @Test
    void nestedTuplesResolveInReturnsParametersAndCalls() {
        String program = """
                fnc make(): Tuple[int, Tuple[string, string]] {
                  return (7, ("left", "right"));
                }
                fnc pass(Tuple[int, Tuple[string, string]] value):
                    Tuple[int, Tuple[string, string]] {
                  return value;
                }
                pub fnc main(): void {
                  val result: Tuple[int, Tuple[string, string]] = pass(make());
                  return;
                }
                """;
        var typed = TypeChecker.check(Parser.parse(program));
        assertDoesNotThrow(() -> OwnershipChecker.check(typed));
    }

    @Test
    void legacyAngleTupleMayReturnToCanonicalBracketTuple() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc old(): Tuple<string, string> {
                  return ("left", "right");
                }
                fnc canonical(): Tuple[string, string] {
                  return old();
                }
                pub fnc main(): void {
                  val result: Tuple[string, string] = canonical();
                  return;
                }
                """)));
    }

    @Test
    void bracketedTupleReturnCannotBeShadowedByUserAlias() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                type Tuple<T> = T;
                fnc make(): Tuple[string, string] {
                  return ("left", "right");
                }
                pub fnc main(): void {
                  val result: Tuple[string, string] = make();
                  return;
                }
                """)));
    }

    @Test
    void rejectsWrongElementTypesOrArityOnReturn() {
        for (String wrong : new String[]{
                "(1, \"right\")",
                "(\"left\", 2)",
                "tuple(\"only-one\")",
                "(\"one\", \"two\", \"three\")",
                "(\"left\", true)"
        }) {
            String program = "fnc make(): Tuple[string, string] { return " + wrong + "; }";
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(program)), wrong);
            assertTrue(error.getMessage().contains("return value"), error::getMessage);
        }
    }

    @Test
    void rejectsTypedArrayVariableReturningAsTuple() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc make(): Tuple[string, string] {
                          val array: Array[string] = arr["left", "right"];
                          return array;
                        }
                        """)));
        assertTrue(error.getMessage().contains("return value"), error::getMessage);
    }

    @Test
    void emptyAndSingletonCanonicalTuplesPreserveExactArity() throws Exception {
        String program = """
                fnc empty(): Tuple[] {
                  return tuple();
                }
                fnc singleton(): Tuple[int] {
                  return tuple(9);
                }
                pub routine main(): void {
                  val zero: Tuple[] = empty();
                  val one: Tuple[int] = singleton();
                  stdio.stdout.write(one[0]);
                  return;
                }
                """;
        var typed = TypeChecker.check(Parser.parse(program));
        assertDoesNotThrow(() -> OwnershipChecker.check(typed));
        assertEquals("9", run(program));
    }

    @Test
    void tildeTupleValueSyntaxIsRejectedInAllTypePositions() {
        for (String program : new String[]{
                "fnc wrong(): ~tuple(string, string) { return ~tuple(1, 2); }",
                "fnc wrong(~tuple(string, string) value): void { return; }",
                "pub fnc main(): void { val value: ~tuple(string, string) = ~tuple(1, 2); return; }",
                "fnc wrong(): ~tuple<string, string>(1, 2) { return ~tuple(1, 2); }"
        }) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class, () -> Parser.parse(program), program);
            assertTrue(error.getMessage().contains("structural") || error.getMessage().contains("removed"), error::getMessage);
        }
    }

    @Test
    void legacyArrConstructorCannotMasqueradeAsTuple() {
        for (String source : new String[]{
                "fnc bad(): Tuple[int, int] { return arr[40, 2]; }",
                "pub fnc main(): void { val tuple: Tuple[int, int] = arr[40, 2]; return; }"
        }) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(source)), source);
            assertTrue(error.getMessage().contains("type"), error::getMessage);
        }
    }

    @Test
    void bareBracketsRetainLegacyContextualTupleTyping() {
        // Compatibility-only parser/typecheck guarantee, NOT compiled guest evidence.
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc legacy(): Tuple[int, int] {
                  return [40, 2];
                }
                pub fnc main(): void {
                  val t: Tuple[int, int] = legacy();
                  return;
                }
                """)));
    }

    @Test
    void explicitArrayCannotSatisfyUnionOfTuples() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc wrong(): Tuple[string, string] | Tuple[int, int] {
                          return arr["left", "right"];
                        }
                        """)));
        assertTrue(error.getMessage().contains("return value"), error::getMessage);
    }

    @Test
    void inferredTupleGenericMarkerCannotBeSmuggledIntoEmptyShape() {
        for (String annotation : new String[]{"Tuple<>[]"}) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class, () -> Parser.parse(
                            "fnc invalid(): " + annotation + " { return tuple(); }"), annotation);
            assertTrue(error.getMessage().contains("sequence type shape cannot be empty"),
                    error::getMessage);
        }
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "fnc invalid(): ~tuple<>[] { return tuple(); }"));
    }

    @Test
    void otherCollectionsStillRejectEmptySequenceShapes() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        fnc wrong(): Array[] {
                          return ~array();
                        }
                        """));
        assertTrue(error.getMessage().contains("sequence type shape cannot be empty"),
                error::getMessage);
    }

    @Test
    void explicitlyConstructedArrayCannotMasqueradeAsTupleReturn() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc make(): Tuple[string, string] {
                          return arr["left", "right"];
                        }
                        """)));
        assertTrue(error.getMessage().contains("return value"), error::getMessage);
    }

    @Test
    void explicitlyConstructedArrayCannotMasqueradeAsTupleBinding() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc main(): void {
                          val tuple: Tuple[string, string] = arr["left", "right"];
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().contains("initializer"), error::getMessage);
    }

    @Test
    void nonVoidTupleFunctionRequiresActualReturnOnEveryPath() {
        IllegalArgumentException bare = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc make(): Tuple[string, string] {
                          return;
                        }
                        """)));
        assertTrue(bare.getMessage().contains("return value"), bare::getMessage);

        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc make(): Tuple[string, string] {
                        }
                        """)));
        assertTrue(missing.getMessage().contains("must explicitly return"), missing::getMessage);
    }

    @Test
    void explicitTypedTildeConstructorStillFailsClosed() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        pub fnc make(): Tuple[string, string] {
                          return ~tuple<string, string>("left", "right");
                        }
                        """));
        assertTrue(error.getMessage().contains("removed"), error::getMessage);
    }

    private static String readFixture(String name) throws Exception {
        try (InputStream input = TupleReturnTypeContractTest.class
                .getClassLoader().getResourceAsStream(name)) {
            assertNotNull(input, "missing AttoLang guest fixture " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "tuple-return-contract.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
