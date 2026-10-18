package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class CheckedEffectsTest {
    private static Ast.Program checked(String source) {
        return TypeChecker.checkTypes(Parser.parse(source));
    }

    @Test
    void parserRetainsThreeIndependentEffectCategories() {
        Ast.Program program = Parser.parse(
                "fnc work(): int panics int throws string raises bool { return 1; }");
        Ast.FunctionDecl fn = (Ast.FunctionDecl)
                program.modules().getFirst().declarations().getFirst();
        assertEquals(java.util.List.of(Ast.TypeRef.simple("string")),
                fn.effects().throwsTypes());
        assertEquals(java.util.List.of(Ast.TypeRef.simple("bool")),
                fn.effects().raisesTypes());
        assertEquals(java.util.List.of(Ast.TypeRef.simple("int")),
                fn.effects().panicsTypes());
    }

    @Test
    void directCallRequiresCallerToCoverEveryCategoryExactly() {
        assertDoesNotThrow(() -> checked(
                "fnc risky(): int throws string raises bool panics int { return 1; }"
              + "fnc wrapper(): int throws string raises bool panics int { return risky(); }"));

        var missing = assertThrows(IllegalArgumentException.class, () -> checked(
                "fnc risky(): int throws string { return 1; }"
              + "fnc wrapper(): int { return risky(); }"));
        assertTrue(missing.getMessage().contains("caller signature does not cover"));

        var category = assertThrows(IllegalArgumentException.class, () -> checked(
                "fnc risky(): int throws string { return 1; }"
              + "fnc wrapper(): int raises string { return risky(); }"));
        assertTrue(category.getMessage().contains("checked throws"));
    }

    @Test
    void duplicateClausesAndTypesFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "fnc f(): int throws string throws int { return 1; }"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "fnc f(): int throws string, string { return 1; }"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "fnc f(): int throws { return 1; }"));
    }

    @Test
    void unsupportedCallableLifetimesCannotPretendToPropagateEffects() {
        for (String source : new String[] {
                "async fnc f(): int throws string { return 1; }",
                "suspends fnc f(): int throws string { return 1; }",
                "generator fnc f(): int throws string { yield 1; }",
                "trap fnc f(): int throws string { return 1; }",
                "actor fnc f(): int throws string { return 1; }"
        }) {
            assertThrows(IllegalArgumentException.class, () -> checked(source), source);
        }
    }

    @Test
    void mainCannotLeakCheckedEffectsAcrossProcessBoundary() {
        var error = assertThrows(IllegalArgumentException.class, () -> checked(
                "fnc main(): int throws string { return 0; }"));
        assertTrue(error.getMessage().contains("main"));
    }

    @Test
    void directExceptionalExecutionRemainsDisabled() {
        var error = assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "fnc f(): int throws string { throw \"boom\"; }"));
        assertTrue(error.getMessage().contains("source effect is not yet enabled"));
    }

    @Test
    void effectfulFunctionsCannotEraseEffectsThroughFunctionValues() {
        var error = assertThrows(IllegalArgumentException.class, () -> checked(
                "fnc risky(): int throws string { return 1; }"
              + "fnc capture(): void throws string { val f = risky; return; }"));
        assertTrue(error.getMessage().contains("direct-call-only"));
    }
}
