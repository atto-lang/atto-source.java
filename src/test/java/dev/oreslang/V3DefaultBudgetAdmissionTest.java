package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.types.V3DefaultArgValsPolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Defend the parser-independent v3 preview from hostile linked/generated ASTs. */
final class V3DefaultBudgetAdmissionTest {
    private static Ast.Param formal(Ast.TypeRef type, String name) {
        return new Ast.Param(type, name);
    }

    @Test
    void deepGenericTypeFailsBeforeRecursiveOptionSugarProjection() {
        Ast.TypeRef nested = Ast.TypeRef.simple("int");
        for (int i = 0; i < 70; i++) {
            nested = new Ast.TypeRef("Box", List.of(nested), false);
        }
        Ast.TypeRef deep = nested;
        var error = assertThrows(IllegalArgumentException.class,
                () -> V3DefaultArgValsPolicy.validateDeclaration(
                        List.of(formal(deep, "payload")), List.of()));
        assertTrue(error.getMessage().contains("budget"), error.getMessage());
    }

    @Test
    void overlyWideGenericSignatureFailsWithBoundedWork() {
        Ast.TypeRef leaf = Ast.TypeRef.simple("int");
        var children = new ArrayList<Ast.TypeRef>();
        for (int i = 0; i < 8200; i++) children.add(leaf);
        Ast.TypeRef wide = new Ast.TypeRef("Many", children, false);
        assertThrows(IllegalArgumentException.class,
                () -> V3DefaultArgValsPolicy.validateDeclaration(
                        List.of(formal(wide, "payload")), List.of()));
    }

    @Test
    void tooManyUniqueFormalsFailBeforeBulkPolicyAllocation() {
        var parameters = new ArrayList<Ast.Param>();
        for (int i = 0; i < 1025; i++) {
            parameters.add(formal(Ast.TypeRef.simple("int"), "p" + i));
        }
        var error = assertThrows(IllegalArgumentException.class,
                () -> V3DefaultArgValsPolicy.validateDeclaration(parameters, List.of()));
        assertTrue(error.getMessage().contains("budget"), error.getMessage());
    }

    @Test
    void zeroArgAndNormalSignaturesStillPassStageValidation() {
        assertTrue(V3DefaultArgValsPolicy.validateDeclaration(List.of(), List.of()).isEmpty());
        var checked = V3DefaultArgValsPolicy.validateDeclaration(
                List.of(formal(Ast.TypeRef.simple("int"), "x"),
                        formal(Ast.TypeRef.simple("bool"), "ok")),
                List.of());
        assertEquals(List.of("x", "ok"), checked.stream()
                .map(V3DefaultArgValsPolicy.FormalPreview::name).toList());
    }

    @Test
    void nestedBorrowIsRejectedEvenWhenDeeplyInsideOption() {
        Ast.TypeRef nested = new Ast.TypeRef("Option", List.of(
                new Ast.TypeRef("List", List.of(
                        Ast.TypeRef.borrowed(Ast.TypeRef.simple("int"), false)), false)), false);
        Ast.Param omittable = new Ast.Param(nested, "payload", false, false,
                List.of(), true);
        assertThrows(IllegalArgumentException.class,
                () -> V3DefaultArgValsPolicy.validateDeclaration(
                        List.of(omittable), List.of()));
    }
}
