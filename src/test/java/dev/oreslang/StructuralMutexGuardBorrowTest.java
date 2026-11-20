package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A structural aggregate stores its member types outside TypeRef.arguments().
 * A guard nested in any member must not bypass escape or suspension checks.
 */
final class StructuralMutexGuardBorrowTest {
    private static boolean detects(String methodName, Ast.TypeRef type) throws Exception {
        Method method = OwnershipChecker.class.getDeclaredMethod(
                methodName, Ast.TypeRef.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(null, type);
    }

    @Test
    void structuralFieldsNeverLaunderLexicalMutexGuards() throws Exception {
        Ast.TypeRef integer = Ast.TypeRef.simple("int");
        Ast.TypeRef guard = new Ast.TypeRef(
                "MutexGuard", List.of(integer), false);
        Ast.TypeRef optionGuard = new Ast.TypeRef(
                "Option", List.of(guard), false);

        for (Ast.TypeRef aggregate : List.of(
                Ast.TypeRef.structType(Map.of("guard", guard)),
                Ast.TypeRef.readonlyRecordType(Map.of("inner", optionGuard)),
                Ast.TypeRef.structType(Map.of(
                        "deep", Ast.TypeRef.tupleType(List.of(integer, optionGuard)))))) {
            assertTrue(detects("containsMutexGuardType", aggregate),
                    "nested guard cannot escape in a structural value");
            assertTrue(detects("containsAcquiredMutexGuardType", aggregate),
                    "nested acquired guard must prohibit suspension");
        }
    }

    @Test
    void ownedStructuralDataAndUnacquiredFuturesStayDistinct() throws Exception {
        Ast.TypeRef integer = Ast.TypeRef.simple("int");
        Ast.TypeRef safe = Ast.TypeRef.readonlyRecordType(
                Map.of("value", integer));
        assertFalse(detects("containsMutexGuardType", safe));
        assertFalse(detects("containsAcquiredMutexGuardType", safe));

        Ast.TypeRef guard = new Ast.TypeRef(
                "MutexGuard", List.of(integer), false);
        Ast.TypeRef pendingGuard = Ast.TypeRef.structType(Map.of(
                "pending", new Ast.TypeRef(
                        "Future", List.of(guard), false)));
        assertTrue(detects("containsMutexGuardType", pendingGuard),
                "an aggregate cannot transport a Future of a guard");
        assertFalse(detects("containsAcquiredMutexGuardType", pendingGuard),
                "an unawaited Future is not an acquired guard");
    }
}
