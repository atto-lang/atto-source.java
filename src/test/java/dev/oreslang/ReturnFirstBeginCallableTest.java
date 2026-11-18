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
}
