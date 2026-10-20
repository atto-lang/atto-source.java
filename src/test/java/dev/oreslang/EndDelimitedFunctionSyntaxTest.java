package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

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
    void optionalDoSeparatorNormalizesToSameAst() {
        var withoutDo = Parser.parse("""
                fnc identity(int x) -> int
                    return x
                end
                """);
        var withDo = Parser.parse("""
                fnc identity(int x) -> int do
                    return x
                end
                """);

        assertEquals(withoutDo, withDo);
        assertDoesNotThrow(() -> TypeChecker.check(withDo));
    }

    @Test
    void doProvidesCompactSameLineBodyBoundary() {
        var compact = Parser.parse("fnc identity(int x) -> int do return x; end");
        var multiline = Parser.parse("""
                fnc identity(int x) -> int
                    return x
                end
                """);

        assertEquals(multiline, compact);
        assertDoesNotThrow(() -> TypeChecker.check(compact));
    }

    @Test
    void doFollowsTheCompleteHigherOrderReturnType() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc callback() -> () => void do
                    return || -> { return; };
                end
                """)));
    }

    @Test
    void higherOrderReturnChecksReturnedLambdaSignature() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc callback() -> () => void do
                    return |int value| -> { return; };
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc callback() -> () => void do
                    return || -> { return 1; };
                end
                """)));
    }

    @Test
    void nestedFunctionReturnTypesAssociateRightWithOptionalDo() {
        var inferredGrouping = Parser.parse("""
                fnc higher_order() -> (int) => (string) => bool do
                    return missing_callback
                end
                """);
        var explicitGrouping = Parser.parse("""
                fnc higher_order() -> (int) => ((string) => bool) do
                    return missing_callback
                end
                """);
        assertEquals(explicitGrouping, inferredGrouping);
    }

    @Test
    void misplacedDoOrMissingReturnTypeCannotStartAFunctionBody() {
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("fnc malformed() -> do return 1; end"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("fnc malformed() do return 1; end"));
    }

    @Test
    void optionalDoDoesNotPermitImplicitNonVoidReturnOrMissingFi() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("fnc f() -> int do 42; end")));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("fnc f(int x) -> int do if x > 0 then return 1; end"));
    }

    @Test
    void compactDoSupportsNestedKeywordControlFlowAndMatchesMultilineAst() {
        var compact = Parser.parse(
                "fnc classify(int x) -> int do if x > 0 then return 1; else return 0; fi end");
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
    void contractSignaturesMayReturnFunctionTypesButDoCannotStartAContractBody() {
        assertDoesNotThrow(() -> Parser.parse("""
                define contract CallbackFactory as
                    fnc make() -> () => void;
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define contract CallbackFactory as
                    fnc make() -> () => void do
                        return || -> { return; };
                    end
                end
                """));
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
                () -> Parser.parse("fnc colon(): int do return 1; end"));
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
                () -> Parser.parse("actor fnc work(int x) -> int do return x; end"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define class Example as
                    static fnc run(int x) -> int do return x; end
                end
                """));
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
    void largeTupleArityIsSharedAcrossBothSpellings() {
        String sixtyFive = String.join(", ", Collections.nCopies(65, "int"));

        var explicit = Parser.parse(
                "fnc identity(tuple (" + sixtyFive + ") value) -> tuple (" + sixtyFive
                        + ") { return value; }");
        var existing = Parser.parse(
                "fnc identity([" + sixtyFive + "] value) -> [" + sixtyFive
                        + "] { return value; }");

        assertEquals(existing, explicit);
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
