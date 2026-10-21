package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ParserTest {
    @Test
    void supportsMultipleModulesAndComplexNumbers() {
        String source = """
                define module math
                  fnc z() => complex {
                    return 3 + 4i;
                  }
                end

                define module app
                  pub fnc main() => void {
                    const answer = 40 + 2;
                    [const first, let second] = [1, 2];
                    stdio.println("oreslang");
                    return;
                  }
                end
                """;

        Ast.Program program = TypeChecker.check(Parser.parse(source));
        assertEquals(2, program.modules().size());
        assertEquals("math", program.modules().getFirst().name());
    }

    @Test
    void parsesIfDoFiWithCommaAndPipeConditions() {
        String source = """
                define module app
                  fnc choose(bool a, bool b) => int {
                    if a, b | false; do
                      return 1;
                    else
                      return 0;
                    fi
                  }
                end
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void methodReceiverIsImplicitOrExplicitSelf() {
        String source = """
                define module model
                  define class x
                    @Ret<self>
                    find() {
                      return self;
                    }

                    @Ret<self>
                    find_with_arg(self x)(int foo) {
                      return self;
                    }
                  end
                end
                """;
        Ast.Program program = Parser.parse(source);
        Ast.ClassDecl klass = (Ast.ClassDecl) program.modules().getFirst().declarations().getFirst();
        assertNull(klass.methods().getFirst().explicitReceiverType());
        assertEquals("x", klass.methods().get(1).explicitReceiverType().name());
    }

    @Test
    void lexerRecognizesLambdaAndFatReturnArrows() {
        var tokens = new Lexer("(int x) -> x + 1; fnc f() => int { return 1; }").scan();
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.ARROW));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.FAT_ARROW));
    }
    @Test
    void compactAndLongInterfaceSyntaxNormalizeToTheSameAst() {
        Ast.InterfaceDecl longForm = (Ast.InterfaceDecl) Parser.parse("""
                define interface Lock<T> as
                  fnc lock() => T;
                  String name;
                end
                """).modules().getFirst().declarations().getFirst();

        Ast.InterfaceDecl compact = (Ast.InterfaceDecl) Parser.parse("""
                interface Lock<T> {
                  fnc lock() => T;
                  String name;
                }
                """).modules().getFirst().declarations().getFirst();

        assertEquals(longForm, compact);
    }

    @Test
    void compactAndLongStructSyntaxNormalizeToTheSameAst() {
        Ast.ClassDecl longForm = (Ast.ClassDecl) Parser.parse("""
                define struct Point as
                  f64 x;
                  f64 y;
                end
                """).modules().getFirst().declarations().getFirst();

        Ast.ClassDecl compact = (Ast.ClassDecl) Parser.parse("""
                struct Point {
                  f64 x;
                  f64 y;
                }
                """).modules().getFirst().declarations().getFirst();

        assertTrue(longForm.isStruct());
        assertEquals(Ast.AggregateKind.STRUCT, compact.kind());
        assertEquals(longForm, compact);
        assertEquals(Ast.BindingKind.VAL, compact.fields().getFirst().bindingKind());
    }

    @Test
    void lexerReservesStructAndIs() {
        var tokens = new Lexer("struct Point is Named {}").scan();
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.STRUCT));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.IS));
    }
}
