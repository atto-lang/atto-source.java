package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.BuildOptions;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class TypedExceptionalEffectAdmissionTest {
    @Test
    void exceptionalEffectNamesHaveDedicatedTokens() {
        List<Token.Type> tokens = new Lexer("throw raise panic recover").scan().stream()
                .map(Token::type).toList();
        assertEquals(List.of(Token.Type.THROW, Token.Type.RAISE,
                Token.Type.PANIC, Token.Type.RECOVER, Token.Type.EOF), tokens);
    }

    @Test
    void throwRaiseAndPanicParseAndTypecheckWithStringDiagnostics() {
        for (String effect : List.of("throw", "raise", "panic")) {
            String source = "pub fnc fail(): int { " + effect + " \"boom\"; }";
            Ast.Program program = assertDoesNotThrow(() -> Parser.parse(source), effect);
            assertDoesNotThrow(() -> TypeChecker.check(program), effect);
        }
    }

    @Test
    void recoverRemainsFailClosedAtTheParserBoundary() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("pub fnc main(): void { recover 1; }"));
        assertTrue(failure.getMessage().contains("recover"), failure::getMessage);
        assertTrue(failure.getMessage().contains("not yet enabled"), failure::getMessage);
    }

    @Test
    void terminalEffectDiagnosticsAreStaticallyStringTyped() {
        for (String effect : List.of("throw", "raise", "panic")) {
            Ast.Program program = Parser.parse(
                    "pub fnc bad(): void { " + effect + " 1; }");
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> TypeChecker.check(program),
                    effect);
            assertTrue(
                    failure.getMessage().contains(effect + " diagnostic"),
                    () -> effect + ": " + failure.getMessage());
        }
    }

    @Test
    void childFailureTypeCannotEscapeCheckedSupervisorHandlerParameter() {
        Ast.Program program = Parser.parse("""
                pub fnc leak(ChildFailure failure): void {
                  return;
                }
                """);
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(program));
        assertTrue(failure.getMessage().contains(
                "ChildFailure is a privileged supervisor CONTROL capability"),
                failure.getMessage());
    }

    @Test
    void checkedSupervisorHandlerBodyCanUseClosedChildFailureSurface() {
        String source = """
                define supervisor actor Manager as
                  on_throw(ChildFailure failure): void {
                    stdio.stdout.write(failure.kind);
                    failure.acknowledge();
                    return;
                  }
                  on_raise(ChildFailure failure): void {
                    stdio.stdout.write(failure.failure_type);
                    failure.retain();
                    return;
                  }
                  on_panic(ChildFailure failure): void {
                    stdio.stdout.write(failure.actor_id);
                    stdio.stdout.write(failure.generation);
                    stdio.stdout.write(failure.parent_generation);
                    failure.restart();
                    return;
                  }
                end
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void terminalEffectIsAControlFlowExitForNonVoidFunctions() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc fail(bool panicNow): int {
                  if panicNow then
                    panic "no-result";
                  else
                    return 42;
                  fi
                }
                """)));
    }

    @Test
    void treeShakerPreservesTypedTerminalEffectAndItsDiagnosticExpression() {
        var result = OresCompiler.compileForBuild("""
                pub fnc fail(): int {
                  panic "boom";
                }
                """, BuildOptions.library());

        Ast.FunctionDecl fail = result.program().modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .filter(function -> function.name().equals("fail"))
                .findFirst()
                .orElseThrow();

        Ast.TerminalEffectStmt effect =
                assertInstanceOf(Ast.TerminalEffectStmt.class, fail.body().getFirst());
        assertEquals(Ast.TerminalEffectKind.PANIC, effect.kind());
        assertInstanceOf(Ast.LiteralExpr.class, effect.value());
    }

    @Test
    void reservedKeywordsCannotMasqueradeAsOrdinaryIdentifiers() {
        for (String effect : List.of("throw", "raise", "panic", "recover")) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse("pub fnc " + effect + "(): int { return 1; }"),
                    effect);
        }
    }

    @Test
    void unrelatedFunctionNamesRemainValid() {
        assertDoesNotThrow(() -> Parser.parse("""
                fnc throwable(): int { return 42; }
                fnc recovery(): int { return throwable(); }
                """));
    }
}
