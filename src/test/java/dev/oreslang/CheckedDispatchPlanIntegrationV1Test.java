package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.ast.CheckedDispatchPlansV1;
import dev.oreslang.compiler.AotDispatchPlannerV1;
import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * These assertions operate on the *real checked and macro-expanded guest AST*.
 * They do not claim machine-code/native guest AOT or speedup evidence.
 */
final class CheckedDispatchPlanIntegrationV1Test {
    private static Ast.FunctionDecl function(Ast.Program program, String name) {
        return program.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(decl -> decl instanceof Ast.FunctionDecl fn && fn.name().equals(name))
                .map(decl -> (Ast.FunctionDecl) decl)
                .findFirst().orElseThrow();
    }

    private static Ast.CallExpr returnCall(Ast.FunctionDecl function) {
        Ast.ReturnStmt returned = (Ast.ReturnStmt) function.body().getFirst();
        return (Ast.CallExpr) returned.value();
    }

    @Test
    void typecheckerRecordsDirectAndVirtualCallPlansOnRealGuestSource() throws Exception {
        String source = Files.readString(
                Path.of("contracts/dispatch-v1/virtual_override_121020.atto"),
                StandardCharsets.UTF_8);
        Ast.Program checked = OresCompiler.parseAndTypeCheck(source);
        var snapshot = CheckedDispatchPlansV1.forProgram(checked);
        assertFalse(snapshot.isEmpty(), "type checking must publish a program-local snapshot");
        assertThrows(UnsupportedOperationException.class, snapshot::clear,
                "guest evaluators must not mutate the checked plan image");

        Ast.CallExpr baseCall = returnCall(function(checked, "invoke_base"));
        CheckedDispatchPlansV1.Plan slot = snapshot.get(baseCall);
        assertNotNull(slot, "base-typed call must have a checked slot plan");
        assertSame(slot, snapshot.get(baseCall),
                "published program metadata must preserve exact AST call identity");
        assertEquals(AotDispatchPlannerV1.CallKind.INSTANCE_METHOD, slot.kind());
        assertEquals(AotDispatchPlannerV1.Strategy.METHOD_SLOT, slot.strategy());
        assertEquals("Base", slot.receiverClass().name());
        assertEquals("value", slot.method().name());
        assertEquals(0, slot.arity());

        Ast.FunctionDecl main = function(checked, "main");
        List<Ast.Stmt> statements = main.body();
        // The last two writes contain the statically bound Class.fixed() calls.
        for (int i = 2; i < 4; i++) {
            Ast.CallExpr write = (Ast.CallExpr) ((Ast.ExprStmt) statements.get(i)).expression();
            Ast.CallExpr staticCall = (Ast.CallExpr) write.arguments().getFirst();
            CheckedDispatchPlansV1.Plan direct = snapshot.get(staticCall);
            assertNotNull(direct, "static qualified method must be planned");
            assertEquals(AotDispatchPlannerV1.CallKind.STATIC_METHOD, direct.kind());
            assertEquals(AotDispatchPlannerV1.Strategy.DIRECT_SYMBOL, direct.strategy());
            assertEquals("fixed", direct.method().name());
            assertEquals(0, direct.arity());
        }

        Ast.CallExpr firstWrite = (Ast.CallExpr) ((Ast.ExprStmt) statements.getFirst()).expression();
        Ast.CallExpr firstInvoke = (Ast.CallExpr) firstWrite.arguments().getFirst();
        CheckedDispatchPlansV1.Plan functionPlan = snapshot.get(firstInvoke);
        assertNotNull(functionPlan, "direct user function call must be planned");
        assertEquals(AotDispatchPlannerV1.CallKind.FREE_FUNCTION, functionPlan.kind());
        assertEquals(AotDispatchPlannerV1.Strategy.DIRECT_SYMBOL, functionPlan.strategy());
        assertSame(function(checked, "invoke_base"), functionPlan.function());

        Ast.CallExpr equalButDifferent = new Ast.CallExpr(
                firstInvoke.callee(),
                firstInvoke.typeArguments(),
                firstInvoke.typeArgumentsPresent(),
                firstInvoke.arguments(),
                firstInvoke.origin());
        assertEquals(firstInvoke, equalButDifferent);
        assertNull(snapshot.get(equalButDifferent),
                "structurally equal but different AST nodes must not inherit compiler proofs");
    }

