package dev.oreslang.ast;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class CheckedMetadataTransferInvariantTest {
    @Test
    void transferPolicyCoversEveryCheckedMetadataSideTable() {
        Set<String> declaredTables = Arrays.stream(Ast.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().startsWith("Checked"))
                .filter(type -> Arrays.stream(type.getDeclaredMethods())
                        .anyMatch(method -> method.getName().equals("copy")
                                && Modifier.isStatic(method.getModifiers())))
                .map(Class::getSimpleName)
                .collect(Collectors.toUnmodifiableSet());

        assertEquals(
                java.util.stream.Stream.concat(
                        declaredTables.stream(), java.util.stream.Stream.of("CheckedCallPlan"))
                        .collect(Collectors.toUnmodifiableSet()),
                Ast.checkedMetadataTransferPolicy(),
                "every identity-bound Checked* side table with copy() needs an explicit transfer policy");
    }

    @Test
    void centralTransferCopiesTheCompleteCompatibleProofBundle() {
        Ast.CallExpr source = unwrapCall("source");
        Ast.CallExpr target = unwrapCall("target");

        Ast.CheckedOptionDepth.record(source, 3);
        Ast.CheckedClassView.record(source, "Parent");
        Ast.CheckedStructuralFieldDomain.record(source, List.of("x", "y"));

        Ast.copyCheckedMetadata(source, target);

        assertEquals(3, Ast.CheckedOptionDepth.require(target));
        assertEquals("Parent", Ast.CheckedClassView.lookup(target));
        assertEquals(Set.of("x", "y"), Ast.CheckedStructuralFieldDomain.lookup(target));
    }

    @Test
    void centralTransferDoesNotCreateProofsFromAnUncheckedSource() {
        Ast.CallExpr source = unwrapCall("source");
        Ast.CallExpr target = unwrapCall("target");

        Ast.copyCheckedMetadata(source, target);

        assertThrows(IllegalStateException.class, () -> Ast.CheckedOptionDepth.require(target));
        assertNull(Ast.CheckedClassView.lookup(target));
        assertNull(Ast.CheckedStructuralFieldDomain.lookup(target));
    }

    @Test
    void incompatibleTargetShapeDropsCallOnlyProofButPreservesExpressionProofs() {
        Ast.CallExpr source = unwrapCall("source");
        Ast.NameExpr target = new Ast.NameExpr("target");

        Ast.CheckedOptionDepth.record(source, 2);
        Ast.CheckedClassView.record(source, "Parent");
        Ast.CheckedStructuralFieldDomain.record(source, List.of("x"));

        Ast.copyCheckedMetadata(source, target);

        assertEquals("Parent", Ast.CheckedClassView.lookup(target));
        assertEquals(Set.of("x"), Ast.CheckedStructuralFieldDomain.lookup(target));
    }

    private static Ast.CallExpr unwrapCall(String receiver) {
        return new Ast.CallExpr(
                new Ast.MemberExpr(new Ast.NameExpr(receiver), "unwrap_all"),
                List.of());
    }
}
