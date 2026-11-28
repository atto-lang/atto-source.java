package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Admission-only audit for AOT-safe fnc/method/constructor slots.
 *
 * These tests use the real Java Parser and TypeChecker, not a source-free
 * model. They do NOT prove Native Image or compiled guest AOT execution.
 */
final class StaticOverloadAuditV1Test {

    @Test
    void freeFunctionsWithDistinctSourceValueArityAdmit() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc multiply(int x): int { return x; }
                fnc multiply(int x, int y): int { return x * y; }
                """)));
    }

    @Test
    void methodsWithDistinctSourceValueArityAdmit() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Counter as
                  pub value(): int { return 42; }
                  pub value(int offset): int { return offset; }
                end
                """)));
    }

    @Test
    void changingOnlyReturnTypeCannotCreateOverload() {
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        fnc parse(int value): int { return value; }
                        fnc parse(int value): string { return "value"; }
                        """)));
    }

    @Test
    void parameterNamesCannotDiscriminateFreeFunctionOverloads() {
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        fnc choose(int left): int { return left; }
                        fnc choose(int right): int { return right; }
                        """)));
    }

    @Test
    void differentPrimitiveTypesCannotDiscriminateSameArityMethods() {
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Counter as
                          pub choose(int value): int { return value; }
                          pub choose(string value): string { return value; }
                        end
                        """)));
    }

    @Test
    void classConstructorSameArityCannotBeOverloadedByPrimitiveType() {
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Counter as
                          constructor(int value) { return; }
                          constructor(string value) { return; }
                        end
                        """)));
    }

    @Test
    void actorsDoNotHaveClassConstructors() {
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          constructor(int value) { return; }
                        end
                        """)));
    }

    @Test
    void constructorReturnTypeCannotSelectAnOverload() {
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Counter as
                          constructor(int value): int { return value; }
                        end
                        """)));
    }
}
