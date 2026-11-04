package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Provenance publication is atomic with respect to deterministic traversal
 * validation: a rejected expansion must not leave partly annotated syntax.
 */
final class MacroSyntaxProvenanceBudgetTest {
    private static MacroSyntaxProvenance.Origin origin(int ordinal) {
        Ast.SourceSite site = new Ast.SourceSite(1, 1);
        return new MacroSyntaxProvenance.Origin(
                new MacroSyntaxProvenance.HygieneIdentity(
                        "test/unit.ores", "root", "root", "identity", site, ordinal),
                site,
                MacroSyntaxProvenance.EmissionKind.CALL_SITE_SPLICE);
    }

    @Test
    void deepSyntaxFailsWithoutPublishingAnyProvenance() {
        Ast.Expr leaf = new Ast.LiteralExpr(7L);
        Ast.Expr root = leaf;
        for (int i = 0; i < 300; i++) {
            root = new Ast.UnaryExpr("+", root);
        }
        Ast.Expr finalRoot = root;
        IllegalArgumentException denied = assertThrows(
                IllegalArgumentException.class,
                () -> MacroSyntaxProvenance.recordTree(finalRoot, origin(0)));
        assertTrue(denied.getMessage().contains("depth budget"), denied::toString);
        assertTrue(MacroSyntaxProvenance.lookup(finalRoot).isEmpty());
        assertTrue(MacroSyntaxProvenance.lookup(leaf).isEmpty());
    }

    @Test
    void oversizedFlatSyntaxFailsWithoutPublishingRootOrLeaves() {
        List<Ast.Expr> leaves = new ArrayList<>();
        for (int i = 0; i < 65_537; i++) {
            leaves.add(new Ast.LiteralExpr((long) i));
        }
        Ast.ListExpr root = new Ast.ListExpr(leaves);
        IllegalArgumentException denied = assertThrows(
                IllegalArgumentException.class,
                () -> MacroSyntaxProvenance.recordTree(root, origin(1)));
        assertTrue(denied.getMessage().contains("node budget"), denied::toString);
        assertTrue(MacroSyntaxProvenance.lookup(root).isEmpty());
        assertTrue(MacroSyntaxProvenance.lookup(leaves.getFirst()).isEmpty());
        assertTrue(MacroSyntaxProvenance.lookup(leaves.getLast()).isEmpty());
    }

    @Test
    void successfulPublicationPreservesNestedOriginOrder() {
        Ast.Expr leaf = new Ast.LiteralExpr(42L);
        Ast.Expr root = new Ast.BinaryExpr("+", leaf, new Ast.LiteralExpr(1L));
        MacroSyntaxProvenance.Origin inner = origin(2);
        MacroSyntaxProvenance.Origin outer = origin(3);

        MacroSyntaxProvenance.recordTree(leaf, inner);
        MacroSyntaxProvenance.recordTree(root, outer);

        assertEquals(List.of(outer), MacroSyntaxProvenance.lookup(root));
        assertEquals(List.of(inner, outer), MacroSyntaxProvenance.lookup(leaf));
    }

    @Test
    void failedTraversalCannotPoisonLaterSuccessfulExpansion() {
        Ast.Expr huge = new Ast.LiteralExpr(0L);
        for (int i = 0; i < 300; i++) huge = new Ast.UnaryExpr("+", huge);
        Ast.Expr tooDeep = huge;
        assertThrows(IllegalArgumentException.class,
                () -> MacroSyntaxProvenance.recordTree(tooDeep, origin(4)));

        Ast.Expr valid = new Ast.BinaryExpr("+",
                new Ast.LiteralExpr(2L), new Ast.LiteralExpr(3L));
        MacroSyntaxProvenance.recordTree(valid, origin(5));
        assertEquals(List.of(origin(5)), MacroSyntaxProvenance.lookup(valid));
    }
}
