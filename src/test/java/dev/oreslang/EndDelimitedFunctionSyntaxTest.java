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
    void tupleBoundIsRepresentationWideAcrossBothSpellings() {
        String sixtyFour = String.join(", ", Collections.nCopies(64, "int"));
        String sixtyFive = String.join(", ", Collections.nCopies(65, "int"));

        assertDoesNotThrow(() -> Parser.parse(
                "fnc explicit64(tuple (" + sixtyFour + ") value): void { return; }"));
        assertDoesNotThrow(() -> Parser.parse(
                "fnc bracket64([" + sixtyFour + "] value): void { return; }"));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "fnc explicit65(tuple (" + sixtyFive + ") value): void { return; }"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "fnc bracket65([" + sixtyFive + "] value): void { return; }"));
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
