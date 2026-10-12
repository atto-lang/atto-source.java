package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cross-backend return-destination contract. Unsupported value-switch syntax
 * must fail closed, never be silently interpreted as function-level switch.
 */
final class DispatchFourFormsReturnContractTest {
    private static String run(String program) throws Exception {
        TypeChecker.check(Parser.parse(program));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context ctx = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            ctx.eval(Source.newBuilder(OresLanguage.ID, program, "dispatch-four-forms.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        return output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    void doMatchReturnExitsEnclosingCallableNotJustArm() throws Exception {
        assertEquals("42\n0\n", run("""
                fnc choose(int n): int {
                  do match n over {
                    on 7 { return 42; }
                    default { return 0; }
                  }
                  return 99;
                }
                pub fnc main(): void {
                  stdio.println(choose(7));
                  stdio.println(choose(8));
                  return;
                }
                """));
    }

    @Test
    void doSwitchReturnExitsEnclosingCallableNotJustArm() throws Exception {
        assertEquals("42\n0\n", run("""
                fnc choose(int n): int {
                  do switch n {
                    case 7 { return 42; }
                    default { return 0; }
                  }
                  return 99;
                }
                pub fnc main(): void {
                  stdio.println(choose(7));
                  stdio.println(choose(8));
                  return;
                }
                """));
    }

    @Test
    void unimplementedValueSwitchMustRejectNotExitEnclosingFunction() {
        assertThrows(RuntimeException.class, () -> TypeChecker.check(Parser.parse("""
                fnc choose(int n): int {
                  const x: int = switch n {
                    case 7 { return 42; }
                    default { return 0; }
                  };
                  return x + 1;
                }
                """)));
    }
}
