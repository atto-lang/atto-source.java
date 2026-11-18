package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/** Genuine ActorRuntime turn tests; separate from the isolated byte-codec smoke harness. */
@Timeout(25)
final class ActorScalarFrameStoreTest {
    private static void onTrustedActor(Runnable exercise) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        try (var runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            var actor = runtime.<String>spawnShared(() -> (message, turn) -> {
                try { exercise.run(); }
                catch (Throwable error) { failure.set(error); }
                finally { completed.countDown(); turn.self().stop(); }
            });
            actor.send("verify");
            assertTrue(completed.await(5, TimeUnit.SECONDS), "trusted actor never ran");
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS), "actor never retired");
        }
        if (failure.get() != null) fail("actor frame regression", failure.get());
    }

    @Test
    void cannotConstructScalarStoreOutsideActorTurn() {
        assertThrows(SecurityException.class, ActorScalarFrameStore::new);
    }

    @Test
    void liveTrustedActorCanUseTaggedLocalsAndStackOperands() throws Exception {
        onTrustedActor(() -> {
            try (var store = new ActorScalarFrameStore()) {
                var frame = store.enter(2, 2);
                assertEquals(80, store.liveBytes());
                frame.setPc(37);
                assertEquals(37, frame.pc());
                frame.putLocal(0, ActorScalarFrameStore.Scalar.ofLong(Long.MIN_VALUE));
                frame.putLocal(1, ActorScalarFrameStore.Scalar.ofBoolean(true));
                assertEquals(Long.MIN_VALUE, frame.local(0).asLong());
                assertTrue(frame.local(1).asBoolean());
                frame.push(ActorScalarFrameStore.Scalar.ofLong(Long.MAX_VALUE));
                frame.push(ActorScalarFrameStore.Scalar.ofBoolean(false));
                assertEquals(2, frame.operandDepth());
                assertFalse(frame.pop().asBoolean());
                assertEquals(Long.MAX_VALUE, frame.pop().asLong());
                assertEquals(0, frame.operandDepth());
                store.leave(frame);
                assertEquals(0, store.liveBytes());
                assertThrows(IllegalStateException.class, frame::pc);
            }
        });
    }

    @Test
    void outOfOrderExitAndOverBudgetAllocationDoNotCorruptLiveFrames() throws Exception {
        onTrustedActor(() -> {
            try (var store = new ActorScalarFrameStore(2, 64)) {
                var first = store.enter(1, 1);
                first.putLocal(0, ActorScalarFrameStore.Scalar.ofLong(42));
                var inner = store.enter(0, 0);
                assertThrows(IllegalStateException.class, () -> store.leave(first));
                assertThrows(IllegalStateException.class, () -> store.enter(0, 0));
                assertThrows(IllegalArgumentException.class,
                        () -> store.enter(Integer.MAX_VALUE, Integer.MAX_VALUE));
                store.leave(inner);
                assertEquals(42, first.local(0).asLong());
                store.leave(first);
                assertEquals(0, store.liveFrames());
                assertEquals(0, store.liveBytes());
            }
        });
    }

    @Test
    void foreignCarrierCannotAccessOrCloseAnActorFrame() throws Exception {
        onTrustedActor(() -> {
            try (var store = new ActorScalarFrameStore()) {
                var frame = store.enter(1, 1);
                frame.putLocal(0, ActorScalarFrameStore.Scalar.ofLong(42));
                AtomicReference<Throwable> failure = new AtomicReference<>();
                Thread outsider = new Thread(() -> {
                    try {
                        assertThrows(SecurityException.class, () -> frame.local(0));
                        assertThrows(SecurityException.class, () -> frame.putLocal(0,
                                ActorScalarFrameStore.Scalar.ofLong(99)));
                        assertThrows(SecurityException.class, () -> store.enter(1, 1));
                        assertThrows(SecurityException.class, store::close);
                    } catch (Throwable error) { failure.set(error); }
                });
                outsider.start();
                try { outsider.join(2000); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
                assertFalse(outsider.isAlive(), "foreign carrier did not stop");
                assertNull(failure.get(), () -> "cross-carrier proof failed: " + failure.get());
                assertEquals(42, frame.local(0).asLong());
            }
        });
    }

    @Test
    void staleFrameFromPreviousActorTurnIsRejected() throws Exception {
        AtomicReference<ActorScalarFrameStore.Frame> previous = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch first = new CountDownLatch(1);
        CountDownLatch second = new CountDownLatch(1);
        try (var runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            var actor = runtime.<String>spawnShared(() -> (message, turn) -> {
                try {
                    if (message.equals("first")) {
                        var store = new ActorScalarFrameStore();
                        previous.set(store.enter(1, 0));
                        previous.get().putLocal(0, ActorScalarFrameStore.Scalar.ofLong(41));
                        store.close();
                    } else {
                        assertThrows(SecurityException.class, () -> previous.get().local(0));
                        try (var store = new ActorScalarFrameStore()) {
                            var current = store.enter(1, 0);
                            current.putLocal(0, ActorScalarFrameStore.Scalar.ofLong(42));
                            assertEquals(42, current.local(0).asLong());
                        }
                        turn.self().stop();
                    }
                } catch (Throwable error) { failure.set(error); turn.self().stop(); }
                finally { (message.equals("first") ? first : second).countDown(); }
            });
            actor.send("first");
            assertTrue(first.await(5, TimeUnit.SECONDS));
            actor.send("second");
            assertTrue(second.await(5, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
        if (failure.get() != null) fail("cross-turn frame replay", failure.get());
    }
}
