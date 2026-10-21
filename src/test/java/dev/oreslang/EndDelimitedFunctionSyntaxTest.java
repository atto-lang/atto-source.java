package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

final class EndDelimitedFunctionSyntaxTest {
    @Test
    void freeFunctionUsesArrowIfFiAndEndWithoutSemicolons() {
        String source = """
                fnc classify(int x) -> int
                    if x > 0 then
                        return 1
                    else
                        return 0
                    fi
                end
                """;

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void optionalAsSeparatorNormalizesToSameAst() {
        var withoutAs = Parser.parse("""
                fnc identity(int x) -> int
                    return x
                end
                """);
        var withAs = Parser.parse("""
                fnc identity(int x) -> int as
                    return x
                end
                """);

        assertEquals(withoutAs, withAs);
        assertDoesNotThrow(() -> TypeChecker.check(withAs));
    }

    @Test
    void newlineDoSelectRemainsARealFirstBodyStatement() {
        var endDelimited = Parser.parse("""
                fnc consume(Channel<int> incoming) -> void
                    do select {
                        case readch incoming: val number {
                            return;
                        }
                    }
                end
                """);
        var braced = Parser.parse("""
                fnc consume(Channel<int> incoming) -> void {
                    do select {
                        case readch incoming: val number {
                            return;
                        }
                    }
                }
                """);

        assertEquals(braced, endDelimited);
    }

    @Test
    void asSeparatorCanPrecedeARealDoSelectBodyStatement() {
        var compact = Parser.parse("""
                fnc consume(Channel<int> incoming) -> void as do select {
                    case readch incoming: val number {
                        return;
                    }
                } end
                """);
        var braced = Parser.parse("""
                fnc consume(Channel<int> incoming) -> void {
                    do select {
                        case readch incoming: val number {
                            return;
                        }
                    }
                }
                """);

        assertEquals(braced, compact);
    }

    @Test
    void standaloneNextLineDoIsParsedAsBodySyntaxAndRejectedWhenIncomplete() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc identity(int x) -> int
                    do
                    return x
                end
                """));
    }

    @Test
    void callableHeaderDoIsRejectedRatherThanAliasedToAs() {
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("fnc identity(int x) -> int do return x; end"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("routine identity(int x) -> int do return x; end"));
    }

    @Test
    void asProvidesCompactSameLineBodyBoundary() {
        var compact = Parser.parse("fnc identity(int x) -> int as return x; end");
        var multiline = Parser.parse("""
                fnc identity(int x) -> int
                    return x
                end
                """);

        assertEquals(multiline, compact);
        assertDoesNotThrow(() -> TypeChecker.check(compact));
    }

    @Test
    void asFollowsTheCompleteHigherOrderReturnType() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc callback() -> () => void as
                    return || -> { return; };
                end
                """)));
    }

    @Test
    void higherOrderReturnChecksReturnedLambdaSignature() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc callback() -> () => void as
                    return |int value| -> { return; };
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc callback() -> () => void as
                    return || -> { return 1; };
                end
                """)));
    }

    @Test
    void nestedFunctionReturnTypesAssociateRightWithOptionalAs() {
        var inferredGrouping = Parser.parse("""
                fnc higher_order() -> (int) => (string) => bool as
                    return missing_callback
                end
                """);
        var explicitGrouping = Parser.parse("""
                fnc higher_order() -> (int) => ((string) => bool) as
                    return missing_callback
                end
                """);
        assertEquals(explicitGrouping, inferredGrouping);
    }

    @Test
    void misplacedAsOrMissingReturnTypeCannotStartAFunctionBody() {
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("fnc malformed() -> as return 1; end"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("fnc malformed() as return 1; end"));
    }

    @Test
    void optionalAsDoesNotPermitImplicitNonVoidReturnOrMissingFi() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("fnc f() -> int as 42; end")));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("fnc f(int x) -> int as if x > 0 then return 1; end"));
    }

    @Test
    void compactAsSupportsNestedKeywordControlFlowAndMatchesMultilineAst() {
        var compact = Parser.parse(
                "fnc classify(int x) -> int as if x > 0 then return 1; else return 0; fi end");
        var multiline = Parser.parse("""
                fnc classify(int x) -> int
                    if x > 0 then
                        return 1
                    else
                        return 0
                    fi
                end
                """);

        assertEquals(multiline, compact);
        assertDoesNotThrow(() -> TypeChecker.check(compact));
    }

    @Test
    void semicolonAfterReturnTypeIsNeverAFreeFunctionBodyBoundary() {
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("fnc declared_only(int x) -> int;"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("fnc malformed(int x) -> int; return x; end"));
    }

    @Test
    void contractSignaturesMayReturnFunctionTypesButAsCannotStartAContractBody() {
        assertDoesNotThrow(() -> Parser.parse("""
                define contract CallbackFactory as
                    fnc make() -> () => void;
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define contract CallbackFactory as
                    fnc make() -> () => void as
                        return || -> { return; };
                    end
                end
                """));
    }

    @Test
    void commentsCannotMasqueradeAsOptionalAsOrCallableEnd() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(
                "fnc value() -> int /* as do end */ as /* end */ return 7; end // as do end\n")));

        var compact = Parser.parse(
                "fnc text() -> string as return \"do end fi\"; end // as do end\n");
        var multiline = Parser.parse("""
                fnc text() -> string
                    return "do end fi"
                end
                """);
        assertEquals(multiline, compact);
    }

    @Test
    void optionalAsMayCrossCommentsOnTheHeaderLineButNeverAPhysicalNewline() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(
                "fnc value() -> int /* boundary */ as return 7; end")));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc value() -> int /* boundary */
                as
                    return 7
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "fnc value() -> int; /* not a body boundary */ as return 7; end"));
    }

    @Test
    void compactAsSupportsExpressionAndBlockLambdasWithoutStealingTheirDelimiters() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(
                "fnc make() -> () => int as return || -> 7; end")));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(
                "fnc make() -> () => string as return || -> { return \"do end\"; }; end")));
    }

    @Test
    void higherOrderEndDelimitedReturnsRespectNamedCallableReifiability() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc increment(int value): int {
                    return value + 1;
                }

                fnc callback() -> (int) => int as
                    return increment;
                end
                """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                type IntFn = typeof fnc(int value) => int;

                fnc increment(int value): int {
                    return value + 1;
                }

                fnc callback() -> IntFn as
                    return increment;
                end
                """)));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        routine direct_only(int value): int {
                            return value + 1;
                        }

                        fnc callback() -> (int) => int as
                            return direct_only;
                        end
                        """)));
        assertTrue(error.getMessage().contains("direct-call-only"), error::getMessage);
    }

    @Test
    void routineUsesTheSameEndDelimitedAndCompactAsRules() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                routine identity(int x) -> int
                    return x
                end
                """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(
                "routine identity(int x) -> int as return x; end")));
    }

    @Test
    void explicitVoidMayFallThrough() {
        String source = """
                fnc noop() -> void
                    const int x = 1
                end
                """;

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void nonVoidStillRequiresAllPathExplicitReturn() {
        String source = """
                fnc classify(int x) -> int
                    if x > 0 then
                        return 1
                    fi
                end
                """;

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains("must explicitly return on every path"), error.getMessage());
    }

    @Test
    void endBodyRequiresExplicitArrowAndFollowingLine() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc implicit()
                    return
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc colon(): int
                    return 1
                end
                """));

        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("fnc same_line() -> int return 1 end"));

        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("fnc casty() -> int return 1 as int; end"));


        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("fnc colon(): int as return 1; end"));
    }

    @Test
    void braceBodiesRemainSourceCompatibleAndAstEquivalent() {
        var endDelimited = Parser.parse("""
                fnc identity(int x) -> int
                    return x
                end
                """);
        var braced = Parser.parse("""
                fnc identity(int x) -> int {
                    return x;
                }
                """);

        assertEquals(braced, endDelimited);
        assertDoesNotThrow(() -> TypeChecker.check(endDelimited));
    }

    @Test
    void actorAndClassMemberImplementationsRemainBraced() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                actor fnc work(int x) -> int
                    return x
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define class Example as
                    run(int x) -> int
                        return x
                    end
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define class Example as
                    static fnc run(int x) -> int
                        return x
                    end
                end
                """));

        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("actor fnc work(int x) -> int as return x; end"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define class Example as
                    static fnc run(int x) -> int as return x; end
                end
                """));
    }

    @Test
    void nestedMatchEndIsOwnedByMatchBeforeCallableEnd() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc inspect(int x) -> int
                    match x
                        1 -> { }
                        else -> { }
                    end
                    return x
                end
                """)));
    }

    @Test
    void missingFiCannotBeConsumedAsCallableEnd() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc broken(int x) -> int
                    if x > 0 then
                        return 1
                end
                """));
    }

    @Test
    void tupleReturnUsesOrdinaryTypeChecking() {
        String good = """
                fnc classify(int x) -> tuple (int, int, string)
                    if x > 0 then
                        return (1, x, "positive")
                    else
                        return (0, x, "non-positive")
                    fi
                end
                """;
        String bad = """
                fnc classify(int x) -> tuple (int, int, string)
                    return 1
                end
                """;

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(good)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(bad)));
    }

    @Test
    void explicitTupleTypeNormalizesToExistingFiniteTupleType() {
        var explicit = Parser.parse("""
                fnc identity(tuple (int, string) value) -> tuple (int, string) {
                    return value;
                }
                """);
        var existing = Parser.parse("""
                fnc identity([int, string] value) -> [int, string] {
                    return value;
                }
                """);

        assertEquals(existing, explicit);
    }

    @Test
    void tupleArityLimitIsSharedAcrossBothSpellings() {
        String sixtyFour = String.join(", ", Collections.nCopies(64, "int"));
        String sixtyFive = String.join(", ", Collections.nCopies(65, "int"));

        var explicit = Parser.parse(
                "fnc identity(tuple (" + sixtyFour + ") value) -> tuple (" + sixtyFour
                        + ") { return value; }");
        var existing = Parser.parse(
                "fnc identity([" + sixtyFour + "] value) -> [" + sixtyFour
                        + "] { return value; }");

        assertEquals(existing, explicit);
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "fnc identity(tuple (" + sixtyFive + ") value) -> int { return 1; }"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "fnc identity([" + sixtyFive + "] value) -> int { return 1; }"));
        assertThrows(IllegalArgumentException.class, () -> Ast.TypeRef.tupleType(
                Collections.nCopies(65, Ast.TypeRef.simple("int"))));
    }

    @Test
    void moduleContractsUseArrowButInstanceInterfacesKeepFatArrow() {
        assertDoesNotThrow(() -> Parser.parse("""
                define contract ClassifierApi as
                    fnc classify(int x) -> tuple (int, int, string);
                    coordinates: tuple (int, int, string);
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                interface InstanceApi
                    fnc classify(int x) -> int;
                end
                """));

        assertDoesNotThrow(() -> Parser.parse("""
                interface InstanceApi
                    fnc classify(int x) => int;
                end
                """));
    }

    @Test
    void nestedRecursiveReturnTypeUsesOrdinaryTypeGrammar() {
        assertDoesNotThrow(() -> Parser.parse("""
                fnc nested() -> Result<Option<tuple (int, string)>, string>
                    return Ok(Some((1, "one")))
                end
                """));
    }

    @Test
    void sharedFormatterRoundTripCorpusParsesAndTypeChecks() throws Exception {
        try (var input = EndDelimitedFunctionSyntaxTest.class
                .getResourceAsStream("/function-syntax/end-delimited-roundtrip.ores")) {
            assertNotNull(input, "missing shared function-syntax corpus");
            String source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        }
    }

    @Test
    void tupleRemainsContextualIdentifierOutsideTypeShape() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc tuple(int value): int {
                    return value;
                }

                fnc use_tuple_name(): int {
                    return tuple(7);
                }
                """)));
    }
}
