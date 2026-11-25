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

    @Test void outerLoopTransfersCannotEscapeInlineTask() {
        for (String transfer : new String[] { "break;", "continue;" }) {
            for (String prefix : new String[] { transfer, "if true { " + transfer + " } fi",
                    "if true do " + transfer + " fi", "if true; do " + transfer + " fi",
                    "if true then " + transfer + " fi" }) {
                String source = """
                        pub fnc main(): int {
                          loop {
                            val int result = sync task<int> {
                              %s
                              return 42;
                            };
                            return result;
                          }
                          return 0;
                        }
                        """.formatted(prefix);
                assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> Parser.parseStrictAtto(source)).getMessage()
                        .contains("E_SYNC_TASK_CONTROL_TRANSFER"), prefix);
            }
        }
    }

    @Test void nestedTaskCannotBorrowItsParentTasksLoopTarget() {
        String source = """
                pub fnc main(): int {
                  return sync task<int> {
                    loop {
                      val int nested = sync task<int> { break; return 42; };
                      break;
                    }
                    return 42;
                  };
                }
                """;
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> Parser.parseStrictAtto(source)).getMessage()
                .contains("E_SYNC_TASK_CONTROL_TRANSFER"));
    }

    @Test void localLoopsCanBreakContinueAndWriteBack() throws Exception {
        String braced = """
                pub fnc main(): int {
                  let int count = 0;
                  val int result = sync task<int> {
                    loop {
                      count = count + 1;
                      if count eq 2 { continue; } fi
                      if count eq 5 { break; } fi
                    }
                    return 37;
                  };
                  return result + count;
                }
                """;
        assertEquals(42L, run(braced));
        for (String delimiter : new String[] { "do", "; do", "then", "; then" }) {
            String keyword = braced.replace("{ continue; } fi", delimiter + " continue; fi")
                    .replace("{ break; } fi", delimiter + " break; fi");
            assertEquals(42L, run(keyword), delimiter);
        }
    }

    @Test void assignmentDestinationIsWrittenAfterTaskCompletes() throws Exception {
        assertEquals(42L, run("""
                pub fnc main(): int {
                  let int value = 1;
                  let int count = 0;
                  value = sync task<int> {
                    value = 100;
                    count = 2;
                    return 40;
                  };
                  return value + count;
                }
                """));
    }

    @Test void lexicalLocalsCannotEscapeAndImmutableBindingsCannotBeReassigned() {
        for (String source : new String[] {
                """
                pub fnc main(): int {
                  val int result = sync task<int> {
                    val int hidden = 42;
                    return hidden;
                  };
                  return hidden;
                }
                """,
                """
                pub fnc main(): int {
                  val int count = 1;
                  return sync task<int> { count = 42; return count; };
                }
                """
        }) {
            // First prove syntax admission: these must fail at semantic checking,
            // not merely because the parser rejects sync task.
            assertDoesNotThrow(() -> Parser.parseStrictAtto(source));
            assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheckStrictAtto(source));
        }
    }
}
