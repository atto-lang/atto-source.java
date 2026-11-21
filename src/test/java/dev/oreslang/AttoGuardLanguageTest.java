package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class AttoGuardLanguageTest {
    @Test
    void successfulGuardFallsThroughAndFailureReturns() throws Exception {
        assertEquals("3-1", run("""
                fnc choose(int x): int {
                  guard x > 0 { return -1; }
                  return x;
                }
                pub routine main(): void {
                  stdio.stdout.write(choose(3));
                  stdio.stdout.write(choose(0));
                }
                """));
    }

    @Test
    void commaConditionsShortCircuitAndRequireBothChecks() throws Exception {
        assertEquals("399", run("""
                fnc choose(int x): int {
                  guard x > 0, x < 5 { return 9; }
                  return x;
                }
                pub routine main(): void {
                  stdio.stdout.write(choose(3));
                  stdio.stdout.write(choose(0));
                  stdio.stdout.write(choose(8));
                }
                """));
    }

    @Test
    void guardConditionsEvaluateOnceAndStopBeforeTheNextCheck() throws Exception {
        assertEquals("cc3c7", run("""
                fnc check(int x): bool {
                  stdio.stdout.write("c");
                  return x > 0;
                }
                fnc choose(int x): int {
                  guard check(x), check(x) { return 7; }
                  return 3;
                }
                pub routine main(): void {
                  stdio.stdout.write(choose(1));
                  stdio.stdout.write(choose(0));
                }
                """));
    }

    @Test
    void guardRequiresExplicitReturnNotLoopExit() {
        // A break or continue may exit a loop/iteration, but never returns
        // from the enclosing function, so neither proves a valid guard.
        for (String failure : new String[]{"break;", "continue;"}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse("""
                        pub routine main(): void {
                          let i = 0;
                          loop {
                            i = i + 1;
                            guard i >= 3 { %s }
                            if i > 5 then break; fi
                          }
                        }
                        """.formatted(failure)));
            assertTrue(error.getMessage().contains(
                    "guard failure block must explicitly return"), error::getMessage);
        }
    }

    @Test
    void guardDoesNotTrustUnreachableReturnAfterLoopExit() {
        for (String failure : new String[]{"break;", "continue;"}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse("""
                        pub routine main(): void {
                          loop {
                            guard false else
                              %s
                              return;
                            end
                            return;
                          }
                        }
                        """.formatted(failure)));
            assertTrue(error.getMessage().contains(
                    "guard failure block must explicitly return"), error::getMessage);
        }
    }

    @Test
    void guardRejectsThrowOnlyFailureEvenIfItWouldTerminate() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                    pub routine main(): void {
                      guard false { throw new Error("bad"); }
                    }
                    """));
        // The Java frontend currently rejects typed throw at source admission
        // before guard flow proof. Either route must reject this program.
        assertTrue(error.getMessage().contains("typed exceptional effect")
                || error.getMessage().contains("guard failure block must explicitly return"),
                error::getMessage);
    }

    @Test
    void guardAcceptsExplicitVoidReturnAndKeepsFollowingStatements() throws Exception {
        assertEquals("yes", run("""
                fnc emit(bool ready): void {
                  guard ready { return; }
                  stdio.stdout.write("yes");
                }
                pub routine main(): void {
                  emit(false);
                  emit(true);
                }
                """));
    }

    @Test
    void enclosingKeywordIfCanOwnElseAndFiAfterGuard() throws Exception {
        assertEquals("4279", run("""
                fnc choose(bool ready, int x): int {
                  if ready then
                    guard x > 0 { return 7; }
                  else
                    return 9;
                  fi
                  return 42;
                }
                pub routine main(): void {
                  stdio.stdout.write(choose(true, 1));
                  stdio.stdout.write(choose(true, 0));
                  stdio.stdout.write(choose(false, 1));
                }
                """));
    }

    @Test
    void semicolonFreeGuardReturnMatchesCanonicalAttoSyntax() throws Exception {
        assertEquals("427", run("""
                fnc choose(int x): int {
                  guard x > 0 {
                    return 7
                  }
                  return 42;
                }
                pub routine main(): void {
                  stdio.stdout.write(choose(1));
                  stdio.stdout.write(choose(0));
                }
                """));
    }

    @Test
    void keywordElseEndGuardMatchesBracedReturnAndFallthrough() throws Exception {
        assertEquals("423-1", run("""
                fnc brace(int x): int {
                  guard x > 0 { return -1; }
                  return x;
                }
                fnc keyword(int x): int {
                  guard x > 0 else
                    return -1;
                  end
                  return x;
                }
                pub routine main(): void {
                  stdio.stdout.write(keyword(42));
                  stdio.stdout.write(brace(3));
                  stdio.stdout.write(keyword(0));
                }
                """));
    }

    @Test
    void keywordGuardUsesOuterIfElseCorrectlyAndSupportsNestedGuards() throws Exception {
        assertEquals("4279", run("""
                fnc choose(bool ready, int x): int {
                  if ready then
                    guard x > 0 else
                      return 7;
                    end
                  else
                    return 9;
                  fi
                  return 42;
                }
                pub routine main(): void {
                  stdio.stdout.write(choose(true, 1));
                  stdio.stdout.write(choose(true, 0));
                  stdio.stdout.write(choose(false, 1));
                }
                """));
    }

    @Test
    void keywordGuardMatchesBracedSideEffectsAndTerminationProof() throws Exception {
        assertEquals("failed42", run("""
                fnc choose(int x): int {
                  guard x > 0 else
                    stdio.stdout.write("failed");
                    return 42;
                  end
                  return x;
                }
                pub routine main(): void {
                  stdio.stdout.write(choose(0));
                }
                """));
        reject("""
                fnc choose(int x): int {
                  guard x > 0 else
                    stdio.stdout.write(x);
                  end
                  return x;
                }
                """);
        reject("""
                fnc choose(int x): int {
                  guard x > 0 else
                    return 0;
                  fi
                  return x;
                }
                """);
    }

    @Test
    void keywordGuardTypechecksOriginalOptionalPortExample() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc optional_port(bool present): Option<int> {
                  guard present else
                    return None;
                  end
                  return Some(8080);
                }
                """)));
    }

    @Test
    void failurePathDeferRunsBeforeGuardReturn() throws Exception {
        assertEquals("cleanup7", run("""
                fnc choose(int x): int {
                  guard x > 0 {
                    defer stdio.stdout.write("cleanup");
                    return 7;
                  }
                  return 42;
                }
                pub routine main(): void {
                  stdio.stdout.write(choose(0));
                }
                """));
    }

    @Test
    void guardFailureCanPerformActionsBeforeTerminalReturn() throws Exception {
        assertEquals("failed42", run("""
                fnc choose(int x): int {
                  guard x > 0 {
                    stdio.stdout.write("failed");
                    return 42;
                  }
                  return x;
                }
                pub routine main(): void {
                  stdio.stdout.write(choose(0));
                }
                """));
    }

    @Test
    void rejectsNonTerminalOrEmptyFailureBody() {
        reject("""
                fnc choose(int x): int {
                  guard x > 0 { stdio.stdout.write(x); }
                  return x;
                }
                """);
        reject("""
                fnc choose(int x): int {
                  guard x > 0 { }
                  return x;
                }
                """);
    }

    @Test
    void rejectsElseSyntaxAndNonBooleanCondition() {
        reject("""
                fnc choose(int x): int {
                  guard x > 0 else { return 0; }
                  return x;
                }
                """);
        reject("""
                fnc choose(int x): int {
                  guard x { return 0; }
                  return x;
                }
                """);
    }

    @Test
    void optionNarrowingRequiresCheckedOwnershipLowering() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                fnc choose(Option<string> username): int {
                  guard username is Some { return 0; }
                  return 42;
                }
                """));
        assertTrue(failure.getMessage().contains("ownership-checked Option narrowing"),
                failure::getMessage);
    }

    @Test
    void requiresBothBranchesOfNestedConditionalToExit() {
        reject("""
                fnc choose(int x): int {
                  guard x > 0 {
                    if x == 0 { return 0; } fi
                  }
                  return x;
                }
                """);
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc choose(int x): int {
                  guard x > 0 {
                    if x == 0 { return 0; } else { return 1; } fi
                  }
                  return x;
                }
                """)));
    }

    private static void reject(String source) {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    private static String run(String source) throws Exception {
        TypeChecker.check(Parser.parse(source));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source program = Source.newBuilder(OresLanguage.ID, source, "guard.atto")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(program);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
