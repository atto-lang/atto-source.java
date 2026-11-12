package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Guest API contract: explicit, typed to_string only; no host Object.toString fallback. */
final class ToStringContractTest {

    private static String run(String source) {
        TypeChecker.check(Parser.parse(source));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(OresLanguage.ID, source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    void physicalAttoFixturesAreExecutableAndFailClosed() throws Exception {
        Path root = Path.of("tests", "fixtures", "atto-to-string-v1");
        assertEquals("i:-42 f:false s:é😀中", run(Files.readString(root.resolve("main.atto"))));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(
                Files.readString(root.resolve("non-string-template-reject.atto")))));
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(
                        Files.readString(root.resolve("class-implicit-conversion-reject.atto")))));
        assertTrue(rejected.getMessage().contains("to_string requires"), rejected::getMessage);
    }

    @Test
    void primitiveFunctionAndMethodsProduceCanonicalText() {
        assertEquals("-42|true|false|é😀中|-42|true|false|é😀中", run("""
                pub fnc main(): void {
                  stdio.stdout.write(to_string(-42));
                  stdio.stdout.write("|");
                  stdio.stdout.write(to_string(true));
                  stdio.stdout.write("|");
                  stdio.stdout.write(to_string(false));
                  stdio.stdout.write("|");
                  stdio.stdout.write(to_string("é😀中"));
                  stdio.stdout.write("|");
                  stdio.stdout.write((-42).to_string());
                  stdio.stdout.write("|");
                  stdio.stdout.write(true.to_string());
                  stdio.stdout.write("|");
                  stdio.stdout.write(false.to_string());
                  stdio.stdout.write("|");
                  stdio.stdout.write("é😀中".to_string());
                  return;
                }
                """));
    }

    @Test
    void interpolationRequiresExplicitConversionAndRunsEachCallOnce() {
        assertEquals("value=42, flag=false", run("""
                pub fnc main(): void {
                  const n = 42;
                  const flag = false;
                  stdio.stdout.write(`value=${n.to_string()}, flag=${to_string(flag)}`);
                  return;
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub fnc main(): void {
                  stdio.stdout.write(`bad ${42}`);
                }
                """)));
    }

    @Test
    void userDeclaredFunctionAndMethodDispatchAreNotReplacedByIntrinsic() {
        assertEquals("custom|Box:7", run("""
                fnc to_string(int n): String { return "custom"; }
                define class Box as
                  pub val int value;
                  pub to_string(): String { return `Box:${self.value.to_string()}`; }
                end
                pub fnc main(): void {
                  const box = new Box(7);
                  stdio.stdout.write(to_string(42));
                  stdio.stdout.write("|");
                  stdio.stdout.write(box.to_string());
                  return;
                }
                """));
    }

    @Test
    void templateToStringEvaluatesEachArgumentExactlyOnceInSourceOrder() {
        assertEquals("12a:42 b:false", run("""
                fnc first(): int { stdio.stdout.write("1"); return 42; }
                fnc second(): bool { stdio.stdout.write("2"); return false; }
                pub fnc main(): void {
                  stdio.stdout.write(`a:${to_string(first())} b:${second().to_string()}`);
                  return;
                }
                """));
    }

    @Test
    void rejectsInvalidArityAndUnsupportedImplicitConversions() {
        for (String expr : new String[] {
                "to_string()", "to_string(1, 2)", "to_string(1.5)",
                "(-1).to_string(10)", "to_string(true, false)"
        }) {
            assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(
                    "pub fnc main(): void { const result = " + expr + "; return; }")), expr);
        }
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Box as end
                pub fnc main(): void {
                  const box = new Box();
                  const text = to_string(box);
                  return;
                }
                """)));
    }

    @Test
    void stringIdentityPreservesEmbeddedNulAndDecimalFormattingIsExact() {
        String source = """
                pub fnc main(): void {
                  const text = "A\\u0000B";
                  stdio.stdout.write(to_string(text));
                  stdio.stdout.write("|");
                  stdio.stdout.write(text.to_string());
                  stdio.stdout.write("|");
                  stdio.stdout.write(to_string(0));
                  stdio.stdout.write("|");
                  stdio.stdout.write(to_string(9223372036854775807));
                  return;
                }
                """;
        String nul = String.valueOf((char) 0);
        assertEquals("A" + nul + "B|A" + nul + "B|0|9223372036854775807", run(source));
    }

    @Test
    void aUserMethodReturningIntCannotBeInterpolatedAsString() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Bad as
                  pub to_string(): int { return 42; }
                end
                pub fnc main(): void {
                  const bad = new Bad();
                  stdio.stdout.write(`nope:${bad.to_string()}`);
                  return;
                }
                """)));
    }

    @Test
    void doesNotExposeConversionAsUnboundedFirstClassFunction() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub fnc main(): void {
                  const fnc converter = to_string;
                  return;
                }
                """)));
    }
}
