package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

final class DurableLockQueuesTest {
    private static DurableLockQueues queue(long generation) {
        return new DurableLockQueues(generation, new DurableLockQueues.Limits(2, 4, 3, 16));
    }
    @Test void tenantFifoActorReentrancyAndCancellation() {
        var a = queue(1);
        var b = queue(2);
        var first = a.enqueue(5, 1, 7, 1, 4);
        var second = a.enqueue(5, 1, 7, 2, 4);
        var other = a.enqueue(5, 1, 9, 3, 4);
        var foreign = b.enqueue(5, 1, 7, 1, 4);
        assertTrue(first.head());
        assertFalse(second.head());
        assertTrue(other.head());
        assertThrows(IllegalStateException.class, () -> a.claim(foreign.ticket()));
        assertThrows(IllegalStateException.class, () -> a.claim(second.ticket()));
        assertThrows(IllegalStateException.class, () -> a.finish(first.ticket()));
        assertTrue(a.claim(first.ticket()));
        assertFalse(a.claim(other.ticket()));
        assertThrows(IllegalStateException.class, () -> a.cancelHead(first.ticket()));
        assertSame(second.ticket(), a.finish(first.ticket()));
        assertTrue(a.claim(other.ticket()));
        assertFalse(a.claim(second.ticket()));
        assertNull(a.finish(other.ticket()));
        assertTrue(a.claim(second.ticket()));
        assertNull(a.finish(second.ticket()));
        assertThrows(IllegalStateException.class, () -> a.claim(first.ticket()));
        var unstarted = a.enqueue(5, 1, 7, 1, 2);
        var waiting = a.enqueue(5, 1, 7, 2, 2);
        a.cancelWaiter(waiting.ticket());
        assertNull(a.cancelHead(unstarted.ticket()));
        assertEquals(0, a.snapshot().entries());
    }
    @Test void admissionLimitAndQuiescenceAreFailClosed() {
        var q = new DurableLockQueues(3, new DurableLockQueues.Limits(1, 2, 2, 6));
        var first = q.enqueue(1,1,4,1,4);
        assertThrows(IllegalStateException.class, () -> q.enqueue(2,1,5,2,1));
        assertThrows(IllegalStateException.class, () -> q.enqueue(2,1,4,2,4));
        assertThrows(IllegalStateException.class, () -> q.enqueue(1,1,4,1,1));
        assertEquals(1, q.snapshot().entries());
        var next = q.enqueue(2,1,4,2,1);
        assertSame(next.ticket(), q.cancelHead(first.ticket()));
        q.quiesce();
        assertThrows(IllegalStateException.class, () -> q.claim(next.ticket()));
        assertThrows(IllegalStateException.class, () -> q.enqueue(3,1,4,3,1));
        assertNull(q.cancelHead(next.ticket()));
        assertTrue(q.drained());
    }
    @Test void distinctActorsCanClaimDifferentLocks() {
        var q = queue(9);
        var a = q.enqueue(1,1,4,1,1);
        var b = q.enqueue(2,1,5,2,1);
        assertTrue(q.claim(a.ticket()));
        assertTrue(q.claim(b.ticket()));
        assertEquals(2, q.snapshot().runningActors());
        q.finish(a.ticket()); q.finish(b.ticket());
    }
}
