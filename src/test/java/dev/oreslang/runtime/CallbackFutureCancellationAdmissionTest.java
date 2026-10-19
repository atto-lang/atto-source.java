package dev.oreslang.runtime;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CallbackFutureCancellationAdmissionTest {
    @Test
    void pendingConsumerCancellationRevokesSourceExactlyOnce() {
        AtomicInteger revoked = new AtomicInteger();
        AtomicReference<OresFuture.Callback<Integer>> callback = new AtomicReference<>();
        OresFuture<Integer> pending = OresFuture.fromCallback(callback::set,
                () -> true, revoked::incrementAndGet);

        assertTrue(pending.cancel(false));
        assertTrue(pending.isCancelled());
        assertEquals(1, revoked.get());
        assertFalse(pending.cancel(false));
        callback.get().resolve(99); // first late producer signal is ignored
        assertThrows(OresFuture.AlreadySettledException.class,
                () -> callback.get().resolve(100));
        assertEquals(1, revoked.get());
    }

    @Test
    void committedOperationRejectsConsumerCancelUntilProducerCleanup() {
        AtomicBoolean committed = new AtomicBoolean();
        AtomicInteger cancelled = new AtomicInteger();
        AtomicReference<OresFuture.Callback<Void>> callback = new AtomicReference<>();
        OresFuture<Void> pending = OresFuture.fromCallback(callback::set,
                () -> !committed.get(), cancelled::incrementAndGet);
        committed.set(true); // ownership/selection committed
        assertFalse(pending.cancel(false));
        assertFalse(pending.isDone());
        callback.get().resolve(null); // producer finished lexical cleanup
        assertTrue(pending.isDone());
        assertFalse(pending.isCancelled());
        assertEquals(0, cancelled.get());
    }

    @Test
    void producerCanPublishCancellationAfterRetirementDespiteAdmissionDenial() {
        AtomicBoolean committed = new AtomicBoolean(true);
        AtomicInteger hooks = new AtomicInteger();
        AtomicReference<OresFuture.Callback<Void>> callback = new AtomicReference<>();
        OresFuture<Void> pending = OresFuture.fromCallback(callback::set,
                () -> !committed.get(), hooks::incrementAndGet);
        assertFalse(pending.cancel(false));
        callback.get().cancel(); // only after actor-source cleanup has retired
        assertTrue(pending.isCancelled());
        assertEquals(1, hooks.get());
        assertThrows(CancellationException.class, pending::join);
    }
}
