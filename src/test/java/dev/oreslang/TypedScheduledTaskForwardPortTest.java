package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(45)
final class TypedScheduledTaskForwardPortTest {
    private static final String SOURCE = """
            pub susp fnc main(): int {
              val Future<int> pending = task<int> { return 42; };
              return await pending;
            }
            """;

    private static long run(String text) throws Exception {
        OresCompiler.parseAndTypeCheck(text);
        try (var context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).build()) {
            var source = Source.newBuilder(OresLanguage.ID, text, "typed-task-main.atto")
                    .mimeType(OresLanguage.MIME_TYPE).build();
            return context.eval(source).asLong();
        }
    }

    @Test void explicitPayloadProducesFuture() throws Exception {
        assertEquals(42L, run(SOURCE));
    }

    @Test void explicitPayloadWorksWithoutExpectedType() throws Exception {
        assertEquals(42L, run(SOURCE.replace("val Future<int> pending", "val pending")));
    }

    @Test void wrongPayloadOrObsoleteWrapperIsRejected() {
        for (String bad : new String[] {
                SOURCE.replace("task<int>", "task<bool>"),
                SOURCE.replace("Future<int>", "Future<bool>"),
                SOURCE.replace("Future<int>", "Task<int>"),
                SOURCE.replace("return 42;", "val int unused = 42;")
        }) {
            assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(bad), bad);
        }
    }
}
