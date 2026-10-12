package dev.oreslang.runtime;

import dev.oreslang.parser.Parser;
import dev.oreslang.ast.Ast;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The mixed-source host preflight must use evaluator-equivalent module lookup. */
final class LinkedProgramRunnerFuturePreflightTest {
    @Test
    void qualifiedNamesFollowTheOwningModuleFutureContract() {
        Ast.Program program = Parser.parse("""
                define module Alpha as
                  pub async fnc work(): int { return 17; }
                  pub fnc read(): int { return 19; }
                end
                define module Beta as
                  pub fnc work(): int { return 22; }
                end
                """);
        assertTrue(LinkedProgramRunner.publicFunctionReturnsFuture(
                program, "Alpha.work", 0));
        assertFalse(LinkedProgramRunner.publicFunctionReturnsFuture(
                program, "Beta.work", 0));
        assertFalse(LinkedProgramRunner.publicFunctionReturnsFuture(
                program, "Alpha.read", 0));
        assertFalse(LinkedProgramRunner.publicFunctionReturnsFuture(
                program, "Gamma.work", 0));
        assertThrows(IllegalArgumentException.class,
                () -> LinkedProgramRunner.publicFunctionReturnsFuture(program, "work", 0),
                "unqualified cross-module function must not silently choose one owner");
    }

    @Test
    void ambiguityAcrossAritiesAlsoRequiresQualification() {
        Ast.Program program = Parser.parse("""
                define module Alpha as
                  pub async fnc work(int value): int { return value; }
                end
                define module Beta as
                  pub fnc work(): int { return 22; }
                end
                """);
        assertTrue(LinkedProgramRunner.publicFunctionReturnsFuture(
                program, "Alpha.work", 1));
        assertFalse(LinkedProgramRunner.publicFunctionReturnsFuture(
                program, "Beta.work", 0));
        assertThrows(IllegalArgumentException.class,
                () -> LinkedProgramRunner.publicFunctionReturnsFuture(program, "work", 1),
                "evaluator rejects unqualified cross-module names regardless of arity");
    }
}
