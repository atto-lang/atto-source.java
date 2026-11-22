package dev.oreslang.runtime;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/** Mutation probes are privileged host test code, never guest-visible APIs. */
@Timeout(25)
final class ActorScalarFrameCodecTest {
    private static byte[] bytes(ActorScalarFrameStore.Frame frame) {
        try {
            Field field = frame.getClass().getDeclaredField("bytes");
            field.setAccessible(true);
            return (byte[]) field.get(frame);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static void onActor(Runnable test) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (var runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            var actor = runtime.<String>spawnShared(() -> (msg, turn) -> {
                try { test.run(); } catch (Throwable error) { failure.set(error); }
                finally { done.countDown(); turn.self().stop(); }
            });
            actor.send("test");
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
        if (failure.get() != null) fail("scalar frame codec", failure.get());
    }

    @Test
    void rejectUninitializedOrCorruptedPrimitiveSlots() throws Exception {
        onActor(() -> {
            try (var store = new ActorScalarFrameStore()) {
                var frame = store.enter(1, 0);
                assertThrows(IllegalStateException.class, () -> frame.local(0));
                frame.putLocal(0, ActorScalarFrameStore.Scalar.ofBoolean(true));
                byte[] raw = bytes(frame);
                raw[17] = 1;
                assertThrows(IllegalStateException.class, () -> frame.local(0));
                raw[17] = 0;
                raw[24] = 2; // malformed canonical bool payload (2 instead of 1)
                assertThrows(IllegalStateException.class, () -> frame.local(0));
                raw[24] = 0;
                raw[16] = 9;
                assertThrows(IllegalStateException.class, () -> frame.local(0));
            }
        });
    }

    @Test
    void leavingAndClosingWipesAllFrameBytes() throws Exception {
        onActor(() -> {
            var store = new ActorScalarFrameStore();
            var first = store.enter(1, 1);
            first.putLocal(0, ActorScalarFrameStore.Scalar.ofLong(-1));
            first.push(ActorScalarFrameStore.Scalar.ofLong(42));
            var second = store.enter(1, 0);
            second.putLocal(0, ActorScalarFrameStore.Scalar.ofBoolean(true));
            byte[] firstBytes = bytes(first);
            byte[] secondBytes = bytes(second);
            assertFalse(Arrays.equals(firstBytes, new byte[firstBytes.length]));
            store.leave(second);
            assertArrayEquals(new byte[secondBytes.length], secondBytes);
            store.close();
            assertArrayEquals(new byte[firstBytes.length], firstBytes);
            assertThrows(IllegalStateException.class, () -> store.enter(0, 0));
        });
    }

    @Test
    void malformedBudgetAndScalarTagsAreRejectedBeforeStorage() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> new ActorScalarFrameStore.Scalar((byte) 42, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ActorScalarFrameStore.Scalar(ActorScalarFrameStore.Scalar.BOOL, 2));
        onActor(() -> {
            assertThrows(IllegalArgumentException.class, () -> new ActorScalarFrameStore(0, 100));
            assertThrows(IllegalArgumentException.class, () -> new ActorScalarFrameStore(1, 15));
            try (var store = new ActorScalarFrameStore(1, 4112)) {
                var frame = store.enter(256, 0);
                assertEquals(4112, store.liveBytes());
                assertThrows(IllegalArgumentException.class, () -> store.enter(256, 1));
                assertThrows(IllegalStateException.class, () -> store.enter(0, 0));
                store.leave(frame);
            }
        });
    }
}
