package dev.oreslang.nodes;

import dev.oreslang.ast.Ast;
import java.util.List;
import java.util.Set;
import java.util.Random;

public final class LambdaCaptureStandaloneHarness {
    private static long cases;

    private static void expected(Set<String> want, Ast.LambdaExpr lambda) {
        Set<String> got = LambdaCapturePlanner.captures(lambda);
        if (!got.equals(want))
            throw new AssertionError("capture mismatch expected=" + want + " observed=" + got);
        cases++;
    }

    public static void main(String[] args) {
        expected(Set.of(), new Ast.LambdaExpr(
                List.of(), new Ast.LiteralExpr(4), null));
        expected(Set.of("outer"), new Ast.LambdaExpr(
                List.of(), new Ast.NameExpr("outer"), null));
        expected(Set.of(), new Ast.LambdaExpr(
                List.of(), new Ast.NameExpr("outer"), null, true));

        Ast.LambdaExpr nested = new Ast.LambdaExpr(
                List.of(new Ast.Param(Ast.TypeRef.simple("int"), "parameter")),
                new Ast.BinaryExpr("+", new Ast.NameExpr("external"),
                        new Ast.NameExpr("parameter")), null);
        expected(Set.of("external"), new Ast.LambdaExpr(List.of(), nested, null));

        Random random = new Random(0x7a11c0deL);
        for (int i = 0; i < 10_000; i++) {
            String binding = "var_" + random.nextInt(400);
            boolean parameter = random.nextBoolean();
            boolean nonLexical = random.nextInt(19) == 0;
            Ast.LambdaExpr fn = new Ast.LambdaExpr(
                    parameter ? List.of(new Ast.Param(Ast.TypeRef.simple("int"), binding)) : List.of(),
                    new Ast.NameExpr(binding), null, nonLexical);
            expected(parameter || nonLexical ? Set.of() : Set.of(binding), fn);
        }

        Ast.BindingStmt local = new Ast.BindingStmt(Ast.BindingKind.VAL,
                Ast.TypeRef.simple("int"), "shadow", new Ast.NameExpr("source"));
        expected(Set.of("source"), new Ast.LambdaExpr(
                List.of(), null, List.of(local, new Ast.ReturnStmt(new Ast.NameExpr("shadow")))));

        System.out.println("PASS " + cases + " standalone lexical capture planner checks on JDK "
                + Runtime.version().feature());
    }
}
