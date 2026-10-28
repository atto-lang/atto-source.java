package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.types.Types;
import dev.oreslang.types.Types.Named;
import dev.oreslang.types.Types.Primitive;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression gates for the explicit one-layer Option/Future type contract.
 * These tests assert compiler admission, not backend AOT/actor parity.
 */
final class NestedWrapperSemanticsContractTest {
    @Test
    void nestedNominalWrappersRemainDistinctAndNonAssignable() {
        Named option = new Named("Option", List.of(Primitive.INT));
        Named nestedOption = new Named("Option", List.of(option));
        Named future = new Named("Future", List.of(Primitive.INT));
        Named nestedFuture = new Named("Future", List.of(future));

        assertNotEquals(option, nestedOption);
        assertNotEquals(future, nestedFuture);
        assertFalse(Types.isAssignable(nestedOption, option));
        assertFalse(Types.isAssignable(option, nestedOption));
        assertFalse(Types.isAssignable(nestedFuture, future));
        assertFalse(Types.isAssignable(future, nestedFuture));
        assertTrue(Types.isAssignable(nestedOption, nestedOption));
        assertTrue(Types.isAssignable(nestedFuture, nestedFuture));
    }

    @Test
    void compilerPreservesNestedOptionParameterAndReturnTypes() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                pub fnc identity(Option<Option<int>> nested): Option<Option<int>> {
                    return nested;
                }
                pub fnc main(): void { return; }
                """));

        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                pub fnc invalid(Option<Option<int>> nested): Option<int> {
                    return nested;
                }
                pub fnc main(): void { return; }
                """));
    }

    @Test
    void compilerRejectsImplicitFutureLayerLossInCallableArgument() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                pub fnc take(Future<Future<int>> nested): void { return; }
                pub fnc pass(Future<Future<int>> nested): void {
                    take(nested);
                    return;
                }
                pub fnc main(): void { return; }
                """));

        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                pub fnc take(Future<int> inner): void { return; }
                pub fnc pass(Future<Future<int>> nested): void {
                    take(nested);
                    return;
                }
                pub fnc main(): void { return; }
                """));
    }

    @Test
    void compilerAwaitRemovesOnlyOneFutureLayer() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                pub suspends fnc take(Future<Future<int>> nested): Future<int> {
                    return await nested;
                }
                pub fnc main(): void { return; }
                """));

        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                pub suspends fnc invalid(Future<Future<int>> nested): int {
                    return await nested;
                }
                pub fnc main(): void { return; }
                """));
    }
}
