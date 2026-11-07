package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host/runtime tests for actor-owned byte-frame storage, not compiler-lowered
 * .atto execution, native stack switching, or supervisor restart proof.
 */
final class ActorFrameArenaTest {
    private static ExecutionContext actor(long domain, long task, long generation) {
        return ExecutionContext.actor(99L, task, domain, 17L, generation, 0, 0);
    }

    private static ActorFrameArena arena(ExecutionContext actor, int frames, int bytes) {
        return new ActorFrameArena(actor.allocationDomain(), frames, bytes);
    }

    @Test
    void constructorRejectsNonActorOwnerAndUnboundedQuotas() {
        ExecutionContext execution = actor(7, 1, 1);
        assertThrows(IllegalArgumentException.class, () ->
                new ActorFrameArena(ExecutionContext.AllocationDomain.none(), 2, 16));
        assertThrows(IllegalArgumentException.class, () -> arena(execution, 0, 16));
        assertThrows(IllegalArgumentException.class, () -> arena(execution, 4097, 16));
        assertThrows(IllegalArgumentException.class, () -> arena(execution, 2, 0));
        assertThrows(IllegalArgumentException.class, () -> arena(execution, 2, 1 << 25));
    }

    @Test
    void frameBytesArePrivateAndReturnedAsDefensiveCopies() {
        ExecutionContext a = actor(7, 1, 1);
        ExecutionContext b = actor(8, 1, 1);
        ActorFrameArena aa = arena(a, 3, 16);
        ActorFrameArena bb = arena(b, 3, 16);
        var aFrame = aa.push(a, 6);
        var bFrame = bb.push(b, 6);
        byte[] initial = {1, 2, 3};
        aa.write(a, aFrame, 1, initial);
        initial[0] = 99;
        byte[] read = aa.read(a, aFrame, 1, 3);
        assertArrayEquals(new byte[]{1, 2, 3}, read);
        read[0] = 88;
        assertArrayEquals(new byte[]{1, 2, 3}, aa.read(a, aFrame, 1, 3));
        assertArrayEquals(new byte[]{0, 0, 0}, bb.read(b, bFrame, 1, 3));
        assertFalse(Serializable.class.isAssignableFrom(aFrame.getClass()));
        aa.pop(a, aFrame);
        bb.pop(b, bFrame);
    }

    @Test
    void byteBudgetExhaustionPoisonsTheIncarnationAndUnwindReleasesCharges() {
        ExecutionContext a = actor(7, 1, 1);
        ActorFrameArena store = arena(a, 4, 10);
        var f1 = store.push(a, 6);
        var f2 = store.push(a, 4);
        assertEquals(10, store.peakBytes());
        assertThrows(ActorFrameArena.BudgetExceeded.class, () -> store.push(a, 1));
        assertTrue(store.isPoisoned());
        assertEquals(0, assertThrows(ActorFrameArena.BudgetExceeded.class,
                () -> store.push(a, 1)).getStackTrace().length);
        store.pop(a, f2);
        store.pop(a, f1);
        assertEquals(0, store.liveFrames());
        assertEquals(0, store.usedBytes());
        assertThrows(ActorFrameArena.BudgetExceeded.class, () -> store.push(a, 1));
    }

    @Test
    void frameCountLimitFailsBeforeAllocationAndCannotBeResetByPop() {
        ExecutionContext a = actor(7, 1, 1);
        ActorFrameArena store = arena(a, 1, 64);
        var first = store.push(a, 1);
        assertThrows(ActorFrameArena.BudgetExceeded.class, () -> store.push(a, 1));
        store.pop(a, first);
        assertTrue(store.isPoisoned());
        assertThrows(ActorFrameArena.BudgetExceeded.class, () -> store.push(a, 1));
    }

    @Test
    void oversizedOrOverflowingReservationDoesNotAllocate() {
        ExecutionContext a = actor(7, 1, 1);
        ActorFrameArena store = arena(a, 1, 8);
        assertThrows(IllegalArgumentException.class, () -> store.push(a, -1));
        assertThrows(ActorFrameArena.BudgetExceeded.class,
                () -> store.push(a, Integer.MAX_VALUE));
        assertEquals(0, store.liveFrames());
        assertEquals(0, store.usedBytes());
    }

    @Test
    void staleGenerationAndForeignActorCannotTouchOrPoisonOwner() {
        ExecutionContext a = actor(7, 1, 1);
        ExecutionContext newer = actor(7, 1, 2);
        ExecutionContext other = actor(8, 1, 1);
        ActorFrameArena store = arena(a, 2, 16);
        var frame = store.push(a, 8);
        assertThrows(SecurityException.class, () -> store.read(newer, frame, 0, 1));
        assertThrows(SecurityException.class, () -> store.pop(other, frame));
        assertFalse(store.isPoisoned(), "foreign input must not poison another actor");
        store.pop(a, frame);
    }

