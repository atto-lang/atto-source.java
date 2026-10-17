package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Cross-surface conformance for implicit readonly structural parameter loans. */
final class StructuralCrossSurfaceParityTest {
    @Test
    void functionRoutineAndInstanceMethodUseTheSameStructuralLoan() {
        var typed = assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  define interface HasValue
                    value: int;
                  end
                  define class Candidate as
                    pub val int value = 7;
                    pub val int extra = 8;
                  end
                  fnc by_function(structural HasValue v): int { return v.value; }
                  routine by_routine(structural HasValue v): int { return v.value; }
                  define class Consumer as
                    pub by_method(structural HasValue v): int { return v.value; }
                  end
                  fnc check(): int {
                    val original = new Candidate();
                    val consumer = new Consumer();
                    return by_function(original) + by_routine(original)
                           + consumer.by_method(original) + original.value;
                  }
                end
                """)));
        assertDoesNotThrow(() -> OwnershipChecker.check(typed));
    }

    @Test
    void mismatchedFieldTypeFailsAtEveryBoundary() {
        String prefix = """
                define module app
                  define interface HasValue
                    value: int;
                  end
                  define class Bad as
                    pub val string value = "wrong";
                  end
                  fnc by_function(structural HasValue v): int { return v.value; }
                  routine by_routine(structural HasValue v): int { return v.value; }
                  define class Consumer as
                    pub by_method(structural HasValue v): int { return v.value; }
                  end
                """;
        for (String expression : new String[]{
                "by_function(new Bad())",
                "by_routine(new Bad())",
                "consumer.by_method(new Bad())"
        }) {
            String setup = expression.startsWith("consumer.")
                    ? "val consumer = new Consumer(); " : "";
            String source = prefix + "fnc check(): int { " + setup
                    + "return " + expression + "; }\nend";
            var parsed = assertDoesNotThrow(() -> Parser.parse(source),
                    "Negative test must reach the type checker: " + expression);
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(parsed), expression);
        }
    }

    @Test
    void canonicalStructuralMatchAllowsSpecificArmBeforeBroadArm() {
        var typed = assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  define class Base as
                  end
                  define class Value extends Base as
                    pub val int value = 1;
                    pub val int extra = 2;
                  end
                  fnc choose(Base item): int {
                    match item over
                      on structural { value: int, extra: int } narrow -> {
                        return narrow.value;
                      }
                      on structural { value: int } broad -> {
                        return broad.value;
                      }
                      on _ -> { return 0; }
                    end
                  }
                end
                """)));
        assertDoesNotThrow(() -> OwnershipChecker.check(typed));
    }

    @Test
    void guestExecutesOrderedGuardAndMissingShapeFallback() throws Exception {
        String sourceText = """
                define module app
                  define class Base as end
                  define class Candidate extends Base as
                    pub val int value = 7;
                    pub val int extra = 8;
                  end
                  define class Plain extends Base as end

                  fnc classify(Base item): int {
                    const ready = false;
                    match item over
                      on structural { value: int, extra: int } narrow when ready -> {
                        return 10;
                      }
                      on structural { value: int } broad -> { return 20; }
                      on _ -> { return 30; }
                    end
                  }

                  pub fnc main(): void {
                    stdio.println(classify(new Candidate()));
                    stdio.println(classify(new Plain()));
                    return;
                  }
                end
                """;
        var typed = TypeChecker.check(Parser.parse(sourceText));
        OwnershipChecker.check(typed);
        var out = new java.io.ByteArrayOutputStream();
        var source = org.graalvm.polyglot.Source.newBuilder(OresLanguage.ID,
                sourceText, "structural-ordered-runtime.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (var context = org.graalvm.polyglot.Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            context.eval(source);
        }
        assertEquals("20\n30\n",
                out.toString(java.nio.charset.StandardCharsets.UTF_8)
                        .replace("\r\n", "\n"),
                "only real guest dispatch proves first-success guards and fallback");
    }

}
