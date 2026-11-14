package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class KeywordTailExecutionTest {
    private static String run(String helper) throws Exception {
        String sourceCode = helper + """
                pub routine main(): void {
                  stdio.stdout.write(answer());
                  return;
                }
                """;
        TypeChecker.check(Parser.parse(sourceCode));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, sourceCode,
                "keyword-tail-value-contract.atto")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(out)
                .build()) {
            context.eval(source);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void explicitAndImplicitReturnExecuteEquivalently() throws Exception {
        String explicit = """
                fnc answer(): int {
                  if true then
                    return 42;
                  else
                    return 1;
                  fi
                }
                """;
        String implicit = """
                fnc answer(): int {
                  if true then
                    42;
                  else
                    1;
                  fi
                }
                """;
        assertEquals("42", run(explicit));
        assertEquals(run(explicit), run(implicit));
    }

    @Test
    void commaBindingIsEvaluatedBeforeFinalTailExpression() throws Exception {
        assertEquals("47", run("""
                fnc answer(): int {
                  if true then
                    let int val = 5,
                    val + 42
                  else
                    1
                  fi
                }
                """));
    }

    @Test
    void nonTailIfContinuesWithoutImplicitFunctionReturn() throws Exception {
        assertEquals("99", run("""
                fnc answer(): int {
                  if true then
                    42;
                  else
                    1;
                  fi
                  return 99;
                }
                """));
    }
}
