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
 * ~tuple(v1, v2) values. Authored tests are NOT execution evidence until
 * the exact source head runs JUnit and compiled Truffle guest code.
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
                  return ~tuple(7, ~tuple("left", "right"));
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
                  return ~tuple("left", "right");
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
                  return ~tuple("left", "right");
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
                "~tuple(1, \"right\")",
                "~tuple(\"left\", 2)",
                "~tuple(\"only-one\")",
                "~tuple(\"one\", \"two\", \"three\")",
                "~tuple(\"left\", true)"
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
                          val array: Array[string] = ~array("left", "right");
                          return array;
                        }
                        """)));
        assertTrue(error.getMessage().contains("return value"), error::getMessage);
    }

    @Test
    void explicitlyConstructedArrayCannotMasqueradeAsTupleReturn() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc make(): Tuple[string, string] {
                          return ~array("left", "right");
                        }
                        """)));
        assertTrue(error.getMessage().contains("return value"), error::getMessage);
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
        assertTrue(error.getMessage().contains("requires checked"), error::getMessage);
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
