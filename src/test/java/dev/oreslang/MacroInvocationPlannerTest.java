package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.MacroExpansionPlanner;
import dev.oreslang.compiler.MacroExpander;
import dev.oreslang.compiler.MacroSyntaxProvenance;
import dev.oreslang.compiler.MacroIdentifierHygiene;
import dev.oreslang.compiler.MacroDeclarationProvenance;
import dev.oreslang.compiler.MacroSourceUnitIdentity;
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
        assertEquals(MacroSourceUnitIdentity.MEMORY_UNIT, mark.sourceUnitId());
        assertEquals(Parser.ROOT_MODULE, mark.callerModule());
        assertEquals(Parser.ROOT_MODULE, mark.macroOwnerModule());
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
        MacroExpansionPlanner.HygieneMark qualified =
                plan.workItems().getFirst().hygieneMark();
        assertEquals("Consumer", qualified.callerModule());
        assertEquals("Producer", qualified.macroOwnerModule());
        assertEquals("exposed", qualified.macroName(),
                "canonical macro identity must not include invocation qualification spelling");
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
    void emptyListConstUsesDeclaredElementTypeWithoutRuntimeInference() {
        Ast.Program checked = assertDoesNotThrow(
                () -> OresCompiler.parseAndTypeCheck("""
                        define macro values(Const<List<int>> value) -> Syntax<Expr> {
                            return quote(value);
                        }
                        fnc empty() -> List<int> {
                            return values!(const []);
                        }
                        """));

        assertEquals(1, checked.modules().getFirst().declarations().size(),
                "macro declaration must be erased from executable AST");
        Ast.FunctionDecl fn = assertInstanceOf(
                Ast.FunctionDecl.class,
                checked.modules().getFirst().declarations().getFirst());
        Ast.ReturnStmt returned =
                assertInstanceOf(Ast.ReturnStmt.class, fn.body().getFirst());
        Ast.ListExpr emitted =
                assertInstanceOf(Ast.ListExpr.class, returned.value());
        assertTrue(emitted.elements().isEmpty());

        List<MacroSyntaxProvenance.Origin> origins =
                MacroSyntaxProvenance.lookup(emitted);
        assertEquals(1, origins.size());
        assertEquals(
                MacroSyntaxProvenance.EmissionKind.CONST_QUOTE,
                origins.getFirst().emissionKind());
    }

    @Test
    void hygienicIdentifierCannotCaptureCallerByEqualSpelling() {
        Ast.Program expanded = MacroExpander.expand(Parser.parse("""
                define macro fresh(Identifier hint) -> Syntax<Expr> {
                    return hygienic(hint);
                }

                fnc demo() -> int {
                    const int value = 1;
                    return fresh!(ident value);
                }
                """));

        Ast.FunctionDecl fn = expanded.modules().getFirst().declarations().stream()
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .findFirst()
                .orElseThrow();
        Ast.ReturnStmt returned = assertInstanceOf(
                Ast.ReturnStmt.class,
                fn.body().get(fn.body().size() - 1));
        Ast.NameExpr generated = assertInstanceOf(Ast.NameExpr.class, returned.value());

        assertNotEquals("value", generated.name(),
                "generated identifier must not capture caller binding by spelling");
        assertTrue(generated.name().startsWith("$macro$g$"),
                "generated identifier must use a source-unspellable compiler handle");

        MacroIdentifierHygiene.Generated identity = assertInstanceOf(
                MacroIdentifierHygiene.Generated.class,
                MacroIdentifierHygiene.lookup(generated));
        assertEquals("value", identity.hint());
        assertEquals(MacroSourceUnitIdentity.MEMORY_UNIT,
                identity.hygiene().sourceUnitId());
        assertEquals(Parser.ROOT_MODULE, identity.hygiene().callerModule());
        assertEquals("fresh", identity.hygiene().macroName());

        List<MacroSyntaxProvenance.Origin> origins =
                MacroSyntaxProvenance.lookup(generated);
        assertEquals(1, origins.size());
        assertEquals(
                MacroSyntaxProvenance.EmissionKind.HYGIENIC_IDENTIFIER,
                origins.getFirst().emissionKind());
    }

    @Test
    void hygienicIdentifierDoesNotCaptureCallerLocalDuringFullTypeCheck() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define macro fresh(Identifier hint) -> Syntax<Expr> {
                            return hygienic(hint);
                        }

                        fnc demo() -> int {
                            const int value = 1;
                            return fresh!(ident value);
                        }
                        """));

        assertTrue(failure.getMessage().contains("unknown name '$macro$g$"),
                failure::toString);
        assertFalse(failure.getMessage().contains("unknown name 'value'"),
                "the caller local must not be mistaken for the generated identifier");
    }

    @Test
    void sourceUnitIdentitySeparatesOtherwiseIdenticalHygiene() {
        String source = """
                define macro fresh(Identifier hint) -> Syntax<Expr> {
                    return hygienic(hint);
                }
                fnc demo() -> int {
                    return fresh!(ident value);
                }
                """;

        Ast.Program firstProgram = MacroSourceUnitIdentity.attach(
                Parser.parse(source), "pkg/first.ores");
        Ast.Program secondProgram = MacroSourceUnitIdentity.attach(
                Parser.parse(source), "pkg/second.ores");

        MacroExpansionPlanner.HygieneMark firstMark =
                MacroExpansionPlanner.plan(firstProgram).workItems().getFirst().hygieneMark();
        MacroExpansionPlanner.HygieneMark secondMark =
                MacroExpansionPlanner.plan(secondProgram).workItems().getFirst().hygieneMark();
        assertEquals("pkg/first.ores", firstMark.sourceUnitId());
        assertEquals("pkg/second.ores", secondMark.sourceUnitId());
        assertNotEquals(firstMark, secondMark,
                "same module/line/ordinal in different units must not share hygiene identity");

        Ast.NameExpr firstGenerated = assertInstanceOf(
                Ast.NameExpr.class,
                assertInstanceOf(
                        Ast.ReturnStmt.class,
                        ((Ast.FunctionDecl) MacroExpander.expand(firstProgram)
                                .modules().getFirst().declarations().getFirst())
                                .body().getFirst()).value());
        Ast.NameExpr secondGenerated = assertInstanceOf(
                Ast.NameExpr.class,
                assertInstanceOf(
                        Ast.ReturnStmt.class,
                        ((Ast.FunctionDecl) MacroExpander.expand(secondProgram)
                                .modules().getFirst().declarations().getFirst())
                                .body().getFirst()).value());

        assertNotEquals(firstGenerated.name(), secondGenerated.name(),
                "compiler-internal generated handles must be collision-free across units");
        MacroIdentifierHygiene.Generated firstIdentity = assertInstanceOf(
                MacroIdentifierHygiene.Generated.class,
                MacroIdentifierHygiene.lookup(firstGenerated));
        MacroIdentifierHygiene.Generated secondIdentity = assertInstanceOf(
                MacroIdentifierHygiene.Generated.class,
                MacroIdentifierHygiene.lookup(secondGenerated));
        assertEquals("pkg/first.ores", firstIdentity.hygiene().sourceUnitId());
        assertEquals("pkg/second.ores", secondIdentity.hygiene().sourceUnitId());
    }

    @Test
    void checkedFunctionCaptureLowersPublicFunctionToUnshadowableModuleRef() {
        Ast.Program expanded = MacroExpander.expand(Parser.parse("""
                pub fnc helper() -> int {
                    return 42;
                }

                define macro reference(Identifier target) -> Syntax<Expr> {
                    return capture_fnc(target);
                }

                fnc demo() -> int {
                    return reference!(ident helper);
                }
                """));

        Ast.FunctionDecl demo = expanded.modules().getFirst().declarations().stream()
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .filter(fn -> fn.name().equals("demo"))
                .findFirst()
                .orElseThrow();
        Ast.ReturnStmt returned =
                assertInstanceOf(Ast.ReturnStmt.class, demo.body().getFirst());
        Ast.MemberExpr captured =
                assertInstanceOf(Ast.MemberExpr.class, returned.value());
        Ast.NameExpr moduleRef =
                assertInstanceOf(Ast.NameExpr.class, captured.receiver());
        assertEquals(Parser.ROOT_MODULE,
                Ast.internalModuleReferenceTarget(moduleRef.name()));
        assertEquals("helper", captured.member());

        MacroIdentifierHygiene.CapturedFunction identity = assertInstanceOf(
                MacroIdentifierHygiene.CapturedFunction.class,
                MacroIdentifierHygiene.lookup(captured));
        assertEquals(MacroSourceUnitIdentity.MEMORY_UNIT,
                identity.hygiene().sourceUnitId());
        assertEquals(Parser.ROOT_MODULE, identity.hygiene().callerModule());
        assertEquals(Parser.ROOT_MODULE, identity.hygiene().macroOwnerModule());
        assertEquals("helper", identity.name());
        assertEquals(1, identity.declarations().size());
        assertEquals(Ast.Visibility.PUBLIC,
                identity.declarations().getFirst().visibility());

        assertEquals(
                MacroSyntaxProvenance.EmissionKind.CHECKED_CAPTURE,
                MacroSyntaxProvenance.lookup(captured).getFirst().emissionKind());
    }

    @Test
    void checkedFunctionCaptureRejectsSameModulePrivateFunction() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> MacroExpander.expand(Parser.parse("""
                        fnc helper() -> int {
                            return 42;
                        }

                        define macro reference(Identifier target) -> Syntax<Expr> {
                            return capture_fnc(target);
                        }

                        fnc demo() -> int {
                            return reference!(ident helper);
                        }
                        """)));

        assertTrue(failure.getMessage().contains(
                "capture_fnc requires public function 'helper'"),
                failure::toString);
        assertTrue(failure.getMessage().contains(
                "private function capture remains closed"),
                failure::toString);
    }

    @Test
    void checkedFunctionCaptureCannotBeShadowedByCallerLocal() {
        assertDoesNotThrow(
                () -> OresCompiler.parseAndTypeCheck("""
                        pub fnc helper() -> int {
                            return 42;
                        }

                        define macro reference(Identifier target) -> Syntax<Expr> {
                            return capture_fnc(target);
                        }

                        fnc demo() -> int {
                            const int helper = 1;
                            return reference!(ident helper)();
                        }
                        """));
    }

    @Test
    void externalMacroMayCapturePublicCallerFunctionExplicitly() {
        Ast.Program expanded = assertDoesNotThrow(
                () -> MacroExpander.expand(Parser.parse("""
                        define module Producer as
                            define pub macro reference(Identifier target) -> Syntax<Expr> {
                                return capture_fnc(target);
                            }
                        end

                        define module Consumer as
                            pub fnc exposed() -> int {
                                return 7;
                            }

                            fnc demo() -> int {
                                return Producer.reference!(ident exposed);
                            }
                        end
                        """)));

        Ast.ModuleDecl consumer = expanded.modules().stream()
                .filter(module -> module.name().equals("Consumer"))
                .findFirst()
                .orElseThrow();
        Ast.FunctionDecl demo = consumer.declarations().stream()
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .filter(fn -> fn.name().equals("demo"))
                .findFirst()
                .orElseThrow();
        Ast.MemberExpr captured = assertInstanceOf(
                Ast.MemberExpr.class,
                assertInstanceOf(Ast.ReturnStmt.class, demo.body().getFirst()).value());
        Ast.NameExpr moduleRef = assertInstanceOf(
                Ast.NameExpr.class, captured.receiver());
        assertEquals("Consumer",
                Ast.internalModuleReferenceTarget(moduleRef.name()));
        assertEquals("exposed", captured.member());
        MacroIdentifierHygiene.CapturedFunction identity = assertInstanceOf(
                MacroIdentifierHygiene.CapturedFunction.class,
                MacroIdentifierHygiene.lookup(captured));
        assertEquals(MacroSourceUnitIdentity.MEMORY_UNIT,
                identity.hygiene().sourceUnitId());
        assertEquals("Consumer", identity.hygiene().callerModule());
        assertEquals("Producer", identity.hygiene().macroOwnerModule());
        assertTrue(identity.declarations().stream()
                .allMatch(fn -> fn.visibility() == Ast.Visibility.PUBLIC));
    }

    @Test
    void externalMacroCannotCapturePrivateCallerFunction() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> MacroExpander.expand(Parser.parse("""
                        define module Producer as
                            define pub macro reference(Identifier target) -> Syntax<Expr> {
                                return capture_fnc(target);
                            }
                        end

                        define module Consumer as
                            fnc secret() -> int {
                                return 7;
                            }

                            fnc demo() -> int {
                                return Producer.reference!(ident secret);
                            }
                        end
                        """)));

        assertTrue(failure.getMessage().contains("capture_fnc"), failure::toString);
        assertTrue(failure.getMessage().contains(
                "capture_fnc requires public function 'secret'"),
                failure::toString);
        assertTrue(failure.getMessage().contains(
                "private function capture remains closed"),
                failure::toString);
    }

    @Test
    void checkedFunctionCaptureRejectsWrongDeclarationKind() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> MacroExpander.expand(Parser.parse("""
                        const int helper = 1;

                        define macro reference(Identifier target) -> Syntax<Expr> {
                            return capture_fnc(target);
                        }

                        fnc demo() -> int {
                            return reference!(ident helper);
                        }
                        """)));

        assertTrue(failure.getMessage().contains("could not resolve ordinary module-level function 'helper'"),
                failure::toString);
    }

    @Test
    void checkedFunctionCaptureRejectsOverloadedFamily() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> MacroExpander.expand(Parser.parse("""
                        pub fnc helper() -> int {
                            return 1;
                        }
                        pub fnc helper(int value) -> int {
                            return value;
                        }

                        define macro reference(Identifier target) -> Syntax<Expr> {
                            return capture_fnc(target);
                        }

                        fnc demo() -> int {
                            return 0;
                        }
                        fnc captured() -> void {
                            reference!(ident helper);
                            return;
                        }
                        """)));

        assertTrue(failure.getMessage().contains(
                "cannot reify overloaded function family 'helper'"),
                failure::toString);
    }

    @Test
    void checkedFunctionCaptureRejectsRoutine() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> MacroExpander.expand(Parser.parse("""
                        pub routine helper() -> int {
                            return 1;
                        }

                        define macro reference(Identifier target) -> Syntax<Expr> {
                            return capture_fnc(target);
                        }

                        fnc demo() -> int {
                            return reference!(ident helper);
                        }
                        """)));

        assertTrue(failure.getMessage().contains(
                "cannot reify routine 'helper'"),
                failure::toString);
    }

    @Test
    void checkedFunctionCaptureRejectsUnspecializedGeneric() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> MacroExpander.expand(Parser.parse("""
                        pub fnc helper<T>(T value) -> T {
                            return value;
                        }

                        define macro reference(Identifier target) -> Syntax<Expr> {
                            return capture_fnc(target);
                        }

                        fnc demo() -> int {
                            return 0;
                        }
                        fnc captured() -> void {
                            reference!(ident helper);
                            return;
                        }
                        """)));

        assertTrue(failure.getMessage().contains(
                "cannot reify generic function 'helper'"),
                failure::toString);
    }

    @Test
    void checkedFunctionCaptureRejectsSuspendsFunction() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> MacroExpander.expand(Parser.parse("""
                        pub suspends fnc helper() -> int {
                            return 1;
                        }

                        define macro reference(Identifier target) -> Syntax<Expr> {
                            return capture_fnc(target);
                        }

                        fnc demo() -> int {
                            return reference!(ident helper);
                        }
                        """)));

        assertTrue(failure.getMessage().contains(
                "cannot reify suspends function 'helper'"),
                failure::toString);
    }

    @Test
    void checkedFunctionCaptureDoesNotGrantActorAuthority() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> MacroExpander.expand(Parser.parse("""
                        actor fnc helper() -> int {
                            return 7;
                        }

                        define macro reference(Identifier target) -> Syntax<Expr> {
                            return capture_fnc(target);
                        }

                        fnc demo() -> int {
                            return reference!(ident helper);
                        }
                        """)));

        assertTrue(failure.getMessage().contains(
                "could not resolve ordinary module-level function 'helper'"),
                failure::toString);
    }

    @Test
    void declareFncIntrinsicRequiresDirectCallOrigin() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define macro make(
                            Identifier name,
                            Type result,
                            Syntax<Expr> body
                        ) -> Syntax<Decl> {
                            return name |> declare_fnc(result, body);
                        }

                        make!(ident answer, type int, syntax expr 42);
                        """));

        assertTrue(failure.getMessage().contains("private zero-arg declare_fnc")
                        || failure.getMessage().contains(
                                "declaration output currently admits only declare_fnc"),
                failure::toString);
        assertTrue(failure.getMessage().contains("runtime fallback is forbidden"),
                failure::toString);
    }

    @Test
    void declareFncProducesPrivateDeclarationAndReentersTypeChecking() {
        Ast.Program checked = assertDoesNotThrow(
                () -> OresCompiler.parseAndTypeCheck("""
                        define macro make(
                            Identifier name,
                            Type result,
                            Syntax<Expr> body
                        ) -> Syntax<Decl> {
                            return declare_fnc(name, result, body);
                        }

                        make!(ident answer, type int, syntax expr 40 + 2);

                        fnc main() -> int {
                            return answer();
                        }
                        """));

        Ast.ModuleDecl root = checked.modules().getFirst();
        Ast.FunctionDecl generated = root.declarations().stream()
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .filter(fn -> fn.name().equals("answer"))
                .findFirst()
                .orElseThrow();
        assertEquals(Ast.Visibility.PRIVATE, generated.visibility());
        assertEquals(Ast.ActorKind.NONE, generated.actorKind());
        assertTrue(generated.parameters().isEmpty());
        assertEquals("int", generated.returnType().name());

        MacroDeclarationProvenance.Entry declarationEntry =
                MacroDeclarationProvenance.lookupEntry(generated);
        assertNotNull(declarationEntry);
        MacroSyntaxProvenance.Origin origin = declarationEntry.origin();
        assertEquals(
                MacroSyntaxProvenance.EmissionKind.DECLARE_FUNCTION,
                origin.emissionKind());
        assertEquals("answer", declarationEntry.exposedName().name());
        assertEquals("declare_fnc", declarationEntry.exposedName().builder());
        assertTrue(declarationEntry.exposedName().identifierSite().line() > 0);
        assertTrue(declarationEntry.exposedName().identifierSite().column() > 0);
        assertEquals(MacroSourceUnitIdentity.MEMORY_UNIT,
                origin.hygiene().sourceUnitId());
        assertEquals("make", origin.hygiene().macroName());

        Ast.ReturnStmt returned = assertInstanceOf(
                Ast.ReturnStmt.class, generated.body().getFirst());
        List<MacroSyntaxProvenance.Origin> expressionOrigins =
                MacroSyntaxProvenance.lookup(returned.value());
        assertEquals(1, expressionOrigins.size());
        assertEquals(origin, expressionOrigins.getFirst());
    }

    @Test
    void declareFncExpandsNestedExpressionMacrosBeforeSemanticChecks() {
        Ast.Program checked = assertDoesNotThrow(
                () -> OresCompiler.parseAndTypeCheck("""
                        define macro literal(Const<int> value) -> Syntax<Expr> {
                            return quote(value);
                        }

                        define macro make(
                            Identifier name,
                            Type result,
                            Syntax<Expr> body
                        ) -> Syntax<Decl> {
                            return declare_fnc(name, result, body);
                        }

                        make!(
                            ident answer,
                            type int,
                            syntax expr literal!(const 40 + 2)
                        );
                        """));

        Ast.FunctionDecl generated = checked.modules().getFirst().declarations().stream()
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .filter(fn -> fn.name().equals("answer"))
                .findFirst()
                .orElseThrow();
        Ast.Expr value = assertInstanceOf(
                Ast.ReturnStmt.class, generated.body().getFirst()).value();
        Ast.LiteralExpr literal = assertInstanceOf(Ast.LiteralExpr.class, value);
        assertEquals(42L, literal.value());

        List<MacroSyntaxProvenance.Origin> origins =
                MacroSyntaxProvenance.lookup(value);
        assertEquals(2, origins.size(),
                "nested literal origin and outer declaration origin must both survive");
        assertEquals(MacroSyntaxProvenance.EmissionKind.CONST_QUOTE,
                origins.get(0).emissionKind());
        assertEquals(MacroSyntaxProvenance.EmissionKind.DECLARE_FUNCTION,
                origins.get(1).emissionKind());
    }

    @Test
    void declareFncRejectsStableNameCollisionBeforePublication() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define macro make(
                            Identifier name,
                            Type result,
                            Syntax<Expr> body
                        ) -> Syntax<Decl> {
                            return declare_fnc(name, result, body);
                        }

                        fnc answer() -> int {
                            return 1;
                        }

                        make!(ident answer, type int, syntax expr 2);
                        """));

        assertTrue(failure.getMessage().contains("declare_fnc generated name 'answer'"),
                failure::toString);
        assertTrue(failure.getMessage().contains("collides with an existing declaration"),
                failure::toString);
    }

    @Test
    void declareFncRejectsCollisionBetweenGeneratedDeclarations() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define macro make(
                            Identifier name,
                            Type result,
                            Syntax<Expr> body
                        ) -> Syntax<Decl> {
                            return declare_fnc(name, result, body);
                        }

                        make!(ident answer, type int, syntax expr 1);
                        make!(ident answer, type int, syntax expr 2);
                        """));

        assertTrue(failure.getMessage().contains("declare_fnc generated name 'answer'"),
                failure::toString);
    }

    @Test
    void generatedFunctionBodyMustPassOrdinaryTypeChecking() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define macro make(
                            Identifier name,
                            Type result,
                            Syntax<Expr> body
                        ) -> Syntax<Decl> {
                            return declare_fnc(name, result, body);
                        }

                        make!(ident answer, type int, syntax expr "wrong");
                        """));

        assertFalse(failure.getMessage().contains("runtime fallback"),
                "failure should come from ordinary semantic rechecking, not a macro fallback gate");
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
        assertEquals(MacroSourceUnitIdentity.MEMORY_UNIT,
                origin.hygiene().sourceUnitId());
        assertEquals(Parser.ROOT_MODULE, origin.hygiene().callerModule());
        assertEquals(Parser.ROOT_MODULE, origin.hygiene().macroOwnerModule());
        assertEquals("literal", origin.hygiene().macroName());
        assertEquals(0, origin.hygiene().expansionOrdinal());
        assertTrue(origin.hygiene().invocationSite().line() > 0);
        assertTrue(origin.definitionSite().line() > 0);
        assertTrue(origin.definitionSite().column() > 0);
        assertNotEquals(origin.definitionSite(), origin.hygiene().invocationSite(),
                "definition and invocation provenance must remain distinct");
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
    void qualifiedExpansionProvenanceUsesCanonicalMacroIdentity() {
        Ast.Program checked = assertDoesNotThrow(
                () -> OresCompiler.parseAndTypeCheck("""
                        define module Producer as
                            define pub macro identity(Syntax<Expr> value) -> Syntax<Expr> {
                                return value;
                            }
                        end

                        define module Consumer as
                            fnc answer() -> int {
                                return Producer.identity!(syntax expr 42);
                            }
                        end
                        """));

        Ast.ModuleDecl consumer = checked.modules().stream()
                .filter(module -> module.name().equals("Consumer"))
                .findFirst()
                .orElseThrow();
        Ast.FunctionDecl fn = assertInstanceOf(
                Ast.FunctionDecl.class, consumer.declarations().getFirst());
        Ast.ReturnStmt returned =
                assertInstanceOf(Ast.ReturnStmt.class, fn.body().getFirst());

        MacroSyntaxProvenance.HygieneIdentity hygiene =
                MacroSyntaxProvenance.lookup(returned.value())
                        .getFirst()
                        .hygiene();
        assertEquals("Consumer", hygiene.callerModule());
        assertEquals("Producer", hygiene.macroOwnerModule());
        assertEquals("identity", hygiene.macroName());
        assertEquals("Producer.identity", hygiene.qualifiedMacroName());
        MacroSyntaxProvenance.Origin qualifiedOrigin =
                MacroSyntaxProvenance.lookup(returned.value()).getFirst();
        assertTrue(qualifiedOrigin.definitionSite().line() > 0);
        assertNotEquals(
                qualifiedOrigin.definitionSite(),
                qualifiedOrigin.hygiene().invocationSite());
    }

    @Test
    void repeatedUnquoteOfUnprovenIdentifierFailsBeforeTypeAndOwnershipChecking() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define macro duplicate(Syntax<Expr> value) -> Syntax<Expr> {
                            return quote([unquote(value), unquote(value)]);
                        }
                        fnc answer() -> List<int> {
                            let int input = 21;
                            return duplicate!(syntax expr input);
                        }
                        """));
        assertTrue(failure.getMessage().contains("repeated unquote"),
                failure::toString);
        assertTrue(failure.getMessage().contains("duplicate effects or ownership transfers"),
                failure::toString);
        assertTrue(failure.getMessage().contains("runtime fallback is forbidden"),
                failure::toString);
    }

    @Test
    void identifierFreeTypedQuoteTemplateSplicesSyntaxParameters() {
        Ast.Program checked = assertDoesNotThrow(
                () -> OresCompiler.parseAndTypeCheck("""
                        define macro twice(Syntax<Expr> value) -> Syntax<Expr> {
                            return quote(unquote(value) + unquote(value));
                        }
                        fnc answer() -> int {
                            return twice!(syntax expr 21);
                        }
                        """));

        Ast.FunctionDecl function = assertInstanceOf(
                Ast.FunctionDecl.class,
                checked.modules().getFirst().declarations().getFirst());
        Ast.ReturnStmt returned = assertInstanceOf(
                Ast.ReturnStmt.class, function.body().getFirst());
        Ast.BinaryExpr expanded = assertInstanceOf(
                Ast.BinaryExpr.class, returned.value());
        assertEquals("+", expanded.operator());
        assertEquals(21L,
                assertInstanceOf(Ast.LiteralExpr.class, expanded.left()).value());
        assertEquals(21L,
                assertInstanceOf(Ast.LiteralExpr.class, expanded.right()).value());
        assertNotSame(expanded.left(), expanded.right(),
                "duplicated unquote occurrences must not alias one AST identity");

        List<MacroSyntaxProvenance.Origin> origins =
                MacroSyntaxProvenance.lookup(expanded);
        assertEquals(1, origins.size());
        assertEquals(MacroSyntaxProvenance.EmissionKind.TEMPLATE_QUOTE,
                origins.getFirst().emissionKind());
        assertEquals("twice", origins.getFirst().hygiene().macroName());
    }

    @Test
    void typedQuoteLiteralNodesHaveDistinctIdentitiesAndInvocationOrigins() {
        Ast.Program checked = assertDoesNotThrow(
                () -> OresCompiler.parseAndTypeCheck("""
                        define macro add_one(Syntax<Expr> value) -> Syntax<Expr> {
                            return quote(unquote(value) + 1);
                        }
                        fnc first() -> int {
                            return add_one!(syntax expr 40);
                        }
                        fnc second() -> int {
                            return add_one!(syntax expr 41);
                        }
                        """));

        Ast.FunctionDecl firstFn = assertInstanceOf(
                Ast.FunctionDecl.class,
                checked.modules().getFirst().declarations().get(0));
        Ast.FunctionDecl secondFn = assertInstanceOf(
                Ast.FunctionDecl.class,
                checked.modules().getFirst().declarations().get(1));
        Ast.BinaryExpr first = assertInstanceOf(
                Ast.BinaryExpr.class,
                assertInstanceOf(Ast.ReturnStmt.class, firstFn.body().getFirst()).value());
        Ast.BinaryExpr second = assertInstanceOf(
                Ast.BinaryExpr.class,
                assertInstanceOf(Ast.ReturnStmt.class, secondFn.body().getFirst()).value());

        assertEquals(1L, assertInstanceOf(Ast.LiteralExpr.class, first.right()).value());
        assertEquals(1L, assertInstanceOf(Ast.LiteralExpr.class, second.right()).value());
        assertNotSame(first.right(), second.right(),
                "separate macro invocations must not share compiler AST node identities");

        List<MacroSyntaxProvenance.Origin> firstOrigins =
                MacroSyntaxProvenance.lookup(first.right());
        List<MacroSyntaxProvenance.Origin> secondOrigins =
                MacroSyntaxProvenance.lookup(second.right());
        assertEquals(1, firstOrigins.size(),
                "the first generated literal has only its own invocation origin");
        assertEquals(1, secondOrigins.size(),
                "the second generated literal has only its own invocation origin");
        assertEquals(MacroSyntaxProvenance.EmissionKind.TEMPLATE_QUOTE,
                firstOrigins.getFirst().emissionKind());
        assertEquals(MacroSyntaxProvenance.EmissionKind.TEMPLATE_QUOTE,
                secondOrigins.getFirst().emissionKind());
        assertNotEquals(firstOrigins.getFirst().hygiene().invocationSite(),
                secondOrigins.getFirst().hygiene().invocationSite());
    }

    @Test
    void duplicatedUnquoteCannotCloneEffectsOrOwnershipTransfers() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define class Box as
                            pub let int value = 7;
                        end

                        define macro duplicate(Syntax<Expr> value) -> Syntax<Expr> {
                            return quote([unquote(value), unquote(value)]);
                        }

                        fnc bad() -> List<Box> {
                            let Box box = new Box();
                            return duplicate!(syntax expr rt take box);
                        }
                        """));

        assertTrue(failure.getMessage().contains("repeated unquote"),
                failure::toString);
        assertTrue(failure.getMessage().contains("duplicate effects or ownership transfers"),
                failure::toString);
        assertTrue(failure.getMessage().contains("runtime fallback is forbidden"),
                failure::toString);
    }

    @Test
    void typedQuoteTemplateCannotIntroduceUnhygienicIdentifier() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define macro bad(Syntax<Expr> value) -> Syntax<Expr> {
                            return quote(helper(unquote(value)));
                        }
                        fnc answer(int input) -> int {
                            return bad!(syntax expr input);
                        }
                        """));

        assertTrue(failure.getMessage().contains("identifier-free"),
                failure::toString);
        assertTrue(failure.getMessage().contains("runtime fallback is forbidden"),
                failure::toString);
    }

    @Test
    void typedUnquoteIntrinsicCannotBeShadowedInsideMacroScope() {
        IllegalArgumentException parameterFailure = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define macro bad(Syntax<Expr> unquote) -> Syntax<Expr> {
                            return quote(unquote(unquote));
                        }
                        """));
        assertTrue(parameterFailure.getMessage().contains("'unquote' is reserved"),
                parameterFailure::toString);

        IllegalArgumentException localFailure = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define macro bad(Syntax<Expr> value) -> Syntax<Expr> {
                            const unquote = value;
                            return quote(unquote(value));
                        }
                        """));
        assertTrue(localFailure.getMessage().contains("'unquote' is reserved"),
                localFailure::toString);
        assertTrue(localFailure.getMessage().contains("cannot be rebound"),
                localFailure::toString);
    }

    @Test
    void typedUnquoteIntrinsicRequiresDirectCallOrigin() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define macro bad(Syntax<Expr> value) -> Syntax<Expr> {
                            return quote(value |> unquote());
                        }
                        fnc answer(int input) -> int {
                            return bad!(syntax expr input);
                        }
                        """));

        assertTrue(failure.getMessage().contains("identifier-free"),
                failure::toString);
        assertTrue(failure.getMessage().contains("runtime fallback is forbidden"),
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
                            return literal!(const 7 / 0);
                        }
                        """));

        assertTrue(failure.getMessage().contains("not a valid compile-time constant"),
                failure::toString);
        assertTrue(failure.getMessage().contains("non-finite compile-time arithmetic"),
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

    @Test
    void templateRejectsUnusedQuotedInputBeforeTypeOrOwnershipChecks() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define macro discard(Syntax<Expr> value) -> Syntax<Expr> {
                            return quote(42);
                        }
                        fnc next() -> int { return 7; }
                        fnc answer() -> int {
                            return discard!(syntax expr next());
                        }
                        """));
        assertTrue(failure.getMessage().contains("first evaluator slice")
                        || failure.getMessage().contains("discarded before semantic checking"),
                failure::toString);
        assertTrue(failure.getMessage().contains("runtime fallback is forbidden"),
                failure::toString);
    }

    @Test
    void templateRejectsRepeatedSideEffectfulExpressionSplicing() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define macro twice(Syntax<Expr> value) -> Syntax<Expr> {
                            return quote(unquote(value) + unquote(value));
                        }
                        fnc next() -> int { return 7; }
                        fnc answer() -> int {
                            return twice!(syntax expr next());
                        }
                        """));
        assertTrue(failure.getMessage().contains("may duplicate effects or ownership transfers"),
                failure::toString);
        assertTrue(failure.getMessage().contains("runtime fallback is forbidden"),
                failure::toString);
    }

    @Test
    void templateRejectsRepeatedOwnershipOperationBeforeExpansion() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define macro twice(Syntax<Expr> value) -> Syntax<Expr> {
                            return quote(unquote(value) + unquote(value));
                        }
                        fnc answer() -> int {
                            let int amount = 7;
                            return twice!(syntax expr rt take amount);
                        }
                        """));
        assertTrue(failure.getMessage().contains("may duplicate effects or ownership transfers"),
                failure::toString);
    }

    @Test
    void templateRejectsExcessiveGeneratedStructureDeterministically() {
        String entries = String.join(", ", java.util.Collections.nCopies(2050, "1"));
        String source = """
                define macro values() -> Syntax<Expr> {
                    return quote([%s]);
                }
                fnc answer() -> List<int> {
                    return values!();
                }
                """.formatted(entries);
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(failure.getMessage().contains("template budget exceeded"),
                failure::toString);
        assertTrue(failure.getMessage().contains("runtime fallback is forbidden"),
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
