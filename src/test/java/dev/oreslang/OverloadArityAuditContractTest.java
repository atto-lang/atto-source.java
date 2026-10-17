package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression contract for caller-visible overload arity.
 *
 * Generic parameter count is not a dispatch dimension. Constructors erase
 * their optional leading self binder before arity identity is computed.
 */
final class OverloadArityAuditContractTest {
    @Test
    void genericFunctionCountCannotCreateSameValueArityOverload() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        fnc pick<T>(T value): T { return value; }
                        fnc pick<T, U>(T value): T { return value; }
                        """)));
        assertTrue(failure.getMessage().contains("arity 1"), failure::getMessage);
    }

    @Test
    void genericFunctionCanOverloadWhenCallerVisibleArityDiffers() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc pick<T>(T value): T { return value; }
                fnc pick<T, U>(T value, U other): T { return value; }
                """)));
    }

    @Test
    void genericMethodCountCannotCreateSameValueArityOverload() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class C as
                          pub pick<T>(T value): T { return value; }
                          pub pick<T, U>(T value): T { return value; }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("already has arity 1"), failure::getMessage);
    }

    @Test
    void constructorSelfBinderCannotCreateAnotherAritySlot() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
                Parser.parse("""
                        define class C as
                          constructor(int value) { return; }
                          constructor(self, string value) { return; }
                        end
                        """));
        assertTrue(failure.getMessage().contains("duplicate constructor arity 1"), failure::getMessage);
    }

    @Test
    void parameterAndReturnTypesCannotCreateAnotherMethodSlot() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class C as
                          pub pick(int value): int { return value; }
                          pub pick(string value): string { return value; }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("already has arity 1"), failure::getMessage);
    }
}
