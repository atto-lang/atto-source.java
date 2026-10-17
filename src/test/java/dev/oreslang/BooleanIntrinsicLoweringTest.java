package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class BooleanIntrinsicLoweringTest {
    @Test
    void resolvedScalarBuiltinsLowerToOrdinaryLogicalAst() {
        Ast.Program program = TypeChecker.checkTypes(Parser.parse("""
                fnc combine(bool a, bool b, bool c): bool {
                  return And(a, Or(b, c), Xor(a, c));
                }
                """));

        Ast.Expr value = returned(program, "combine");
        Ast.BinaryExpr outer = assertInstanceOf(Ast.BinaryExpr.class, value);
        assertEquals("&&", outer.operator());

        Ast.BinaryExpr prefix = assertInstanceOf(Ast.BinaryExpr.class, outer.left());
        assertEquals("&&", prefix.operator());
        assertName("a", prefix.left());

        Ast.BinaryExpr nestedOr = assertInstanceOf(Ast.BinaryExpr.class, prefix.right());
        assertEquals("||", nestedOr.operator());
        assertName("b", nestedOr.left());
        assertName("c", nestedOr.right());

        Ast.BinaryExpr nestedXor = assertInstanceOf(Ast.BinaryExpr.class, outer.right());
        assertEquals("^^", nestedXor.operator());
        assertName("a", nestedXor.left());
        assertName("c", nestedXor.right());

        assertNoScalarBooleanBuiltinCall(value);
    }

    @Test
    void aggregateReducersRemainCallsUntilPackagedCoreSourceCanOwnThem() {
        Ast.Program program = TypeChecker.checkTypes(Parser.parse("""
                fnc all(Array<bool> values): bool {
                  return And(values);
                }

                fnc any(Array<bool> values): bool {
                  return BooleanOps.Or(values);
                }

                fnc parity(Array<bool> values): bool {
                  return Xor(values);
                }
                """));

        for (String name : new String[]{"all", "any", "parity"}) {
            Ast.CallExpr call = assertInstanceOf(Ast.CallExpr.class, returned(program, name));
            assertEquals(1, call.arguments().size());
        }
    }

    @Test
    void sourceFunctionsAndRealBooleanOpsModulesAreNeverRewrittenAsBuiltins() {
        Ast.Program program = TypeChecker.checkTypes(Parser.parse("""
                fnc And(bool left, bool right): bool {
                  return left;
                }

                define module BooleanOps as
                  pub fnc Or(bool left, bool right): bool {
                    return right;
                  }
                end

                fnc local(bool a, bool b): bool {
                  return And(a, b);
                }

                fnc qualified(bool a, bool b): bool {
                  return BooleanOps.Or(a, b);
                }
                """));

        Ast.CallExpr local = assertInstanceOf(Ast.CallExpr.class, returned(program, "local"));
        Ast.NameExpr localName = assertInstanceOf(Ast.NameExpr.class, local.callee());
        assertEquals("And", localName.name());

        Ast.CallExpr qualified = assertInstanceOf(Ast.CallExpr.class, returned(program, "qualified"));
        Ast.MemberExpr member = assertInstanceOf(Ast.MemberExpr.class, qualified.callee());
        assertEquals("Or", member.member());
        assertName("BooleanOps", member.receiver());
    }

    @Test
    void scalarLoweringIsLeftAssociatedAndPreservesSourceOrder() {
        Ast.Program program = TypeChecker.checkTypes(Parser.parse("""
                fnc combine(bool a, bool b, bool c, bool d): bool {
                  return Or(a, b, c, d);
                }
                """));

        Ast.BinaryExpr third = assertInstanceOf(Ast.BinaryExpr.class, returned(program, "combine"));
        assertEquals("||", third.operator());
        assertName("d", third.right());

        Ast.BinaryExpr second = assertInstanceOf(Ast.BinaryExpr.class, third.left());
        assertEquals("||", second.operator());
        assertName("c", second.right());

        Ast.BinaryExpr first = assertInstanceOf(Ast.BinaryExpr.class, second.left());
        assertEquals("||", first.operator());
        assertName("a", first.left());
        assertName("b", first.right());
    }

    private static Ast.Expr returned(Ast.Program program, String name) {
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.FunctionDecl function
                        && function.name().equals(name)) {
                    for (Ast.Stmt statement : function.body()) {
                        if (statement instanceof Ast.ReturnStmt returned) {
                            return returned.value();
                        }
                    }
                }
            }
        }
        throw new AssertionError("missing returned expression from function " + name);
    }

    private static void assertName(String expected, Ast.Expr expression) {
        Ast.NameExpr name = assertInstanceOf(Ast.NameExpr.class, expression);
        assertEquals(expected, name.name());
    }

    private static void assertNoScalarBooleanBuiltinCall(Ast.Expr expression) {
        if (expression instanceof Ast.CallExpr call) {
            String builtin = null;
            if (call.callee() instanceof Ast.NameExpr name) {
                builtin = name.name();
            } else if (call.callee() instanceof Ast.MemberExpr member
                    && member.receiver() instanceof Ast.NameExpr namespace
                    && namespace.name().equals("BooleanOps")) {
                builtin = member.member();
            }
            if ((builtin != null)
                    && (builtin.equals("And") || builtin.equals("Or") || builtin.equals("Xor"))
                    && call.arguments().size() >= 2) {
                fail("scalar Boolean builtin survived lowering: " + builtin);
            }
            assertNoScalarBooleanBuiltinCall(call.callee());
            for (Ast.Expr argument : call.arguments()) assertNoScalarBooleanBuiltinCall(argument);
            return;
        }
        if (expression instanceof Ast.BinaryExpr binary) {
            assertNoScalarBooleanBuiltinCall(binary.left());
            assertNoScalarBooleanBuiltinCall(binary.right());
            return;
        }
        if (expression instanceof Ast.UnaryExpr unary) {
            assertNoScalarBooleanBuiltinCall(unary.operand());
            return;
        }
        if (expression instanceof Ast.ConditionalExpr conditional) {
            assertNoScalarBooleanBuiltinCall(conditional.condition());
            assertNoScalarBooleanBuiltinCall(conditional.whenTrue());
            assertNoScalarBooleanBuiltinCall(conditional.whenFalse());
        }
    }
}
