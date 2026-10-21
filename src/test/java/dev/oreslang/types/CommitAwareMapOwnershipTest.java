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

        // Existing conservative ownership poisons every move observed in try.
        assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(source)));

        // Staged commit-aware lowering distinguishes admission rejection from
        // the successful commit edge.
        assertDoesNotThrow(
                () -> OwnershipChecker.checkWithCommitAwareMapWritesForTesting(
                        Parser.parse(source)));
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
                () -> OwnershipChecker.checkWithCommitAwareMapWritesForTesting(
                        Parser.parse(source)));
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
                () -> OwnershipChecker.checkWithCommitAwareMapWritesForTesting(
                        Parser.parse(secondCandidateRemainsOwned)));

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
                () -> OwnershipChecker.checkWithCommitAwareMapWritesForTesting(
                        Parser.parse(earlierCandidateMayAlreadyBeMoved)));
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
                () -> OwnershipChecker.checkWithCommitAwareMapWritesForTesting(
                        Parser.parse(source)));
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
                () -> OwnershipChecker.checkWithCommitAwareMapWritesForTesting(
                        Parser.parse(source)));
    }
}
