package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.Types;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ResolvedTaskSendAuthorityTest {
    private static TypeChecker checker() throws Exception {
        var ctor = TypeChecker.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    @SuppressWarnings("unchecked")
    private static void installClass(
            TypeChecker checker, Ast.ClassDecl declaration) throws Exception {
        Field classes = TypeChecker.class.getDeclaredField("classes");
        classes.setAccessible(true);
        ((Map<String, Ast.ClassDecl>) classes.get(checker))
                .put(declaration.name(), declaration);
    }

    private static boolean sharedSafe(
            TypeChecker checker, Types.Type type) throws Exception {
        Method method = TypeChecker.class.getDeclaredMethod(
                "isSharedSafe",
                Types.Type.class,
                java.util.Set.class,
                java.util.Map.class);
        method.setAccessible(true);
        return (boolean) method.invoke(
                checker, type, new LinkedHashSet<>(), Map.of());
    }

    private static boolean ownedSend(
            TypeChecker checker, Types.Type type) throws Exception {
        Method method = TypeChecker.class.getDeclaredMethod(
                "isThreadSend", Types.Type.class);
        method.setAccessible(true);
        return (boolean) method.invoke(checker, type);
    }

    @Test
    void recursiveNominalStorageCannotBePositiveFixedPoint() throws Exception {
        TypeChecker checker = checker();
        Ast.FieldDecl next = new Ast.FieldDecl(
                "next",
                Ast.Visibility.PUBLIC,
                Ast.BindingKind.CONST,
                Ast.TypeRef.simple("Node"),
                List.of(),
                null,
                false);
        Ast.ClassDecl node = new Ast.ClassDecl(
                "Node",
                false,
                Ast.ActorKind.NONE,
                List.of(),
                List.of(),
                List.of(),
                List.of(next),
                List.of());
        installClass(checker, node);

        Types.Named nodeType = new Types.Named("Node", List.of());
        assertFalse(sharedSafe(checker, nodeType));
        assertFalse(ownedSend(checker, nodeType),
                "recursive nominal storage must not acquire detached Send");
    }

    @Test
    @org.junit.jupiter.api.Timeout(5)
    void sharedSafetyProofBudgetFailsClosedAndDoesNotPoisonLaterQuery()
            throws Exception {
        TypeChecker checker = checker();
        Types.Type wide = new Types.Tuple(
                java.util.Collections.nCopies(
                        100_001, Types.Primitive.INT));

        assertFalse(sharedSafe(checker, wide));
        assertFalse(ownedSend(checker, wide));
        assertTrue(sharedSafe(checker, Types.Primitive.INT));
        assertTrue(ownedSend(checker, Types.Primitive.INT));
    }

    @Test
    void readonlyRecordViewCannotCrossDetachedTaskBoundary() throws Exception {
        TypeChecker checker = checker();
        Types.Record readonly = new Types.Record(
                Map.of("value", Types.Primitive.INT), true);

        assertFalse(sharedSafe(checker, readonly));
        assertFalse(ownedSend(checker, readonly));
    }

    @Test
    void compilerInternalUnwrapErrorIsNotSourceNameable() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                    pub fnc fake(OptionUnwrapError value): int {
                        return 1;
                    }
                    """)));
        assertTrue(failure.getMessage().contains("compiler-internal"),
                failure::getMessage);
    }
}
