package dev.oreslang.parser;

import dev.oreslang.ast.Ast;
import java.util.List;

/** Dependency-free parser regression check; does not claim runtime lifting. */
public final class FuturePipelineProvenanceCheck {
    private FuturePipelineProvenanceCheck() { }

    public static void main(String[] args) {
        directCallsStayDirect();
        chainedStagesKeepSeparateOrigins();
        explicitTypeArgumentsAndArgumentOrderSurvive();
        sumCombinatorsKeepReceiverOrigins();
        actorPipeStaysDistinct();
        typedLambdaStageKeepsOrigin();
        trapAwaitOrderSurvives();
        stageLimitStillApplies();
        malformedOriginsAreRejected();
        System.out.println("9 pipeline provenance checks passed (parser/AST only).");
    }

    static void directCallsStayDirect() {
        Ast.CallExpr direct = call("save(source(), argument())");
        require(direct.origin() == Ast.CallOrigin.DIRECT, "ordinary call marked as pipeline");
        require(((Ast.CallExpr) direct.arguments().get(0)).origin() == Ast.CallOrigin.DIRECT,
                "ordinary Future producer marked as pipeline");
    }

    static void chainedStagesKeepSeparateOrigins() {
        Ast.CallExpr outer = call("source() |> validate |> save(argument())");
        require(outer.origin() == Ast.CallOrigin.VALUE_PIPE_ARGUMENT, "outer origin lost");
        Ast.CallExpr inner = (Ast.CallExpr) outer.arguments().get(0);
        require(inner.origin() == Ast.CallOrigin.VALUE_PIPE_ARGUMENT, "inner origin lost");
        require(((Ast.CallExpr) inner.arguments().get(0)).origin() == Ast.CallOrigin.DIRECT,
                "producer origin changed");
        require(((Ast.CallExpr) outer.arguments().get(1)).origin() == Ast.CallOrigin.DIRECT,
                "extra argument origin changed");
        require(!call("source() |> save()").equals(call("save(source())")),
                "pipeline and direct call became structurally indistinguishable");
    }

    static void explicitTypeArgumentsAndArgumentOrderSurvive() {
        Ast.CallExpr pipe = call("source() |> save<int>(argument())");
        require(pipe.origin() == Ast.CallOrigin.VALUE_PIPE_ARGUMENT, "generic stage origin lost");
        require(pipe.typeArgumentsPresent() && pipe.typeArguments().size() == 1,
                "explicit generic arguments lost");
        require(pipe.typeArguments().get(0).name().equals("int"), "generic type changed");
        require(name((Ast.CallExpr) pipe.arguments().get(0)).equals("source"), "input moved");
        require(name((Ast.CallExpr) pipe.arguments().get(1)).equals("argument"), "argument moved");
    }

    static void sumCombinatorsKeepReceiverOrigins() {
        Ast.CallExpr outer = call("input |> Result.map(convert) |> Option.unwrap_or(0)");
        require(outer.origin() == Ast.CallOrigin.VALUE_PIPE_RECEIVER, "outer receiver origin lost");
        Ast.CallExpr inner = (Ast.CallExpr) ((Ast.MemberExpr) outer.callee()).receiver();
        require(inner.origin() == Ast.CallOrigin.VALUE_PIPE_RECEIVER, "inner receiver origin lost");
        require(((Ast.MemberExpr) inner.callee()).receiver().equals(new Ast.NameExpr("input")),
                "sum input changed");
        require(call("input.map(convert)").origin() == Ast.CallOrigin.DIRECT,
                "ordinary receiver call marked as namespace pipeline");
    }

    static void actorPipeStaysDistinct() {
        Ast.Expr expression = expression("left pipe right");
        require(expression instanceof Ast.BinaryExpr binary && binary.operator().equals("pipe"),
                "actor pipe confused with value pipeline");
    }

    static void typedLambdaStageKeepsOrigin() {
        Ast.CallExpr pipe = call("7 |> (|int n| -> n + 1)");
        require(pipe.origin() == Ast.CallOrigin.VALUE_PIPE_ARGUMENT
                && pipe.callee() instanceof Ast.LambdaExpr, "lambda stage origin lost");
    }

    static void trapAwaitOrderSurvives() {
        Ast.TrapExpr trapped = (Ast.TrapExpr) expression("trap await (source() |> save())");
        Ast.AwaitExpr awaited = (Ast.AwaitExpr) trapped.expression();
        require(((Ast.CallExpr) awaited.expression()).origin() == Ast.CallOrigin.VALUE_PIPE_ARGUMENT,
                "pipeline origin lost under trap await");
        Ast.AwaitExpr reverse = (Ast.AwaitExpr) expression("await trap (source() |> save())");
        require(reverse.expression() instanceof Ast.TrapExpr, "await trap was reordered");
    }

    static void stageLimitStillApplies() {
        Ast.Expr accepted = expression("1" + " |> step".repeat(128));
        int stages = 0;
        while (accepted instanceof Ast.CallExpr call) {
            require(call.origin() == Ast.CallOrigin.VALUE_PIPE_ARGUMENT, "bounded stage origin lost");
            stages++;
            accepted = call.arguments().get(0);
        }
        require(stages == 128, "128-stage chain changed");
        rejected(() -> expression("1" + " |> step".repeat(129)), "129 stages accepted");
    }

    static void malformedOriginsAreRejected() {
        rejected(() -> new Ast.CallExpr(new Ast.NameExpr("f"), List.of(), false,
                List.of(), Ast.CallOrigin.VALUE_PIPE_ARGUMENT), "missing upstream accepted");
        rejected(() -> new Ast.CallExpr(new Ast.NameExpr("f"), List.of(), false,
                List.of(), Ast.CallOrigin.VALUE_PIPE_RECEIVER), "non-member receiver accepted");
        require(new Ast.CallExpr(new Ast.NameExpr("f"), List.of()).origin() == Ast.CallOrigin.DIRECT,
                "legacy constructor changed");
    }

    private static Ast.Expr expression(String text) {
        // A semicolon makes trailing input observable: parse a complete declaration.
        Ast.Program program = Parser.parse("fnc test(): int { return " + text + "; }");
        Ast.FunctionDecl function = (Ast.FunctionDecl) program.modules().get(0).declarations().get(0);
        return ((Ast.ReturnStmt) function.body().get(0)).value();
    }

    private static Ast.CallExpr call(String text) { return (Ast.CallExpr) expression(text); }
    private static String name(Ast.CallExpr call) { return ((Ast.NameExpr) call.callee()).name(); }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void rejected(Runnable operation, String message) {
        try { operation.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(message);
    }
}

