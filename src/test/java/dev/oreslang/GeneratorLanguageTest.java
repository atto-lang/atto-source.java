package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class GeneratorLanguageTest {
    @Test
    void yieldIsAnExpressionAndReceivesTheNextValue() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                define module app
                  fnc exchange(int first) => Generator<int, String, int> {
                    val int reply = yield first;
                    yield reply + 1;
                    return "done";
                  }
                end
                """));

        Ast.FunctionDecl fn = (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
        Ast.BindingStmt binding = (Ast.BindingStmt) fn.body().getFirst();
        assertInstanceOf(Ast.YieldExpr.class, binding.initializer());

        Ast.ExprStmt second = (Ast.ExprStmt) fn.body().get(1);
        assertInstanceOf(Ast.YieldExpr.class, second.expression());
    }

    @Test
    void shorthandGeneratorSupportsSimpleIteratorYield() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc numbers() => Generator<int> {
                    yield 1;
                    yield 2;
                    return;
                  }

                  fnc sum(Generator<int> values) => int {
                    let int total = 0;
                    for (val item of values) {
                      total = total + item;
                    }
                    return total;
                  }
                end
                """)));
    }

    @Test
    void yieldOutsideGeneratorIsRejected() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module app
                          fnc bad() => int {
                            yield 1;
                            return 2;
                          }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("yield"));
        assertTrue(failure.getMessage().contains("Generator"));
    }

    @Test
    void wrongYieldTypeIsRejected() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module app
                          fnc bad() => Generator<int> {
                            yield "not-an-int";
                            return;
                          }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("yield value"));
    }

    @Test
    void generatorFinalReturnUsesRNotTheGeneratorObjectType() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc values() => Generator<int, String, void> {
                    yield 1;
                    return "complete";
                  }
                end
                """)));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module app
                          fnc bad() => Generator<int, String, void> {
                            yield 1;
                            return 42;
                          }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("return value"));
    }

    @Test
    void actorCallableCannotLeakGeneratorFrameAcrossBoundary() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub actor fnc stream() => Generator<int> {
                          yield 1;
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("Generator"));
        assertTrue(failure.getMessage().contains("actor"));
    }

    @Test
    void yieldRequiresAValue() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        fnc bad() => Generator<int> {
                          yield;
                          return;
                        }
                        """));
        assertTrue(failure.getMessage().contains("yield requires a value"));
    }
}
