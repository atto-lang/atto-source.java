package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.VerifiedDirectModuleCall;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Full-front-end admission proof for ordinary calls only. */
final class VerifiedDirectModuleCallTest {
    private static String module(String caller) {
        return """
                define module app as
                    pub fnc target(int x): int { return x; }
                    %s
                end
                """.formatted(caller);
    }

    @Test
    void completeCompilerChecksAndSelectsOriginalPositionalSite() {
        var proof = VerifiedDirectModuleCall.analyzeAndIssue(
                module("pub fnc caller(): int { return target(42); }"),
                "app", "caller");
        assertSame(proof.checkedProgram().modules().getFirst(), proof.module());
        assertSame(proof.module().declarations().getFirst(),
                proof.selectedDeclaration());
        assertSame(proof.module().declarations().get(1), proof.caller());
        assertSame(((Ast.ReturnStmt) proof.caller().body().getFirst()).value(),
                proof.sourceCall());
        assertEquals("target", ((Ast.NameExpr) proof.sourceCall().callee()).name());
    }

    @Test
    void namedAndOptionalStagingCannotEarnOrdinaryCompilerProof() {
        assertThrows(IllegalArgumentException.class, () ->
                VerifiedDirectModuleCall.analyzeAndIssue(
                        module("pub fnc caller(): int { return target(x: 42); }"),
                        "app", "caller"));
        assertThrows(IllegalArgumentException.class, () ->
                VerifiedDirectModuleCall.analyzeAndIssue("""
                        define module app as
                            pub fnc target(int? x): int { return 42; }
                            pub fnc caller(): int { return target(); }
                        end
                        """, "app", "caller"));
    }

    @Test
    void wrongTypesAndMissingReturnCannotClaimFullCheckerAdmission() {
        assertThrows(IllegalArgumentException.class, () ->
                VerifiedDirectModuleCall.analyzeAndIssue(
                        module("pub fnc caller(): int { return target(true); }"),
                        "app", "caller"));
        assertThrows(IllegalArgumentException.class, () ->
                VerifiedDirectModuleCall.analyzeAndIssue(
                        module("pub fnc caller(): int { target(42); }"),
                        "app", "caller"));
    }

    @Test
    void detachedOrMissingCallerSiteCannotBeAuthorized() {
        String valid = module("pub fnc caller(): int { return target(42); }");
        assertThrows(IllegalArgumentException.class, () ->
                VerifiedDirectModuleCall.analyzeAndIssue(valid, "app", "missing"));
        assertThrows(IllegalArgumentException.class, () ->
                VerifiedDirectModuleCall.analyzeAndIssue(valid, "other", "caller"));
        assertThrows(IllegalArgumentException.class, () ->
                VerifiedDirectModuleCall.analyzeAndIssue(
                        module("pub fnc caller(): int { return 42; }"),
                        "app", "caller"));
    }

    @Test
    void constructorIsPrivateEvenThoughCheckedReferencesAreReadable() {
        assertEquals(0, VerifiedDirectModuleCall.class.getConstructors().length);
    }
}
