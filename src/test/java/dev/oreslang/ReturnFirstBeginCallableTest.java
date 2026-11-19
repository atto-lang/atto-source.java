package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Return-type-first declarations share ordinary fnc/mtd ASTs and type checking. */
final class ReturnFirstBeginCallableTest {
    @Test
    void fourFreeFunctionSpellingsHaveIdenticalReturnTypesAndBodies() {
        Ast.Program program = Parser.parse("""
                int <- foo()
                begin
                  return 42;
                end.

                int• bar()
                begin
                  return 42;
                end.

                fnc int <- baz()
                begin
                  return 42;
                end;

                fnc int• qux()
                begin
                  return 42;
                end
                """);
        assertEquals(4, program.modules().getFirst().declarations().size());
        for (Ast.Decl declaration : program.modules().getFirst().declarations()) {
            Ast.FunctionDecl fn = assertInstanceOf(Ast.FunctionDecl.class, declaration);
            assertEquals("int", fn.returnType().name());
            assertEquals(1, fn.body().size());
        }
        assertDoesNotThrow(() -> TypeChecker.check(program));
    }

    @Test
    void everyReturnFirstMarkerAndCloserPassesTheSameTypeChecker() {
        for (String header : new String[] {
                "int <- foo()", "int• foo()", "fnc int <- foo()", "fnc int• foo()"
        }) {
            for (String closer : new String[] {"end", "end.", "end;"}) {
                String source = header + "\nbegin\n  return 42;\n" + closer + "\n";
                Ast.Program program = Parser.parse(source);
                Ast.FunctionDecl function = assertInstanceOf(Ast.FunctionDecl.class,
                        program.modules().getFirst().declarations().getFirst());
                assertEquals("int", function.returnType().name(), source);
                assertEquals(1, function.body().size(), source);
                assertDoesNotThrow(() -> TypeChecker.check(program), source);
            }
        }
    }

    @Test
    void classAndActorMethodSpellingsUseTheSameCheckedReceiverSemantics() {
        Ast.Program program = Parser.parse("""
                define class Counter as
                  fnc int <- legacy()
                  begin
                    return 42;
                  end.
                  mtd int• canonical()
                  begin
                    return 42;
                  end;
                  int <- bare()
                  begin
                    return 42;
                  end
                end
                define actor Worker as
                  mtd int <- read()
                  begin
                    return 42;
                  end;
                end
                """);
        assertDoesNotThrow(() -> TypeChecker.check(program));
        Ast.ClassDecl counter = assertInstanceOf(Ast.ClassDecl.class,
                program.modules().getFirst().declarations().get(0));
        assertEquals(3, counter.methods().size());
        for (Ast.MethodDecl method : counter.methods()) {
            assertFalse(method.isStatic());
            assertEquals("int", method.returnType().name());
            assertEquals(1, method.body().size());
        }
        Ast.ClassDecl actor = assertInstanceOf(Ast.ClassDecl.class,
                program.modules().getFirst().declarations().get(1));
        assertEquals("read", actor.methods().getFirst().name());
    }

