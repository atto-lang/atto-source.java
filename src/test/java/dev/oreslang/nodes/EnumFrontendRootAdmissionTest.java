package dev.oreslang.nodes;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class EnumFrontendRootAdmissionTest {
    @Test
    void evaluatorRejectsEnumAndPatternAstEvenWithoutTypeChecker() {
        for (String source : new String[]{
                "define enum Color as Red end",
                "define match pattern P as Color.Red end",
                "define pattern P as Color.Red end"}) {
            Ast.Program unchecked = Parser.parse(source);
            IllegalArgumentException denied = assertThrows(
                    IllegalArgumentException.class,
                    () -> OresEvalRootNode.rejectUnlinkedEnumAndPatternDeclarations(unchecked));
            assertTrue(denied.getMessage().contains("runtime execution is not enabled"),
                    denied.getMessage());
        }
    }

    @Test
    void ordinaryClassAstIsNotRejectedBySyntaxOnlyEnumGate() {
        Ast.Program ordinary = Parser.parse("""
                define class Worker as
                    pub work(): int { return 1; }
                end
                """);
        assertDoesNotThrow(() ->
                OresEvalRootNode.rejectUnlinkedEnumAndPatternDeclarations(ordinary));
    }

    @Test
    void forgedDirectEnumAstStillCannotBeExecuted() {
        Ast.Program forged = new Ast.Program(java.util.List.of(
                new Ast.ModuleDecl("root", java.util.List.of(
                        new Ast.EnumDecl("Color",
                                java.util.List.of(new Ast.EnumCase("Red", null, 1)))))));
        assertThrows(IllegalArgumentException.class, () ->
                OresEvalRootNode.rejectUnlinkedEnumAndPatternDeclarations(forged));
    }
}
