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
        Object captures = collect.invoke(checker, lambda, scope, null);
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
    void shortCircuitRefinementNameIsNotAnOuterCapture() throws Exception {
        Ast.Expr condition = new Ast.BinaryExpr("&&",
                new Ast.TypeTestExpr(new Ast.NameExpr("subject"),
                        Ast.TypeRef.simple("int"), "shadow"),
                new Ast.NameExpr("shadow"));
        Ast.LambdaExpr lambda = new Ast.LambdaExpr(List.of(), condition, null);
        Set<String> expected = Set.of("subject");
        assertEquals(expected, capturedByOwnership(lambda, "subject", "shadow", "self"));
        assertEquals(expected, dev.oreslang.nodes.LambdaCapturePlanner.captures(lambda));
    }

    @Test
    void conditionalTrueArmUsesItsRefinementNotOuterAlias() throws Exception {
        Ast.Expr conditional = new Ast.ConditionalExpr(
                new Ast.TypeTestExpr(new Ast.NameExpr("subject"),
                        Ast.TypeRef.simple("int"), "shadow"),
                new Ast.NameExpr("shadow"), new Ast.NameExpr("fallback"));
        Ast.LambdaExpr lambda = new Ast.LambdaExpr(List.of(), conditional, null);
        Set<String> expected = Set.of("subject", "fallback");
        assertEquals(expected, capturedByOwnership(
                lambda, "subject", "shadow", "fallback", "self"));
        assertEquals(expected, dev.oreslang.nodes.LambdaCapturePlanner.captures(lambda));
    }

    @Test
    void deterministicNestedAndRefinedCaptureParityMatrix() throws Exception {
        java.util.Random random = new java.util.Random(0x5eeda11L);
        for (int i = 0; i < 400; i++) {
            String outerName = "owned" + random.nextInt(100);
            Ast.Expr body;
            Set<String> expected;
            switch (i % 4) {
                case 0 -> {
                    body = new Ast.NameExpr(outerName);
                    expected = Set.of(outerName);
                }
                case 1 -> {
                    body = new Ast.MatchExpr(new Ast.NameExpr("subject"),
                            List.of(new Ast.MatchValueArm(
                                    new Ast.BindingPattern("armBound"),
                                    new Ast.NameExpr("guard"),
                                    new Ast.BinaryExpr("+", new Ast.NameExpr("armBound"),
                                            new Ast.NameExpr(outerName)))));
                    expected = Set.of("subject", "guard", outerName);
                }
                case 2 -> {
                    body = new Ast.BinaryExpr("&&",
                            new Ast.TypeTestExpr(new Ast.NameExpr("subject"),
                                    Ast.TypeRef.simple("int"), "armBound"),
                            new Ast.NameExpr("armBound"));
                    expected = Set.of("subject");
                }
                default -> {
                    body = new Ast.LambdaExpr(
                            List.of(new Ast.Param(Ast.TypeRef.simple("int"), "parameter")),
                            new Ast.BinaryExpr("+", new Ast.NameExpr("parameter"),
                                    new Ast.NameExpr(outerName)), null);
                    expected = Set.of(outerName);
                }
            }
            Ast.LambdaExpr lambda = new Ast.LambdaExpr(List.of(), body, null);
            assertEquals(expected, capturedByOwnership(lambda,
                    outerName, "subject", "guard", "armBound", "self"), "ownership " + i);
            assertEquals(expected, dev.oreslang.nodes.LambdaCapturePlanner.captures(lambda),
                    "runtime capture " + i);
        }
    }

    @Test
    void classicForInitializerBindingShadowsOuterNameAcrossHeaderAndBody() throws Exception {
        Ast.BindingStmt initializer = new Ast.BindingStmt(
                Ast.BindingKind.LET, Ast.TypeRef.simple("int"), "loop",
                new Ast.NameExpr("source"));
        Ast.ForStmt loop = new Ast.ForStmt(
                initializer,
                new Ast.BinaryExpr("<", new Ast.NameExpr("loop"), new Ast.LiteralExpr(3)),
                new Ast.AssignExpr(new Ast.NameExpr("loop"),
                        new Ast.BinaryExpr("+", new Ast.NameExpr("loop"), new Ast.LiteralExpr(1))),
                List.of(new Ast.ExprStmt(new Ast.NameExpr("loop"))));
        Ast.LambdaExpr lambda = new Ast.LambdaExpr(
                List.of(), null, List.of(loop, new Ast.ReturnStmt(new Ast.NameExpr("tail"))));
        Set<String> expected = Set.of("source", "tail");
        assertEquals(expected, capturedByOwnership(
                lambda, "source", "tail", "loop", "self"));
        assertEquals(expected, dev.oreslang.nodes.LambdaCapturePlanner.captures(lambda));
    }

    @Test
    void matchArmBorrowedValueCannotEscapeThroughClosure() throws Exception {
        Ast.LambdaExpr lambda = new Ast.LambdaExpr(List.of(),
                new Ast.MatchExpr(new Ast.NameExpr("subject"),
                        List.of(new Ast.MatchValueArm(
                                new Ast.WildcardPattern(), null,
                                new Ast.NameExpr("loan")))), null);

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
        Method define = scopeType.getDeclaredMethod("define", String.class, stateType);
        stateFor.setAccessible(true);
        define.setAccessible(true);
        define.invoke(scope, "subject", stateFor.invoke(checker,
                new Ast.Param(Ast.TypeRef.simple("int"), "subject")));
        define.invoke(scope, "loan", stateFor.invoke(checker,
                new Ast.Param(Ast.TypeRef.borrowed(Ast.TypeRef.simple("int"), false), "loan")));

        Method check = cls.getDeclaredMethod("checkLambda", Ast.LambdaExpr.class,
                scopeType, String.class, boolean.class, boolean.class);
        check.setAccessible(true);
        java.lang.reflect.InvocationTargetException caught = assertThrows(
                java.lang.reflect.InvocationTargetException.class,
                () -> check.invoke(checker, lambda, scope, null, false, false));
        assertInstanceOf(IllegalArgumentException.class, caught.getCause());
        assertTrue(caught.getCause().getMessage().contains("closure cannot capture borrowed value"),
                "match arm closure must honor the same loan exclusion as other expressions");
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
