package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.MacroExpansionPlanner;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class MacroInvocationPlannerTest {
    @Test
    void parsesDeclarationAndExpressionMacroInvocationsAsCompilerSyntax() {
        Ast.Program declarationProgram = Parser.parse("""
                define macro emit(Type target) -> SyntaxList<Decl> {
                    return generated(target);
                }
                emit!(type User);
                """);

        Ast.MacroInvocationDecl declarationCall = assertInstanceOf(
                Ast.MacroInvocationDecl.class,
                declarationProgram.modules().getFirst().declarations().get(1));
        assertEquals("emit", declarationCall.name());
        Ast.MacroTypeArgument typeArgument = assertInstanceOf(
                Ast.MacroTypeArgument.class, declarationCall.arguments().getFirst());
        assertEquals("User", typeArgument.type().name());

        Ast.Program expressionProgram = Parser.parse("""
                define macro literal(Const<int> value) -> Syntax<Expr> {
                    return generated(value);
                }
                fnc answer() -> int {
                    return literal!(const 42);
                }
                """);
        Ast.FunctionDecl fn = assertInstanceOf(
                Ast.FunctionDecl.class,
                expressionProgram.modules().getFirst().declarations().get(1));
        Ast.ReturnStmt returned = assertInstanceOf(Ast.ReturnStmt.class, fn.body().getFirst());
        Ast.MacroInvocationExpr expressionCall = assertInstanceOf(
                Ast.MacroInvocationExpr.class, returned.value());
        assertEquals("literal", expressionCall.name());
        assertInstanceOf(Ast.MacroConstArgument.class, expressionCall.arguments().getFirst());
    }

    @Test
    void parsesExplicitIdentifierAndTypedSyntaxArgumentsWithoutTextualCapture() {
        Ast.Program program = Parser.parse("""
                define macro inspect(
                    Identifier field,
                    Syntax<Expr> expression,
                    Syntax<Type> target,
                    Syntax<Pattern> pattern,
                    Syntax<Stmt> statement
                ) -> Syntax<Expr> {
                    return expression;
                }
                fnc sample(int value) -> int {
                    return inspect!(
                        ident value,
                        syntax expr value + 1,
                        syntax type Array<int>,
                        syntax pattern int item,
                        syntax stmt { return 7; }
                    );
                }
                """);

        Ast.FunctionDecl fn = assertInstanceOf(
                Ast.FunctionDecl.class,
                program.modules().getFirst().declarations().get(1));
        Ast.ReturnStmt returned = assertInstanceOf(Ast.ReturnStmt.class, fn.body().getFirst());
        Ast.MacroInvocationExpr call = assertInstanceOf(Ast.MacroInvocationExpr.class, returned.value());
        assertInstanceOf(Ast.MacroIdentifierArgument.class, call.arguments().get(0));
        assertInstanceOf(Ast.MacroSyntaxExprArgument.class, call.arguments().get(1));
        assertInstanceOf(Ast.MacroSyntaxTypeArgument.class, call.arguments().get(2));
        assertInstanceOf(Ast.MacroSyntaxPatternArgument.class, call.arguments().get(3));
        assertInstanceOf(Ast.MacroSyntaxStmtArgument.class, call.arguments().get(4));
    }

    @Test
    void rejectsWrongResultPositionAndArgumentCategoryBeforeExpansion() {
        Ast.Program wrongContext = Parser.parse("""
                define macro emit(Type target) -> SyntaxList<Decl> {
                    return generated(target);
                }
                fnc bad() -> int {
                    return emit!(type int);
                }
                """);
        IllegalArgumentException contextFailure = assertThrows(
                IllegalArgumentException.class,
                () -> MacroExpansionPlanner.plan(wrongContext));
        assertTrue(contextFailure.getMessage().contains("cannot be used in expression position"),
                contextFailure::toString);

        Ast.Program wrongArgument = Parser.parse("""
                define macro expression(Type target) -> Syntax<Expr> {
                    return generated(target);
                }
                fnc bad() -> int {
                    return expression!(const 1);
                }
                """);
        IllegalArgumentException argumentFailure = assertThrows(
                IllegalArgumentException.class,
                () -> MacroExpansionPlanner.plan(wrongArgument));
        assertTrue(argumentFailure.getMessage().contains("requires Type, got const"),
                argumentFailure::toString);
    }

    @Test
    void constMacroInputIsFailClosedUntilProvenCompileTime() {
        Ast.Program program = Parser.parse("""
                define macro literal(Const<int> value) -> Syntax<Expr> {
                    return generated(value);
                }
                fnc bad(int runtime_value) -> int {
                    return literal!(const runtime_value);
                }
                """);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> MacroExpansionPlanner.plan(program));
        assertTrue(failure.getMessage().contains("not a closed compile-time expression"),
                failure::toString);
        assertTrue(failure.getMessage().contains("runtime evaluation fallback is forbidden"),
                failure::toString);
    }

    @Test
    void hygieneMarksAreDeterministicAcrossEquivalentParses() {
        String source = """
                define macro literal(Const<int> value) -> Syntax<Expr> {
                    return generated(value);
                }
                fnc answer() -> int {
                    return literal!(const 40 + 2);
                }
                """;

        MacroExpansionPlanner.Plan first = MacroExpansionPlanner.plan(Parser.parse(source));
        MacroExpansionPlanner.Plan second = MacroExpansionPlanner.plan(Parser.parse(source));

        assertEquals(1, first.workItems().size());
        assertEquals(first.workItems().getFirst().hygieneMark(),
                second.workItems().getFirst().hygieneMark());
    }

    @Test
    void boundInvocationStillCannotFallBackToRuntimeExecution() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define macro literal(Const<int> value) -> Syntax<Expr> {
                            return generated(value);
                        }
                        fnc answer() -> int {
                            return literal!(const 42);
                        }
                        """));

        assertTrue(failure.getMessage().contains("deterministic macro evaluator/expander"),
                failure::toString);
        assertTrue(failure.getMessage().contains("runtime fallback is forbidden"),
                failure::toString);
    }
}
