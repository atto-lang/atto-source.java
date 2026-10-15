package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** First-stage syntax is admitted only with synchronous local ownership proof. */
final class PostfixQuestionAdmissionTest {
    @Test
    void ternaryRemainsTernaryRatherThanEarlyReturn() {
        assertDoesNotThrow(() -> Parser.parse("""
                fnc choose(bool flag): int {
                  return flag ? 1 : 2;
                }
                fnc choose_compact(bool flag): int {
                  return flag?1:2;
                }
                """));
    }

    @Test
    void asQuestionCastRemainsAnOptionalCast() {
        assertDoesNotThrow(() -> Parser.parse("""
                fnc cast_value(Any value): Option<int> {
                  return value as? int;
                }
                """));
    }

    @Test
    void completedPostfixQuestionHasADedicatedAstNode() {
        Ast.Program parsed = assertDoesNotThrow(() -> Parser.parse("""
                fnc value(Result<int, String> incoming): Result<int, String> {
                  val int n = incoming?;
                  return Ok(n);
                }
                """));
        Ast.FunctionDecl fn = assertInstanceOf(Ast.FunctionDecl.class,
                parsed.modules().getFirst().declarations().getFirst());
        Ast.BindingStmt statement = assertInstanceOf(Ast.BindingStmt.class,
                fn.body().getFirst());
        assertInstanceOf(Ast.PropagateExpr.class, statement.initializer());
    }

    @Test
    void unsupportedPostfixQuestionContextsFailAtTypeOrOwnershipChecking() {
        for (String source : new String[] {
                "fnc wrong(Result<int, String> v): int { return v?; }",
                "fnc wrong(Option<int> v): Result<int, String> { return Ok(v?); }",
                "fnc wrong(Future<int> v): Result<int, String> { return Ok(v?); }",
                "fnc wrong(Result<int, int> v): Result<int, String> { return Ok(v?); }",
                "async fnc wrong(Result<int, String> v): Result<int, String> { return Ok(v?); }",
                "fnc direct(): Result<int, String> { return Ok(1); } fnc wrong(): Result<int, String> { val int n = direct()?; return Ok(n); }"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(source), source);
        }
    }

    @Test
    void enclosingFunctionQuestionContextCannotLeakIntoLambdaCallback() {
        String source = """
                fnc make_callback(): Result<int, String> {
                  val Fnc<int, Result<int, String>> callback = |int n| -> {
                    val Result<int, String> local = Err("bad");
                    val int value = local?;
                    return Ok(value + n);
                  };
                  return Ok(1);
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(failure.getMessage().contains("postfix '?'"), failure::getMessage);
    }

    @Test
    void postfixDoesNotBecomeImplicitOptionalMemberLookup() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("fnc f(): int { return find_user()?.age; }"));
        assertTrue(failure.getMessage().contains("postfix '?'"));
    }
}
