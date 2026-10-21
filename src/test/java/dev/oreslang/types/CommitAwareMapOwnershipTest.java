package dev.oreslang.types;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class CommitAwareMapOwnershipTest {

    private static String isolatedRejectedWrite() {
        return """
                define class Payload as
                end

                pub fnc main(): void {
                  val Map<string, Payload> values =
                      new Map<string, Payload>();
                  val Payload item = new Payload();

                  try {
                    values.put("x", item);
                  } catch (err) {
                    val Payload recovered = item;
                  }

                  return;
                }
                """;
    }

    @Test
    void isolatedRejectedWriteRestoresCandidateOnCatchEdge() {
        String source = isolatedRejectedWrite();

        // Production ownership analysis distinguishes admission rejection
        // from the successful commit edge. TypeChecker still gates this
        // move-only Map value domain from ordinary source compilation.
        assertDoesNotThrow(
                () -> OwnershipChecker.check(Parser.parse(source)));
    }

    @Test
    void laterThrowableStatementPromotesCommittedCandidateToCatchEdge() {
        String source = """
                define class Payload as
                end

                pub fnc main(): void {
                  val Map<string, Payload> values =
                      new Map<string, Payload>();
                  val Payload item = new Payload();

                  try {
                    values.put("x", item);
                    stdio.println("after commit");
                  } catch (err) {
                    val Payload invalid = item;
                  }

                  return;
                }
                """;

        assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(source)));
    }

    @Test
    void secondRejectedWriteRestoresOnlyItsOwnCandidate() {
        String secondCandidateRemainsOwned = """
                define class Payload as
                end

                pub fnc main(): void {
                  val Map<string, Payload> values =
                      new Map<string, Payload>();
                  val Payload first = new Payload();
                  val Payload second = new Payload();

                  try {
                    values.put("first", first);
                    values.put("second", second);
                  } catch (err) {
                    val Payload recovered = second;
                  }

                  return;
                }
                """;

        assertDoesNotThrow(
                () -> OwnershipChecker.check(Parser.parse(secondCandidateRemainsOwned)));

        String earlierCandidateMayAlreadyBeMoved = """
                define class Payload as
                end

                pub fnc main(): void {
                  val Map<string, Payload> values =
                      new Map<string, Payload>();
                  val Payload first = new Payload();
                  val Payload second = new Payload();

                  try {
                    values.put("first", first);
                    values.put("second", second);
                  } catch (err) {
                    val Payload invalid = first;
                  }

                  return;
                }
                """;

        assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(earlierCandidateMayAlreadyBeMoved)));
    }

    @Test
    void namedPutCanBindLinearPreviousValueAndStillRestoreCandidateOnRejection() {
        String source = """
                define class Payload as
                end

                pub fnc main(): void {
                  val Map<string, Payload> values =
                      new Map<string, Payload>();
                  val Payload item = new Payload();

                  try {
                    val Option<Payload> previous =
                        values.put("x", item);
                  } catch (err) {
                    val Payload recovered = item;
                  }

                  return;
                }
                """;

        assertDoesNotThrow(
                () -> OwnershipChecker.check(Parser.parse(source)));
    }

    @Test
    void indexedMoveOnlyInsertionCannotInitializeAnotherBinding() {
        String source = """
                define class Payload as
                end

                pub fnc main(): void {
                  val Map<string, Payload> values =
                      new Map<string, Payload>();
                  val Payload item = new Payload();
                  val Payload alias = (values["x"] = item);
                  return;
                }
                """;

        assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(source)));
    }

    @Test
    void topLevelDeferDisablesTerminalCatchRecovery() {
        String source = """
                define class Payload as
                end

                pub fnc main(): void {
                  val Map<string, Payload> values =
                      new Map<string, Payload>();
                  val Payload item = new Payload();

                  try {
                    defer stdio.println("cleanup");
                    values.put("x", item);
                  } catch (err) {
                    val Payload invalid = item;
                  }

                  return;
                }
                """;

        assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(source)));
    }

    @Test
    void nestedWriteNeverInheritsTerminalTryRecoveryPrivilege() {
        String source = """
                define class Payload as
                end

                pub fnc main(): void {
                  val Map<string, Payload> values =
                      new Map<string, Payload>();
                  val Payload item = new Payload();

                  try {
                    if true then
                      values.put("x", item);
                    fi
                  } catch (err) {
                    val Payload invalid = item;
                  }

                  return;
                }
                """;

        assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(source)));
    }

    @Test
    void laterReturnKeepsEarlierCommitMovedOnCatchEdge() {
        String source = """
                define class Payload as
                end

                fnc exercise(): void {
                  val Map<string, Payload> values =
                      new Map<string, Payload>();
                  val Payload item = new Payload();

                  try {
                    values.put("x", item);
                    return;
                  } catch (err) {
                    val Payload invalid = item;
                  }
                }
                """;

        assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(source)));
    }

    @Test
    void terminalBindingPutWithTopLevelDeferAlsoStaysConservative() {
        String source = """
                define class Payload as
                end

                pub fnc main(): void {
                  val Map<string, Payload> values =
                      new Map<string, Payload>();
                  val Payload item = new Payload();

                  try {
                    defer stdio.println("cleanup");
                    val Option<Payload> previous =
                        values.put("x", item);
                  } catch (err) {
                    val Payload invalid = item;
                  }

                  return;
                }
                """;

        assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(source)));
    }

    @Test
    void liveMutexGuardDisablesTerminalCatchRecovery() {
        String source = """
                define class Payload as
                end

                pub fnc main(): void {
                  val Map<string, Payload> values =
                      new Map<string, Payload>();
                  val Payload item = new Payload();
                  val mutex = Mutex.new(1);

                  try {
                    val guard = mutex.lock();
                    values.put("x", item);
                  } catch (err) {
                    val Payload invalid = item;
                  }

                  return;
                }
                """;

        assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(source)));
    }

    @Test
    void candidateIsStillMovedAfterTryBecauseCommitMayHaveSucceeded() {
        String source = """
                define class Payload as
                end

                pub fnc main(): void {
                  val Map<string, Payload> values =
                      new Map<string, Payload>();
                  val Payload item = new Payload();

                  try {
                    values.put("x", item);
                  } catch (err) {
                    const int ignored = 1;
                  }

                  val Payload invalid = item;
                  return;
                }
                """;

        assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(source)));
    }

    @Test
    void moveOnlyIndexedInsertionUsesSameCatchEdgeRule() {
        String source = """
                define class Payload as
                end

                pub fnc main(): void {
                  val Map<string, Payload> values =
                      new Map<string, Payload>();
                  val Payload item = new Payload();

                  try {
                    values["x"] = item;
                  } catch (err) {
                    val Payload recovered = item;
                  }

                  return;
                }
                """;

        assertDoesNotThrow(
                () -> OwnershipChecker.check(Parser.parse(source)));
    }
}
