package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression for the two independent typed completion destinations.
 * ARM_RESULT belongs to a value-producing match. FUNCTION_RETURN belongs
 * to the enclosing function, including blocking procedural channel select.
 */
final class TypedDispatchReturnIsolationTest {
    @Test
    void valueMatchResultIsConsumedBeforeEnclosingFunctionReturns() throws Exception {
        String source = """
                fnc compute(): int {
                    const answer: int = match<ret=int> 7 over {
                        on 7 { return 42; }
                        default { return 0; }
                    };
                    return answer + 1;
                }
                pub fnc main(): void {
                    stdio.println(compute());
                    return;
                }
                """;
        assertEquals("43\n", execute(source));
    }

    @Test
    void directReturnMatchWithoutRetAnnotationUsesCallableResultType() throws Exception {
        String source = """
                fnc classify(bool ready): int {
                    return match ready over
                        on true -> { return 42; }
                        on _ -> { return 0; }
                    end
                }
                pub fnc main(): void {
                    stdio.println(classify(true));
                    return;
                }
                """;
        assertEquals("42\n", execute(source));
    }

    @Test
    void directReturnMatchTypeHintDoesNotLeakIntoItsSubject() {
        IllegalArgumentException diagnostic = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                    fnc classify(bool ready): int {
                        return match match ready over
                            on true -> { return true; }
                            on _ -> { return false; }
                        end over
                            on true -> { return 1; }
                            on _ -> { return 0; }
                        end
                    }
                    """));
        assertTrue(diagnostic.getMessage().contains("<ret=Type>"),
                diagnostic::toString);

        assertDoesNotThrow(() -> Parser.parse("""
                fnc classify(bool ready): int {
                    return match match<ret=bool> ready over
                        on true -> { return true; }
                        on _ -> { return false; }
                    end over
                        on true -> { return 1; }
                        on _ -> { return 0; }
                    end
                }
                """));
    }

    @Test
    void plainValueMatchCannotBorrowEnclosingCallableReturnContract() {
        IllegalArgumentException diagnostic = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                    fnc classify(bool ready): int {
                        const answer: int = match ready over
                            on true -> { return 42; }
                            on _ -> { return 0; }
                        end;
                        return answer;
                    }
                    """));
        assertTrue(diagnostic.getMessage().contains("<ret=Type>"),
                diagnostic::toString);
    }

    @Test
    void blockingDoSelectReturnExitsEnclosingCallableWithoutWarning() throws Exception {
        String source = """
                fnc compute(): int {
                    val Channel<int> incoming = Channel.new<int>(1);
                    writech incoming, 7;
                    do select {
                        case readch incoming: val value {
                            stdio.stdout.write("selected|");
                            return value * 6;
                        }
                    }
                    return 99;
                }
                pub fnc main(): void {
                    stdio.println(compute());
                    return;
                }
                """;
        OresCompiler.AnalysisResult result =
                OresCompiler.parseAndTypeCheckWithDiagnostics(source);
        assertTrue(result.warnings().stream()
                        .noneMatch(warning -> warning.contains("W-SELECT-RETURN")),
                () -> "blocking do-select must not be treated as detached callback: "
                        + result.warnings());
        assertEquals("selected|42\n", execute(source));
    }

    @Test
    void nestedDoSelectReturnKeepsFunctionScopeNotValueMatchArmScope() {
        Ast.Program parsed = Parser.parse("""
                fnc nested(): int {
                    return match<ret=int> true over {
                        on true {
                            val Channel<int> c = Channel.new<int>(1);
                            do select {
                                default: { return 9; }
                            }
                            return 42;
                        }
                        default { return 0; }
                    };
                }
                """);
        Ast.FunctionDecl fn = (Ast.FunctionDecl)
                parsed.modules().getFirst().declarations().getFirst();
        Ast.ReturnStmt enclosingReturn =
                assertInstanceOf(Ast.ReturnStmt.class, fn.body().getFirst());
        Ast.ValueMatchExpr valueMatch =
                assertInstanceOf(Ast.ValueMatchExpr.class, enclosingReturn.value());
        var matchArm = valueMatch.arms().getFirst().body();
        Ast.SelectStmt nestedSelect = matchArm.stream()
                .filter(Ast.SelectStmt.class::isInstance)
                .map(Ast.SelectStmt.class::cast).findFirst().orElseThrow();
        Ast.Stmt proceduralReturn = nestedSelect.arms().getFirst().body().getFirst();
        assertInstanceOf(Ast.ReturnStmt.class, proceduralReturn,
                "do-select must never capture its enclosing match's ARM_RESULT channel");
        assertInstanceOf(Ast.ArmReturnStmt.class, matchArm.getLast(),
                "terminal return in the value match arm still owns ARM_RESULT");
    }

    @Test
    void proceduralSelectInsideTypedMatchIsRejectedUntilNonlocalFlowIsLowered() {
        String source = """
                fnc nested(): int {
                    return match<ret=int> true over {
                        on true {
                            do select {
                                default: { return 9; }
                            }
                            return 42;
                        }
                        default { return 0; }
                    };
                }
                """;
        // Parsing knows that a procedural select returns from the CALLABLE,
        // not from the containing value arm. But the typed expression's
        // suspended SourceValueCont cannot yet transport FUNCTION_RETURN.
        assertDoesNotThrow(() -> Parser.parse(source));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheckWithDiagnostics(source));
        assertTrue(error.getMessage().contains("typed value match arm"),
                error::toString);
    }

    @Test
    void unsupportedReturnSwitchAndBracedSelectFailClosedRatherThanReturningFromFunction() {
        for (String source : new String[] {
                """
                fnc wrong(int n): int {
                    return switch n
                        case 1 -> { return 1; }
                        default -> { return 2; }
                    end
                }
                """,
                """
                fnc wrong(): int {
                    return select {
                        default: { return 1; }
                    }
                }
                """
        }) {
            IllegalArgumentException error =
                    assertThrows(IllegalArgumentException.class, () -> Parser.parse(source));
            assertTrue(error.getMessage().contains("ARM_RESULT"), error::toString);
        }
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc wrong(bool x): int {
                    return do match x over
                        on true -> { return 1; }
                        on _ -> { return 2; }
                    end
                }
                """));
    }

    @Test
    void illegalBareDispatchIsRejectedBeforeRuntime() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc invalid(int n): int {
                    match n over on 1 -> { return 1; } on _ -> { return 0; } end
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc invalid(int n): int {
                    switch n case 1 -> { return 1; } default -> { return 0; } end
                }
                """));
    }

    private static String execute(String source) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, source,
                    "typed-dispatch-return-isolation.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        return output.toString(StandardCharsets.UTF_8).replace("\r", "");
    }
}
