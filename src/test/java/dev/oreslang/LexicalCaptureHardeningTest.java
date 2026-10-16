package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Fail-closed regression tests for capture-by-use and lex indexing ambiguity. */
final class LexicalCaptureHardeningTest {
    private static Ast.Expr expression(String value) {
        Ast.Program ast = Parser.parse(
                "fnc f(): void { val result = " + value + "; return; }");
        Ast.FunctionDecl fn = (Ast.FunctionDecl) ast.modules().getFirst()
                .declarations().getFirst();
        return ((Ast.BindingStmt) fn.body().getFirst()).initializer();
    }

    @Test
    void lexCaptureIsRecognizedBeforeGenericIdentifiers() {
        Ast.LambdaExpr allowed = assertInstanceOf(
                Ast.LambdaExpr.class, expression("lex[alpha] |int n| -> n + alpha"));
        assertEquals(Ast.LambdaCapturePolicy.ALLOW, allowed.capturePolicy());
        assertEquals(Set.of("alpha"), Set.copyOf(allowed.captureNames()));

        Ast.LambdaExpr denied = assertInstanceOf(
                Ast.LambdaExpr.class, expression("nlex[alpha] |int n| -> n + beta"));
        assertEquals(Ast.LambdaCapturePolicy.DENY, denied.capturePolicy());
    }

    @Test
    void contextualLexDoesNotBreakOrdinaryIndexing() {
        assertInstanceOf(Ast.IndexExpr.class, expression("lex[0]"));
        assertInstanceOf(Ast.IndexExpr.class, expression("lex[index]"));
        assertInstanceOf(Ast.NameExpr.class, expression("lex"));
    }

    @Test
    void lexicalFreeVariablesExcludeParametersLocalsAndUnusedValues() {
        Ast.LambdaExpr lambda = (Ast.LambdaExpr) expression(
                "lex[used] |int parameter| -> { val int local = parameter; return local + used; }");
        assertEquals(Set.of("used"), OwnershipChecker.referencedOuterBindings(
                lambda, Set.of("used", "unused", "self", "parameter", "local")));
    }

    @Test
    void denylistCanStillCaptureOtherReferencedOuterNames() {
        Ast.LambdaExpr lambda = (Ast.LambdaExpr) expression(
                "nlex[self, hidden] |int n| -> n + kept");
        assertEquals(Set.of("kept"), OwnershipChecker.referencedOuterBindings(
                lambda, Set.of("self", "hidden", "kept", "unused")));
    }

    @Test
    void nestedClosuresOnlyContributePermittedTransitiveCaptures() {
        Ast.LambdaExpr lambda = (Ast.LambdaExpr) expression(
                "nlex[secret] || -> { val next = lex[other] || -> other; return next(); }");
        assertEquals(Set.of("other"), OwnershipChecker.referencedOuterBindings(
                lambda, Set.of("secret", "other", "self", "unused")));
    }

    @Test
    void runtimeCaptureEnvironmentRetainsOnlyUsedSlots() throws Exception {
        Class<?> envClass = Class.forName("dev.oreslang.nodes.OresEvalRootNode$Env");
        Constructor<?> constructor = envClass.getDeclaredConstructor(envClass);
        constructor.setAccessible(true);
        Method define = envClass.getDeclaredMethod(
                "define", String.class, Object.class, Ast.BindingKind.class);
        define.setAccessible(true);
        Method snapshot = envClass.getDeclaredMethod("snapshotForLambda", Ast.LambdaExpr.class);
        snapshot.setAccessible(true);
        Field slotsField = envClass.getDeclaredField("slots");
        slotsField.setAccessible(true);
        Field parentField = envClass.getDeclaredField("parent");
        parentField.setAccessible(true);

        Object root = constructor.newInstance(new Object[] {null});
        define.invoke(root, "self", new Object(), Ast.BindingKind.VAL);
        define.invoke(root, "unused", new Object(), Ast.BindingKind.VAL);
        Object local = constructor.newInstance(root);
        define.invoke(local, "used", 42L, Ast.BindingKind.VAL);
        define.invoke(local, "hidden", 7L, Ast.BindingKind.VAL);

        Ast.LambdaExpr lambda = (Ast.LambdaExpr) expression(
                "nlex[self, hidden] |int n| -> n + used");
        Object captured = snapshot.invoke(local, lambda);
        @SuppressWarnings("unchecked")
        Map<String, ?> slots = (Map<String, ?>) slotsField.get(captured);
        assertEquals(Set.of("used"), slots.keySet());
        assertNull(parentField.get(captured), "capture must not retain enclosing frame");
        assertNotNull(((Map<?, ?>) slotsField.get(root)).get("self"),
                "creating a closure must not mutate the source scope");
    }
}
