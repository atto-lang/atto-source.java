package dev.oreslang.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Child-JVM helper for real SQLite lock/claim races.
 *
 * <p>Exit 0 means this process durably won the claim; exit 3 means another
 * process won first. Runtime.halt intentionally skips close/finally handlers so
 * the parent test observes recovery after abrupt process death.</p>
 */
public final class SqliteWorkflowJournalClaimWorkerV1 {
    private SqliteWorkflowJournalClaimWorkerV1() { }

    public static void main(String[] args) {
        if (args.length != 3) {
            Runtime.getRuntime().halt(64);
        }

        Path database = Path.of(args[0]);
        String workerId = args[1];
        Path gate = Path.of(args[2]);

        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!Files.exists(gate)) {
                if (System.nanoTime() >= deadline) {
                    Runtime.getRuntime().halt(69);
                }
                Thread.sleep(1L);
            }

            Optional<WorkflowJournalV1.Claim> claim;
            try (var store = SqliteWorkflowJournalV1.open(database)) {
                claim = store.claimNext(workerId, 1_000L, 1_000L);
            }

            Runtime.getRuntime().halt(claim.isPresent() ? 0 : 3);
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            Runtime.getRuntime().halt(70);
        }
    }
}
