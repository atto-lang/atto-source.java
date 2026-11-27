package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Compiler-backed contract: type-only queries never evaluate a guest expression. */
final class TypeofBindingQueryTest {
    private static Ast.TypeRef returnType(String spelling) {
        Ast.Program parsed = Parser.parse("pub fnc id(int x): " + spelling + " { return x; }");
        Ast.FunctionDecl fn = (Ast.FunctionDecl)
                parsed.modules().getFirst().declarations().getFirst();
        return fn.returnType();
    }

    @Test
    void bareAndParenthesizedFormsProduceIdenticalStaticAst() {
        Ast.TypeRef bare = returnType("typeof x");
        assertEquals(bare, returnType("typeof(x)"));
        assertTrue(bare.isTypeQuery());
        assertEquals("x", bare.queriedBinding());
        assertNotEquals("Fnc", bare.name());
    }

    @Test
    void parameterQueriesTypecheckAndPreserveReturnTypesForCalls() {
        for (String form : new String[]{"typeof x", "typeof(x)"}) {
            String source = "pub fnc id(int x): " + form + " { return x; } "
                    + "pub fnc main(): int { return id(5); }";
            assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)), form);
        }
    }

    @Test
    void directTypedForwardValQueryHasNoRuntimeReflection() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc answer(): typeof tbr {
                    val int tbr = 5;
                    return tbr;
                }
                pub fnc main(): int { return answer(); }
                """)));
    }

    @Test
    void localLiteralInferenceIsStaticAndNonExecutable() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc answer(): typeof(tbr) {
                    val tbr = 5;
                    return tbr;
                }
                pub fnc main(): int { return answer(); }
                """)));
    }

    @Test
    void existingCallableSignatureAndNamedFunctionQueryStayDistinct() {
        assertDoesNotThrow(() -> Parser.parse("""
                type Callback = typeof fnc(int) => int;
                fnc square(int x): int { return x * x; }
                type SquareType = typeof square;
                """));
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc square(int x): int { return x * x; }
                type SquareType = typeof square;
                pub fnc main(): int { return square(3); }
                """)));
    }

    @Test
    void typeofBangAndArbitraryOperandExpressionsAreNotQueries() {
        for (String form : new String[]{
                "typeof! x", "typeof !x", "typeof()", "typeof(x, x)",
                "typeof(x + 1)", "typeof(make())"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse("pub fnc id(int x): " + form + " { return x; }"),
                    form);
        }
    }

    @Test
    void missingBindingsAndOverloadedCallableTypesReject() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub fnc answer(): typeof missing { return 5; }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc choose(): int { return 1; }
                fnc choose(int n): int { return n; }
                type Ambiguous = typeof choose;
                """)));
    }

    @Test
    void recursiveReturnQueryRejectsBeforeCodeGeneration() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc selfref(): typeof selfref { return selfref; }
                        """)));
        assertTrue(error.getMessage().contains("E_RETURN_INFER_CYCLE"), error.getMessage());
    }

    @Test
    void nestedShadowedForwardLocalsRejectConservatively() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub fnc answer(): typeof tbr {
                    val int tbr = 5;
                    if true then val string tbr = "shadow"; fi
                    return tbr;
                }
                """)));
    }
    @Test
    void crossModulePrivateCallableQueryMustNotBypassVisibility() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module alpha as
                    fnc secret(int x): int { return x; }
                end
                define module beta as
                    type Hidden = typeof secret;
                end
                """)));
    }


    @Test
    void staticRecordParameterFieldQueries() {
        for (String form : new String[]{"typeof input.c", "typeof(input.c)"}) {
            String src = "fnc value({a: bool, b: string, c: int} input): " + form
                    + " { return input.c; }";
            Ast.Program parsed = Parser.parse(src);
            Ast.FunctionDecl fn = (Ast.FunctionDecl) parsed.modules().getFirst().declarations().getFirst();
            assertEquals("input.c", fn.returnType().queriedBinding());
            assertDoesNotThrow(() -> TypeChecker.check(parsed), form);
        }
    }

    @Test
    void staticProjectionRejectsUnknownFieldNonRecordAndNonParameter() {
        for (String[] bad : new String[][]{
                {"fnc value({c: int} input): typeof input.missing { return 42; }", "E_NO_FIELD"},
                {"fnc value(int input): typeof input.c { return input; }", "E_TYPE_QUERY_UNSUPPORTED"},
                {"fnc value({c: int} input): typeof other.c { return input.c; }", "E_TYPE_QUERY_SCOPE"}
        }) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(bad[0])), bad[0]);
            assertTrue(ex.getMessage().contains(bad[1]), ex.getMessage());
        }
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("fnc value({c: int} input): typeof(input.c + 1) { return input.c; }"));
    }
}
