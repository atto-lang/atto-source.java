package dev.oreslang;

import dev.oreslang.ast.Ast;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SelectAstInvariantsTest {
    private static Ast.SelectArm defaultArm() {
        return new Ast.SelectArm(
                Ast.ChannelOperation.DEFAULT, null, null, null, null, List.of());
    }

    private static Ast.SelectArm readArm() {
        return new Ast.SelectArm(
                Ast.ChannelOperation.READ,
                new Ast.NameExpr("input"), null,
                Ast.BindingKind.VAL, "item", List.of());
    }

    @Test
    void rejectsDefaultBeforeOrdinaryCaseEvenWhenAstBypassesParser() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new Ast.SelectStmt(
                        Ast.WaitMode.BLOCKING, Ast.SelectPolicy.FAIR,
                        List.of(defaultArm(), readArm()), true));
        assertTrue(error.getMessage().contains("last"), error::getMessage);
    }

    @Test
    void rejectsDuplicateDefaultsFromProgrammaticAst() {
        assertThrows(IllegalArgumentException.class,
                () -> new Ast.SelectStmt(
                        Ast.WaitMode.BLOCKING, Ast.SelectPolicy.FAIR,
                        List.of(defaultArm(), defaultArm()), true));
    }

    @Test
    void rejectsNullOperationAndNullBodyBeforeRuntime() {
        assertThrows(IllegalArgumentException.class,
                () -> new Ast.SelectArm(
                        null, new Ast.NameExpr("input"), null,
                        Ast.BindingKind.VAL, "item", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new Ast.SelectArm(
                        Ast.ChannelOperation.READ,
                        new Ast.NameExpr("input"), null,
                        Ast.BindingKind.VAL, "item", null));
    }

    @Test
    void rejectsUnboundMutableReferentOnReadOrWriteArm() {
        assertThrows(IllegalArgumentException.class,
                () -> new Ast.SelectArm(
                        Ast.ChannelOperation.READ,
                        new Ast.NameExpr("input"), null,
                        null, null, List.of(), true));
        assertThrows(IllegalArgumentException.class,
                () -> new Ast.SelectArm(
                        Ast.ChannelOperation.WRITE,
                        new Ast.NameExpr("output"), new Ast.LiteralExpr(7L),
                        null, null, List.of(), true));
    }

    @Test
    void permitsOrderedCasesFollowedByOneDefault() {
        assertDoesNotThrow(() -> new Ast.SelectStmt(
                Ast.WaitMode.IMMEDIATE, Ast.SelectPolicy.PRIORITY,
                List.of(readArm(), defaultArm()), false));
    }
}
