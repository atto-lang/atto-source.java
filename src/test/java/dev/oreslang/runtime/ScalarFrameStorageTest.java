package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Actor-owned primitive frame lease tests. They are host/runtime tests,
 * NOT source-compiler-backed actor execution receipts.
 */
final class ScalarFrameStorageTest {
    @Test
    void typedLocalOperandAndPcSlotsFollowLifoAndRejectStaleHandles() {
        try (ScalarFrameStorage storage = new ScalarFrameStorage()) {
            var outer = storage.enter(2, 2);
            outer.putLocal(0, ScalarFrameStorage.Scalar.ofLong(-42));
            outer.putLocal(1, ScalarFrameStorage.Scalar.ofBoolean(true));
            outer.setPc(19);
            outer.push(ScalarFrameStorage.Scalar.ofLong(7));
            assertEquals(19, outer.pc());
            assertEquals(-42, outer.local(0).asLong());
            assertTrue(outer.local(1).asBoolean());
            assertEquals(7, outer.pop().asLong());
            assertEquals(0, outer.operandDepth());

            var nested = storage.enter(1, 1);
            nested.putLocal(0, ScalarFrameStorage.Scalar.ofLong(8));
            assertThrows(IllegalStateException.class, () -> outer.local(0),
                    "a parent frame must not be accessible while a child is active");
            assertEquals(8, nested.local(0).asLong());
            storage.leave(nested);
            assertThrows(IllegalStateException.class, () -> nested.local(0));
            assertEquals(-42, outer.local(0).asLong());
            storage.leave(outer);
            assertEquals(0, storage.liveFrames());
            assertEquals(2, storage.peakFrames());
            assertThrows(IllegalStateException.class, () -> storage.leave(outer));
        }
    }

    @Test
    void typedBooleanTagRejectsIntegerReadsAndInvalidRepresentations() {
        assertThrows(IllegalArgumentException.class,
                () -> new ScalarFrameStorage.Scalar(ScalarFrameStorage.Scalar.BOOL, 2));
        try (ScalarFrameStorage storage = new ScalarFrameStorage()) {
            var frame = storage.enter(1, 1);
            frame.putLocal(0, ScalarFrameStorage.Scalar.ofBoolean(false));
            assertFalse(frame.local(0).asBoolean());
            assertThrows(IllegalArgumentException.class, () -> frame.local(0).asLong());
            assertThrows(IllegalArgumentException.class,
                    () -> frame.push(ScalarFrameStorage.Scalar.from("object")));
        }
    }

    @Test
    void actorOwnedScalarFramesConsumeAndReleaseIncarnationByteQuota()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(), new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            AtomicReference<ActorFrameArena> observed = new AtomicReference<>();
            AtomicInteger executed = new AtomicInteger();
            var actor = runtime.<String>spawnShared(factory -> (message, turn) -> {
                ActorFrameArena arena = ActorRuntime.currentActorFrameArena();
                observed.set(arena);
                assertEquals(0, arena.usedBytes());
                try (ScalarFrameStorage storage = new ScalarFrameStorage()) {
                    assertTrue(storage.actorOwned());
                    var frame = storage.enter(1, 2);
                    frame.setPc(17);
                    frame.putLocal(0, ScalarFrameStorage.Scalar.ofLong(77));
                    frame.push(ScalarFrameStorage.Scalar.ofBoolean(true));
                    assertEquals(16 + 16 * 3, arena.usedBytes());
                    assertEquals(1, arena.liveFrames());
                    assertEquals(17, frame.pc());
                    assertTrue(frame.pop().asBoolean());
                    assertEquals(77, frame.local(0).asLong());
                    executed.incrementAndGet();
                }
                assertEquals(0, arena.usedBytes());
                assertEquals(0, arena.liveFrames());
                turn.self().stop();
            });
            actor.send("run");
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
            assertEquals(1, executed.get());
            assertNotNull(observed.get());
            assertEquals(0, observed.get().usedBytes());
        }
    }

    @Test
    void caughtStaleFrameCapabilityPoisonsOnlyOwningActor() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(), new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            AtomicReference<ActorFrameArena> compromised = new AtomicReference<>();
            AtomicReference<ActorFrameArena> other = new AtomicReference<>();
            AtomicInteger siblingMessages = new AtomicInteger();
            var failing = runtime.<String>spawnShared(factory -> (message, turn) -> {
                compromised.set(ActorRuntime.currentActorFrameArena());
                try (ScalarFrameStorage storage = new ScalarFrameStorage()) {
                    var lease = storage.enter(0, 1);
                    storage.leave(lease);
                    // Malicious host adapter attempts to suppress use-after-
                    // free. Arena must still become terminal for this actor.
                    assertThrows(IllegalStateException.class, lease::pc);
                }
            });
            var sibling = runtime.<String>spawnShared(factory -> (message, turn) -> {
                other.set(ActorRuntime.currentActorFrameArena());
                siblingMessages.incrementAndGet();
                turn.self().stop();
            });
            failing.send("use-after-free");
            assertTrue(failing.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(failing.failure().isPresent());
            assertNotNull(compromised.get());
            assertTrue(compromised.get().isPoisoned());
            assertEquals(0, compromised.get().usedBytes());
            sibling.send("still-alive");
            assertTrue(sibling.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(sibling.failure().isEmpty());
            assertEquals(1, siblingMessages.get());
            assertNotSame(compromised.get(), other.get());
        }
    }

    @Test
    void actorScalarQuotaPoisonDoesNotDamageSiblingCarrier()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(), new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            AtomicReference<ActorFrameArena> exhausted = new AtomicReference<>();
            AtomicReference<ActorFrameArena> healthy = new AtomicReference<>();
            AtomicInteger responses = new AtomicInteger();
            var failing = runtime.<String>spawnShared(factory -> (message, turn) -> {
                exhausted.set(ActorRuntime.currentActorFrameArena());
                try (ScalarFrameStorage store = new ScalarFrameStorage()) {
                    for (int index = 0; index < 128; index++) {
                        var frame = store.enter(0, 1);
                        frame.push(ScalarFrameStorage.Scalar.ofLong(index));
                    }
                }
            });
            var sibling = runtime.<String>spawnShared(factory -> (message, turn) -> {
                healthy.set(ActorRuntime.currentActorFrameArena());
                responses.incrementAndGet();
                turn.self().stop();
            });
            failing.send("quota");
            assertTrue(failing.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(failing.failure().isPresent(),
                    "typed frame admission over quota must be terminal for this actor");
            assertNotNull(exhausted.get());
            assertTrue(exhausted.get().isPoisoned());
            assertEquals(0, exhausted.get().usedBytes());
            sibling.send("alive");
            assertTrue(sibling.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(sibling.failure().isEmpty());
            assertEquals(1, responses.get());
            assertNotSame(exhausted.get(), healthy.get());
        }
    }
}
