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
}
