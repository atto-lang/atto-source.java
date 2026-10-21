package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

final class EndDelimitedFunctionCurrentMainTest {
    @Test
    void endDelimitedFreeFunctionUsesCanonicalIfFi() {
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
    void explicitVoidMayFallThroughClosingEnd() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc nop() -> void
                end
                """)));
    }

    @Test
    void nonVoidStillRequiresReturnOnEveryPath() {
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
    void endDelimitedBodyRequiresExplicitSlimArrowAndNextLine() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc implicit()
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
    void optionalSameLineBodySeparatorsAreSemanticsFree() {
        var noSeparator = Parser.parse("""
                fnc identity(int x) -> int
                    return x
                end
                """);
        var explicitAs = Parser.parse("""
                fnc identity(int x) -> int as
                    return x
                end
                """);
        var explicitDoubleColon = Parser.parse("""
                fnc identity(int x) -> int ::
                    return x
                end
                """);
        var compactAs = Parser.parse("fnc identity(int x) -> int as return x; end");
        var compactDoubleColon = Parser.parse("fnc identity(int x) -> int :: return x; end");

        assertEquals(noSeparator, explicitAs);
        assertEquals(noSeparator, explicitDoubleColon);
        assertEquals(noSeparator, compactAs);
        assertEquals(noSeparator, compactDoubleColon);
    }

    @Test
    void callableBodySeparatorsMustShareHeaderLineAndHeaderDoIsRetired() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc bad(int x) -> int
                as
                    return x
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc bad(int x) -> int
                ::
                    return x
                end
                """));

        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("fnc bad(int x) -> int do return x; end"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("fnc bad(int x) -> int as :: return x; end"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("fnc bad(int x): int :: return x; end"));
    }

    @Test
    void completeHigherOrderReturnTypePrecedesOptionalBodySeparator() {
        assertDoesNotThrow(() -> Parser.parse("""
                fnc callback() -> () => void as
                    return || -> { return; };
                end
                """));
        assertDoesNotThrow(() -> Parser.parse("""
                fnc callback() -> () => void ::
                    return || -> { return; };
                end
                """));
    }

    @Test
    void doubleColonSeparatorDoesNotStealStaticAccessInsideBody() {
        assertDoesNotThrow(() -> Parser.parse(
                "fnc lookup() -> int :: return Math::answer(); end"));
    }

    @Test
    void existingBracedFormsRemainAccepted() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc colon(int x): int {
                    return x;
                }

                fnc arrow(int x) -> int {
                    return x;
                }

                fnc implicit_void() {
                    return;
                }
                """)));
    }

    @Test
    void classAndStaticImplementationsRemainBraceOnly() {
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
    void actorCallableDoesNotGainKeywordBody() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                actor fnc work(int x) -> int
                    return x
                end
                """));
    }

    @Test
    void tupleKeywordReturnTypeUsesExistingTupleSemantics() {
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
                fnc bad() -> tuple (int, int, string)
                    return 1
                end
                """;

        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void bodySpellingNormalizesToSameAst() {
        var keyword = Parser.parse("""
                fnc identity(int x) -> int
                    return x
                end
                """);
        var braced = Parser.parse("""
                fnc identity(int x) -> int {
                    return x;
                }
                """);

        assertEquals(braced, keyword);
    }

    @Test
    void tupleTypeSpellingsNormalizeToSameAst() {
        var keyword = Parser.parse("""
                fnc identity(tuple (int, string) value) -> tuple (int, string) {
                    return value;
                }
                """);
        var existing = Parser.parse("""
                fnc identity([int, string] value) -> [int, string] {
                    return value;
                }
                """);

        assertEquals(existing, keyword);
    }

    @Test
    void contextualTupleSpellingPreservesExistingArityPolicy() {
        String slots65 = String.join(", ", Collections.nCopies(65, "int"));

        assertDoesNotThrow(() -> Parser.parse(
                "fnc a([" + slots65 + "] value): void { return; }"));
        assertDoesNotThrow(() -> Parser.parse(
                "fnc b(tuple (" + slots65 + ") value): void { return; }"));
    }

    @Test
    void moduleContractMayUseSlimArrowButInterfaceDoesNot() {
        assertDoesNotThrow(() -> Parser.parse("""
                define contract ModuleApi as
                    fnc classify(int x) -> tuple (int, string);
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define interface InstanceApi
                    fnc classify(int x) -> int;
                end
                """));
    }

    @Test
    void nestedKeywordDelimitedConstructsOwnTheirTerminators() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc classify(int x) -> int
                    switch x
                        case 1 -> { return 1; }
                        default -> { return 0; }
                    end
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc broken(int x) -> int
                    if x > 0 then
                        return 1
                end
                """));
    }

    @Test
    void recursiveReturnTypeUsesOrdinaryTypeGrammar() {
        assertDoesNotThrow(() -> Parser.parse("""
                fnc nested() -> Result<Option<tuple (int, string)>, string>
                    return Ok(Some((1, "one")))
                end
                """));
    }
}
