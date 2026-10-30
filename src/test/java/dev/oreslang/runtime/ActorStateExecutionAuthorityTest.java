package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

final class ActorStateExecutionAuthorityTest {
    private static ExecutionContext actor(
            long execution, long slot, long executionGeneration,
            long actorGeneration) {
        return ExecutionContext.actorBound(
                7, execution,
                slot, executionGeneration,
                9, 3, actorGeneration,
                0, 0);
    }

    @Test
    void equivalentLogicalExecutionSurvivesCarrierMigration() throws Exception {
        try (var loans = new ActorStateLoans(4)) {
            var original = actor(1001, 41, 6, 11);
            var rematerialized = actor(1001, 41, 6, 11);
            var loan = loans.acquireAsync(original).join();

            assertTrue(loan.heldBy(original));
            assertTrue(loan.heldBy(rematerialized),
                    "logical execution authority must not depend on Java thread/object identity");
            assertTrue(loans.acquireAsync(rematerialized).isCompletedExceptionally(),
                    "same logical execution cannot recursively acquire via a new context object");

            AtomicBoolean carrierObserved = new AtomicBoolean();
            Thread carrier = Thread.ofPlatform().start(
                    () -> carrierObserved.set(loan.heldBy(rematerialized)));
            carrier.join();
            assertTrue(carrierObserved.get(),
                    "physical carrier migration must preserve logical state authority");

            loan.close();
            assertFalse(loan.heldBy(rematerialized));
        }
    }

    @Test
    void staleExecutionAndActorGenerationsCannotUseCurrentLoan() {
        try (var loans = new ActorStateLoans(4)) {
            var current = actor(1001, 41, 6, 11);
            var staleExecution = actor(1001, 41, 5, 11);
            var otherSlot = actor(1001, 42, 6, 11);
            var staleActor = actor(1001, 41, 6, 10);
            var foreignExecution = actor(1002, 41, 6, 11);

            var loan = loans.acquireAsync(current).join();
            assertFalse(loan.heldBy(staleExecution));
            assertFalse(loan.heldBy(otherSlot));
            assertFalse(loan.heldBy(staleActor));
            assertFalse(loan.heldBy(foreignExecution));
            assertTrue(loan.heldBy(current));
            loan.close();
        }
    }

    @Test
    void unboundOrNonActorContextCannotAcquireActorStateAuthority() {
        try (var loans = new ActorStateLoans(2)) {
            var unboundActor = ExecutionContext.actor(
                    7, 1001, 9, 3, 11, 0, 0);
            var plainTask = ExecutionContext.task(7, 1002);
            assertTrue(loans.acquireAsync(unboundActor).isCompletedExceptionally());
            assertTrue(loans.acquireAsync(plainTask).isCompletedExceptionally());
            assertEquals(0, loans.activeCount());
            assertEquals(0, loans.pendingCount());
        }
    }

    @Test
    void erasedExecutionContextCannotBypassBoundAuthorityChecks() {
        try (var loans = new ActorStateLoans(2)) {
            Object erasedUnbound = ExecutionContext.actor(
                    7, 1001, 9, 3, 11, 0, 0);
            assertTrue(loans.acquireAsync(erasedUnbound).isCompletedExceptionally());
            assertEquals(0, loans.activeCount());
        }
    }

    @Test
    void distinctExecutionsRemainFifoAndExclusive() {
        try (var loans = new ActorStateLoans(2)) {
            var firstExecution = actor(1001, 41, 6, 11);
            var secondExecution = actor(1002, 42, 2, 11);
            var first = loans.acquireAsync(firstExecution).join();
            var secondFuture = loans.acquireAsync(secondExecution);
            assertFalse(secondFuture.isDone());

            first.close();
            var second = secondFuture.join();
            assertTrue(second.heldBy(secondExecution));
            assertFalse(second.heldBy(firstExecution));
            second.close();
            assertEquals(0, loans.activeCount());
        }
    }
}
