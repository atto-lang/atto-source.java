package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
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
                    loop { return 42; }
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

    @Test void boundedConditionalReturnsStayInsideTaskAndPreserveWriteback() throws Exception {
        String source = """
                fnc calculate(bool first): int {
                  let int count = 0;
                  val int result = sync task<int> {
                    if first do
                      count = 2;
                      return 40;
                    fi
                    count = 3;
                    return 39;
                  };
                  return result + count;
                }
                pub fnc main(): int {
                  if calculate(true) neq 42 do return 1; fi
                  return calculate(false);
                }
                """;
        assertEquals(42L, run(source));
        assertEquals(42L, run(source.replace("if first do", "if first then")));
    }

    @Test void earlyReturnPayloadAndConditionAreCheckedOnBothPaths() {
        for (String source : new String[] {
                """
                pub fnc main(): int {
                  return sync task<int> {
                    if false do return true; fi
                    return 42;
                  };
                }
                """,
                """
                pub fnc main(): int {
                  val int number = 1;
                  return sync task<int> {
                    if number do return 42; fi
                    return 1;
                  };
                }
                """
        }) {
            assertDoesNotThrow(() -> Parser.parseStrictAtto(source));
            assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheckStrictAtto(source));
        }
    }

    @Test void boundedConditionalReturnCannotChangeFallthroughDeclarationScope() {
        String source = """
                pub fnc main(): int {
                  return sync task<int> {
                    val int local = 40;
                    if false do return 42; fi
                    val int local = 2;
                    return local;
                  };
                }
                """;
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> Parser.parseStrictAtto(source)).getMessage()
                .contains("E_SYNC_TASK_EARLY_RETURN_UNSUPPORTED"));
    }

    @Test void orderedEarlyReturnChainEvaluatesOnlySelectedBranch() throws Exception {
        String source = """
                fnc matches(int value, int target): bool { return value eq target; }
                fnc choose(int route): int {
                  let int count = 0;
                  val int result = sync task<int> {
                    if matches(route, 0) && count eq 0 do
                      count = count + 2;
                      return 40;
                    elif matches(route, 1) then
                      count = count + 3;
                      return 39;
                    fi
                    count = count + 4;
                    return 38;
                  };
                  return result + count;
                }
                pub fnc main(): int {
                  guard choose(0) eq 42, choose(1) eq 42, choose(2) eq 42 { return 1; }
                  return 42;
                }
                """;
        assertEquals(42L, run(source));
    }

    @Test void conditionalReturnBudgetIsBounded() {
        String prefix = "pub fnc main(): int { return sync task<int> { ";
        String branch = "if false do return 1; fi ";
        String suffix = "return 42; }; }";
        assertDoesNotThrow(() -> Parser.parseStrictAtto(prefix + branch.repeat(16) + suffix));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> Parser.parseStrictAtto(prefix + branch.repeat(17) + suffix))
                .getMessage().contains("E_SYNC_TASK_EARLY_RETURN_BUDGET"));
    }

    @Test void skippedShortCircuitTasksCannotMutateOuterState() throws Exception {
        String source = """
                fnc choose(bool run): int {
                  let int count = 0;
                  val bool result = run && sync task<bool> {
                    count = count + 2;
                    return true;
                  };
                  if run do
                    guard result { return 1; }
                    return 40 + count;
                  fi
                  guard !result { return 2; }
                  return 42 + count;
                }
                pub fnc main(): int {
                  guard choose(true) eq 42, choose(false) eq 42 { return 1; }
                  return 42;
                }
                """;
        assertEquals(42L, run(source));
    }

    @Test void voidTaskUsesCallerScopeAndDoesNotReturnFromCaller() throws Exception {
        assertEquals(42L, run("""
                pub fnc main(): int {
                  let int count = 0;
                  do sync task<void> {
                    count = 20;
                    rt cooperate;
                    return;
                  }
                  do sync task<void> {
                    count = count + 22;
                  };
                  return count;
                }
                """));
    }

    @Test void voidTaskLocalLoopAndNestedScopeRemainIndependent() throws Exception {
        assertEquals(42L, run("""
                pub fnc main(): int {
                  let int count = 0;
                  do sync task<void> {
                    loop {
                      count = count + 1;
                      if count eq 2 { continue; } fi
                      if count eq 4 { break; } fi
                    }
                    do sync task<void> { count = count + 38; }
                  }
                  return count;
                }
                """));
    }

    @Test void voidTaskRejectsNonlocalTransfersAndValueReturns() {
        for (String body : new String[] {
                "return 42;", "return; rt cooperate;", "if true do return; fi"
        }) {
            String source = "pub fnc main(): int { do sync task<void> { "
                    + body + " } return 42; }";
            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> Parser.parseStrictAtto(source)).getMessage()
                    .contains("E_SYNC_TASK_VOID_RETURN_UNSUPPORTED"), body);
        }
        for (String transfer : new String[] { "break;", "continue;" }) {
            String source = "pub fnc main(): int { loop { do sync task<void> { "
                    + transfer + " } return 42; } return 1; }";
            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> Parser.parseStrictAtto(source)).getMessage()
                    .contains("E_SYNC_TASK_CONTROL_TRANSFER"), transfer);
        }
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> Parser.parseStrictAtto(
                        "pub fnc main(): int { do sync task<int> { return 42; } return 0; }"))
                .getMessage().contains("E_SYNC_TASK_VOID_RESULT"));
    }

    @Test void inlineTaskDefersExecuteOnceInLexicalOrderBeforeCallerContinues() throws Exception {
        String source = """
                pub fnc main(): int {
                  do sync task<void> {
                    defer stdio.stdout.write("outer|");
                    val int result = sync task<int> {
                      defer stdio.stdout.write("inner|");
                      return 42;
                    };
                    stdio.stdout.write("joined|");
                    return;
                  }
                  stdio.stdout.write("caller");
                  return 42;
                }
                """;
        OresCompiler.parseAndTypeCheckStrictAtto(source);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (var context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            assertEquals(42L, context.eval(Source.newBuilder(OresLanguage.ID, source,
                    "inline-task-cleanup.atto").mimeType(OresLanguage.MIME_TYPE).build()).asLong());
        }
        assertEquals("inner|joined|outer|caller", output.toString(StandardCharsets.UTF_8));
    }
}
