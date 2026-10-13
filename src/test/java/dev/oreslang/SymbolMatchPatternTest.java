package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.OresSymbol;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Symbol identity semantics in literal match arms and constant switch cases. */
final class SymbolMatchPatternTest {
    @Test
    void matchArmsParseToCanonicalNominalSymbolIdentity() {
        Ast.Program p = Parser.parse("""
                fnc classify(Symbol value): int {
                    do match value over
                        on #connected -> { return 1; }
                        on #'hello world' -> { return 2; }
                        on _ -> { return 3; }
                    end
                }
                """);
        assertDoesNotThrow(() -> TypeChecker.check(p));
        Ast.FunctionDecl fn = (Ast.FunctionDecl) p.modules().getFirst().declarations().getFirst();
        Ast.MatchStmt match = (Ast.MatchStmt) fn.body().getFirst();
        assertEquals(OresSymbol.process("connected"),
                ((Ast.LiteralPattern) match.arms().get(0).pattern()).value());
        assertEquals(OresSymbol.process("hello world"),
                ((Ast.LiteralPattern) match.arms().get(1).pattern()).value());
    }

    @Test
    void matchRequiresFallbackForOpenSymbolDomain() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                    fnc invalid(Symbol state): int {
                        do match state over
                            on #connected -> { return 1; }
                        end
                    }
                    """)));
        assertTrue(failure.getMessage().contains("non-exhaustive match"), failure::toString);
    }

    @Test
    void sameLogicalSymbolTwiceIsUnreachableEvenUnderNfcNormalization() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                    fnc invalid(Symbol state): int {
                        do match state over
                            on #ready -> { return 1; }
                            on #ready -> { return 2; }
                            on _ -> { return 3; }
                        end
                    }
                    """)));
        assertTrue(failure.getMessage().contains("unreachable match arm"), failure::toString);
        assertEquals(OresSymbol.process("café"), OresSymbol.process("cafe\u0301"));
    }

    @Test
    void symbolLiteralDoesNotMatchStringTypedSubject() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                    fnc invalid(String state): int {
                        do match state over
                            on #ready -> { return 1; }
                            on _ -> { return 2; }
                        end
                    }
                    """)));
        assertTrue(failure.getMessage().contains("literal pattern type"), failure::toString);
    }

    @Test
    void symbolArmsAndSwitchCasesAgreeAtRuntime() throws Exception {
        String program = """
                fnc matchSymbol(Symbol state): int {
                    do match state over
                        on #ready -> { return 10; }
                        on #'two words' -> { return 20; }
                        on _ -> { return 30; }
                    end
                }

                fnc switchSymbol(Symbol state): int {
                    do switch state
                        case #ready -> { return 40; }
                        case #'two words' -> { return 50; }
                        default -> { return 60; }
                    end
                }

                pub fnc main(): void {
                    stdio.println(matchSymbol(#ready));
                    stdio.println(matchSymbol(#'two words'));
                    stdio.println(matchSymbol(#failed));
                    stdio.println(switchSymbol(#ready));
                    stdio.println(switchSymbol(#'two words'));
                    stdio.println(switchSymbol(#failed));
                    return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "symbol-match.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals("10\n20\n30\n40\n50\n60",
                output.toString(StandardCharsets.UTF_8).trim().replace("\r", ""));
    }

    @Test
    void explicitReturnAndDoDispatchAreRequired() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc invalid(Symbol v): int {
                    match v over
                        on #ready -> { return 1; }
                        on _ -> { return 0; }
                    end
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc invalid(Symbol v): int {
                    switch v
                        case #ready -> { return 1; }
                        default -> { return 0; }
                    end
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc invalid(Any ch): void {
                    select {
                        default: { return; }
                    }
                }
                """));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc status(Symbol v): int {
                    return match v over
                        on #ready -> { return 1; }
                        on _ -> { return 2; }
                    end
                }
                fnc code(Symbol v): int {
                    return switch v
                        case #ready -> { return 3; }
                        default -> { return 4; }
                    end
                }
                """)));
    }

    @Test
    void duplicateSwitchSymbolCaseRejected() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                    fnc invalid(Symbol state): int {
                        do switch state
                            case #ready -> { return 1; }
                            case #ready -> { return 2; }
                            default -> { return 3; }
                        end
                    }
                    """)));
        assertTrue(failure.getMessage().contains("duplicate switch case"), failure::toString);
    }
}
