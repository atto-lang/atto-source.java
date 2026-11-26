package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Runtime frame tests; not compiler-lowered .atto await or native stack protection. */
final class ActorReceiveInvocationTest {
    private static ActorFrameArena arena(long domain, long generation, int maxFrames, int bytes) {
        ExecutionContext actor = ExecutionContext.actor(99, 1, domain, 17, generation, 0, 0);
        return new ActorFrameArena(actor.allocationDomain(), maxFrames, bytes);
    }

    @Test
    void concurrentInvocationsNeverShareFramesOrExecutionIdentity() {
        ActorFrameArena frames = arena(10, 1, 6, 256);
        ActorReceiveInvocation a = ActorReceiveInvocation.begin(frames);
        ActorReceiveInvocation b = ActorReceiveInvocation.begin(frames);
        assertNotEquals(a.executionContext().id(), b.executionContext().id());
        assertEquals(2 * ActorReceiveInvocation.ROOT_BYTES, frames.usedBytes());

        ExecutionContext aContext = a.executionContext();
        ExecutionContext bContext = b.executionContext();
        var child = frames.push(aContext, 16);
        frames.write(aContext, child, 0, new byte[]{4, 2});
        assertArrayEquals(new byte[]{4, 2}, frames.read(aContext, child, 0, 2));
        assertThrows(SecurityException.class, () -> frames.read(bContext, child, 0, 1));
        assertFalse(frames.isPoisoned(), "foreign frame denial must not poison owner");

        frames.pop(aContext, child);
        a.suspendAfterHandlerExit();
        assertEquals(2 * ActorReceiveInvocation.ROOT_BYTES, frames.usedBytes(),
                "suspension retains the receive's root budget");
        assertThrows(IllegalStateException.class, a::suspendAfterHandlerExit);
        b.finish();
        assertEquals(ActorReceiveInvocation.ROOT_BYTES, frames.usedBytes());
        a.completeSuspended();
        assertEquals(0, frames.usedBytes());
        assertEquals(0, frames.liveFrames());
        assertTrue(a.retired());
        assertTrue(b.retired());
        assertThrows(IllegalStateException.class, a::completeSuspended);
    }

    @Test
    void leakedCalleeFrameFailsStopAndCannotBecomeAnotherReceive() {
        ActorFrameArena frames = arena(11, 1, 4, 256);
        ActorReceiveInvocation invocation = ActorReceiveInvocation.begin(frames);
        frames.push(invocation.executionContext(), 16);
        assertThrows(IllegalStateException.class, invocation::finish,
                "leaked CALL frame must not be skipped or reclaimed as if successful");
        assertTrue(invocation.retired());
        assertTrue(frames.isPoisoned());
        assertEquals(0, frames.usedBytes());
        assertThrows(ActorFrameArena.BudgetExceeded.class,
                () -> ActorReceiveInvocation.begin(frames));
    }

    @Test
    void aggregateSuspendedStackBytesAreChargedBeforeNewInvocation() {
        ActorFrameArena frames = arena(12, 1, 3, 2 * ActorReceiveInvocation.ROOT_BYTES);
        ActorReceiveInvocation first = ActorReceiveInvocation.begin(frames);
        first.suspendAfterHandlerExit();
        ActorReceiveInvocation second = ActorReceiveInvocation.begin(frames);
        assertEquals(2 * ActorReceiveInvocation.ROOT_BYTES, frames.usedBytes());
        assertThrows(ActorFrameArena.BudgetExceeded.class,
                () -> ActorReceiveInvocation.begin(frames));
        assertTrue(frames.isPoisoned());
        first.cancelAfterFailure();
        second.cancelAfterFailure();
        assertEquals(0, frames.usedBytes());
        assertEquals(0, frames.liveFrames());
        ActorFrameArena sibling = arena(13, 1, 2, 128);
        ActorReceiveInvocation otherActor = ActorReceiveInvocation.begin(sibling);
        otherActor.finish();
        assertFalse(sibling.isPoisoned());
    }

    @Test
    void cancellationAndRestartInvalidateOldFrameDomain() {
        ActorFrameArena old = arena(15, 1, 4, 256);
        ActorReceiveInvocation invocation = ActorReceiveInvocation.begin(old);
        invocation.suspendAfterHandlerExit();
        invocation.cancelAfterFailure();
        invocation.cancelAfterFailure();
        assertEquals(0, old.usedBytes());
        old.retireQuiesced();
        assertThrows(IllegalStateException.class, invocation::completeSuspended);
        assertThrows(IllegalStateException.class, () -> ActorReceiveInvocation.begin(old));

        ActorFrameArena restarted = arena(15, 2, 4, 256);
        ActorReceiveInvocation fresh = ActorReceiveInvocation.begin(restarted);
        assertNotEquals(invocation, fresh);
        assertEquals(ActorReceiveInvocation.ROOT_BYTES, restarted.usedBytes());
        fresh.finish();
    }

