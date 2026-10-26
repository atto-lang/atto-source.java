package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Const<T> metadata must be no less bounded than value evaluation itself. */
final class CompileTimeConstTypeBudgetTest {
    @Test
    void ordinaryClosedTypesAndPrimitiveAliasesContinueToMatch() {
        Ast.TypeRef list = new Ast.TypeRef("List",
                List.of(Ast.TypeRef.simple("int")), false);
        assertTrue(CompileTimeConstEvaluator.typeMatches(list, list));
        assertEquals("List<int>", CompileTimeConstEvaluator.displayType(list));
        assertTrue(CompileTimeConstEvaluator.typeMatches(
                Ast.TypeRef.simple("boolean"), Ast.TypeRef.simple("bool")));
        assertFalse(CompileTimeConstEvaluator.typeMatches(
                Ast.TypeRef.simple("int"), Ast.TypeRef.simple("float")));
    }

    @Test
    void deeplyNestedTypeCannotOverflowJVMStackDuringConstAdmission() {
        Ast.TypeRef deep = Ast.TypeRef.simple("int");
        for (int i = 0; i < 2_000; i++) {
            deep = new Ast.TypeRef("List", List.of(deep), false);
        }
        assertFalse(CompileTimeConstEvaluator.typeMatches(deep, deep));
        String diagnostic = CompileTimeConstEvaluator.displayType(deep);
        assertTrue(diagnostic.contains("..."), diagnostic);
        assertTrue(diagnostic.length() <= 1_027);
    }

    @Test
    void wideTypeCannotAllocateUnboundedComparatorWorklist() {
        ArrayList<Ast.TypeRef> children = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            children.add(Ast.TypeRef.simple("int"));
        }
        Ast.TypeRef wide = new Ast.TypeRef("Tuple", children, false);
        assertFalse(CompileTimeConstEvaluator.typeMatches(wide, wide));
        String diagnostic = CompileTimeConstEvaluator.displayType(wide);
        assertTrue(diagnostic.contains("..."), diagnostic);
        assertTrue(diagnostic.length() <= 1_027);
    }

    @Test
    void diagnosticTruncatesUntrustedLongTypeName() {
        Ast.TypeRef absurd = Ast.TypeRef.simple("A".repeat(8_192));
        String diagnostic = CompileTimeConstEvaluator.displayType(absurd);
        assertTrue(diagnostic.startsWith("A"));
        assertTrue(diagnostic.endsWith("..."));
        assertTrue(diagnostic.length() <= 1_027);
    }

    @Test
    void inferredOrNullTypesNeverMatchConstAdmission() {
        assertFalse(CompileTimeConstEvaluator.typeMatches(
                null, Ast.TypeRef.simple("int")));
        assertFalse(CompileTimeConstEvaluator.typeMatches(
                Ast.TypeRef.simple("int"), null));
        assertFalse(CompileTimeConstEvaluator.typeMatches(
                new Ast.TypeRef("List", List.of(), true),
                new Ast.TypeRef("List", List.of(), true)));
    }
}
