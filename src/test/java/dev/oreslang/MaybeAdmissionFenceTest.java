package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Admission fence only: not positive syntax support or guest execution.
 * Remove these expected rejections only with real checker+CFG+AOT tests.
 */
final class MaybeAdmissionFenceTest {
    @Test
    void contextualMaybeIdentifierRemainsLegalInOrdinaryExpressions() {
        assertDoesNotThrow(() -> Parser.parse("""
                fnc same(int maybe): int {
                    return maybe;
                }
                fnc plus_one(int maybe): int {
                    return maybe + 1;
                }
                fnc invoke(int maybe): int {
                    return maybe;
                }
                """));
    }

    @Test
    void proceduralMaybeMustNotBeSilentlyReinterpreted() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc f(int id): int {
                    do maybe
                        Ok(value) ?= fetch(id);
                        return value;
                    else
                        Err(reason) -> return 0;
                    end
                }
                """));
        assertTrue(error.getMessage().contains("'do maybe' requires checked"),
                error::getMessage);
    }

    @Test
    void ReturnMaybeWithNestedFunctionReturnsNeedsDedicatedLowering() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc f(int id): int {
                    return maybe
                        Ok(value) ?= fetch(id);
                        return value;
                    else
                        Err(reason) -> return 0;
                    end;
                }
                """));
        assertTrue(error.getMessage().contains("'return maybe' requires checked"),
                error::getMessage);
    }

    @Test
    void FallibleBindingOutsideMaybeDoesNotParseAsTernaryOrAssignment() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc f(int id): int {
                    Ok(value) ?= fetch(id);
                    return value;
                }
                """));
    }
}
