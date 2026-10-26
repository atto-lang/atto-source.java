package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.MacroExpansionPlanner;
import dev.oreslang.compiler.MacroExpander;
import dev.oreslang.compiler.MacroSyntaxProvenance;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

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
        MacroExpansionPlanner.HygieneMark mark =
                first.workItems().getFirst().hygieneMark();
        assertEquals(Parser.ROOT_MODULE, mark.module());
        assertEquals("literal", mark.macroName());
        assertTrue(mark.line() > 0);
        assertTrue(mark.column() > 0);
        assertEquals(0, mark.sourceOrdinal());
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

        Ast.Program composite = Parser.parse("""
                define macro values(Const<List<int>> value) -> Syntax<Expr> {
                    return generated(value);
                }
                fnc answer() -> int {
                    return values!(const [1, 2 + 3, -4]);
                }
                """);

        MacroExpansionPlanner.Plan compositePlan =
                assertDoesNotThrow(() -> MacroExpansionPlanner.plan(composite));
        assertEquals(1, compositePlan.workItems().size());
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
    void expandedSyntaxCannotBypassOwnershipChecking() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define class Box as
                            pub let int value = 7;
                        end

                        define macro identity(Syntax<Expr> value) -> Syntax<Expr> {
                            return value;
                        }

                        fnc bad() -> void {
                            let Box box = new Box();
                            val Box moved = identity!(syntax expr rt take box);
                            stdio.println(box.value);
                            stdio.println(moved.value);
                            return;
                        }
                        """));

        assertTrue(failure.getMessage().toLowerCase().contains("moved"),
                failure::toString);
    }

    @Test
    void directCapabilityPassRejectsResidualMacroNodes() {
        Ast.Program parsed = Parser.parse("""
                define macro identity(Syntax<Expr> value) -> Syntax<Expr> {
                    return value;
                }
                fnc blocked() -> void {
                    identity!(syntax expr stdio.println("blocked"));
                    return;
                }
                """);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> CapabilityChecker.check(parsed, IsolatePolicy.untrustedActor()));

        assertTrue(failure.getMessage().contains("unexpanded macro"),
                failure::toString);
        assertTrue(failure.getMessage().contains("capability admission"),
                failure::toString);
    }

    @Test
    void expandedSyntaxCannotBypassCapabilityChecking() {
        SecurityException failure = assertThrows(
                SecurityException.class,
                () -> OresCompiler.validateForIsolate("""
                        define macro identity(Syntax<Expr> value) -> Syntax<Expr> {
                            return value;
                        }

                        fnc blocked() -> void {
                            identity!(syntax expr stdio.println("blocked"));
                            return;
                        }
                        """, IsolatePolicy.untrustedActor()));

        assertTrue(failure.getMessage().contains("STDOUT"), failure::toString);
    }

    @Test
    void unusedUnsupportedMacroBodyStillFailsClosed() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define macro literal(Const<int> value) -> Syntax<Expr> {
                            return generated(value);
                        }
                        fnc answer() -> int {
                            return 42;
                        }
                        """));

        assertTrue(failure.getMessage().contains("first evaluator slice"),
                failure::toString);
        assertTrue(failure.getMessage().contains("runtime fallback is forbidden"),
                failure::toString);
        assertTrue(failure.getMessage().contains("JIT/source-string fallback is forbidden"),
                failure::toString);
    }

    @Test
    void nestedExpansionDepthIsBoundedDeterministically() {
        String expression = "42";
        for (int i = 0; i < 65; i++) {
            expression = "identity!(syntax expr " + expression + ")";
        }
        String source = """
                define macro identity(Syntax<Expr> value) -> Syntax<Expr> {
                    return value;
                }
                fnc answer() -> int {
                    return %s;
                }
                """.formatted(expression);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));

        assertTrue(failure.getMessage().contains("macro expansion depth exceeds 64"),
                failure::toString);
        assertTrue(failure.getMessage().contains("runtime fallback is forbidden"),
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
        assertTrue(failure.getMessage().contains("runtime fallback is forbidden"),
                failure::toString);
        assertTrue(failure.getMessage().contains("JIT/source-string fallback is forbidden"),
                failure::toString);
    }

    @Test
    void compileTimeConstQuoteEmitsLiteralIntoCheckedRuntimeProgram() {
        Ast.Program checked = assertDoesNotThrow(
                () -> OresCompiler.parseAndTypeCheck("""
                        define macro literal(Const<int> value) -> Syntax<Expr> {
                            return quote(value);
                        }
                        fnc answer() -> int {
                            return literal!(const 42);
                        }
                        """));

        assertEquals(1, checked.modules().getFirst().declarations().size(),
                "macro declarations must be erased from executable AST");
        Ast.FunctionDecl function = assertInstanceOf(Ast.FunctionDecl.class,
                checked.modules().getFirst().declarations().getFirst());
        Ast.ReturnStmt returned = assertInstanceOf(
                Ast.ReturnStmt.class, function.body().getFirst());
        Ast.LiteralExpr emitted = assertInstanceOf(
                Ast.LiteralExpr.class, returned.value());
        assertEquals(42L, emitted.value(),
                "the macro must materialize a literal, not a runtime macro call");
    }

    @Test
    void stringQuoteProducesRuntimeStringExpressionWithoutRuntimeCompiler() {
        Ast.Program checked = assertDoesNotThrow(
                () -> OresCompiler.parseAndTypeCheck("""
                        define macro label(Const<String> value) -> Syntax<Expr> {
                            return quote(value);
                        }
                        fnc heading() -> String {
                            return label!(const "<h1>Count</h1>");
                        }
                        """));

        Ast.FunctionDecl function = assertInstanceOf(Ast.FunctionDecl.class,
                checked.modules().getFirst().declarations().getFirst());
        Ast.ReturnStmt returned = assertInstanceOf(
                Ast.ReturnStmt.class, function.body().getFirst());
        Ast.LiteralExpr emitted = assertInstanceOf(
                Ast.LiteralExpr.class, returned.value());
        assertEquals("<h1>Count</h1>", emitted.value());
    }

    @Test
    void boundedConstArithmeticIsEvaluatedAndQuotedBeforeRuntime() {
        Ast.Program checked = assertDoesNotThrow(
                () -> OresCompiler.parseAndTypeCheck("""
                        define macro literal(Const<int> value) -> Syntax<Expr> {
                            return quote(value);
                        }
                        fnc answer() -> int {
                            return literal!(const (40 + 2) * 2 - 42);
                        }
                        """));

        Ast.FunctionDecl function = assertInstanceOf(
                Ast.FunctionDecl.class,
                checked.modules().getFirst().declarations().getFirst());
        Ast.ReturnStmt returned = assertInstanceOf(
                Ast.ReturnStmt.class, function.body().getFirst());
        Ast.LiteralExpr emitted = assertInstanceOf(
                Ast.LiteralExpr.class, returned.value());
        assertEquals(42L, emitted.value());
    }

    @Test
    void boundedConstCollectionsAreNormalizedIntoCheckedRuntimeAst() {
        Ast.Program checked = assertDoesNotThrow(
                () -> OresCompiler.parseAndTypeCheck("""
                        define macro values(Const<List<int>> value) -> Syntax<Expr> {
                            return quote(value);
                        }
                        fnc answer() -> List<int> {
                            return values!(const [1 + 1, 3 * 2, 10 - 1]);
                        }
                        """));

        Ast.FunctionDecl function = assertInstanceOf(
                Ast.FunctionDecl.class,
                checked.modules().getFirst().declarations().getFirst());
        Ast.ReturnStmt returned = assertInstanceOf(
                Ast.ReturnStmt.class, function.body().getFirst());
        Ast.ListExpr emitted = assertInstanceOf(
                Ast.ListExpr.class, returned.value());
        assertEquals(List.of(2L, 6L, 9L),
                emitted.elements().stream()
                        .map(Ast.LiteralExpr.class::cast)
                        .map(Ast.LiteralExpr::value)
                        .toList());
    }

    @Test
    void constDivisionUsesRuntimeCompatibleFloatTypeProof() {
        IllegalArgumentException mismatch = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define macro literal(Const<int> value) -> Syntax<Expr> {
                            return quote(value);
                        }
                        fnc answer() -> int {
                            return literal!(const 4 / 2);
                        }
                        """));
        assertTrue(mismatch.getMessage().contains("compile-time type float"),
                mismatch::toString);
        assertTrue(mismatch.getMessage().contains("Const<int>"),
                mismatch::toString);

        Ast.Program checked = assertDoesNotThrow(
                () -> OresCompiler.parseAndTypeCheck("""
                        define macro literal(Const<float> value) -> Syntax<Expr> {
                            return quote(value);
                        }
                        fnc answer() -> float {
                            return literal!(const 4 / 2);
                        }
                        """));
        Ast.FunctionDecl fn = assertInstanceOf(
                Ast.FunctionDecl.class,
                checked.modules().getFirst().declarations().getFirst());
        Ast.ReturnStmt returned =
                assertInstanceOf(Ast.ReturnStmt.class, fn.body().getFirst());
        Ast.LiteralExpr emitted =
                assertInstanceOf(Ast.LiteralExpr.class, returned.value());
        assertEquals(2.0d, emitted.value());
    }

    @Test
    void emittedConstSyntaxCarriesCompilerOnlyProvenance() {
        Ast.Program checked = assertDoesNotThrow(
                () -> OresCompiler.parseAndTypeCheck("""
                        define macro literal(Const<int> value) -> Syntax<Expr> {
                            return quote(value);
                        }
                        fnc answer() -> int {
                            return literal!(const 40 + 2);
                        }
                        """));

        Ast.FunctionDecl fn = assertInstanceOf(
                Ast.FunctionDecl.class,
                checked.modules().getFirst().declarations().getFirst());
        Ast.ReturnStmt returned =
                assertInstanceOf(Ast.ReturnStmt.class, fn.body().getFirst());

        List<MacroSyntaxProvenance.Origin> origins =
                MacroSyntaxProvenance.lookup(returned.value());
        assertEquals(1, origins.size());
        MacroSyntaxProvenance.Origin origin = origins.getFirst();
        assertEquals(
                MacroSyntaxProvenance.EmissionKind.CONST_QUOTE,
                origin.emissionKind());
        assertEquals(Parser.ROOT_MODULE, origin.hygiene().module());
        assertEquals("literal", origin.hygiene().macroName());
        assertEquals(0, origin.hygiene().expansionOrdinal());
        assertTrue(origin.hygiene().invocationSite().line() > 0);
    }

    @Test
    void nestedExpansionPreservesInnerAndOuterProvenanceChain() {
        Ast.Program checked = assertDoesNotThrow(
                () -> OresCompiler.parseAndTypeCheck("""
                        define macro identity(Syntax<Expr> value) -> Syntax<Expr> {
                            return value;
                        }
                        define macro literal(Const<int> value) -> Syntax<Expr> {
                            return quote(value);
                        }
                        fnc answer() -> int {
                            return identity!(
                                syntax expr literal!(const 40 + 2)
                            );
                        }
                        """));

        Ast.FunctionDecl fn = assertInstanceOf(
                Ast.FunctionDecl.class,
                checked.modules().getFirst().declarations().getFirst());
        Ast.ReturnStmt returned =
                assertInstanceOf(Ast.ReturnStmt.class, fn.body().getFirst());
        List<MacroSyntaxProvenance.Origin> origins =
                MacroSyntaxProvenance.lookup(returned.value());

        assertEquals(2, origins.size());
        assertEquals(
                MacroSyntaxProvenance.EmissionKind.CONST_QUOTE,
                origins.get(0).emissionKind());
        assertEquals("literal", origins.get(0).hygiene().macroName());
        assertEquals(
                MacroSyntaxProvenance.EmissionKind.CALL_SITE_SPLICE,
                origins.get(1).emissionKind());
        assertEquals("identity", origins.get(1).hygiene().macroName());
        assertNotEquals(
                origins.get(0).hygiene(),
                origins.get(1).hygiene(),
                "nested expansions must retain distinct exact hygiene identities");
    }

    @Test
    void runtimeDependentConstStillFailsClosedWithoutEarlyExecution() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define macro literal(Const<int> value) -> Syntax<Expr> {
                            return quote(value);
                        }
                        fnc answer(int runtimeValue) -> int {
                            return literal!(const runtimeValue + 1);
                        }
                        """));

        assertTrue(failure.getMessage().contains("not a closed compile-time expression"),
                failure::toString);
        assertTrue(failure.getMessage().contains("runtime evaluation fallback is forbidden"),
                failure::toString);
    }

    @Test
    void expansionProvenanceDistinguishesSpliceFromConstQuote() {
        Ast.Program splice = assertDoesNotThrow(
                () -> OresCompiler.parseAndTypeCheck("""
                        define macro identity(Syntax<Expr> value) -> Syntax<Expr> {
                            return value;
                        }
                        fnc answer() -> int {
                            return identity!(syntax expr 42);
                        }
                        """));
        Ast.FunctionDecl spliceFunction = assertInstanceOf(
                Ast.FunctionDecl.class,
                splice.modules().getFirst().declarations().getFirst());
        Ast.Expr spliceExpr = assertInstanceOf(
                Ast.ReturnStmt.class, spliceFunction.body().getFirst()).value();
        List<MacroSyntaxProvenance.Origin> spliceOrigins =
                MacroSyntaxProvenance.lookup(spliceExpr);
        assertEquals(1, spliceOrigins.size());
        assertEquals(MacroSyntaxProvenance.EmissionKind.CALL_SITE_SPLICE,
                spliceOrigins.getFirst().emissionKind());
        assertEquals("identity", spliceOrigins.getFirst().hygiene().macroName());

        Ast.Program quoted = assertDoesNotThrow(
                () -> OresCompiler.parseAndTypeCheck("""
                        define macro literal(Const<int> value) -> Syntax<Expr> {
                            return quote(value);
                        }
                        fnc answer() -> int {
                            return literal!(const 40 + 2);
                        }
                        """));
        Ast.FunctionDecl quoteFunction = assertInstanceOf(
                Ast.FunctionDecl.class,
                quoted.modules().getFirst().declarations().getFirst());
        Ast.Expr quoteExpr = assertInstanceOf(
                Ast.ReturnStmt.class, quoteFunction.body().getFirst()).value();
        List<MacroSyntaxProvenance.Origin> quoteOrigins =
                MacroSyntaxProvenance.lookup(quoteExpr);
        assertEquals(1, quoteOrigins.size());
        assertEquals(MacroSyntaxProvenance.EmissionKind.CONST_QUOTE,
                quoteOrigins.getFirst().emissionKind());
        assertEquals("literal", quoteOrigins.getFirst().hygiene().macroName());
    }

    @Test
    void boundedConstBitwiseAndConditionalEvaluationMatchesRuntimeSemantics() {
        Ast.Program checked = assertDoesNotThrow(
                () -> OresCompiler.parseAndTypeCheck("""
                        define macro literal(Const<int> value) -> Syntax<Expr> {
                            return quote(value);
                        }
                        fnc answer() -> int {
                            return literal!(const true ? ((1 << 4) | 3) : 0);
                        }
                        """));

        Ast.FunctionDecl function = assertInstanceOf(
                Ast.FunctionDecl.class,
                checked.modules().getFirst().declarations().getFirst());
        Ast.ReturnStmt returned = assertInstanceOf(
                Ast.ReturnStmt.class, function.body().getFirst());
        Ast.LiteralExpr emitted = assertInstanceOf(
                Ast.LiteralExpr.class, returned.value());
        assertEquals(19L, emitted.value());
    }

    @Test
    void invalidConstOperationFailsDuringCompileTimeEvaluation() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define macro literal(Const<int> value) -> Syntax<Expr> {
                            return quote(value);
                        }
                        fnc answer() -> int {
                            return literal!(const 7 % 0);
                        }
                        """));

        assertTrue(failure.getMessage().contains("not a valid compile-time constant"),
                failure::toString);
        assertTrue(failure.getMessage().contains("remainder by zero"),
                failure::toString);
        assertTrue(failure.getMessage().contains("runtime evaluation fallback is forbidden"),
                failure::toString);
    }

    @Test
    void constAggregateBudgetFailsClosedDeterministically() {
        String elements = String.join(", ", java.util.Collections.nCopies(257, "1"));
        String source = """
                define macro values(Const<List<int>> value) -> Syntax<Expr> {
                    return quote(value);
                }
                fnc answer() -> List<int> {
                    return values!(const [%s]);
                }
                """.formatted(elements);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));

        assertTrue(failure.getMessage().contains("exceeds deterministic compile-time evaluation budgets"),
                failure::toString);
        assertTrue(failure.getMessage().contains("aggregate element budget"),
                failure::toString);
        assertTrue(failure.getMessage().contains("runtime evaluation fallback is forbidden"),
                failure::toString);
    }

    @Test
    void quoteIntrinsicRequiresDirectCallOrigin() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define macro literal(Const<int> value) -> Syntax<Expr> {
                            return value |> quote();
                        }
                        fnc answer() -> int {
                            return literal!(const 42);
                        }
                        """));

        assertTrue(failure.getMessage().contains("first evaluator slice"),
                failure::toString);
        assertTrue(failure.getMessage().contains("runtime fallback is forbidden"),
                failure::toString);
    }

    @Test
    void quoteCannotArbitrarilyTransformOrCallGuestCodeAtCompileTime() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define macro literal(Const<int> value) -> Syntax<Expr> {
                            return quote(value + 1);
                        }
                        fnc answer() -> int {
                            return literal!(const 42);
                        }
                        """));
        assertTrue(failure.getMessage().contains("first evaluator slice"),
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
