package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration candidate for two independently pending syntax PRs.
 * These tests require real guest execution; parser-only checks are insufficient.
 */
final class MaybeTryKeywordIntegrationTest {
    private static final String NESTED = """
            fnc check(Result<int, String> value): Result<int, String> {
              try
                return maybe
                  Ok(n) ?= value;
                  return Ok(n + 1)
                else
                  Err(reason) -> {
                    stdio.stdout.write("E");
                    return Err(reason);
                  };
                end;
              catch()
                stdio.stdout.write("C");
                return Err("caught");
              finally
                stdio.stdout.write("F");
              end
            }
            pub fnc main(): void {
              stdio.stdout.write(check(Ok(7)).unwrap());
              stdio.stdout.write("|");
              stdio.stdout.write(check(Err("bad")).is_err());
              return;
            }
            """;

    @Test
    void bothKeywordFramesOwnTheirEndAndExceptionAstIsPreserved() {
        var tokens = new Lexer("Ok(n) ?= v; true ? 1 : 0").scan();
        assertEquals(1, tokens.stream()
                .filter(t -> t.type() == Token.Type.FALLIBLE_BIND).count());
        assertEquals(1, tokens.stream()
                .filter(t -> t.type() == Token.Type.QUESTION).count());

        Ast.Program program = Parser.parse(NESTED);
        Ast.FunctionDecl fn = (Ast.FunctionDecl) program.modules()
                .getFirst().declarations().getFirst();
        Ast.TryStmt tryStmt = (Ast.TryStmt) fn.body().getFirst();
        Ast.BlockStmt scope = (Ast.BlockStmt) tryStmt.body().getFirst();
        Ast.MatchStmt dispatch = (Ast.MatchStmt) scope.body().getFirst();
        assertTrue(dispatch.procedural());
        assertFalse(tryStmt.finallyBody().isEmpty());
        assertFalse(tryStmt.catchBody().isEmpty());
    }

    @Test
    void failedValueGoesToMaybeElseNotOuterCatchAndFinallyRunsOnce() throws Exception {
        var typed = TypeChecker.check(Parser.parse(NESTED));
        OwnershipChecker.check(typed);
        assertEquals("F8|EFtrue", run(NESTED));
    }

    @Test
    void missingMaybeElseDoesNotConsumeOuterCatchOrFinally() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc fail(Result<int, String> value): int {
                  try
                    do maybe
                      Ok(n) ?= value;
                      return n;
                    end
                  catch()
                    return 0;
                  finally
                    stdio.stdout.write("F");
                  end
                }
                """));
    }

    @Test
    void bracedTryAlsoSupportsNestedReturnMaybeAndPreservesNoEndRule() {
        assertDoesNotThrow(() -> Parser.parse("""
                fnc read(Result<int, String> value): Result<int, String> {
                  try {
                    return maybe
                      Ok(n) ?= value;
                      return Ok(n)
                    else
                      Err(reason) -> { return Err(reason); };
                    end;
                  } finally {
                    stdio.stdout.write("F");
                  }
                }
                """));
    }

    @Test
    void legacyOnFailureMarkerIsAcceptedInsideKeywordTry() {
        assertDoesNotThrow(() -> Parser.parse("""
                fnc read(Result<int, String> value): Result<int, String> {
                  try
                    return maybe
                      Ok(n) ?= value;
                      return Ok(n)
                    else
                      on Err(reason) -> { return Err(reason); };
                    end;
                  finally
                    stdio.stdout.write("F");
                  end
                }
                """));
    }

    @Test
    void failureArmDuplicatesShadowingAndVariantArityFailClosed() {
        String[] invalidArms = {
                "Err(reason) -> { return Err(reason); }; Err(other) -> { return Err(other); };",
                "_ -> { return Err(\"fallback\"); }; Err(reason) -> { return Err(reason); };",
                "Err() -> { return Err(\"no payload\"); };",
                "None(value) -> { return Err(\"invalid None payload\"); };",
                "Err(reason) -> { return Err(reason); }; None -> { return Err(\"none\"); }; None -> { return Err(\"again\"); };"
        };
        for (String arms : invalidArms) {
            String guest = "fnc f(Result<int, String> input): Result<int, String> {\n"
                    + "  return maybe\n"
                    + "    Ok(n) ?= input;\n"
                    + "    return Ok(n)\n"
                    + "  else\n"
                    + "    " + arms + "\n"
                    + "  end;\n"
                    + "}\n";
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(guest), arms);
        }
    }

    private static String run(String source) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source guest = Source.newBuilder(OresLanguage.ID, source, "maybe-try-integration.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            context.eval(guest);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