    @Test
    void rejectAmbiguousBodiesOrReturnTypesRatherThanGuessing() {
        for (String invalid : new String[] {
                "int <- f() begin return 1; end",
                "int <- f() return 1; end",
                "int <- f()\nbegin\n return 1;",
                "fnc int <- f() -> int\nbegin\n return 1;\nend",
                "fnc int• f(): int\nbegin\n return 1;\nend",
                "int < - f()\nbegin\n return 1;\nend",
                "int <- f()\nbegin\n return 1;\nend.."
        }) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(invalid), invalid);
        }
    }

    @Test
    void nestedEndAndStringTextCannotStealCallableCloser() {
        Ast.FunctionDecl fn = assertInstanceOf(Ast.FunctionDecl.class,
                Parser.parse("""
                    string <- text()
                    begin
                      match 1 over
                        on 1 -> { return "begin end; • <-"; }
                        on _ -> { return "none"; }
                      end
                    end.
                    """).modules().getFirst().declarations().getFirst());
        assertEquals("string", fn.returnType().name());
        assertEquals(1, fn.body().size());
    }
    @Test
    void genericsAndDefaultedParametersKeepTheirExistingCallableSemantics() {
        Ast.Program program = Parser.parse("""
                fnc Result<int, string> <- wrap<T>(T value)
                begin
                  return Ok(42);
                end
                define class Generic as
                  fnc int <- choose<T>(T value, int fallback = 2)
                  begin
                    return fallback;
                  end.
                end
                """);
        Ast.FunctionDecl fn = assertInstanceOf(Ast.FunctionDecl.class,
                program.modules().getFirst().declarations().get(0));
        assertEquals("Result", fn.returnType().name());
        assertEquals(1, fn.genericParameters().size());
        Ast.ClassDecl klass = assertInstanceOf(Ast.ClassDecl.class,
                program.modules().getFirst().declarations().get(1));
        assertEquals("choose", klass.methods().getFirst().name());
        assertEquals("int", klass.methods().getFirst().returnType().name());
    }

    @Test
    void returnFirstCannotBypassActorLifecycleAndSupervisorControlRestrictions() {
        for (String name : new String[] {
                "tear_down", "handle_state", "on_panic", "on_throw",
                "on_throws", "on_raise", "on_recover"
        }) {
            for (String marker : new String[] {"<-", "•"}) {
                for (String introducer : new String[] {"", "fnc ", "mtd "}) {
                    String signaturePrefix = marker.equals("•") ? "void• " : "void <- ";
                    String source = "define actor A as\n  " + introducer
                            + signaturePrefix + name
                            + "()\n  begin\n    return;\n  end\nend\n";
                    IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                            () -> Parser.parse(source), source);
                    assertTrue(denied.getMessage().contains("return-first actor lifecycle/CONTROL"),
                            "must reject through the CONTROL-specific gate, not a generic syntax error: "
                                    + source + " => " + denied.getMessage());
                }
            }
        }
    }

    @Test
    void staticReturnFirstFunctionKeepsStaticDispatch() {
        Ast.Program program = Parser.parse("""
                define class Helpers as
                  static fnc int <- utility()
                  begin
                    return 42;
                  end.
                end
                """);
        Ast.ClassDecl klass = assertInstanceOf(Ast.ClassDecl.class,
                program.modules().getFirst().declarations().getFirst());
        assertTrue(klass.methods().getFirst().isStatic());
        assertEquals("int", klass.methods().getFirst().returnType().name());
    }

    @Test
    void tokenizationDoesNotStealLessThanNegativeArithmetic() {
        var kinds = new dev.oreslang.parser.Lexer("1<-2").scan().stream()
                .map(dev.oreslang.parser.Token::type).toList();
        assertEquals(java.util.List.of(
                dev.oreslang.parser.Token.Type.INT,
                dev.oreslang.parser.Token.Type.LT,
                dev.oreslang.parser.Token.Type.MINUS,
                dev.oreslang.parser.Token.Type.INT,
                dev.oreslang.parser.Token.Type.EOF), kinds);
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc compare(): bool {
                  return 1<-2;
                }
                """)));
    }

    @Test
    void standaloneBeginAllowsCommentButRejectsSameLineStatements() {
        Ast.FunctionDecl fn = assertInstanceOf(Ast.FunctionDecl.class,
                Parser.parse("""
                    int <- good()
                    begin // opener comment is allowed
                      return 42;
                    end; // closer comment is allowed
                    """).modules().getFirst().declarations().getFirst());
        assertEquals(1, fn.body().size());
        for (String invalid : new String[] {
                "int <- bad()\nbegin return 42;\nend",
                "int <- bad()\nbegin end",
                "int <- bad()\nbegin;\nreturn 42;\nend"
        }) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(invalid), invalid);
        }
    }

    @Test
    void structuralAndGenericReturnTypesPreserveTheTypeAst() {
        Ast.Program program = Parser.parse("""
                fnc struct{value: int} <- shape()
                begin
                  return value;
                end
                fnc Result<int, string>• result()
                begin
                  return Ok(42);
                end;
                """);
        Ast.FunctionDecl shape = assertInstanceOf(Ast.FunctionDecl.class,
                program.modules().getFirst().declarations().get(0));
        assertEquals(1, shape.body().size());
        Ast.FunctionDecl result = assertInstanceOf(Ast.FunctionDecl.class,
                program.modules().getFirst().declarations().get(1));
        assertEquals("Result", result.returnType().name());
        assertEquals(2, result.returnType().arguments().size());
    }

}
