package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.ExportInterfaceCodec;
import dev.oreslang.compiler.SccAstShapeGuard;
import dev.oreslang.compiler.SccVerificationInputs;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class SccAstShapeGuardTest {
    private static final ExportInterfaceCodec.Header ENV =
            new ExportInterfaceCodec.Header("", "compiler21", "x86_64", "strict");

    private static Ast.Program program(Ast.Expr expression) {
        var entry = new Ast.FunctionDecl("main",Ast.Visibility.PRIVATE,false,
                List.of(),List.of(),Ast.TypeRef.simple("void"),List.of(),
                List.of(new Ast.ExprStmt(expression)));
        return new Ast.Program(List.of(new Ast.ModuleDecl("main",List.of(entry))));
    }

    private static void admitted(Ast.Expr expression) {
        Ast.Program value = program(expression);
        assertDoesNotThrow(() -> SccAstShapeGuard.requireImmutable(value));
        assertDoesNotThrow(() -> SccVerificationInputs.snapshot(
                Map.of("unit.ores",value),Map.of("unit.ores",ENV),Map.of()));
    }

    private static void refused(Ast.Expr expression) {
        Ast.Program value = program(expression);
        assertThrows(IllegalArgumentException.class, () ->
                SccVerificationInputs.snapshot(Map.of("unit.ores",value),
                        Map.of("unit.ores",ENV),Map.of()));
    }

    @Test void immutableScalarAndNestedAstLiteralsAreAllowed() {
        admitted(new Ast.LiteralExpr(3L));
        admitted(new Ast.LiteralExpr(new BigDecimal("3.14")));
        admitted(new Ast.LiteralExpr(new Ast.Imaginary(2.0)));
        admitted(new Ast.UnaryExpr("-",new Ast.LiteralExpr(7L)));
        admitted(new Ast.ListExpr(List.of(new Ast.LiteralExpr("ok"),
                new Ast.LiteralExpr(4L))));
    }

    @Test void mutableLiteralPayloadsAndPatternsCannotReceiveVerification() {
        refused(new Ast.LiteralExpr(new byte[]{1,2,3}));
        refused(new Ast.LiteralExpr(new ArrayList<>(List.of("mutable"))));
        refused(new Ast.LiteralExpr(new StringBuilder("mutated after check")));
        refused(new Ast.LiteralExpr(Map.of("untyped","payload")));
        refused(new Ast.LiteralExpr(new Object()));
        refused(new Ast.PatternTestExpr(new Ast.NameExpr("x"),
                new Ast.LiteralPattern(new byte[]{1})));
        refused(new Ast.PatternTestExpr(new Ast.NameExpr("x"),
                new Ast.LiteralPattern(new ArrayList<>(List.of(5)))));
    }

    @Test void cyclicOrTooDeepInputsFailClosed() {
        Ast.Expr nested = new Ast.LiteralExpr(1L);
        for(int i=0;i<260;i++)nested = new Ast.UnaryExpr("-",nested);
        refused(nested);
    }
    @Test void entireSccSharesOneAstBudgetEvenWhenEachUnitFitsIndividually() {
        var expressions = new ArrayList<Ast.Expr>();
        for (int i = 0; i < 60000; i++) {
            expressions.add(new Ast.NameExpr("n" + i));
        }
        var oneUnit = program(new Ast.ListExpr(expressions));
        assertDoesNotThrow(() -> SccAstShapeGuard.requireImmutable(oneUnit));
        assertThrows(IllegalArgumentException.class, () ->
                SccVerificationInputs.snapshot(
                        Map.of("a.ores", oneUnit, "b.ores", oneUnit),
                        Map.of("a.ores", ENV, "b.ores", ENV),
                        Map.of()),
                "a graph-level AST budget must not reset for every source unit");
    }

}
