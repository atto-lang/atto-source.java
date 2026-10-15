package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class CallbackDeliveryContractTest {
    @Test
    void typedDeliveryContractSurvivesParsing() {
        Ast.Program program = Parser.parse("""
                pub fnc register(@callback(deferred) Fnc<int, void> later,
                                 @callback(inline) Fnc<int, void> now,
                                 @callback(maybe_inline) Fnc<int, void> legacy): void {
                    return;
                }
                """);
        Ast.FunctionDecl fn = assertInstanceOf(Ast.FunctionDecl.class,
                program.modules().getFirst().declarations().getFirst());
        assertEquals(Ast.CallbackDelivery.DEFERRED,
                fn.parameters().get(0).callbackDelivery());
        assertEquals(Ast.CallbackDelivery.INLINE,
                fn.parameters().get(1).callbackDelivery());
        assertEquals(Ast.CallbackDelivery.MAYBE_INLINE,
                fn.parameters().get(2).callbackDelivery());
    }

    @Test
    void invalidAnnotationModesAndNonCallableParametersAreErrors() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc bad(@callback(eventually) Fnc<int, void> cb): void { return; }
                """));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        fnc bad(@callback(deferred) int value): void { return; }
                        """));
        assertTrue(failure.getMessage().contains("must have an Fnc"),
                failure::getMessage);
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc bad(@callback(deferred) @callback(inline) Fnc<int, void> cb): void {
                    return;
                }
                """));
    }

    @Test
    void publicCallbackWithoutDeliveryModeWarns() {
        var diagnostics = OresCompiler.parseAndTypeCheckWithDiagnostics("""
                pub fnc register(Fnc<int, void> cb): void { return; }
                """);
        assertTrue(diagnostics.warnings().stream().anyMatch(
                warning -> warning.contains("W-CB001") && warning.contains("cb")));
    }

    @Test
    void annotatedCallbackDoesNotProduceUnspecifiedModeWarning() {
        var diagnostics = OresCompiler.parseAndTypeCheckWithDiagnostics("""
                pub fnc register(@callback(deferred) Fnc<int, void> cb): void {
                    return;
                }
                """);
        assertFalse(diagnostics.warnings().stream().anyMatch(
                warning -> warning.contains("W-CB001")));
    }

    @Test
    void borrowedLocalCannotEscapeIntoDeferredCallback() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        fnc register(@callback(deferred) Fnc<int, void> cb): void {
                            return;
                        }
                        pub fnc main(): void {
                            let String value = "abc";
                            val &String loan = rt borrow value;
                            register(|int event| -> { stdio.println(loan); });
                            return;
                        }
                        """));
        assertTrue(failure.getMessage().contains("borrow"), failure::getMessage);
    }

    @Test
    void moveOnlyCaptureCannotAlsoBeUsedByRegisteringCaller() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define class Box as
                            pub let int value = 7;
                        end
                        fnc register(@callback(deferred) Fnc<int, void> cb): void {
                            return;
                        }
                        pub fnc main(): void {
                            let Box box = new Box();
                            register(|int event| -> { stdio.println(box.value); });
                            stdio.println(box.value);
                            return;
                        }
                        """));
    }

    @Test
    void ordinaryInlineCallbackRemainsCallableAndNonSuspending() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                fnc run_now(@callback(inline) Fnc<int, void> cb): void {
                    cb(5);
                    return;
                }
                pub fnc main(): void {
                    run_now(|int value| -> { stdio.println(value); });
                    return;
                }
                """));
    }
}
