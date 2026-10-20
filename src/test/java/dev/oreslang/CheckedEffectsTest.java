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
    @Test
    void rejectsUnresolvedAndRuntimeAffinePayloadsInAllCategories() {
        for (String category : new String[]{"throws", "raises", "panics"}) {
            for (String payload : new String[]{
                    "NotDeclared", "Future<int>", "Channel<int>", "Mutex<int>",
                    "Option<Future<int>>", "Result<int, Channel<int>>",
                    "Fnc<int>", "ActorMail<int>"
            }) {
                String source = "fnc risky(): int " + category + " " + payload
                        + " { return 1; }";
                assertThrows(IllegalArgumentException.class, () -> checked(source),
                        category + " " + payload);
            }
        }
    }

    @Test
    void rejectsAliasIndirectionToRuntimeAffineTypes() {
        assertThrows(IllegalArgumentException.class, () -> checked(
                "define type Alias = Future<int>; "
                + "fnc risky(): int throws Alias { return 1; }"));
    }

    @Test
    void acceptsClosedSumPayloadsWithoutSilentlyAllowingDynamicTypes() {
        assertDoesNotThrow(() -> checked(
                "fnc risky(): int throws Option<int> raises Result<string,int> "
                + "{ return 1; }"));
    }

    @Test
    void boundedNestedPayloadRefinementRejectsBeforeUnboundedResolution() {
        String payload = "int";
        for (int i = 0; i < 19; ++i) payload = "Option<" + payload + ">";
        String source = "fnc risky(): int throws " + payload + " { return 1; }";
        var error = assertThrows(IllegalArgumentException.class,
                () -> checked(source));
        assertTrue(error.getMessage().contains("budget"));
    }


    @Test
    void rejectsAffineSourceEffectPayloadsFromSupersededHardeningDraft() {
        // Additional payload cases from #1703, evaluated against the stronger
        // closed, bounded ownership/data-only proof already in this branch.
        for (String kind : new String[]{"throws", "raises", "panics"}) {
            for (String payload : new String[]{
                    "ActorRef<int>", "Generator<int>", "Pointer<int>",
                    "List<int>", "Ref<int>", "unknown", "any"}) {
                String source = "fnc unsafe(): int " + kind + " "
                        + payload + " { return 1; }";
                assertThrows(IllegalArgumentException.class, () -> checked(source),
                        kind + " " + payload);
            }
        }
    }

    @Test
    void stableScalarEffectTypesKeepExactCategoryChecking() {
        assertDoesNotThrow(() -> checked(
                "fnc f(): int throws int raises string panics bool { return 1; }"
              + "fnc g(): int throws int raises string panics bool { return f(); }"));
        assertThrows(IllegalArgumentException.class, () -> checked(
                "fnc f(): int throws string { return 1; }"
              + "fnc g(): int throws bool { return f(); }"));
    }
}
