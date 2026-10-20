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
    @org.junit.jupiter.api.Timeout(5)
    void nominalCallbacksShareOneGlobalSendProofBudget() throws Exception {
        TypeChecker checker = checker();

        java.util.ArrayList<Ast.FieldDecl> fields = new java.util.ArrayList<>();
        for (int i = 0; i < 400; i++) {
            fields.add(new Ast.FieldDecl(
                    "f" + i,
                    Ast.Visibility.PUBLIC,
                    Ast.BindingKind.CONST,
                    Ast.TypeRef.simple("int"),
                    List.of(),
                    null,
                    false));
        }
        Ast.ClassDecl wideNominal = new Ast.ClassDecl(
                "WideNominal",
                false,
                Ast.ActorKind.NONE,
                List.of(),
                List.of(),
                List.of(),
                fields,
                List.of());
        installClass(checker, wideNominal);

        java.util.ArrayList<Types.Type> members = new java.util.ArrayList<>();
        for (int i = 0; i < 300; i++) {
            // Distinct type nodes defeat identity memoization, forcing each
            // nominal callback to consume from the same compiler-work budget.
            members.add(new Types.Named("WideNominal", List.of()));
        }
        Types.Tuple wide = new Types.Tuple(members);

        assertFalse(sharedSafe(checker, wide),
                "canonical shared-safety traversal must exhaust its global budget");
        assertFalse(ownedSend(checker, wide),
                "owned Send must not restart a fresh shared-safety budget per nominal");
        assertTrue(ownedSend(checker, Types.Primitive.INT),
                "budget exhaustion must be query-local and not poison later checks");
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
    void opaqueImportedValueCannotBypassDetachedTaskCaptureAuthority() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                    import fnc remote from './dep';
                    pub fnc main(): void {
                        do nb { remote(); };
                        return;
                    }
                    """)));
        assertTrue(failure.getMessage().contains("opaque imported runtime value"),
                failure::getMessage);
    }

    @Test
    void opaqueImportedValueCannotBypassScheduledLambdaCaptureAuthority() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                    import fnc remote from './dep';
                    pub fnc main(): void {
                        let Task<int> result =
                            (async task |int value| -> {
                                remote();
                                return value;
                            })(1);
                        return;
                    }
                    """)));
        assertTrue(failure.getMessage().contains("opaque imported runtime value"),
                failure::getMessage);
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
