package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Tests the real ownership scanner against the runtime planner on one AST. */
final class OwnershipMatchExpressionCaptureTest {
    private static Set<String> capturedByOwnership(Ast.LambdaExpr lambda,
                                                    String... outerNames) throws Exception {
        Class<?> cls = OwnershipChecker.class;
        Constructor<?> make = cls.getDeclaredConstructor(Ast.Program.class);
        make.setAccessible(true);
        Object checker = make.newInstance(new Ast.Program(List.of()));

        Class<?> scopeType = Class.forName("dev.oreslang.types.OwnershipChecker$Scope");
        Class<?> stateType = Class.forName("dev.oreslang.types.OwnershipChecker$VarState");
        Constructor<?> scopeCtor = scopeType.getDeclaredConstructor(scopeType, boolean.class);
        scopeCtor.setAccessible(true);
        Object scope = scopeCtor.newInstance(null, false);

        Method stateFor = cls.getDeclaredMethod("stateForParam", Ast.Param.class);
        stateFor.setAccessible(true);
        Method define = scopeType.getDeclaredMethod("define", String.class, stateType);
        define.setAccessible(true);
        for (String name : outerNames) {
            Ast.Param param = new Ast.Param(Ast.TypeRef.simple("int"), name);
            define.invoke(scope, name, stateFor.invoke(checker, param));
        }

        Method collect = cls.getDeclaredMethod(
                "collectCaptures", Ast.LambdaExpr.class, scopeType, String.class);
        collect.setAccessible(true);
        Object captures = collect.invoke(checker, lambda, null);
        Field values = captures.getClass().getDeclaredField("values");
        values.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, ?> captured = (Map<String, ?>) values.get(captures);
        return Set.copyOf(captured.keySet());
    }

    @Test
    void expressionMatchCapturesSubjectAndGuardButNotPatternLocal() throws Exception {
        Ast.MatchExpr matched = new Ast.MatchExpr(
                new Ast.NameExpr("subject"),
                List.of(new Ast.MatchValueArm(new Ast.BindingPattern("patternLocal"),
                        new Ast.NameExpr("guard"),
                        new Ast.BinaryExpr("+",
                                new Ast.NameExpr("patternLocal"),
                                new Ast.NameExpr("value")))));
        Ast.LambdaExpr lambda = new Ast.LambdaExpr(List.of(), matched, null);
        Set<String> expected = Set.of("subject", "guard", "value");
        assertEquals(expected, capturedByOwnership(
                lambda, "subject", "guard", "value", "patternLocal", "self"));
        assertEquals(expected,
                dev.oreslang.nodes.LambdaCapturePlanner.captures(lambda));
    }

    @Test
    void nestedExpressionMatchCapturesThroughNestedLambda() throws Exception {
        Ast.LambdaExpr nested = new Ast.LambdaExpr(List.of(),
                new Ast.MatchExpr(new Ast.NameExpr("subject"),
                        List.of(new Ast.MatchValueArm(new Ast.WildcardPattern(), null,
                                new Ast.NameExpr("outer")))), null);
        Ast.LambdaExpr parent = new Ast.LambdaExpr(List.of(), nested, null);
        Set<String> expected = Set.of("subject", "outer");
        assertEquals(expected, capturedByOwnership(parent, "subject", "outer", "self"));
        assertEquals(expected, dev.oreslang.nodes.LambdaCapturePlanner.captures(parent));
    }
}
