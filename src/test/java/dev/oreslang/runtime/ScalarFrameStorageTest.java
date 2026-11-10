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
    void laterSourceTaskCannotAcquireACompletedExecutionsStack() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            AtomicReference<ScalarFrameStorage> previous = new AtomicReference<>();
            AtomicReference<ScalarFrameStorage.Frame> previousFrame = new AtomicReference<>();
            AtomicReference<ExecutionContext> previousExecution = new AtomicReference<>();
            AtomicReference<OresFuture<Integer>> first = new AtomicReference<>();
            AtomicReference<OresFuture<Integer>> second = new AtomicReference<>();
            var firstAdmitted = new java.util.concurrent.CountDownLatch(1);
            var secondAdmitted = new java.util.concurrent.CountDownLatch(1);
            var actor = runtime.<String>spawnSharedTrusted(factory -> (message, turn) -> {
                if (message.equals("first")) {
                    first.set(runtime.startActorTask(resume -> {
                        var storage = new ScalarFrameStorage();
                        var frame = storage.enter(1, 1);
                        frame.putLocal(0, ScalarFrameStorage.Scalar.ofLong(41));
                        previous.set(storage);
                        previousFrame.set(frame);
                        previousExecution.set(OresScheduler.currentExecutionContext());
                        // The scheduler retires the completed context's frames.
                        return OresScheduler.done(1);
                    }));
                    firstAdmitted.countDown();
                } else if (message.equals("second")) {
                    second.set(runtime.startActorTask(resume -> {
                        assertNotSame(previousExecution.get(), OresScheduler.currentExecutionContext());
                        assertThrows(SecurityException.class, () -> previousFrame.get().local(0));
                        assertThrows(SecurityException.class, () -> previous.get().enter(1, 1));
                        assertThrows(SecurityException.class, () -> previous.get().close());
                        assertFalse(ActorRuntime.currentActorFrameArena().isPoisoned());
                        try (var storage = new ScalarFrameStorage()) {
                            var own = storage.enter(1, 1);
                            own.putLocal(0, ScalarFrameStorage.Scalar.ofLong(42));
                            assertEquals(42, own.local(0).asLong());
                            storage.leave(own);
                        }
                        return OresScheduler.done(2);
                    }));
                    secondAdmitted.countDown();
                } else turn.self().stop();
            });
            actor.send("first");
            assertTrue(firstAdmitted.await(3, TimeUnit.SECONDS));
            assertEquals(1, first.get().get(3, TimeUnit.SECONDS));
            actor.send("second");
            assertTrue(secondAdmitted.await(3, TimeUnit.SECONDS));
            assertEquals(2, second.get().get(3, TimeUnit.SECONDS));
            actor.send("stop");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty(), () -> "owner failure: " + actor.failure());
        }
    }

    @Test
    void foreignCarrierCannotReadGrowOrCloseAnActorsExecutionStack() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            AtomicReference<Throwable> observed = new AtomicReference<>();
            var actor = runtime.<String>spawnSharedTrusted(factory -> (message, turn) -> {
                try (ScalarFrameStorage storage = new ScalarFrameStorage()) {
                    var frame = storage.enter(1, 1);
                    frame.putLocal(0, ScalarFrameStorage.Scalar.ofLong(42));
                    Thread outsider = new Thread(() -> {
                        try {
                            assertThrows(SecurityException.class, () -> frame.local(0));
                            assertThrows(SecurityException.class, () -> frame.putLocal(0,
                                    ScalarFrameStorage.Scalar.ofLong(99)));
                            assertThrows(SecurityException.class, () -> storage.enter(1, 1));
                            assertThrows(SecurityException.class, storage::close);
                        } catch (Throwable failure) { observed.set(failure); }
                    });
                    outsider.start();
                    outsider.join();
                    assertNull(observed.get(), () -> "foreign stack authority: " + observed.get());
                    assertEquals(42, frame.local(0).asLong());
                    assertFalse(ActorRuntime.currentActorFrameArena().isPoisoned());
                    storage.leave(frame);
                }
                turn.self().stop();
            });
            actor.send("execute");
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty(), () -> "owner failure: " + actor.failure());
            assertNull(observed.get());
        }
    }

    @Test
    void overflowingFrameLayoutCannotWrapPastTheSlotLimit() {
        try (ScalarFrameStorage storage = new ScalarFrameStorage()) {
            assertThrows(IllegalArgumentException.class,
                    () -> storage.enter(Integer.MAX_VALUE, Integer.MAX_VALUE));
            assertThrows(IllegalArgumentException.class,
                    () -> storage.enter(Integer.MAX_VALUE, 1));
            assertThrows(IllegalArgumentException.class, () -> storage.enter(256, 1));
            assertEquals(0, storage.liveFrames());
            var frame = storage.enter(255, 1);
            frame.putLocal(254, ScalarFrameStorage.Scalar.ofLong(42));
            assertEquals(42, frame.local(254).asLong());
            storage.leave(frame);
        }
    }

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
            var actor = runtime.<String>spawnSharedTrusted(factory -> (message, turn) -> {
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
            var failing = runtime.<String>spawnSharedTrusted(factory -> (message, turn) -> {
                compromised.set(ActorRuntime.currentActorFrameArena());
                try (ScalarFrameStorage storage = new ScalarFrameStorage()) {
                    var lease = storage.enter(0, 1);
                    storage.leave(lease);
                    // Malicious host adapter attempts to suppress use-after-
                    // free. Arena must still become terminal for this actor.
                    assertThrows(IllegalStateException.class, lease::pc);
                }
            });
            var sibling = runtime.<String>spawnSharedTrusted(factory -> (message, turn) -> {
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
            var failing = runtime.<String>spawnSharedTrusted(factory -> (message, turn) -> {
                exhausted.set(ActorRuntime.currentActorFrameArena());
                try (ScalarFrameStorage store = new ScalarFrameStorage()) {
                    for (int index = 0; index < 128; index++) {
                        var frame = store.enter(0, 1);
                        frame.push(ScalarFrameStorage.Scalar.ofLong(index));
                    }
                }
            });
            var sibling = runtime.<String>spawnSharedTrusted(factory -> (message, turn) -> {
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
