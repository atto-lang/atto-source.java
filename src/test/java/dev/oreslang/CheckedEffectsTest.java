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

    @Test
    void tryCatchCannotConsumePanicOrRaiseFromDirectCallee() {
        for (String category : new String[]{"panics", "raises"}) {
            String leaf = "fnc leaf(): int " + category + " int { return 1; }";
            String attempted = "try { return leaf(); } catch (err) { return 0; }";

            // Even though catch has an error binding, it cannot discharge
            // the checked panic/raise effect from leaf().
            var missing = assertThrows(IllegalArgumentException.class, () -> checked(
                    leaf + "fnc bad(): int { " + attempted + " }"));
            assertTrue(missing.getMessage().contains("checked " + category));

            // Keeping the category in the calling signature is permitted
            // by today's restricted signature-only checker.
            assertDoesNotThrow(() -> checked(
                    leaf + "fnc forwarding(): int " + category
                            + " int { " + attempted + " }"));
        }
    }

    @Test
    void routinesMustPropagateCheckedEffectsWithoutCategoryConversion() {
        assertDoesNotThrow(() -> checked(
                "fnc risky(): int panics int raises bool throws string { return 1; }"
              + "routine forwarding(): int throws string raises bool panics int { return risky(); }"));

        for (String category : new String[]{"throws", "raises", "panics"}) {
            String leaf = "fnc risky(): int " + category + " int { return 1; }";
            var missing = assertThrows(IllegalArgumentException.class, () -> checked(
                    leaf + "routine bad(): int { return risky(); }"));
            assertTrue(missing.getMessage().contains("checked " + category));
        }
    }

    @Test
    void panicEffectMustStayInCallerSignatureEvenWithUnrelatedThrows() {
        var error = assertThrows(IllegalArgumentException.class, () -> checked(
                "fnc risky(): int panics int { return 1; }"
              + "fnc bad(): int throws int { return risky(); }"));
        assertTrue(error.getMessage().contains("checked panics"));
    }

    @Test
    void parserRetainsCheckedMethodAndStaticEffects() {
        Ast.Program parsed = Parser.parse(
                "define class Work as "
              + "  risky(): int panics int throws string raises bool { return 1; }"
              + "  static fnc reset(): int throws int { return 1; }"
              + "end");
        Ast.ClassDecl klass = (Ast.ClassDecl) parsed.modules().getFirst().declarations().getFirst();
        Ast.MethodDecl instance = klass.methods().getFirst();
        Ast.MethodDecl statik = klass.methods().get(1);
        assertEquals(java.util.List.of(Ast.TypeRef.simple("int")), instance.effects().panicsTypes());
        assertEquals(java.util.List.of(Ast.TypeRef.simple("string")), instance.effects().throwsTypes());
        assertEquals(java.util.List.of(Ast.TypeRef.simple("bool")), instance.effects().raisesTypes());
        assertEquals(java.util.List.of(Ast.TypeRef.simple("int")), statik.effects().throwsTypes());
    }

    @Test
    void instanceMethodsMustDeclareTransitiveEffects() {
        String prefix = "define class Work as "
                + "  risky(): int panics int { return 1; }";
        var missing = assertThrows(IllegalArgumentException.class, () -> checked(
                prefix + "  bad(): int { return self.risky(); } end"));
        assertTrue(missing.getMessage().contains("checked panics"));
        assertDoesNotThrow(() -> checked(
                prefix + "  good(): int panics int { return self.risky(); } end"));
    }

    @Test
    void staticMethodCallsRequireMatchingCallerCategory() {
        String klass = "define class Work as "
                + "  static fnc risky(): int raises int { return 1; }"
                + "end";
        var missing = assertThrows(IllegalArgumentException.class, () -> checked(
                klass + "fnc call(): int { return Work.risky(); }"));
        assertTrue(missing.getMessage().contains("checked raises"));
        assertDoesNotThrow(() -> checked(
                klass + "fnc call(): int raises int { return Work.risky(); }"));
    }

    @Test
    void wideningInheritedMethodEffectsIsRejected() {
        var widened = assertThrows(IllegalArgumentException.class, () -> checked(
                "define class Parent as pub run(): int { return 1; } end"
              + "define class Child extends Parent as "
              + "  pub run(): int panics int { return 2; }"
              + "end"));
        assertTrue(widened.getMessage().contains("checked panics"));
    }

    @Test
    void effectfulStaticFunctionsCannotBeErasedIntoFunctionValues() {
        var erased = assertThrows(IllegalArgumentException.class, () -> checked(
                "define class Work as "
              + "  static fnc risky(): int panics int { return 1; }"
              + "end"
              + "fnc store(): void panics int { val callback = Work.risky; return; }"));
        assertTrue(erased.getMessage().contains("direct-call-only"));
    }

    @Test
    void interfaceConformanceCannotEraseMethodEffects() {
        var erased = assertThrows(IllegalArgumentException.class, () -> checked(
                "define interface Service as fnc handle(): int; end"
              + "define class Work implements Service as "
              + "  pub handle(): int panics int { return 1; }"
              + "end"));
        assertTrue(erased.getMessage().contains("interface"));
    }

    @Test
    void catchStillCannotConsumePanicFromInstanceMethod() {
        var escaped = assertThrows(IllegalArgumentException.class, () -> checked(
                "define class Work as "
              + "  panicWork(): int panics int { return 1; }"
              + "  bad(): int { "
              + "    try { return self.panicWork(); } catch (err) { return 0; }"
              + "  }"
              + "end"));
        assertTrue(escaped.getMessage().contains("checked panics"));
    }

    @Test
    void interfaceParserRetainsIndependentCheckedCategories() {
        Ast.Program parsed = Parser.parse(
                "define interface Worker as "
              + "  fnc handle(): int throws string raises bool panics int;"
              + "end");
        Ast.InterfaceDecl iface = (Ast.InterfaceDecl)
                parsed.modules().getFirst().declarations().getFirst();
        Ast.InterfaceFunctionDecl fn = (Ast.InterfaceFunctionDecl) iface.members().getFirst();
        assertEquals(java.util.List.of(Ast.TypeRef.simple("string")), fn.effects().throwsTypes());
        assertEquals(java.util.List.of(Ast.TypeRef.simple("bool")), fn.effects().raisesTypes());
        assertEquals(java.util.List.of(Ast.TypeRef.simple("int")), fn.effects().panicsTypes());
    }

    @Test
    void effectfulInterfaceFunctionTypeIsRejectedRatherThanErased() {
        var erased = assertThrows(IllegalArgumentException.class, () -> checked(
                "define interface Worker as fnc handle(): int panics int; end"));
        assertTrue(erased.getMessage().contains("cannot be represented"));
        assertTrue(erased.getMessage().contains("effects"));
    }

    @Test
    void duplicateAndMissingInterfaceEffectsAreParserErrors() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "define interface Worker as fnc handle(): int panics int panics bool; end"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "define interface Worker as fnc handle(): int raises; end"));
    }

    @Test
    void effectlessInterfaceRemainsAccepted() {
        assertDoesNotThrow(() -> checked(
                "define interface Worker as fnc handle(): int; end"));
    }

    @Test
    void escapingLambdaMustNotInheritEnclosingPanicAllowance() {
        var leaked = assertThrows(IllegalArgumentException.class, () -> checked(
                "fnc risky(): int panics int { return 1; }"
              + "fnc parent(): int panics int {"
              + "  val callback = || -> { return risky(); };"
              + "  return 0;"
              + "}"));
        assertTrue(leaked.getMessage().contains("checked panics"));
    }

    @Test
    void expectedFunctionLambdaMustNotLaunderPanicThroughCallbackType() {
        var leaked = assertThrows(IllegalArgumentException.class, () -> checked(
                "fnc risky(): int panics int { return 1; }"
              + "fnc apply(cb: Fnc<int>): int { return cb(); }"
              + "fnc parent(): int panics int {"
              + "  return apply(|| -> { return risky(); });"
              + "}"));
        assertTrue(leaked.getMessage().contains("checked panics"));
    }

    @Test
    void pureLambdaRemainsValidWithinEffectfulCaller() {
        assertDoesNotThrow(() -> checked(
                "fnc pure(): int { return 1; }"
              + "fnc parent(): int panics int {"
              + "  val callback = || -> { return pure(); };"
              + "  return 0;"
              + "}"));
    }
}
