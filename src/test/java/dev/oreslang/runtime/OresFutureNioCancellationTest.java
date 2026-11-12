package dev.oreslang.runtime;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class OresFutureNioCancellationTest {
    @Test void cancellingPendingNioCallbackPhysicallyRetiresOperationExactlyOnce() {
        AtomicInteger retirements = new AtomicInteger();
        AtomicReference<OresFuture.Callback<Integer>> callback = new AtomicReference<>();
        OresFuture<Integer> pending = OresFuture.fromCallback(
                callback::set, retirements::incrementAndGet);
        assertTrue(pending.cancel(false));
        assertTrue(pending.isCancelled());
        assertFalse(pending.cancel(false));
        assertEquals(1, retirements.get(), "kernel NIO operation retired exactly once");
        assertDoesNotThrow(() -> callback.get().resolve(41),
                "late NIO completion after cancellation must not revive an actor");
        assertTrue(pending.isCancelled());
        assertEquals(1, retirements.get());
    }

    @Test void successfulCompletionMustNotCloseLiveNioConnection() {
        AtomicInteger retirements = new AtomicInteger();
        OresFuture<Integer> done = OresFuture.fromCallback(
                cb -> cb.resolve(42), retirements::incrementAndGet);
        assertTrue(done.isDone());
        assertFalse(done.isCancelled());
        assertFalse(done.cancel(false));
        assertEquals(0, retirements.get(),
                "normal completion preserves a persistent pipelined socket");
    }

    @Test void producerFailureMustNotRunCancellationHook() {
        AtomicInteger retirements = new AtomicInteger();
        OresFuture<Integer> failed = OresFuture.fromCallback(
                cb -> cb.reject(new IllegalStateException("I/O timeout")),
                retirements::incrementAndGet);
        assertTrue(failed.isDone());
        assertFalse(failed.isCancelled());
        assertFalse(failed.cancel(false));
        assertEquals(0, retirements.get(),
                "ordinary producer failure is not consumer cancellation");
    }
    @Test void cancelledOwnedReadResultCannotEscapeAndMayBeZeroized() {
        AtomicInteger retirements = new AtomicInteger();
        AtomicReference<OresFuture.Callback<OresBytes>> callback = new AtomicReference<>();
        OresFuture<OresBytes> pending = OresFuture.fromCallback(
                callback::set, retirements::incrementAndGet);
        assertTrue(pending.cancel(false));
        byte[] backing = {(byte) 255, 2, 3};
        OresBytes rejected = OresBytes.fromDetached(backing);
        assertFalse(callback.get().tryResolve(rejected),
                "NIO consumer cancellation must win over a late resource callback");
        rejected.close();
        assertArrayEquals(new byte[] {0, 0, 0}, backing,
                "late, unpublished owned NIO payload must be erased");
        assertEquals(1, retirements.get());
    }

    @Test void acceptedOwnedReadCanBeRetiredByReceiver() {
        AtomicReference<OresFuture.Callback<OresBytes>> callback = new AtomicReference<>();
        OresFuture<OresBytes> pending = OresFuture.fromCallback(callback::set);
        byte[] backing = {9, 8, 7};
        OresBytes value = OresBytes.fromDetached(backing);
        assertTrue(callback.get().tryResolve(value));
        assertFalse(pending.cancel(false));
        assertEquals(3, value.size());
        value.close();
        assertArrayEquals(new byte[] {0, 0, 0}, backing);
    }

}
