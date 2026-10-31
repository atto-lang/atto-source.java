package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Primitive Math dispatch is compiler-owned but remains shadowable by source namespaces. */
final class MathIntrinsicSemanticsTest {
    private static String execute(String name, String guest) throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, guest, name)
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(stdout).build()) {
            context.eval(source);
        }
        return stdout.toString(StandardCharsets.UTF_8).trim().replace("\r", "");
    }

    @Test
    void bareMathIntrinsicHandlesCheckedIntegers() throws Exception {
        assertEquals("42|-42|42", execute("math-int-intrinsic.ores", """
                pub routine main(): void {
                  stdio.stdout.write(Math.abs(-42));
                  stdio.stdout.write("|");
                  stdio.stdout.write(Math.neg(42));
                  stdio.stdout.write("|");
                  stdio.stdout.write(Math.neg(-42));
                  return;
                }
                """));
    }

    @Test
    void binaryFloatMathUsesSignBitsAndLeavesNanUnordered() throws Exception {
        assertEquals("true|true|true|false", execute("math-float-intrinsic.ores", """
                pub routine main(): void {
                  val float positive_zero = Math.abs(-0.0);
                  val float negative_zero = Math.neg(0.0);
                  val float restored_zero = Math.neg(-0.0);
                  val float nan = Math.abs(0.0 / 0.0);

                  stdio.stdout.write(1.0 / positive_zero > 0.0);
                  stdio.stdout.write("|");
                  stdio.stdout.write(1.0 / negative_zero < 0.0);
                  stdio.stdout.write("|");
                  stdio.stdout.write(1.0 / restored_zero > 0.0);
                  stdio.stdout.write("|");
                  stdio.stdout.write(nan == nan);
                  return;
                }
                """));
    }

    private static String sharedFixture(String name) throws Exception {
        return Files.readString(Path.of("tests", "fixtures", "numeric-v1", name),
                StandardCharsets.UTF_8);
    }

    @Test
    void executesByteIdenticalPortableMathFixture() throws Exception {
        String guest = sharedFixture("math-i64-ok.ores");
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            assertEquals(84L, context.eval(OresLanguage.ID, guest).asLong());
        }
    }

    @Test
    void portableMinFixturesTakeCheckedOverflowPath() throws Exception {
        for (String name : new String[] {
                "math-i64-abs-min.ores",
                "math-i64-neg-min.ores",
                "i64-add-overflow.ores",
                "i64-sub-overflow.ores",
                "i64-mul-overflow.ores",
                "i64-neg-overflow.ores"
        }) {
            String guest = sharedFixture(name);
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).build()) {
                var failure = assertThrows(org.graalvm.polyglot.PolyglotException.class,
                        () -> context.eval(OresLanguage.ID, guest), name);
                assertTrue(failure.getMessage() != null
                                && failure.getMessage().contains("ORES_I64_OVERFLOW"),
                        () -> "portable fixture did not use checked overflow path: "
                                + name + ": " + failure.getMessage());
            }
        }
    }

    @Test
    void sourceMathNamespaceShadowsBuiltinIntrinsic() throws Exception {
        assertEquals("77", execute("math-shadowing.ores", """
                define module Math as
                  pub fnc abs(int value): int {
                    return 77;
                  }
                end

                pub routine main(): void {
                  stdio.stdout.write(Math.abs(-42));
                  return;
                }
                """));
    }
}
