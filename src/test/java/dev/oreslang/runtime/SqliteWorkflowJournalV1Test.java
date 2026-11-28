package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class SqliteWorkflowJournalV1Test {

    @TempDir
    Path tempDir;

    @Test
    void emptyStoreReopensWithCanonicalInitialState() {
        Path db = tempDir.resolve("empty.db");
        try (var store = SqliteWorkflowJournalV1.open(db)) {
            var snapshot = store.snapshot();
            assertNull(snapshot.runId());
            assertNull(snapshot.maxIterations());
            assertEquals(1L, snapshot.nextSequence());
            assertEquals(0, snapshot.journalEntries());
            assertTrue(store.effects().isEmpty());
        }

        try (var reopened = SqliteWorkflowJournalV1.open(db)) {
            assertEquals(1L, reopened.snapshot().nextSequence());
            assertEquals(0, reopened.snapshot().journalEntries());
        }
    }

    @Test
    void roundTripReplaysWorkflowAndEffectLifecycle() {
        Path db = tempDir.resolve("roundtrip.db");
        var loop = new DurableDevLoopV1("run-sqlite", 4);
        String sha = sha('a');

        try (var store = SqliteWorkflowJournalV1.open(db)) {
            store.append(loop.accept(
                    "start",
                    new DurableDevLoopV1.Start("goal")));
            var codeClaim = store.claimNext(
                    "coder", 100L, 100L).orElseThrow();
            store.complete(
                    codeClaim.effectKey(),
                    codeClaim.claimToken(),
                    "receipt://code",
                    150L);

            store.append(loop.accept(
                    "code",
                    new DurableDevLoopV1.CodeChanged(
                            1, "patch://one")));
            store.append(loop.accept(
                    "push",
                    new DurableDevLoopV1.PushRecorded(
                            1, sha)));

            var ci = store.effect(
                    "devloop/run-sqlite/iteration/1/ci/" + sha)
                    .orElseThrow();
            assertEquals(
                    WorkflowJournalV1.EffectState.PENDING,
                    ci.state());
        }

        try (var reopened = SqliteWorkflowJournalV1.open(db)) {
            var replay = reopened.replayDevLoop();
            assertEquals(DurableDevLoopV1.Phase.WAITING_FOR_CI,
                    replay.machine().snapshot().phase());
            assertEquals(3, reopened.journal().size());

            var code = reopened.effect(
                    "devloop/run-sqlite/iteration/1/code")
                    .orElseThrow();
            assertEquals(
                    WorkflowJournalV1.EffectState.COMPLETED,
                    code.state());
            assertEquals("receipt://code", code.receiptRef());

            var ci = reopened.effect(
                    "devloop/run-sqlite/iteration/1/ci/" + sha)
                    .orElseThrow();
            assertEquals(
                    WorkflowJournalV1.EffectState.PENDING,
                    ci.state());
        }
    }

    @Test
    void claimedAndAmbiguousStateSurvivesReopen() {
        Path db = tempDir.resolve("lease.db");
        var loop = new DurableDevLoopV1("run-lease", 3);
        String effectKey;

        try (var store = SqliteWorkflowJournalV1.open(db)) {
            store.append(loop.accept(
                    "start",
                    new DurableDevLoopV1.Start("goal")));
            var claim = store.claimNext(
                    "worker-a", 1_000L, 50L).orElseThrow();
            effectKey = claim.effectKey();
        }

        try (var reopened = SqliteWorkflowJournalV1.open(db)) {
            var claimed = reopened.effect(effectKey).orElseThrow();
            assertEquals(
                    WorkflowJournalV1.EffectState.CLAIMED,
                    claimed.state());
            assertEquals("worker-a", claimed.workerId());

            assertEquals(1, reopened.expireClaims(1_050L));
        }

        try (var reopened = SqliteWorkflowJournalV1.open(db)) {
            var ambiguous = reopened.effect(effectKey).orElseThrow();
            assertEquals(
                    WorkflowJournalV1.EffectState.AMBIGUOUS,
                    ambiguous.state());
            assertNotNull(ambiguous.claimToken());
            assertTrue(ambiguous.leaseUntilMillis() > 0);

            reopened.resolveAmbiguousForRetry(
                    effectKey,
                    "reconcile://not-found");
            assertEquals(
                    WorkflowJournalV1.EffectState.PENDING,
                    reopened.effect(effectKey).orElseThrow().state());
        }
    }

    @Test
    void everyPreCommitFaultRollsBackWholeJournalOutboxMutation() {
        EnumSet<SqliteWorkflowJournalV1.FaultPoint> points =
                EnumSet.of(
                        SqliteWorkflowJournalV1.FaultPoint.AFTER_BEGIN,
                        SqliteWorkflowJournalV1.FaultPoint.AFTER_META_WRITE,
                        SqliteWorkflowJournalV1.FaultPoint.AFTER_JOURNAL_WRITE,
                        SqliteWorkflowJournalV1.FaultPoint.AFTER_EFFECT_WRITE,
                        SqliteWorkflowJournalV1.FaultPoint.BEFORE_COMMIT);

        for (var point : points) {
            Path db = tempDir.resolve(
                    "rollback-" + point.name() + ".db");
            seedStarted(db, "run-" + point.name());

            try (var store = SqliteWorkflowJournalV1.open(
                    db,
                    actual -> {
                        if (actual == point) {
                            throw new SimulatedCrash(point);
                        }
                    })) {
                var machine = store.replayDevLoop().machine();
                assertThrows(
                        SimulatedCrash.class,
                        () -> store.append(machine.accept(
                                "code",
                                new DurableDevLoopV1.CodeChanged(
                                        1, "patch://one"))));
            }

            try (var reopened = SqliteWorkflowJournalV1.open(db)) {
                assertEquals(1, reopened.journal().size(), point::name);
                assertEquals(
                        DurableDevLoopV1.Phase.CODING,
                        reopened.replayDevLoop().machine()
                                .snapshot().phase(),
                        point::name);
                assertEquals(1, reopened.effects().size(), point::name);
                assertEquals(
                        WorkflowJournalV1.EffectState.PENDING,
                        reopened.effects().getFirst().state(),
                        point::name);
            }
        }
    }

    @Test
    void atomicOutcomeFaultBeforeCommitRollsBackReceiptAndEventTogether() {
        Path db = tempDir.resolve("atomic-before-commit.db");
        seedStarted(db, "run-atomic-before");
        WorkflowJournalV1.Claim claim;
        try (var store = SqliteWorkflowJournalV1.open(db)) {
            claim = store.claimNext(
                    "worker-a", 100L, 100L).orElseThrow();
        }

        try (var store = SqliteWorkflowJournalV1.open(
                db,
                point -> {
                    if (point
                            == SqliteWorkflowJournalV1.FaultPoint.BEFORE_COMMIT) {
                        throw new SimulatedCrash(point);
                    }
                })) {
            assertThrows(
                    SimulatedCrash.class,
                    () -> store.completeAndAppend(
                            claim.effectKey(),
                            claim.claimToken(),
                            "receipt://code/1",
                            150L,
                            "code-1",
                            new DurableDevLoopV1.CodeChanged(
                                    1, "patch://one")));
        }

        try (var reopened = SqliteWorkflowJournalV1.open(db)) {
            assertEquals(1, reopened.journal().size());
            assertEquals(
                    DurableDevLoopV1.Phase.CODING,
                    reopened.replayDevLoop().machine().snapshot().phase());
            var effect = reopened.effect(
                    claim.effectKey()).orElseThrow();
            assertEquals(
                    WorkflowJournalV1.EffectState.CLAIMED,
                    effect.state());
            assertNull(effect.receiptRef());
        }
    }

    @Test
    void atomicOutcomeFaultAfterCommitRecoversReceiptAndEventTogether() {
        Path db = tempDir.resolve("atomic-after-commit.db");
        seedStarted(db, "run-atomic-after");
        WorkflowJournalV1.Claim claim;
        try (var store = SqliteWorkflowJournalV1.open(db)) {
            claim = store.claimNext(
                    "worker-a", 100L, 100L).orElseThrow();
        }

        try (var store = SqliteWorkflowJournalV1.open(
                db,
                point -> {
                    if (point
                            == SqliteWorkflowJournalV1.FaultPoint.AFTER_COMMIT) {
                        throw new SimulatedCrash(point);
                    }
                })) {
            assertThrows(
                    SimulatedCrash.class,
                    () -> store.completeAndAppend(
                            claim.effectKey(),
                            claim.claimToken(),
                            "receipt://code/1",
                            150L,
                            "code-1",
                            new DurableDevLoopV1.CodeChanged(
                                    1, "patch://one")));
        }

        try (var reopened = SqliteWorkflowJournalV1.open(db)) {
            assertEquals(2, reopened.journal().size());
            assertEquals(
                    DurableDevLoopV1.Phase.WAITING_FOR_PUSH,
                    reopened.replayDevLoop().machine().snapshot().phase());
            var effect = reopened.effect(
                    claim.effectKey()).orElseThrow();
            assertEquals(
                    WorkflowJournalV1.EffectState.COMPLETED,
                    effect.state());
            assertEquals("receipt://code/1", effect.receiptRef());
            assertEquals(1, reopened.snapshot().pendingEffects());
        }
    }

    @Test
    void expiredCompletionPersistsAmbiguousStateBeforeRethrow() {
        Path db = tempDir.resolve("expired-complete.db");
        var loop = new DurableDevLoopV1("run-expired-complete", 3);
        WorkflowJournalV1.Claim claim;

        try (var store = SqliteWorkflowJournalV1.open(db)) {
            store.append(loop.accept(
                    "start",
                    new DurableDevLoopV1.Start("goal")));
            claim = store.claimNext(
                    "worker-a", 100L, 20L).orElseThrow();

            assertThrows(
                    IllegalStateException.class,
                    () -> store.complete(
                            claim.effectKey(),
                            claim.claimToken(),
                            "receipt://late",
                            120L));

            assertEquals(
                    WorkflowJournalV1.EffectState.AMBIGUOUS,
                    store.effect(claim.effectKey())
                            .orElseThrow()
                            .state());
        }

        try (var reopened = SqliteWorkflowJournalV1.open(db)) {
            var effect = reopened.effect(
                    claim.effectKey()).orElseThrow();
            assertEquals(
                    WorkflowJournalV1.EffectState.AMBIGUOUS,
                    effect.state());
            assertNull(effect.receiptRef());
            assertNotNull(effect.claimToken());
        }
    }

    @Test
    void semanticMutationFaultBeforeCommitRecoversPriorClaimState() {
        Path db = tempDir.resolve("expired-complete-crash.db");
        var loop = new DurableDevLoopV1("run-expired-crash", 3);
        WorkflowJournalV1.Claim claim;

        try (var seed = SqliteWorkflowJournalV1.open(db)) {
            seed.append(loop.accept(
                    "start",
                    new DurableDevLoopV1.Start("goal")));
            claim = seed.claimNext(
                    "worker-a", 100L, 20L).orElseThrow();
        }

        try (var store = SqliteWorkflowJournalV1.open(
                db,
                point -> {
                    if (point
                            == SqliteWorkflowJournalV1.FaultPoint.BEFORE_COMMIT) {
                        throw new SimulatedCrash(point);
                    }
                })) {
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> store.complete(
                            claim.effectKey(),
                            claim.claimToken(),
                            "receipt://late",
                            120L));
            assertTrue(
                    java.util.Arrays.stream(failure.getSuppressed())
                            .anyMatch(SimulatedCrash.class::isInstance));
        }

        try (var reopened = SqliteWorkflowJournalV1.open(db)) {
            var effect = reopened.effect(
                    claim.effectKey()).orElseThrow();
            assertEquals(
                    WorkflowJournalV1.EffectState.CLAIMED,
                    effect.state());
            assertNull(effect.receiptRef());
            assertEquals(claim.claimToken(), effect.claimToken());
        }
    }

    @Test
    void invalidClaimFailureRollsBackOnceAndStoreRemainsUsable() {
        Path db = tempDir.resolve("invalid-claim.db");
        var loop = new DurableDevLoopV1("run-invalid-claim", 3);

        try (var store = SqliteWorkflowJournalV1.open(db)) {
            store.append(loop.accept(
                    "start",
                    new DurableDevLoopV1.Start("goal")));
            var claim = store.claimNext(
                    "worker-a", 100L, 100L).orElseThrow();

            assertThrows(
                    IllegalStateException.class,
                    () -> store.complete(
                            claim.effectKey(),
                            claim.claimToken() + "-wrong",
                            "receipt://invalid",
                            150L));

            var effect = store.effect(
                    claim.effectKey()).orElseThrow();
            assertEquals(
                    WorkflowJournalV1.EffectState.CLAIMED,
                    effect.state());

            store.complete(
                    claim.effectKey(),
                    claim.claimToken(),
                    "receipt://valid",
                    150L);
            assertEquals(
                    WorkflowJournalV1.EffectState.COMPLETED,
                    store.effect(claim.effectKey())
                            .orElseThrow()
                            .state());
        }
    }

    @Test
    void faultAfterCommitRecoversNewStateRatherThanOldState() {
        Path db = tempDir.resolve("after-commit.db");
        seedStarted(db, "run-after-commit");

        try (var store = SqliteWorkflowJournalV1.open(
                db,
                point -> {
                    if (point
                            == SqliteWorkflowJournalV1.FaultPoint.AFTER_COMMIT) {
                        throw new SimulatedCrash(point);
                    }
                })) {
            var machine = store.replayDevLoop().machine();
            assertThrows(
                    SimulatedCrash.class,
                    () -> store.append(machine.accept(
                            "code",
                            new DurableDevLoopV1.CodeChanged(
                                    1, "patch://one"))));
        }

        try (var reopened = SqliteWorkflowJournalV1.open(db)) {
            assertEquals(2, reopened.journal().size());
            assertEquals(
                    DurableDevLoopV1.Phase.WAITING_FOR_PUSH,
                    reopened.replayDevLoop().machine().snapshot().phase());
            assertEquals(
                    WorkflowJournalV1.EffectState.ABANDONED,
                    reopened.effect(
                            "devloop/run-after-commit/iteration/1/code")
                            .orElseThrow()
                            .state());
            assertEquals(
                    WorkflowJournalV1.EffectState.PENDING,
                    reopened.effect(
                            "devloop/run-after-commit/iteration/1/push")
                            .orElseThrow()
                            .state());
        }
    }

    @Test
    void completeAndAppendIsAtomicAcrossEveryPreCommitFault() {
        EnumSet<SqliteWorkflowJournalV1.FaultPoint> points =
                EnumSet.of(
                        SqliteWorkflowJournalV1.FaultPoint.AFTER_BEGIN,
                        SqliteWorkflowJournalV1.FaultPoint.AFTER_META_WRITE,
                        SqliteWorkflowJournalV1.FaultPoint.AFTER_JOURNAL_WRITE,
                        SqliteWorkflowJournalV1.FaultPoint.AFTER_EFFECT_WRITE,
                        SqliteWorkflowJournalV1.FaultPoint.BEFORE_COMMIT);

        for (var point : points) {
            Path db = tempDir.resolve(
                    "complete-append-" + point.name() + ".db");
            WorkflowJournalV1.Claim claim =
                    seedClaimedCode(db, "run-ca-" + point.name());

            try (var store = SqliteWorkflowJournalV1.open(
                    db,
                    actual -> {
                        if (actual == point) {
                            throw new SimulatedCrash(point);
                        }
                    })) {
                assertThrows(
                        SimulatedCrash.class,
                        () -> store.completeAndAppend(
                                claim.effectKey(),
                                claim.claimToken(),
                                "receipt://code",
                                150L,
                                "code",
                                new DurableDevLoopV1.CodeChanged(
                                        1, "patch://one")));
            }

            try (var reopened = SqliteWorkflowJournalV1.open(db)) {
                assertEquals(1, reopened.journal().size(), point::name);
                var code = reopened.effect(
                        claim.effectKey()).orElseThrow();
                assertEquals(
                        WorkflowJournalV1.EffectState.CLAIMED,
                        code.state(),
                        point::name);
                assertNull(code.receiptRef(), point::name);
                assertEquals(
                        DurableDevLoopV1.Phase.CODING,
                        reopened.replayDevLoop().machine()
                                .snapshot().phase(),
                        point::name);
                assertEquals(1, reopened.effects().size(), point::name);
            }
        }
    }

    @Test
    void completeAndAppendSurvivesCrashAfterCommitAsOneDurableUnit() {
        Path db = tempDir.resolve("complete-append-after.db");
        WorkflowJournalV1.Claim claim =
                seedClaimedCode(db, "run-ca-after");

        try (var store = SqliteWorkflowJournalV1.open(
                db,
                point -> {
                    if (point
                            == SqliteWorkflowJournalV1.FaultPoint.AFTER_COMMIT) {
                        throw new SimulatedCrash(point);
                    }
                })) {
            assertThrows(
                    SimulatedCrash.class,
                    () -> store.completeAndAppend(
                            claim.effectKey(),
                            claim.claimToken(),
                            "receipt://code",
                            150L,
                            "code",
                            new DurableDevLoopV1.CodeChanged(
                                    1, "patch://one")));
        }

        try (var reopened = SqliteWorkflowJournalV1.open(db)) {
            assertEquals(2, reopened.journal().size());
            assertEquals(
                    DurableDevLoopV1.Phase.WAITING_FOR_PUSH,
                    reopened.replayDevLoop().machine().snapshot().phase());

            var code = reopened.effect(
                    claim.effectKey()).orElseThrow();
            assertEquals(
                    WorkflowJournalV1.EffectState.COMPLETED,
                    code.state());
            assertEquals("receipt://code", code.receiptRef());

            assertEquals(
                    WorkflowJournalV1.EffectState.PENDING,
                    reopened.effect(
                            "devloop/run-ca-after/iteration/1/push")
                            .orElseThrow()
                            .state());
        }
    }

    @Test
    void separateConnectionsHaveSingleWinnerForOnePendingEffect()
            throws Exception {
        Path db = tempDir.resolve("two-connections.db");
        seedStarted(db, "run-two-connections");

        try (var first = SqliteWorkflowJournalV1.open(db);
             var second = SqliteWorkflowJournalV1.open(db);
             var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var results = new ArrayList<
                    java.util.concurrent.Future<
                            java.util.Optional<WorkflowJournalV1.Claim>>>();

            results.add(pool.submit(() -> {
                start.await();
                return first.claimNext(
                        "worker-a", 100L, 100L);
            }));
            results.add(pool.submit(() -> {
                start.await();
                return second.claimNext(
                        "worker-b", 100L, 100L);
            }));
            start.countDown();

            List<WorkflowJournalV1.Claim> winners =
                    new ArrayList<>();
            for (var result : results) {
                result.get(10, TimeUnit.SECONDS)
                        .ifPresent(winners::add);
            }
            assertEquals(1, winners.size());
        }

        try (var reopened = SqliteWorkflowJournalV1.open(db)) {
            assertEquals(1, reopened.snapshot().claimedEffects());
            assertEquals(0, reopened.snapshot().pendingEffects());
        }
    }

    @Test
    void concurrentReaderNeverObservesMixedSqliteGeneration()
            throws Exception {
        Path db = tempDir.resolve("consistent-read.db");
        seedStarted(db, "run-consistent-read");

        try (var writer = SqliteWorkflowJournalV1.open(db);
             var reader = SqliteWorkflowJournalV1.open(db);
             var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);

            var writeFuture = pool.submit(() -> {
                start.await();
                for (int i = 0; i < 100; i++) {
                    var machine = writer.replayDevLoop().machine();
                    writer.append(machine.accept(
                            "late-start-" + i,
                            new DurableDevLoopV1.Start("again")));
                }
                return null;
            });

            var readFuture = pool.submit(() -> {
                start.await();
                for (int i = 0; i < 200; i++) {
                    var view = reader.readView();
                    assertEquals(
                            view.storeSnapshot().journalEntries(),
                            view.journal().size());
                    assertEquals(
                            view.storeSnapshot().nextSequence(),
                            view.journal().size() + 1L);
                    assertEquals(
                            view.journal(),
                            view.replay().machine().journal());
                }
                return null;
            });

            start.countDown();
            writeFuture.get(20, TimeUnit.SECONDS);
            readFuture.get(20, TimeUnit.SECONDS);
        }

        try (var reopened = SqliteWorkflowJournalV1.open(db)) {
            assertEquals(101, reopened.journal().size());
            assertEquals(
                    102L,
                    reopened.snapshot().nextSequence());
        }
    }

    @Test
    void tamperedEffectCommandFailsClosedOnOpen() throws Exception {
        Path db = tempDir.resolve("tamper.db");
        seedStarted(db, "run-tamper");

        try (var connection = DriverManager.getConnection(
                "jdbc:sqlite:" + db.toAbsolutePath());
             var statement = connection.createStatement()) {
            assertEquals(
                    1,
                    statement.executeUpdate("""
                            UPDATE workflow_effects
                            SET command_text1 = 'tampered goal'
                            WHERE command_type = 'request_code'
                            """));
        }

        assertThrows(
                IllegalStateException.class,
                () -> SqliteWorkflowJournalV1.open(db));
    }

    @Test
    void reorderedPersistedEffectsFailClosedOnOpen() throws Exception {
        Path db = tempDir.resolve("effect-order.db");
        var loop = new DurableDevLoopV1("run-effect-order", 3);

        try (var store = SqliteWorkflowJournalV1.open(db)) {
            store.append(loop.accept(
                    "start",
                    new DurableDevLoopV1.Start("goal")));
            store.append(loop.accept(
                    "code",
                    new DurableDevLoopV1.CodeChanged(
                            1, "patch://one")));
            assertEquals(2, store.effects().size());
        }

        try (var connection = DriverManager.getConnection(
                "jdbc:sqlite:" + db.toAbsolutePath());
             var statement = connection.createStatement()) {
            assertEquals(
                    1,
                    statement.executeUpdate(
                            "UPDATE workflow_effects "
                                    + "SET ordinal = -1 WHERE ordinal = 0"));
            assertEquals(
                    1,
                    statement.executeUpdate(
                            "UPDATE workflow_effects "
                                    + "SET ordinal = 0 WHERE ordinal = 1"));
            assertEquals(
                    1,
                    statement.executeUpdate(
                            "UPDATE workflow_effects "
                                    + "SET ordinal = 1 WHERE ordinal = -1"));
        }

        assertThrows(
                IllegalStateException.class,
                () -> SqliteWorkflowJournalV1.open(db));
    }

    @Test
    void noncanonicalIgnoredEventColumnFailsClosedOnOpen() throws Exception {
        Path db = tempDir.resolve("noncanonical-event.db");
        seedStarted(db, "run-noncanonical-event");

        try (var connection = DriverManager.getConnection(
                "jdbc:sqlite:" + db.toAbsolutePath());
             var statement = connection.createStatement()) {
            assertEquals(
                    1,
                    statement.executeUpdate("""
                            UPDATE workflow_journal
                            SET event_text4 = 'ignored-but-present'
                            WHERE event_type = 'start'
                            """));
        }

        assertThrows(
                IllegalStateException.class,
                () -> SqliteWorkflowJournalV1.open(db));
    }

    @Test
    void noncanonicalIgnoredCommandColumnFailsClosedOnOpen()
            throws Exception {
        Path db = tempDir.resolve("noncanonical-command.db");
        seedStarted(db, "run-noncanonical-command");

        try (var connection = DriverManager.getConnection(
                "jdbc:sqlite:" + db.toAbsolutePath());
             var statement = connection.createStatement()) {
            assertEquals(
                    1,
                    statement.executeUpdate("""
                            UPDATE workflow_effects
                            SET command_text4 = 'ignored-but-present'
                            WHERE command_type = 'request_code'
                            """));
        }

        assertThrows(
                IllegalStateException.class,
                () -> SqliteWorkflowJournalV1.open(db));
    }

    @Test
    void realChildProcessAtomicOutcomeCrashBeforeCommitRecoversClaimOnly()
            throws Exception {
        Path db = tempDir.resolve("process-atomic-before.db");
        seedStarted(db, "run-process-atomic-before");
        WorkflowJournalV1.Claim claim;
        try (var store = SqliteWorkflowJournalV1.open(db)) {
            claim = store.claimNext(
                    "worker-a", 100L, 100L).orElseThrow();
        }

        int exit = runCrashWorker(
                db,
                SqliteWorkflowJournalV1.FaultPoint.BEFORE_COMMIT,
                claim.claimToken());
        assertNotEquals(0, exit);

        try (var reopened = SqliteWorkflowJournalV1.open(db)) {
            assertEquals(1, reopened.journal().size());
            assertEquals(
                    DurableDevLoopV1.Phase.CODING,
                    reopened.replayDevLoop().machine().snapshot().phase());
            var effect = reopened.effect(
                    claim.effectKey()).orElseThrow();
            assertEquals(
                    WorkflowJournalV1.EffectState.CLAIMED,
                    effect.state());
            assertNull(effect.receiptRef());
        }
    }

    @Test
    void realChildProcessAtomicOutcomeCrashAfterCommitRecoversBothSides()
            throws Exception {
        Path db = tempDir.resolve("process-atomic-after.db");
        seedStarted(db, "run-process-atomic-after");
        WorkflowJournalV1.Claim claim;
        try (var store = SqliteWorkflowJournalV1.open(db)) {
            claim = store.claimNext(
                    "worker-a", 100L, 100L).orElseThrow();
        }

        int exit = runCrashWorker(
                db,
                SqliteWorkflowJournalV1.FaultPoint.AFTER_COMMIT,
                claim.claimToken());
        assertNotEquals(0, exit);

        try (var reopened = SqliteWorkflowJournalV1.open(db)) {
            assertEquals(2, reopened.journal().size());
            assertEquals(
                    DurableDevLoopV1.Phase.WAITING_FOR_PUSH,
                    reopened.replayDevLoop().machine().snapshot().phase());
            var effect = reopened.effect(
                    claim.effectKey()).orElseThrow();
            assertEquals(
                    WorkflowJournalV1.EffectState.COMPLETED,
                    effect.state());
            assertEquals(
                    "receipt://child-process",
                    effect.receiptRef());
        }
    }

    @Test
    void realChildProcessCrashBeforeCommitRecoversOldState()
            throws Exception {
        Path db = tempDir.resolve("process-before.db");
        seedStarted(db, "run-process-before");

        int exit = runCrashWorker(
                db,
                SqliteWorkflowJournalV1.FaultPoint.BEFORE_COMMIT);
        assertNotEquals(0, exit);

        try (var reopened = SqliteWorkflowJournalV1.open(db)) {
            assertEquals(1, reopened.journal().size());
            assertEquals(
                    DurableDevLoopV1.Phase.CODING,
                    reopened.replayDevLoop().machine().snapshot().phase());
        }
    }

    @Test
    void realChildProcessCrashAfterCommitRecoversNewState()
            throws Exception {
        Path db = tempDir.resolve("process-after.db");
        seedStarted(db, "run-process-after");

        int exit = runCrashWorker(
                db,
                SqliteWorkflowJournalV1.FaultPoint.AFTER_COMMIT);
        assertNotEquals(0, exit);

        try (var reopened = SqliteWorkflowJournalV1.open(db)) {
            assertEquals(2, reopened.journal().size());
            assertEquals(
                    DurableDevLoopV1.Phase.WAITING_FOR_PUSH,
                    reopened.replayDevLoop().machine().snapshot().phase());
        }
    }

    @Test
    void separateProcessesHaveSingleDurableWinnerForOnePendingEffect()
            throws Exception {
        Path db = tempDir.resolve("two-process-claim.db");
        seedStarted(db, "run-two-process-claim");
        Path gate = tempDir.resolve("claim-start.gate");

        Process first = startClaimWorker(db, "worker-a", gate);
        Process second = startClaimWorker(db, "worker-b", gate);
        java.nio.file.Files.writeString(gate, "go");

        assertTrue(first.waitFor(20, TimeUnit.SECONDS));
        assertTrue(second.waitFor(20, TimeUnit.SECONDS));

        int firstExit = first.exitValue();
        int secondExit = second.exitValue();
        assertTrue(
                (firstExit == 0 && secondExit == 3)
                        || (firstExit == 3 && secondExit == 0),
                () -> "expected exactly one durable claim winner, exits="
                        + firstExit + "," + secondExit);

        try (var reopened = SqliteWorkflowJournalV1.open(db)) {
            assertEquals(1, reopened.snapshot().claimedEffects());
            assertEquals(0, reopened.snapshot().pendingEffects());
            var effect = reopened.effects().getFirst();
            assertEquals(1, effect.attempts());
            assertTrue(
                    "worker-a".equals(effect.workerId())
                            || "worker-b".equals(effect.workerId()));
            assertNotNull(effect.claimToken());
        }
    }

    private static Process startClaimWorker(
            Path database,
            String workerId,
            Path gate) throws Exception {
        Path java = Path.of(
                System.getProperty("java.home"), "bin", "java");
        String classPath = System.getProperty("java.class.path");
        return new ProcessBuilder(
                java.toString(),
                "-cp",
                classPath,
                SqliteWorkflowJournalClaimWorkerV1.class.getName(),
                database.toAbsolutePath().toString(),
                workerId,
                gate.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .start();
    }

    private static int runCrashWorker(
            Path database,
            SqliteWorkflowJournalV1.FaultPoint point,
            String claimToken)
            throws Exception {
        return runCrashWorker(
                database,
                point,
                new String[] {"atomic-outcome", claimToken});
    }

    private static int runCrashWorker(
            Path database,
            SqliteWorkflowJournalV1.FaultPoint point)
            throws Exception {
        return runCrashWorker(
                database,
                point,
                new String[0]);
    }

    private static int runCrashWorker(
            Path database,
            SqliteWorkflowJournalV1.FaultPoint point,
            String[] extraArgs)
            throws Exception {
        String classPath = System.getProperty(
                "surefire.test.class.path",
                System.getProperty("java.class.path"));
        java.util.ArrayList<String> command =
                new java.util.ArrayList<>();
        command.add(Path.of(
                System.getProperty("java.home"),
                "bin",
                "java").toString());
        command.add("-cp");
        command.add(classPath);
        command.add(SqliteWorkflowJournalCrashWorkerV1.class.getName());
        command.add(database.toAbsolutePath().toString());
        command.add(point.name());
        command.addAll(java.util.List.of(extraArgs));

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        boolean exited = process.waitFor(20, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            fail("crash worker did not terminate");
        }
        return process.exitValue();
    }

    private static WorkflowJournalV1.Claim seedClaimedCode(
            Path db,
            String runId) {
        var loop = new DurableDevLoopV1(runId, 3);
        try (var store = SqliteWorkflowJournalV1.open(db)) {
            store.append(loop.accept(
                    "start",
                    new DurableDevLoopV1.Start("goal")));
            return store.claimNext(
                    "coder", 100L, 100L).orElseThrow();
        }
    }

    private static void seedStarted(Path db, String runId) {
        var loop = new DurableDevLoopV1(runId, 3);
        try (var store = SqliteWorkflowJournalV1.open(db)) {
            store.append(loop.accept(
                    "start",
                    new DurableDevLoopV1.Start("goal")));
        }
    }

    private static String sha(char value) {
        return String.valueOf(value).repeat(40);
    }

    private static final class SimulatedCrash
            extends RuntimeException {
        private SimulatedCrash(
                SqliteWorkflowJournalV1.FaultPoint point) {
            super(point.name());
        }
    }
}
