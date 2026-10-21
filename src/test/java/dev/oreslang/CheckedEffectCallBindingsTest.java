package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.types.EffectContracts;
import dev.oreslang.types.SourceEffectAudit;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Direct-call proof must belong to the checker and the exact AST node. */
final class CheckedEffectCallBindingsTest {
    private static final String SOURCE = """
            pub fnc helper(): int { return 42; }
            pub fnc first(): int { return helper(); }
            pub fnc second(): int { return helper(); }
            """;

    @Test void distinctEqualCallSitesHaveSeparateCheckerBindings() {
        var checked = OresCompiler.parseAndTypeCheckForEffectAudit(SOURCE);
        var targets = checked.resolvedLocalCalls();
        assertEquals(2, targets.size());
        var calls = List.copyOf(targets.keySet());
        assertNotSame(calls.get(0), calls.get(1));
        assertEquals(calls.get(0), calls.get(1), "AST records compare structurally");
        assertSame(targets.get(calls.get(0)), targets.get(calls.get(1)));

        // A forged, structurally equal call must not borrow a checked target.
        var fabricated = new Ast.CallExpr(new Ast.NameExpr("helper"), List.of());
        assertEquals(calls.get(0), fabricated);
        assertFalse(targets.containsKey(fabricated));
        assertNull(targets.get(fabricated));
        assertThrows(UnsupportedOperationException.class, () ->
                targets.put(fabricated, targets.get(calls.get(0))));
    }

    @Test void transitiveCheckerResolvedCallsRemainEffectFree() {
        var report = SourceEffectAudit.inspect(SOURCE);
        assertEquals(3, report.functions().size());
        assertTrue(report.unsupportedDeclarations().isEmpty());
        assertTrue(report.conservativelyEffectFree());
        assertTrue(report.functions().values().stream().allMatch(Set::isEmpty));
    }

    @Test void checkerResolutionDoesNotHideImpurityAcrossCalls() {
        var report = SourceEffectAudit.inspect("""
                pub fnc helper(): int { return 40 + 2; }
                pub fnc first(): int { return helper(); }
                pub fnc second(): int { return first(); }
                """);
        assertEquals(3, report.functions().size());
        assertTrue(report.functions().values().stream().allMatch(
                row -> row.contains(EffectContracts.Effect.UNKNOWN_CALL)));
        assertFalse(report.conservativelyEffectFree());
    }

    @Test void checkedLocalTargetDoesNotCertifyUnverifiedSurroundingOperator() {
        var report = SourceEffectAudit.inspect("""
                pub fnc helper(): int { return 42; }
                pub fnc entry(): int { return helper() + 2; }
                """);
        assertEquals(2, report.functions().size());
        assertTrue(report.functions().values().stream().anyMatch(Set::isEmpty));
        assertTrue(report.functions().values().stream().anyMatch(
                row -> row.contains(EffectContracts.Effect.UNKNOWN_CALL)));
        assertFalse(report.conservativelyEffectFree());
    }

    @Test void sourceDigestCannotBeForgedByRenamingAnEqualProgram() {
        var report = SourceEffectAudit.inspect(SOURCE);
        var edited = SourceEffectAudit.inspect(SOURCE + "\n");
        assertNotEquals(report.sourceSha256(), edited.sourceSha256());
        assertTrue(report.conservativelyEffectFree());
        assertTrue(edited.conservativelyEffectFree());
    }
}
