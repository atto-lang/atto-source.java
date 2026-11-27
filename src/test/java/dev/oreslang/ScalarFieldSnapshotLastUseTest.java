package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ScalarFieldSnapshotLastUseTest {
    private static final String PREFIX = """
            define class Counter as
              pub let int value = 40;
            end
            pub fnc main(): int {
              let counter = new Counter();
              val view = borrow! counter.value;
              val snapshot = rt copy view;
            """;

    @Test void detachedSnapshotAllowsOwnerWriteAfterFinalUse() {
        String source = PREFIX + """
              counter.value = 2;
              return snapshot + counter.value;
            }
            """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        try (Context context = Context.newBuilder(OresLanguage.ID).build()) {
            assertEquals(42, context.eval(OresLanguage.ID, source).asInt());
        }
    }

    @Test void laterLoanUseStillBlocksMutation() {
        String source = PREFIX + """
              counter.value = 2;
              return snapshot + (rt copy view);
            }
            """;
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(source)));
    }

    @Test void independentLiveLoanStillBlocksMutation() {
        String source = """
            define class Counter as
              pub let int value = 40;
            end
            pub fnc main(): int {
              let counter = new Counter();
              val other = borrow! counter.value;
              val view = borrow! counter.value;
              val snapshot = rt copy view;
              counter.value = 2;
              return snapshot + (rt copy other);
            }
            """;
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(source)));
    }
}
