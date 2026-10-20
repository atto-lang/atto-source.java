package dev.oreslang;

import dev.oreslang.ast.Ast;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class CheckedClassViewMetadataTest {
    @Test
    void metadataIsIdentityBoundAndRejectsConflictingViews() {
        Ast.NameExpr first = new Ast.NameExpr("value");
        Ast.NameExpr structurallyEqual = new Ast.NameExpr("value");

        Ast.CheckedClassView.record(first, "Parent");

        assertEquals("Parent", Ast.CheckedClassView.lookup(first));
        assertNull(Ast.CheckedClassView.lookup(structurallyEqual));

        Ast.CheckedClassView.record(first, "Parent");
        assertThrows(
                IllegalStateException.class,
                () -> Ast.CheckedClassView.record(first, "Child"));

        Ast.RuntimeCallExpr rewritten =
                new Ast.RuntimeCallExpr("borrow", java.util.List.of(structurallyEqual));
        Ast.CheckedClassView.copy(first, rewritten);
        assertEquals("Parent", Ast.CheckedClassView.lookup(rewritten));
        assertNull(Ast.CheckedClassView.lookup(structurallyEqual));
    }
}