    @Test
    void foreignGenerationCannotProbeWhetherAnotherActorHasPoisonedItsStack() {
        ExecutionContext a = actor(7, 1, 1);
        ExecutionContext attacker = actor(7, 1, 2);
        ActorFrameArena store = arena(a, 1, 8);
        var frame = store.push(a, 8);
        assertThrows(ActorFrameArena.BudgetExceeded.class, () -> store.push(a, 1));
        assertThrows(SecurityException.class, () -> store.read(attacker, frame, 0, 1),
                "auth must precede the poisoned-state diagnostic");
        assertThrows(ActorFrameArena.BudgetExceeded.class, () -> store.read(a, frame, 0, 1));
        store.cancel(a);
    }

    @Test
    void cancellationAndQuiescentRetirementZeroizeBackingFrameBytes() throws Exception {
        ExecutionContext a = actor(7, 1, 1);
        ActorFrameArena store = arena(a, 2, 16);
        var first = store.push(a, 8);
        store.write(a, first, 0, new byte[]{7, 8, 9});
        byte[] canceledBacking = privateTopFrameBytes(store, a);
        store.cancel(a);
        assertArrayEquals(new byte[8], canceledBacking,
                "cancellation must scrub the original backing allocation");

        var second = store.push(a, 8);
        store.write(a, second, 0, new byte[]{1, 2, 3});
        byte[] retiredBacking = privateTopFrameBytes(store, a);
        store.retireQuiesced();
        assertArrayEquals(new byte[8], retiredBacking,
                "retirement must zero even a still-live frame before reclamation");
        assertThrows(IllegalStateException.class, () -> store.pop(a, second));
    }

    /** Trusted host-only reflection: no frame backing arrays become guest values. */
    private static byte[] privateTopFrameBytes(
            ActorFrameArena arena, ExecutionContext execution) throws Exception {
        var stacksField = ActorFrameArena.class.getDeclaredField("stacks");
        stacksField.setAccessible(true);
        Map<?, ?> stacks = (Map<?, ?>) stacksField.get(arena);
        ArrayDeque<?> stack = (ArrayDeque<?>) stacks.get(execution.id());
        assertNotNull(stack);
        Object frame = stack.peekLast();
        assertNotNull(frame);
        var bytesField = frame.getClass().getDeclaredField("bytes");
        bytesField.setAccessible(true);
        return (byte[]) bytesField.get(frame);
    }

    @Test
    void wrongTaskAndForeignFrameCapabilityAreDenied() {
        ExecutionContext a = actor(7, 1, 1);
        ExecutionContext otherTask = actor(7, 2, 1);
        ActorFrameArena store = arena(a, 2, 16);
        var frame = store.push(a, 8);
        assertThrows(SecurityException.class, () -> store.read(otherTask, frame, 0, 1));
        assertFalse(store.isPoisoned());
        store.pop(a, frame);
        ActorFrameArena another = arena(a, 2, 16);
        var token = another.push(a, 3);
        assertThrows(SecurityException.class, () -> store.read(a, token, 0, 1));
        another.pop(a, token);
    }

    @Test
    void nonLifoPopPoisonsStackAndCancellationDrainsSafely() {
        ExecutionContext a = actor(7, 1, 1);
        ActorFrameArena store = arena(a, 3, 32);
        var parent = store.push(a, 4);
        store.push(a, 5);
        assertThrows(IllegalStateException.class, () -> store.pop(a, parent));
        assertTrue(store.isPoisoned());
        store.cancel(a);
        store.cancel(a);
        assertEquals(0, store.usedBytes());
        assertEquals(0, store.liveFrames());
    }

    @Test
    void suspensionRetainsBytesAcrossCarrierMigration() throws Exception {
        ExecutionContext a = actor(7, 1, 1);
        ActorFrameArena store = arena(a, 3, 64);
        var parent = store.push(a, 10);
        var child = store.push(a, 8);
        store.write(a, child, 0, new byte[]{4, 2});
        store.suspend(a, child);
        assertEquals(18, store.usedBytes());
        var result = new AtomicReference<byte[]>();
        var error = new AtomicReference<Throwable>();
        Thread resumed = new Thread(() -> {
            try {
                store.resume(a, child);
                result.set(store.read(a, child, 0, 2));
                store.pop(a, child);
                store.pop(a, parent);
            } catch (Throwable problem) { error.set(problem); }
        });
        resumed.start();
        resumed.join(TimeUnit.SECONDS.toMillis(3));
        assertFalse(resumed.isAlive(), "bounded worker must finish");
        assertNull(error.get());
        assertArrayEquals(new byte[]{4, 2}, result.get());
        assertEquals(0, store.usedBytes());
        assertEquals(18, store.peakBytes());
    }

