package dev.oreslang;

import dev.oreslang.ast.Ast;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Checked source call-site identity, not textual equality, controls depth. */
final class CheckedOptionDepthMetadataTest {
    private static Ast.CallExpr unwrapCall() {
        return new Ast.CallExpr(
                new Ast.MemberExpr(new Ast.NameExpr("value"), "unwrap_all"),
                List.of());
    }

    @Test
    void structurallyEqualDistinctCallsKeepIndependentDepths() {
        Ast.CallExpr shallow = unwrapCall();
        Ast.CallExpr deep = unwrapCall();
        assertEquals(shallow, deep, "precondition: source-equivalent AST calls");
        assertNotSame(shallow, deep);

        assertThrows(IllegalStateException.class,
                () -> Ast.CheckedOptionDepth.require(shallow));
        Ast.CheckedOptionDepth.record(shallow, 1);
        assertEquals(1, Ast.CheckedOptionDepth.require(shallow));
        assertThrows(IllegalStateException.class,
                () -> Ast.CheckedOptionDepth.require(deep));

        Ast.CheckedOptionDepth.record(deep, 3);
        assertEquals(1, Ast.CheckedOptionDepth.require(shallow));
        assertEquals(3, Ast.CheckedOptionDepth.require(deep));
    }

    @Test
    void checkedDepthCanBeTransferredToARewrittenCall() {
        Ast.CallExpr source = unwrapCall();
        Ast.CallExpr rewritten = unwrapCall();

        Ast.CheckedOptionDepth.record(source, 4);
        Ast.CheckedOptionDepth.copy(source, rewritten);

        assertEquals(4, Ast.CheckedOptionDepth.require(source));
        assertEquals(4, Ast.CheckedOptionDepth.require(rewritten));
    }

    @Test
    void copyingAnUncheckedCallDoesNotAuthorizeTheTarget() {
        Ast.CallExpr source = unwrapCall();
        Ast.CallExpr rewritten = unwrapCall();

        Ast.CheckedOptionDepth.copy(source, rewritten);

        assertThrows(IllegalStateException.class,
                () -> Ast.CheckedOptionDepth.require(rewritten));
    }

    @Test
    void checkedDepthIsWriteOnceAndMustBePositive() {
        Ast.CallExpr call = unwrapCall();
        assertThrows(IllegalArgumentException.class,
                () -> Ast.CheckedOptionDepth.record(call, 0));
        Ast.CheckedOptionDepth.record(call, 2);
        assertDoesNotThrow(() -> Ast.CheckedOptionDepth.record(call, 2));
        assertThrows(IllegalStateException.class,
                () -> Ast.CheckedOptionDepth.record(call, 3));
        assertEquals(2, Ast.CheckedOptionDepth.require(call));
    }
}
