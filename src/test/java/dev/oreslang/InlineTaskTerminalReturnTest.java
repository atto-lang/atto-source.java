package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** Checked inline task slice; the separate .NET fixture gate must still run 4/4. */
@Timeout(45)
final class InlineTaskTerminalReturnTest {
    private static long run(String text) throws Exception {
        OresCompiler.parseAndTypeCheckStrictAtto(text);
        try (var context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).build()) {
            return context.eval(Source.newBuilder(OresLanguage.ID, text, "inline-task.atto")
                    .mimeType(OresLanguage.MIME_TYPE).build()).asLong();
        }
    }

    @Test void terminalReturnIsScalarAndNestedBlocksStayLocal() throws Exception {
        assertEquals(42L, run("""
                pub fnc main(): int {
                  val int value = sync task<int> {
                    val int nested = sync task<int> { return 20; };
                    return nested + 22;
                  };
                  return value;
                }
                """));
    }

    @Test void cooperationUsesCallerAndPreservesOuterAssignment() throws Exception {
        assertEquals(42L, run("""
                pub fnc main(): int {
                  let int count = 0;
                  val int value = sync task<int> {
                    count = 2;
                    rt cooperate;
                    return 40;
                  };
                  return value + count;
                }
                """));
    }

    @Test void resultCannotBeMistypedAsFutureOrDifferentPayload() {
        for (String type : new String[] { "Future<int>", "bool" }) {
            String source = """
                    pub fnc main(): int {
                      val %s value = sync task<int> { return 42; };
                      return 1;
                    }
                    """.formatted(type);
            assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheckStrictAtto(source), type);
        }
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheckStrictAtto("""
                        pub fnc main(): int {
                          val int value = sync task<int> { return true; };
                          return value;
                        }
                        """));
    }

    @Test void unimplementedControlFlowIsExplicitlyRejected() {
        String early = """
                pub fnc main(): int {
                  return sync task<int> {
                    if true { return 42; } fi
                    return 1;
                  };
                }
                """;
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Parser.parseStrictAtto(early))
                .getMessage().contains("E_SYNC_TASK_EARLY_RETURN_UNSUPPORTED"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Parser.parseStrictAtto("""
                pub fnc main(): int {
                  val int value = sync task<int> { val int unused = 42; };
                  return value;
                }
                """)).getMessage().contains("E_SYNC_TASK_RESULT"));
    }
}