    @Test
    void prematureSuspendedAccessFailsClosedButCanStillCancel() {
        ExecutionContext a = actor(7, 1, 1);
        ActorFrameArena store = arena(a, 3, 32);
        var ref = store.push(a, 4);
        store.suspend(a, ref);
        assertThrows(IllegalStateException.class, () -> store.read(a, ref, 0, 1));
        assertTrue(store.isPoisoned());
        assertThrows(ActorFrameArena.BudgetExceeded.class, () -> store.resume(a, ref));
        store.cancel(a);
        assertEquals(0, store.liveFrames());
    }

    @Test
    void duplicateResumeFailsClosedAndStaleTokenCannotResume() {
        ExecutionContext a = actor(7, 1, 1);
        ActorFrameArena store = arena(a, 3, 32);
        var ref = store.push(a, 4);
        store.suspend(a, ref);
        store.resume(a, ref);
        assertThrows(IllegalStateException.class, () -> store.resume(a, ref));
        assertTrue(store.isPoisoned());
        store.cancel(a);
        assertThrows(IllegalStateException.class, () -> store.resume(a, ref));
    }

    @Test
    void retirementInvalidatesOldFramesAndRestartGetsFreshStorage() {
        ExecutionContext a = actor(7, 1, 1);
        ExecutionContext newGeneration = actor(7, 1, 2);
        ActorFrameArena old = arena(a, 3, 32);
        var ref = old.push(a, 5);
        old.suspend(a, ref);
        old.retireQuiesced();
        old.retireQuiesced();
        assertEquals(0, old.usedBytes());
        assertEquals(0, old.liveFrames());
        assertTrue(old.isRetired());
        assertThrows(IllegalStateException.class, () -> old.resume(a, ref));
        ActorFrameArena next = arena(newGeneration, 3, 32);
        var fresh = next.push(newGeneration, 5);
        assertThrows(SecurityException.class, () -> next.read(a, fresh, 0, 1));
        next.pop(newGeneration, fresh);
        assertFalse(next.isPoisoned());
    }

    @Test
    void exhaustionIsNonTrappableEvenThroughCauseWrappers() {
        RuntimeException exhausted = new ActorFrameArena.BudgetExceeded("test");
        assertFalse(TrapFailurePolicy.isTrappable(exhausted, IllegalArgumentException.class));
        assertFalse(TrapFailurePolicy.isGuestCatchable(exhausted, IllegalArgumentException.class));
        assertFalse(TrapFailurePolicy.isGuestCatchable(
                new IllegalStateException("wrapped", exhausted),
                IllegalArgumentException.class));
    }

    @Test
    void swallowedArenaExhaustionStillFailsOnlyItsActorAndRetiresFrames() throws Exception {
        ActorRuntime.DispatcherConfig config = new ActorRuntime.DispatcherConfig(1, 1, 8);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            AtomicReference<ActorFrameArena> failed = new AtomicReference<>();
            AtomicReference<ActorFrameArena> survivor = new AtomicReference<>();
            AtomicInteger liveResponses = new AtomicInteger();
            var a = runtime.<String>spawnShared(factory -> (message, turn) -> {
                ActorFrameArena frames = ActorRuntime.currentActorFrameArena();
                failed.set(frames);
                var domain = frames.allocationDomain();
                var ctx = ExecutionContext.actor(domain.domain(), 1, domain.domain(),
                        domain.owner(), domain.generation(), 0, 0);
                frames.push(ctx, ActorFrameArena.DEFAULT_MAX_BYTES);
                assertThrows(ActorFrameArena.BudgetExceeded.class, () ->
                        frames.push(ctx, 1));
                // An attempted host catch must not let another user turn run.
            });
            var b = runtime.<String>spawnShared(factory -> (message, turn) -> {
                ActorFrameArena frames = ActorRuntime.currentActorFrameArena();
                survivor.set(frames);
                assertNotSame(failed.get(), frames);
                liveResponses.incrementAndGet();
                turn.self().stop();
            });
            a.send("overflow");
            assertTrue(a.awaitTermination(3, TimeUnit.SECONDS));
            assertInstanceOf(ActorFrameArena.BudgetExceeded.class, a.failure().orElseThrow());
            assertTrue(failed.get().isRetired(), "finalization reclaims actor-owned frames");
            assertEquals(0, failed.get().usedBytes());
            b.send("alive");
            assertTrue(b.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(b.failure().isEmpty());
            assertEquals(1, liveResponses.get());
            assertTrue(survivor.get().isRetired(), "sibling's successful STOP reclaims frames");
            assertEquals(0, survivor.get().usedBytes());
        }
    }
}
