package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ActorCompletionFrontierTest {
    @Test
    void inOrderCompletionAdvancesWithoutGaps() {
        var frontier = new ActorCompletionFrontier(4);
        assertEquals(0, frontier.complete(0));
        assertEquals(1, frontier.complete(1));
        assertEquals(2, frontier.complete(2));
        assertEquals(3, frontier.completedCount());
        assertEquals(3, frontier.contiguousCount());
        assertEquals(0, frontier.outOfOrderCount());
    }

    @Test
    void outOfOrderCompletionDoesNotReleaseDrainFrontierEarly() {
        var frontier = new ActorCompletionFrontier(4);
        assertEquals(-1, frontier.complete(2));
        assertEquals(-1, frontier.complete(1));
        assertEquals(2, frontier.completedCount());
        assertEquals(0, frontier.contiguousCount());
        assertEquals(2, frontier.outOfOrderCount());

        assertEquals(2, frontier.complete(0));
        assertEquals(3, frontier.completedCount());
        assertEquals(3, frontier.contiguousCount());
        assertEquals(0, frontier.outOfOrderCount());
    }

    @Test
    void fillsMultipleHolesAndAdvancesOnlyContiguousPrefix() {
        var frontier = new ActorCompletionFrontier(8);
        frontier.complete(1);
        frontier.complete(3);
        frontier.complete(4);
        assertEquals(-1, frontier.contiguousSequence());
        frontier.complete(0);
        assertEquals(1, frontier.contiguousSequence());
        assertEquals(2, frontier.outOfOrderCount());
        frontier.complete(2);
        assertEquals(4, frontier.contiguousSequence());
        assertEquals(0, frontier.outOfOrderCount());
        assertEquals(5, frontier.completedCount());
    }

    @Test
    void duplicateStaleAndExcessiveGapsFailClosed() {
        var frontier = new ActorCompletionFrontier(2);
        frontier.complete(2);
        frontier.complete(3);
        assertThrows(IllegalStateException.class, () -> frontier.complete(4));
        assertThrows(IllegalStateException.class, () -> frontier.complete(2));

        var contiguous = new ActorCompletionFrontier(2);
        contiguous.complete(0);
        assertThrows(IllegalStateException.class, () -> contiguous.complete(0));
        assertThrows(IllegalArgumentException.class, () -> contiguous.complete(-1));
    }
}
