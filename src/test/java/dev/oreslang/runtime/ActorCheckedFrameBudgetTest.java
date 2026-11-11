package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** HOST-only bounded stack accounting; NOT guest recursion or JVM SOE proof. */
final class ActorCheckedFrameBudgetTest {
    @Test
    void terminalFrameCountCannotBeRecoveredByCatchingHostException() {
        var failed = new ActorCheckedFrameBudget(64, 2);
        var outer = failed.enter_frame(24);
        var inner = failed.enter_frame(32);
        assertEquals(56, failed.used_bytes());
        assertEquals(2, failed.active_frames());
        assertThrows(IllegalStateException.class, () -> failed.leave_frame(outer));
        var error = assertThrows(ActorCheckedFrameBudget.Overflow.class,
                () -> failed.enter_frame(1));
        assertEquals(ActorCheckedFrameBudget.Limit.FRAME_COUNT, error.limit());
        assertEquals(ActorCheckedFrameBudget.Limit.FRAME_COUNT,
                failed.terminal_overflow().orElseThrow());
        failed.leave_frame(inner);
        failed.leave_frame(outer);
        assertEquals(0, failed.used_bytes());
        assertEquals(0, failed.active_frames());
        assertEquals(ActorCheckedFrameBudget.Limit.FRAME_COUNT,
                assertThrows(ActorCheckedFrameBudget.Overflow.class,
                        () -> failed.enter_frame(1)).limit(),
                "catch and LIFO unwind must not resume a terminal actor");
    }

    @Test
    void byteQuotaIsIndependentFromDepthAndHealthyActorContinues() {
        var failed = new ActorCheckedFrameBudget(48, 4);
        var outer = failed.enter_frame(32);
        var error = assertThrows(ActorCheckedFrameBudget.Overflow.class,
                () -> failed.enter_frame(17));
        assertEquals(ActorCheckedFrameBudget.Limit.BYTE_CAPACITY, error.limit());
        failed.leave_frame(outer);
        assertEquals(0, failed.used_bytes());
        assertEquals(ActorCheckedFrameBudget.Limit.BYTE_CAPACITY,
                failed.terminal_overflow().orElseThrow());

        var healthy = new ActorCheckedFrameBudget(48, 2);
        var own = healthy.enter_frame(16);
        assertThrows(IllegalStateException.class, () -> failed.leave_frame(own));
        healthy.leave_frame(own);
        assertTrue(healthy.terminal_overflow().isEmpty());
        assertEquals(0, healthy.used_bytes());
    }

    @Test
    void replayedStaleOrOutOfOrderFramesCannotMutateLiveBudget() {
        var b = new ActorCheckedFrameBudget(128, 3);
        var outer = b.enter_frame(20);
        var inner = b.enter_frame(30);
        assertThrows(IllegalStateException.class, () -> b.leave_frame(outer));
        assertEquals(50, b.used_bytes());
        b.leave_frame(inner);
        b.leave_frame(outer);
        assertThrows(IllegalStateException.class, () -> b.leave_frame(outer));
        var replacement = b.enter_frame(20);
        assertThrows(IllegalStateException.class, () -> b.leave_frame(outer),
                "new frame at same depth and size requires a different issuer serial");
        b.leave_frame(replacement);
        assertEquals(0, b.active_frames());
        assertTrue(b.terminal_overflow().isEmpty());
    }

    @Test
    void invalidBudgetsAndFrameSizesFailBeforeMutation() {
        assertThrows(IllegalArgumentException.class, () -> new ActorCheckedFrameBudget(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ActorCheckedFrameBudget(64, 0));
        assertThrows(IllegalArgumentException.class, () -> new ActorCheckedFrameBudget(64, 1025));
        var b = new ActorCheckedFrameBudget(64, 1);
        assertThrows(IllegalArgumentException.class, () -> b.enter_frame(0));
        assertEquals(0, b.active_frames());
        assertTrue(b.terminal_overflow().isEmpty(),
                "invalid caller input is not a guest stack-exhaustion panic");
    }
}
