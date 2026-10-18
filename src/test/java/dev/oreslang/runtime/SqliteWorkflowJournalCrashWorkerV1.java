package dev.oreslang.runtime;

import java.nio.file.Path;

/**
 * Child-process helper used by SQLite crash-recovery tests.
 *
 * <p>The process halts without running finally/close handlers at the selected
 * persistence fault point, approximating abrupt worker/process death.</p>
 */
public final class SqliteWorkflowJournalCrashWorkerV1 {

    private SqliteWorkflowJournalCrashWorkerV1() { }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.exit(64);
        }

        Path database = Path.of(args[0]);
        SqliteWorkflowJournalV1.FaultPoint target =
                SqliteWorkflowJournalV1.FaultPoint.valueOf(args[1]);

        try (var store = SqliteWorkflowJournalV1.open(
                database,
                point -> {
                    if (point == target) {
                        Runtime.getRuntime().halt(97);
                    }
                })) {
            DurableDevLoopV1 machine =
                    store.replayDevLoop().machine();
            store.append(machine.accept(
                    "code",
                    new DurableDevLoopV1.CodeChanged(
                            1, "patch://child-process")));
        }

        System.exit(0);
    }
}
