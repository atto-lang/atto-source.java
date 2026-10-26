package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.MacroExpansionPlanner;
import dev.oreslang.compiler.MacroExpander;
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
    void macroQualificationRespectsCompileTimeVisibility() {
        Ast.Program privateProgram = Parser.parse("""
                define module Producer as
                    define macro secret(Type target) -> SyntaxList<Decl> {
                        return generated(target);
                    }
                end

                define module Consumer as
                    Producer.secret!(type int);
                end
                """);

        IllegalArgumentException privateFailure = assertThrows(
                IllegalArgumentException.class,
                () -> MacroExpansionPlanner.plan(privateProgram));
        assertTrue(privateFailure.getMessage().contains("private compile-time macro"),
                privateFailure::toString);
        assertTrue(privateFailure.getMessage().contains("visibility cannot be bypassed"),
                privateFailure::toString);

        Ast.Program publicProgram = Parser.parse("""
                define module Producer as
                    define pub macro exposed(Type target) -> SyntaxList<Decl> {
                        return generated(target);
                    }
                end

                define module Consumer as
                    Producer.exposed!(type int);
                end
                """);
        MacroExpansionPlanner.Plan plan = assertDoesNotThrow(
                () -> MacroExpansionPlanner.plan(publicProgram));
        assertEquals(1, plan.workItems().size());
        assertEquals("exposed", plan.workItems().getFirst().macro().name());
        assertEquals(Ast.Visibility.PUBLIC, plan.workItems().getFirst().macro().visibility());
    }

    @Test
    void constMacroArgumentsMustMatchDeclaredCompileTimeType() {
        Ast.Program wrongPrimitive = Parser.parse("""
                define macro literal(Const<int> value) -> Syntax<Expr> {
                    return generated(value);
                }
                fnc answer() -> int {
                    return literal!(const "42");
                }
                """);

        IllegalArgumentException mismatch = assertThrows(
                IllegalArgumentException.class,
                () -> MacroExpansionPlanner.plan(wrongPrimitive));
        assertTrue(mismatch.getMessage().contains("Const<int>"), mismatch::toString);
        assertTrue(mismatch.getMessage().contains("compile-time type String"), mismatch::toString);
        assertTrue(mismatch.getMessage().contains("match their declared type exactly"), mismatch::toString);

        Ast.Program unsupportedComposite = Parser.parse("""
                define macro values(Const<List<int>> value) -> Syntax<Expr> {
                    return generated(value);
                }
                fnc answer() -> int {
                    return values!(const [1, 2, 3]);
                }
                """);

        IllegalArgumentException unsupported = assertThrows(
                IllegalArgumentException.class,
                () -> MacroExpansionPlanner.plan(unsupportedComposite));
        assertTrue(unsupported.getMessage().contains("cannot yet prove its compile-time type"),
                unsupported::toString);
        assertTrue(unsupported.getMessage().contains("runtime/type-check fallback is forbidden"),
                unsupported::toString);
    }

    @Test
    void expandsHygienicSyntaxExprPassthroughBeforeOrdinaryTypeChecking() {
        Ast.Program checked = assertDoesNotThrow(
                () -> OresCompiler.parseAndTypeCheck("""
                        define macro identity(Syntax<Expr> value) -> Syntax<Expr> {
                            return value;
                        }
                        fnc answer() -> int {
                            return identity!(syntax expr 40 + 2);
                        }
                        """));

        assertEquals(1, checked.modules().getFirst().declarations().size());
        Ast.FunctionDecl fn = assertInstanceOf(
                Ast.FunctionDecl.class,
                checked.modules().getFirst().declarations().getFirst());
        Ast.ReturnStmt returned = assertInstanceOf(
                Ast.ReturnStmt.class, fn.body().getFirst());
        Ast.BinaryExpr expanded = assertInstanceOf(
                Ast.BinaryExpr.class, returned.value());
        assertEquals("+", expanded.operator());
        assertFalse(containsMacroInvocation(expanded));
    }

    @Test
    void unusedSupportedMacroIsValidatedAndErasedFromExecutableProgram() {
        Ast.Program checked = assertDoesNotThrow(
                () -> OresCompiler.parseAndTypeCheck("""
                        define macro identity(Syntax<Expr> value) -> Syntax<Expr> {
                            return value;
                        }
                        fnc answer() -> int {
                            return 42;
                        }
                        """));

        assertEquals(1, checked.modules().getFirst().declarations().size());
        Ast.FunctionDecl fn = assertInstanceOf(
                Ast.FunctionDecl.class,
                checked.modules().getFirst().declarations().getFirst());
        assertEquals("answer", fn.name());
    }

    @Test
    void identityExpansionPreservesQuotedExpressionAstExactly() {
        Ast.Program withMacro = Parser.parse("""
                define macro identity(Syntax<Expr> value) -> Syntax<Expr> {
                    return value;
                }
                fnc sample() -> int {
                    return identity!(syntax expr consume(
                        [1, 2][0],
                        infer<T> struct { x: 3 },
                        |int value| -> value + 1
                    ));
                }
                """);

        Ast.Program handwritten = Parser.parse("""
                fnc sample() -> int {
                    return consume(
                        [1, 2][0],
                        infer<T> struct { x: 3 },
                        |int value| -> value + 1
                    );
                }
                """);

        Ast.Program expanded = assertDoesNotThrow(() -> MacroExpander.expand(withMacro));
        assertEquals(handwritten, expanded,
                "identity expansion must preserve every quoted AST field and semantic tag");
    }

    @Test
    void expandedSyntaxReentersOrdinaryTypeChecking() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define macro identity(Syntax<Expr> value) -> Syntax<Expr> {
                            return value;
                        }
                        fnc answer() -> int {
                            return identity!(syntax expr "not-an-int");
                        }
                        """));

        assertFalse(failure.getMessage().contains("macro evaluator"), failure::toString);
    }

    @Test
    void nestedPassthroughExpansionTerminatesAndEliminatesAllInvocations() {
        Ast.Program checked = assertDoesNotThrow(
                () -> OresCompiler.parseAndTypeCheck("""
                        define macro identity(Syntax<Expr> value) -> Syntax<Expr> {
                            return value;
                        }
                        fnc answer() -> int {
                            return identity!(
                                syntax expr identity!(syntax expr 42)
                            );
                        }
                        """));

        Ast.FunctionDecl fn = assertInstanceOf(
                Ast.FunctionDecl.class,
                checked.modules().getFirst().declarations().getFirst());
        Ast.ReturnStmt returned = assertInstanceOf(
                Ast.ReturnStmt.class, fn.body().getFirst());
        assertInstanceOf(Ast.LiteralExpr.class, returned.value());
        assertFalse(containsMacroInvocation(returned.value()));
    }

    @Test
    void quotedSyntaxCannotSmugglePrivateMacroInvocationAcrossModules() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define module Producer as
                            define macro secret(Syntax<Expr> value) -> Syntax<Expr> {
                                return value;
                            }
                            define pub macro expose(Syntax<Expr> value) -> Syntax<Expr> {
                                return value;
                            }
                        end

                        define module Consumer as
                            fnc answer() -> int {
                                return Producer.expose!(
                                    syntax expr Producer.secret!(syntax expr 42)
                                );
                            }
                        end
                        """));

        assertTrue(failure.getMessage().contains("private compile-time macro"),
                failure::toString);
        assertTrue(failure.getMessage().contains("cannot bypass compile-time visibility"),
                failure::toString);
    }

    @Test
    void unsupportedMacroBodyCannotFallBackToRuntimeExecution() {
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

        assertTrue(failure.getMessage().contains("first evaluator slice"),
                failure::toString);
        assertTrue(failure.getMessage().contains("runtime/JIT/source-string fallback is forbidden"),
                failure::toString);
    }

    private static boolean containsMacroInvocation(Ast.Expr expression) {
        if (expression instanceof Ast.MacroInvocationExpr) return true;
        if (expression instanceof Ast.BinaryExpr e) {
            return containsMacroInvocation(e.left()) || containsMacroInvocation(e.right());
        }
        if (expression instanceof Ast.UnaryExpr e) return containsMacroInvocation(e.operand());
        if (expression instanceof Ast.ConditionalExpr e) {
            return containsMacroInvocation(e.condition())
                    || containsMacroInvocation(e.whenTrue())
                    || containsMacroInvocation(e.whenFalse());
        }
        return false;
    }
}