    @Test
    void quotaFailureUsesPreReservedActorLocalErrors() throws Exception {
        ActorFrameArena frames = arena(16, 1, 2, ActorReceiveInvocation.ROOT_BYTES);
        var quotaField = ActorFrameArena.class.getDeclaredField("quotaExhausted");
        var poisonedField = ActorFrameArena.class.getDeclaredField("poisonedFailure");
        quotaField.setAccessible(true);
        poisonedField.setAccessible(true);
        Object reservedQuota = quotaField.get(frames);
        Object reservedPoisoned = poisonedField.get(frames);
        ActorReceiveInvocation first = ActorReceiveInvocation.begin(frames);
        assertSame(reservedQuota, assertThrows(ActorFrameArena.BudgetExceeded.class,
                () -> ActorReceiveInvocation.begin(frames)));
        assertSame(reservedPoisoned, assertThrows(ActorFrameArena.BudgetExceeded.class,
                () -> ActorReceiveInvocation.begin(frames)));
        assertTrue(frames.isPoisoned());
        first.cancelAfterFailure();
        assertEquals(0, frames.usedBytes());
        assertSame(reservedPoisoned, assertThrows(ActorFrameArena.BudgetExceeded.class,
                () -> ActorReceiveInvocation.begin(frames)));
        ActorFrameArena sibling = arena(17, 1, 2, ActorReceiveInvocation.ROOT_BYTES);
        assertNotSame(reservedQuota, quotaField.get(sibling));
        assertNotSame(reservedPoisoned, poisonedField.get(sibling));
        ActorReceiveInvocation.begin(sibling).finish();
        assertFalse(sibling.isPoisoned());
    }

    @Test
    void schedulerPreservesFirstBudgetFailureAfterUnwind() {
        ActorFrameArena frames = arena(18, 1, 2, ActorReceiveInvocation.ROOT_BYTES);
        ActorReceiveInvocation first = ActorReceiveInvocation.begin(frames);
        var original = assertThrows(ActorFrameArena.BudgetExceeded.class,
                () -> ActorReceiveInvocation.begin(frames));
        first.cancelAfterFailure();
        assertEquals(0, frames.usedBytes());
        assertSame(original, assertThrows(ActorFrameArena.BudgetExceeded.class,
                frames::checkTerminalBudgetFailure));
        assertSame(original, assertThrows(ActorFrameArena.BudgetExceeded.class,
                frames::checkTerminalBudgetFailure));
        assertEquals(0, original.getStackTrace().length);
        assertEquals(0, original.getSuppressed().length);
        ActorFrameArena sibling = arena(19, 1, 2, ActorReceiveInvocation.ROOT_BYTES);
        assertDoesNotThrow(sibling::checkTerminalBudgetFailure);
    }

    @Test
    void guardPoisonHasReservedSchedulerFailureEvenWithoutQuotaThrow() {
        ActorFrameArena frames = arena(20, 1, 2, 128);
        frames.poisonFromGuestGuard();
        var first = assertThrows(ActorFrameArena.BudgetExceeded.class,
                frames::checkTerminalBudgetFailure);
        assertEquals("actor-owned frame byte quota exhausted", first.getMessage());
        assertSame(first, assertThrows(ActorFrameArena.BudgetExceeded.class,
                frames::checkTerminalBudgetFailure));
    }

    @Test
    void oneHundredThousandIncarnationsDrainFramesAndKeepFailureIdentityLocal() {
        ActorFrameArena.BudgetExceeded previous = null;
        for (long generation = 1; generation <= 100_000; generation++) {
            ActorFrameArena frames = arena(21, generation, 2, 128);
            ActorReceiveInvocation first = ActorReceiveInvocation.begin(frames);
            first.suspendAfterHandlerExit();
            ActorReceiveInvocation second = ActorReceiveInvocation.begin(frames);
            var cause = assertThrows(ActorFrameArena.BudgetExceeded.class,
                    () -> ActorReceiveInvocation.begin(frames));
            assertNotSame(previous, cause);
            first.cancelAfterFailure();
            second.cancelAfterFailure();
            assertEquals(0, frames.usedBytes());
            assertEquals(0, frames.liveFrames());
            assertSame(cause, assertThrows(ActorFrameArena.BudgetExceeded.class,
                    frames::checkTerminalBudgetFailure));
            frames.retireQuiesced();
            assertTrue(frames.isRetired());
            previous = cause;
        }
    }
}
