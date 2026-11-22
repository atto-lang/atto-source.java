package dev.oreslang;

import dev.oreslang.runtime.ActorStateLoans;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * VM-level logical actor-state loan conformance; compilation of source
 * handle_state/critical syntax and real async channel wait points is separate.
 */
@Timeout(15)
final class ActorStateLoansContractTest {
    @Test
    void logicalExclusivityAndFifoSurviveSuspendedWaiter() throws Exception {
        Object firstOwner = new Object();
        Object secondOwner = new Object();
        Object thirdOwner = new Object();
        try (ActorStateLoans loans = new ActorStateLoans(2)) {
            var first = loans.acquireAsync(firstOwner).get(2, TimeUnit.SECONDS);
            var secondWaiter = loans.acquireAsync(secondOwner);
            var thirdWaiter = loans.acquireAsync(thirdOwner);
            assertTrue(first.heldBy(firstOwner));
            assertFalse(first.heldBy(secondOwner));
            assertEquals(1, loans.activeCount());
            assertEquals(2, loans.pendingCount());
            assertFalse(secondWaiter.isDone());
            assertFalse(thirdWaiter.isDone());

            first.close();
            var second = secondWaiter.get(2, TimeUnit.SECONDS);
            assertTrue(second.heldBy(secondOwner));
            assertFalse(first.heldBy(firstOwner));
            assertEquals(1, loans.pendingCount());
            assertFalse(thirdWaiter.isDone());
            second.close();

            var third = thirdWaiter.get(2, TimeUnit.SECONDS);
            assertTrue(third.heldBy(thirdOwner));
            third.close();
            assertEquals(0, loans.activeCount());
            assertEquals(0, loans.pendingCount());
        }
    }

    @Test
    void nonReentrantAndCancelledPendingLoansCannotStealOwnership() throws Exception {
        Object owner = new Object();
        Object cancelledOwner = new Object();
        Object successorOwner = new Object();
        try (ActorStateLoans loans = new ActorStateLoans(2)) {
            var first = loans.acquireAsync(owner).get(2, TimeUnit.SECONDS);
            var reentrant = loans.acquireAsync(owner);
            assertThrows(ExecutionException.class,
                    () -> reentrant.get(2, TimeUnit.SECONDS));
            var cancelled = loans.acquireAsync(cancelledOwner);
            var next = loans.acquireAsync(successorOwner);
            assertTrue(cancelled.cancel(true));
            assertEquals(1, loans.pendingCount());
            first.close();
            var promoted = next.get(2, TimeUnit.SECONDS);
            assertTrue(promoted.heldBy(successorOwner));
            promoted.close();
            assertEquals(0, loans.pendingCount());
            assertEquals(0, loans.activeCount());
        }
    }

    @Test
    void closeRevokesAdmissionWithoutPromotingQueuedWaiters() throws Exception {
        ActorStateLoans loans = new ActorStateLoans(1);
        Object owner = new Object();
        var first = loans.acquireAsync(owner).get(2, TimeUnit.SECONDS);
        var waiter = loans.acquireAsync(new Object());
        loans.close();
        assertFalse(first.heldBy(owner));
        assertThrows(ExecutionException.class,
                () -> waiter.get(2, TimeUnit.SECONDS));
        first.close();
        assertEquals(0, loans.activeCount());
        assertEquals(0, loans.pendingCount());
        assertThrows(ExecutionException.class,
                () -> loans.acquireAsync(new Object()).get(2, TimeUnit.SECONDS));
    }
}
