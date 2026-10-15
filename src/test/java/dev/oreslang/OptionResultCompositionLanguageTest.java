package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class OptionResultCompositionLanguageTest {

    @Test
    void synchronousSumCombinatorsTransformOnlyActiveVariant() throws Exception {
        String program = """
                pub fnc main(): void {
                  val Option<int> present = Some(20);
                  val Option<int> mapped = present.map<int>(|int x| -> {
                    return x + 1;
                  });
                  val Option<int> bound = mapped.and_then<int>(|int x| -> {
                    return Some(x * 2);
                  });
                  stdio.stdout.write(bound.unwrap());
                  stdio.stdout.write(":");

                  val Option<int> missing = None;
                  val Option<int> skipped = missing.map<int>(|int x| -> {
                    stdio.stdout.write("BAD-OPTION");
                    return x + 1;
                  });
                  stdio.stdout.write(skipped.is_none());
                  stdio.stdout.write(":");

                  val Result<int, String> good = Ok(4);
                  val Result<int, String> after = good.map<int>(|int x| -> {
                    return x * 3;
                  }).and_then<int>(|int x| -> {
                    return Ok(x + 1);
                  });
                  stdio.stdout.write(after.unwrap());
                  stdio.stdout.write(":");

                  val Result<int, String> bad = Err("oops");
                  val Result<int, String> no_call = bad.and_then<int>(|int x| -> {
                    stdio.stdout.write("BAD-RESULT");
                    return Ok(x);
                  });
                  val Result<int, String> renamed = no_call.map_err<String>(|String e| -> {
                    return e.replace_literal("oops", "bad");
                  });
                  stdio.stdout.write(renamed.is_err());
                  stdio.stdout.write(":");
                  stdio.stdout.write(renamed.unwrap_or(9));
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("42:true:13:true:9", run(program));
    }

    @Test
    void rejectsAmbiguousOutputTypeAndWrongCallbackReturn() {
        reject("fnc f(Option<int> x): void { x.map(|int n| -> { return n; }); return; }",
                "explicit output type");
        reject("fnc f(Option<int> x): void { x.map<int>(|int n| -> { return \"no\"; }); return; }",
                "expected int");
        reject("fnc f(Option<int> x): void { x.and_then<int>(|int n| -> { return n; }); return; }",
                "Option<int>");
        reject("fnc f(Result<int, String> x): void { x.map_err<int>(|int n| -> { return n; }); return; }",
                "lambda parameter");
    }

    @Test
    void rejectsArityAndUnsafeCarrierOwnershipUntilProven() {
        reject("fnc f(Option<int> x): void { x.map<int>(); return; }",
                "one callback");
        reject("""
                define class Box as
                end
                fnc f(Option<Box> value): void {
                  value.map<int>(|Box box| -> { return 1; });
                  return;
                }
                """, "scalar");
        reject("fnc f(Option<int> x): void { x.map<int>(42); return; }",
                "callable");
    }

    @Test
    void ordinaryCallbackFailureReachesCatchInsteadOfBecomingErr() throws Exception {
        String program = """
                pub fnc main(): void {
                  try {
                    val Result<int, String> before = Ok(7);
                    val Result<int, String> result = before.map<int>(|int value| -> {
                      "abc".replace_literal("", "x");
                      return value;
                    });
                    stdio.stdout.write(result.unwrap());
                  } catch (error) {
                    stdio.stdout.write("caught");
                  } finally {
                    stdio.stdout.write(":finally");
                  }
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("caught:finally", run(program));
    }

    @Test
    void panicInCallbackBypassesCatchAndIsNotConvertedToErr() throws Exception {
        String program = """
                pub fnc main(): void {
                  try {
                    val Result<int, String> before = Ok(7);
                    before.map<int>(|int value| -> {
                      val Option<int> missing = None;
                      return missing.unwrap();
                    });
                  } catch (error) {
                    stdio.stdout.write("BAD-CATCH");
                  }
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        Throwable thrown = assertThrows(Throwable.class, () -> run(program));
        assertNotNull(thrown.getMessage());
        assertTrue(thrown.getMessage().contains("Option::unwrap"), thrown::getMessage);
    }

    private static void reject(String program, String expected) {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(program)));
        assertTrue(error.getMessage().contains(expected),
                () -> "Expected '" + expected + "' in: " + error.getMessage());
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                OresLanguage.ID, program, "sum-composition.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
