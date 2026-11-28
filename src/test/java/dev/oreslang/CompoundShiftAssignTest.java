package dev.oreslang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.oreslang.compiler.OresCompiler;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * atto-spec bitwise-and-stream-operators-v1 compound shifts: `x <<<= n`,
 * `x >>>= n` and `x ashr= n` are `x = x op n` on a mutable local, with the
 * checked 0..63 distance. Same cases as the LLVM native fixture.
 */
@Timeout(60)
final class CompoundShiftAssignTest {

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, program, "compound-shift.ores")
                    .mimeType(OresLanguage.MIME_TYPE).buildLiteral());
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    void compoundShiftsUpdateAMutableLocal() throws Exception {
        assertEquals("48|12|-8|1", run("""
                pub fnc main(): void {
                  let int x = 3;
                  x <<<= 4;
                  stdio.stdout.write(x);
                  stdio.stdout.write("|");
                  x >>>= 2;
                  stdio.stdout.write(x);
                  stdio.stdout.write("|");
                  let int y = -64;
                  y ashr= 3;
                  stdio.stdout.write(y);
                  stdio.stdout.write("|");
                  let int z = -1;
                  z >>>= 63;
                  stdio.stdout.write(z);
                  return;
                }
                """));
    }

    @Test
    void anOutOfRangeDistanceStillFails() {
        PolyglotException failure = assertThrows(PolyglotException.class, () -> run("""
                pub fnc main(): void {
                  let int x = 1;
                  let int d = 64;
                  x <<<= d;
                  return;
                }
                """));
        assertTrue(failure.getMessage().contains("shift distance"), failure::getMessage);
    }

    @Test
    void compoundShiftsKeepAssignmentRules() {
        RuntimeException immutable = assertThrows(RuntimeException.class, () -> OresCompiler.parseAndTypeCheck("""
                pub fnc main(): void {
                  val int x = 3;
                  x <<<= 1;
                  return;
                }
                """));
        assertTrue(immutable.getMessage().contains("E_IMMUTABLE_REBIND"), immutable::getMessage);
        RuntimeException field = assertThrows(RuntimeException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        fnc f(): int { return 3; }
                        pub fnc main(): void {
                          f() ashr= 1;
                          return;
                        }
                        """));
        assertTrue(field.getMessage().contains("compound shift assignment requires a named local")
                || field.getMessage().contains("parse error"), field::getMessage);
        // A plain shift and comparison are unchanged.
        OresCompiler.parseAndTypeCheck("""
                pub fnc main(): void {
                  let int x = 3;
                  val bool small = x <<< 1 <= 6;
                  val int y = x >>> 1;
                  return;
                }
                """);
    }
}
