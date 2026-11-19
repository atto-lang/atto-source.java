package dev.oreslang;

import dev.oreslang.runtime.ActorIsolationAccessV1;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ActorIsolationAccessV1Test {
    @Test
    void boundsForeignTenantAndStaleGrantFailClosed() {
        var a = new ActorIsolationAccessV1(1, 2, 3, 64);
        var g = a.issueAllocation(8, 16);
        assertEquals(8, a.checkedOffset(g, 2, 16));
        assertEquals(16, a.checkedOffset(g, 3, 8));
        assertThrows(IndexOutOfBoundsException.class, () -> a.checkedOffset(g, 1, 8));
        assertThrows(IndexOutOfBoundsException.class, () -> a.checkedOffset(g, 3, 9));
        assertThrows(IndexOutOfBoundsException.class, () -> a.checkedOffset(g, 2, Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> a.checkedOffset(g, 0, 8));
        assertThrows(IllegalArgumentException.class, () -> a.checkedOffset(g, 0x1_0000_0000L, 8));
        assertThrows(IllegalArgumentException.class, () -> a.issueAllocation(16, 8));
        assertThrows(IllegalArgumentException.class, () -> a.issueAllocation(1, 8));
        for (long[] identity : new long[][]{{1, 4, 3}, {5, 2, 3}, {1, 2, 4}}) {
            var foreign = new ActorIsolationAccessV1(identity[0], identity[1], identity[2], 64);
            assertThrows(SecurityException.class, () -> foreign.checkedOffset(g, 2, 8));
        }
        a.release(g);
        var replacement = a.issueAllocation(8, 16);
        assertThrows(SecurityException.class, () -> a.checkedOffset(g, 2, 8));
        assertEquals(8, a.checkedOffset(replacement, 2, 8));
        a.retire();
        assertThrows(IllegalStateException.class, () -> a.checkedOffset(replacement, 2, 8));
        assertThrows(IllegalStateException.class, () -> a.issueAllocation(0, 8));
    }

    @Test
    void lastWordCannotWrapAndOtherActorSurvives() {
        long span = ActorIsolationAccessV1.MAX_COMPRESSED32_SPAN;
        var max = new ActorIsolationAccessV1(1, 1, 1, span);
        var tail = max.issueAllocation(span - 8, 8);
        assertEquals(span - 8, max.checkedOffset(tail, 0xffff_ffffL, 8));
        assertThrows(IndexOutOfBoundsException.class,
                () -> max.checkedOffset(tail, 0xffff_ffffL, 9));
        assertThrows(IllegalArgumentException.class, () -> max.issueAllocation(span, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ActorIsolationAccessV1(1, 1, 1, span + 1));
        var sibling = new ActorIsolationAccessV1(1, 2, 1, 64);
        var siblingGrant = sibling.issueAllocation(0, 8);
        max.retire();
        assertEquals(0, sibling.checkedOffset(siblingGrant, 1, 8));
    }
}
