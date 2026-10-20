package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class EndDelimitedFunctionSyntaxTest {
    @Test
    void freeFunctionUsesArrowTypeIfFiAndEndWithoutSemicolons() {
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
    void explicitVoidMayFallThroughEndDelimitedBody() {
        String source = """
                fnc log_once() -> void
                    const int x = 1
                    x + 1
                end
                """;

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void nonVoidStillRequiresExplicitReturnOnEveryPath() {
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
    void endDelimitedBodyRequiresExplicitSlimArrowReturnType() {
        String implicitVoid = """
                fnc nope()
                    return
                end
                """;
        String colonReturn = """
                fnc nope(): int
                    return 1
                end
                """;

        assertThrows(IllegalArgumentException.class, () -> Parser.parse(implicitVoid));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(colonReturn));
    }

    @Test
    void endDelimitedBodyStartsOnFollowingLine() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "fnc nope() -> int return 1 end"));
    }

    @Test
    void braceBodiesRemainSourceCompatible() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc old_style(int x): int {
                    return x;
                }

                fnc arrow_braces(int x) -> int {
                    return x;
                }

                fnc implicit_void() {
                    return;
                }
                """)));
    }

    @Test
    void actorCallableDoesNotGainEndDelimitedBodyYet() {
        String source = """
                actor fnc work(int x) -> int
                    return x
                end
                """;

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> Parser.parse(source));
        assertTrue(error.getMessage().contains("actor callable"), error.getMessage());
    }

    @Test
    void instanceAndStaticClassMethodsRemainBraced() {
        String instance = """
                define class Example as
                    run(int x) -> int
                        return x
                    end
                end
                """;
        String staticFunction = """
                define class Example as
                    static fnc run(int x) -> int
                        return x
                    end
                end
                """;

        assertThrows(IllegalArgumentException.class, () -> Parser.parse(instance));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(staticFunction));
    }

    @Test
    void explicitTupleReturnTypeUsesOrdinaryTypeChecking() {
        String source = """
                fnc classify(int x) -> tuple (int, int, string)
                    if x > 0 then
                        return (1, x, "positive")
                    else
                        return (0, x, "non-positive")
                    fi
                end
                """;

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void scalarCannotSatisfyTupleReturnType() {
        String source = """
                fnc classify(int x) -> tuple (int, int, string)
                    if x > 0 then
                        return 1
                    else
                        return 0
                    fi
                end
                """;

        assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void tupleTypeWorksForModuleFieldsAndModuleContractSignatures() {
        String source = """
                define contract ClassifierApi as
                    fnc classify(int x) -> tuple (int, int, string);
                    coordinates: tuple (int, int, string);
                end

                define module classifier conforms ClassifierApi as
                    pub const tuple (int, int, string) coordinates = (0, 0, "origin")

                    pub fnc classify(int x) -> tuple (int, int, string)
                        if x > 0 then
                            return (1, x, "positive")
                        else
                            return (0, x, "non-positive")
                        fi
                    end
                end
                """;

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void interfaceMethodSignaturesDoNotAcquireExecutableArrowSyntax() {
        String source = """
                interface InstanceApi as
                    fnc classify(int x) -> int;
                end
                """;

        assertThrows(IllegalArgumentException.class, () -> Parser.parse(source));
    }

    @Test
    void tupleKeywordStaysContextualAndTupleArityIsBounded() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc tuple(int value): int {
                    return value;
                }
                fnc use_tuple_name(): int {
                    return tuple(7);
                }
                """)));

        String slots = String.join(", ", java.util.Collections.nCopies(65, "int"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "fnc too_wide() -> tuple (" + slots + ")\n"
                        + "    return ()\n"
                        + "end\n"));
    }
}
