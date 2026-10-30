package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

final class ActorStateRwLoansTest {
    private static ExecutionContext actor(
            long execution, long slot, long executionGeneration) {
        return ExecutionContext.actorBound(
                7, execution,
                slot, executionGeneration,
                9, 3, 11,
                0, 0);
    }

    @Test
    void readersCoexistWriterIsExclusiveAndLateReaderCannotBypassWriter() {
        try (var loans = new ActorStateRwLoans(8)) {
            var r1ctx = actor(1001, 41, 1);
            var r2ctx = actor(1002, 42, 1);
            var wctx = actor(1003, 43, 1);
            var latectx = actor(1004, 44, 1);

            var r1 = loans.acquireReadAsync(r1ctx).join();
            var r2 = loans.acquireReadAsync(r2ctx).join();
            assertEquals(2, loans.activeReaderCount());
            assertEquals(0, loans.activeWriterCount());

            var writer = loans.acquireWriteAsync(wctx);
            var lateReader = loans.acquireReadAsync(latectx);
            assertFalse(writer.isDone());
            assertFalse(lateReader.isDone(),
                    "reader arriving after queued writer must not bypass it");

            r1.close();
            assertFalse(writer.isDone());
            r2.close();

            var w = writer.join();
            assertEquals(0, loans.activeReaderCount());
            assertEquals(1, loans.activeWriterCount());
            assertTrue(w.heldBy(wctx));
            assertFalse(lateReader.isDone());

            w.close();
            var late = lateReader.join();
            assertEquals(1, loans.activeReaderCount());
            assertTrue(late.heldBy(latectx));
            late.close();
        }
    }

    @Test
    void contiguousQueuedReadersPromoteAsCohortBeforeFollowingWriter() {
        try (var loans = new ActorStateRwLoans(8)) {
            var firstWriterCtx = actor(2001, 51, 1);
            var firstWriter = loans.acquireWriteAsync(firstWriterCtx).join();

            var r1 = loans.acquireReadAsync(actor(2002, 52, 1));
            var r2 = loans.acquireReadAsync(actor(2003, 53, 1));
            var writer = loans.acquireWriteAsync(actor(2004, 54, 1));
            assertFalse(r1.isDone());
            assertFalse(r2.isDone());
            assertFalse(writer.isDone());

            firstWriter.close();
            var a = r1.join();
            var b = r2.join();
            assertEquals(2, loans.activeReaderCount());
            assertFalse(writer.isDone());

            a.close();
            assertFalse(writer.isDone());
            b.close();
            var w = writer.join();
            assertEquals(1, loans.activeWriterCount());
            w.close();
        }
    }

    @Test
    void equivalentContextSurvivesCarrierMigrationButStaleGenerationDoesNot() throws Exception {
        try (var loans = new ActorStateRwLoans(4)) {
            var original = actor(3001, 61, 8);
            var rematerialized = actor(3001, 61, 8);
            var stale = actor(3001, 61, 7);
            var loan = loans.acquireWriteAsync(original).join();

            AtomicBoolean observed = new AtomicBoolean();
            Thread carrier = Thread.ofPlatform().start(
                    () -> observed.set(loan.heldBy(rematerialized)));
            carrier.join();

            assertTrue(observed.get());
            assertFalse(loan.heldBy(stale));
            assertFalse(loan.readyToSuspend(rematerialized),
                    "live protected access cannot create actor-visible suspension");
            loan.close();
            assertTrue(loan.readyToSuspend(rematerialized));
        }
    }

    @Test
    void sameLogicalExecutionCannotRecursivelyAcquireOrChangeMode() {
        try (var loans = new ActorStateRwLoans(4)) {
            var context = actor(4001, 71, 1);
            var equivalent = actor(4001, 71, 1);
            var read = loans.acquireReadAsync(context).join();
            assertTrue(loans.acquireReadAsync(equivalent).isCompletedExceptionally());
            assertTrue(loans.acquireWriteAsync(equivalent).isCompletedExceptionally());
            read.close();
        }
    }

    @Test
    void cancellingQueuedWriterLetsFollowingReaderJoinLiveReaders() {
        try (var loans = new ActorStateRwLoans(8)) {
            var first = loans.acquireReadAsync(actor(5001, 81, 1)).join();
            var writer = loans.acquireWriteAsync(actor(5002, 82, 1));
            var reader = loans.acquireReadAsync(actor(5003, 83, 1));

            assertFalse(reader.isDone());
            assertTrue(writer.cancel(false));
            var promoted = reader.join();
            assertEquals(2, loans.activeReaderCount());

            promoted.close();
            first.close();
        }
    }

    @Test
    void unboundNonActorAndQueueOverflowFailClosed() {
        try (var loans = new ActorStateRwLoans(1)) {
            assertTrue(loans.acquireReadAsync(
                    ExecutionContext.task(7, 6001)).isCompletedExceptionally());
            assertTrue(loans.acquireReadAsync(
                    ExecutionContext.actor(7, 6002, 9, 3, 11, 0, 0))
                    .isCompletedExceptionally());

            var active = loans.acquireWriteAsync(actor(6003, 91, 1)).join();
            var queued = loans.acquireReadAsync(actor(6004, 92, 1));
            assertTrue(loans.acquireReadAsync(actor(6005, 93, 1))
                    .isCompletedExceptionally());
            active.close();
            queued.join().close();
        }
    }

    @Test
    void closeFailsPendingAndFencesActiveAuthority() {
        var loans = new ActorStateRwLoans(2);
        var activeContext = actor(7001, 101, 1);
        var active = loans.acquireWriteAsync(activeContext).join();
        var pending = loans.acquireReadAsync(actor(7002, 102, 1));
        loans.close();

        assertTrue(pending.isCompletedExceptionally());
        assertFalse(active.heldBy(activeContext),
                "arbiter close immediately fences state access");
        active.close();
        assertEquals(0, loans.activeWriterCount());
    }
}