    @Test
    void publicationRejectsWrongCallShapeAndDifferentTargetIdentity() {
        Ast.Program typed = OresCompiler.parseAndTypeCheck(
                "fnc target(int x): int { return x; }");
        Ast.FunctionDecl target = function(typed, "target");
        Ast.CallExpr call = new Ast.CallExpr(
                new Ast.NameExpr("target"), List.of(new Ast.LiteralExpr(1)));
        CheckedDispatchPlansV1.Plan valid = new CheckedDispatchPlansV1.Plan(
                AotDispatchPlannerV1.CallKind.FREE_FUNCTION,
                AotDispatchPlannerV1.Strategy.DIRECT_SYMBOL,
                "target", 1, target, null, null);

        // Publication is only metadata; it must not authorize execution of
        // this deliberately unchecked, standalone Program.
        Ast.Program unchecked = new Ast.Program(List.of());
        assertDoesNotThrow(() -> CheckedDispatchPlansV1.publish(
                unchecked, java.util.Map.of(call, valid)));
        assertSame(valid, CheckedDispatchPlansV1.forProgram(unchecked).get(call));

        assertThrows(IllegalArgumentException.class, () ->
                new CheckedDispatchPlansV1.Plan(
                        AotDispatchPlannerV1.CallKind.FREE_FUNCTION,
                        AotDispatchPlannerV1.Strategy.METHOD_SLOT,
                        "target", 1, target, null, null));
        assertThrows(IllegalArgumentException.class, () ->
                CheckedDispatchPlansV1.publish(
                        new Ast.Program(List.of()),
                        java.util.Map.of(new Ast.CallExpr(
                                new Ast.NameExpr("target"), List.of()), valid)));

        Ast.CallExpr spread = new Ast.CallExpr(new Ast.NameExpr("target"),
                List.of(new Ast.SpreadExpr(new Ast.NameExpr("args"))));
        assertThrows(IllegalArgumentException.class, () ->
                CheckedDispatchPlansV1.publish(
                        new Ast.Program(List.of()), java.util.Map.of(spread, valid)));

        Ast.CallExpr wrongName = new Ast.CallExpr(new Ast.NameExpr("other"),
                List.of(new Ast.LiteralExpr(1)));
        assertThrows(IllegalArgumentException.class, () ->
                CheckedDispatchPlansV1.publish(
                        new Ast.Program(List.of()), java.util.Map.of(wrongName, valid)));

        Ast.FunctionDecl equallyShapedButDifferentTarget = function(
                OresCompiler.parseAndTypeCheck(
                        "fnc target(int x): int { return x; }"), "target");
        assertNotSame(target, equallyShapedButDifferentTarget);
        CheckedDispatchPlansV1.Plan conflicting = new CheckedDispatchPlansV1.Plan(
                AotDispatchPlannerV1.CallKind.FREE_FUNCTION,
                AotDispatchPlannerV1.Strategy.DIRECT_SYMBOL,
                "target", 1, equallyShapedButDifferentTarget, null, null);
        assertThrows(IllegalStateException.class, () ->
                CheckedDispatchPlansV1.publish(
                        unchecked, java.util.Map.of(call, conflicting)),
                "rechecking the same Program must not silently replace its resolved targets");
    }

    @Test
    void unresolvedGenericSpecializationsCannotBeMisreportedAsDirectAotSymbols() {
        // A resolved generic source declaration is not a unique *emitted*
        // symbol until the post-macro, post-ownership specialization graph
        // has been closed and compiled. Keep interpreter semantics unchanged.
        Ast.Program genericFunction = OresCompiler.parseAndTypeCheck("""
                define module app
                  fnc identity<T>(T value): T { return value; }
                  fnc read(): int { return identity(42); }
                end
                """);
        Ast.CallExpr call = returnCall(function(genericFunction, "read"));
        assertNull(CheckedDispatchPlansV1.forProgram(genericFunction).get(call),
                "generic callable without emitted specialization must not claim DIRECT");

        Ast.Program genericMethod = OresCompiler.parseAndTypeCheck("""
                define module app
                  define class Box as
                    pub map<T>(T value): T { return value; }
                  end
                  fnc read(Box box): int { return box.map(7); }
                end
                """);
        Ast.CallExpr methodCall = returnCall(function(genericMethod, "read"));
        assertNull(CheckedDispatchPlansV1.forProgram(genericMethod).get(methodCall),
                "unmonomorphized method must not claim a checked static symbol");
    }

    @Test
    void repeatedExplicitScalarGenericCallsNeverInheritUncheckedAotProofs() throws Exception {
        Ast.Program checked = OresCompiler.parseAndTypeCheck(Files.readString(
                Path.of("tests/fixtures/dispatch/generic_i64_reuse_v1.atto"),
                StandardCharsets.UTF_8));
        var plans = CheckedDispatchPlansV1.forProgram(checked);
        Ast.FunctionDecl main = function(checked, "main");
        assertEquals(2, main.body().size());
        for (Ast.Stmt statement : main.body()) {
            Ast.CallExpr write = (Ast.CallExpr) ((Ast.ExprStmt) statement).expression();
            Ast.CallExpr generic = (Ast.CallExpr) write.arguments().getFirst();
            assertTrue(generic.typeArgumentsPresent());
            assertEquals("int", generic.typeArguments().getFirst().name());
            assertNull(plans.get(generic),
                    "source-level generic target is not an emitted AOT specialization");
        }
    }
}
