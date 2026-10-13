package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class SymbolLiteralSyntaxTest {
    @Test
    void lexerDistinguishesHashLiteralAndExistingColonSeparator() {
        List<Token> tokens = new Lexer("let a: Symbol = #ready; let x = infer struct{foo: #class};").scan();
        assertEquals(2, tokens.stream().filter(t -> t.type() == Token.Type.SYMBOL_LITERAL).count());
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.COLON));
        assertEquals(List.of("ready", "class"), tokens.stream()
                .filter(t -> t.type() == Token.Type.SYMBOL_LITERAL)
                .map(Token::lexeme).toList());
    }

    @Test
    void quotedSymbolsNormalizeToOneLogicalName() {
        List<Token> tokens = new Lexer("#'ready foo' #\"ready foo\" #\"cafe\\u0301\"").scan();
        assertEquals("ready foo", tokens.get(0).lexeme());
        assertEquals(tokens.get(0).lexeme(), tokens.get(1).lexeme());
        assertEquals("café", tokens.get(2).lexeme());
    }

    @Test
    void symbolIsNominalAndWorksInClosedStringKeyedRecordValues() {
        Ast.Program program = Parser.parse("""
                fnc make(): Symbol {
                  let a: Symbol = #ready;
                  let payload = infer struct{status: #ready, text: "ready"};
                  return a;
                }
                """);
        assertDoesNotThrow(() -> TypeChecker.checkTypes(program));
        // The field is still the *textual* field "status"; the value is Symbol.
        Ast.FunctionDecl fn = (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
        Ast.BindingStmt payload = fn.body().stream().filter(x -> x instanceof Ast.BindingStmt b
                && b.name().equals("payload")).map(x -> (Ast.BindingStmt) x).findFirst().orElseThrow();
        Ast.ObjectExpr fields = assertInstanceOf(Ast.ObjectExpr.class, payload.initializer());
        assertEquals("status", fields.fields().getFirst().name());
        assertInstanceOf(Ast.Symbol.class,
                ((Ast.LiteralExpr) fields.fields().getFirst().value()).value());
    }

    @Test
    void invalidPrefixesDoNotSilentlyBecomeFieldsOrComments() {
        for (String source : List.of("#", "# ready", "#123", ":ready", "#\"unterminated")) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse("fnc main(): void { let v = " + source + "; }"),
                    source);
        }
        assertThrows(IllegalArgumentException.class, () ->
                Parser.parse("fnc main(): void { let v = infer struct{#foo: 1}; }"));
        assertThrows(IllegalArgumentException.class, () ->
                Parser.parse("fnc main(): void { let v = infer struct{[#foo]: 1}; }"));
    }

    @Test
    void mistakenTypesFailBeforeRuntime() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.checkTypes(
                Parser.parse("fnc fail(): int { return #ready; }")));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.checkTypes(
                Parser.parse("fnc fail(): Symbol { return \"ready\"; }")));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.checkTypes(
                Parser.parse("fnc fail(): Symbol { return new Symbol(\"ready\"); }")));
    }
}
