package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host integration tests for the evaluator's invocation-frame bridge.
 *
 * <p>These are NOT compiler-backed .atto actor execution, supervisor restart
 * or real JVM/native stack-fault-containment proof. See
 * ActorGuestInvocationLanguageTest for authored compiler-backed .atto checks.
 */
final class GuestInvocationFrameTest {
    @Test
    void actorInvocationStoresDescriptorAndRewritesTailInTheSameFrame() throws Exception {
        ActorRuntime.DispatcherConfig config = new ActorRuntime.DispatcherConfig(1, 1, 8);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            AtomicReference<ActorFrameArena> observed = new AtomicReference<>();
            var actor = runtime.<String>spawnShared(factory -> (message, turn) -> {
                ActorFrameArena store = ActorRuntime.currentActorFrameArena();
                observed.set(store);
                assertEquals(ExecutionContext.AllocationKind.ACTOR,
                        store.allocationDomain().kind());
                try (GuestInvocationFrame outer = GuestInvocationFrame.enter(0, 2)) {
                    assertEquals(GuestCallDepthGuard.MANAGED_FRAME_CHARGE_BYTES, store.usedBytes());
                    ByteBuffer descriptor = ByteBuffer.wrap(outer.snapshotDescriptor())
                            .order(ByteOrder.BIG_ENDIAN);
                    assertEquals(0, descriptor.getInt());
                    assertEquals(2, descriptor.getInt());
                    assertEquals(0L, descriptor.getLong());
                    outer.tailTransfer(3, 1, 100000L);
                    descriptor = ByteBuffer.wrap(outer.snapshotDescriptor())
                            .order(ByteOrder.BIG_ENDIAN);
                    assertEquals(3, descriptor.getInt());
                    assertEquals(1, descriptor.getInt());
                    assertEquals(100000L, descriptor.getLong());
                    assertEquals(GuestCallDepthGuard.MANAGED_FRAME_CHARGE_BYTES, store.usedBytes(),
                            "100000 proper tail hops must not allocate new frames");
                    try (GuestInvocationFrame inner = GuestInvocationFrame.enter(2, 0)) {
                        assertEquals(2 * GuestCallDepthGuard.MANAGED_FRAME_CHARGE_BYTES, store.usedBytes());
                        assertEquals(2, store.liveFrames());
                    }
                    assertEquals(1, store.liveFrames());
                }
                assertEquals(0, store.liveFrames());
                assertEquals(0, store.usedBytes());
                turn.self().stop();
            });
            actor.send("run");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty(), () -> "unexpected actor failure: " + actor.failure());
            assertEquals(2 * GuestCallDepthGuard.MANAGED_FRAME_CHARGE_BYTES,
                    observed.get().peakBytes());
            assertTrue(observed.get().isRetired());
        }
    }

    @Test
    void foreignCarrierCannotRewriteOrCloseAnotherActorInvocationFrame() throws Exception {
        ActorRuntime.DispatcherConfig config = new ActorRuntime.DispatcherConfig(1, 1, 8);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            AtomicReference<Throwable> foreignFault = new AtomicReference<>();
            var actor = runtime.<String>spawnShared(factory -> (message, turn) -> {
                try (GuestInvocationFrame frame = GuestInvocationFrame.enter(1, 1)) {
                    Thread stranger = new Thread(() -> {
                        try {
                            assertThrows(SecurityException.class, () ->
                                    frame.tailTransfer(1, 1, 1));
                            assertThrows(SecurityException.class, frame::close);
                        } catch (Throwable failure) {
                            foreignFault.set(failure);
                        }
                    }, "foreign-frame-carrier");
                    stranger.start();
                    stranger.join(TimeUnit.SECONDS.toMillis(2));
                    assertFalse(stranger.isAlive());
                    assertNull(foreignFault.get());
                    assertEquals(GuestCallDepthGuard.MANAGED_FRAME_CHARGE_BYTES,
                            ActorRuntime.currentActorFrameArena().usedBytes());
                }
                turn.self().stop();
            });
            actor.send("run");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty(), () -> "actor failed: " + actor.failure());
        }
    }

    @Test
    void frameByteExhaustionRollsBackCarrierDepthAndTerminatesOnlyFailedActor()
            throws Exception {
        ActorRuntime.DispatcherConfig config = new ActorRuntime.DispatcherConfig(1, 1, 8);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            AtomicReference<ActorFrameArena> failed = new AtomicReference<>();
            AtomicInteger sibling = new AtomicInteger();
            var exhausted = runtime.<String>spawnShared(factory -> (message, turn) -> {
                ActorFrameArena arena = ActorRuntime.currentActorFrameArena();
                ExecutionContext execution = arena.newSynchronousExecution();
                failed.set(arena);
                var existing = arena.push(execution, ActorFrameArena.DEFAULT_MAX_BYTES);
                try {
                    assertThrows(ActorFrameArena.BudgetExceeded.class,
                            () -> GuestInvocationFrame.enter(1, 1));
                    assertTrue(arena.isPoisoned());
                    assertThrows(GuestCallDepthExceededException.class,
                            GuestCallDepthGuard::enter,
                            "actor generation may not reenter after frame quota failure");
                } finally {
                    arena.pop(execution, existing);
                }
                // The actor must fail-stop, although this host handler swallowed
                // the typed failure. The sibling can use the same worker.
            });
            var healthy = runtime.<String>spawnShared(factory -> (message, turn) -> {
                try (var frame = GuestInvocationFrame.enter(0, 0)) {
                    assertEquals(GuestCallDepthGuard.MANAGED_FRAME_CHARGE_BYTES,
                            ActorRuntime.currentActorFrameArena().usedBytes());
                    sibling.incrementAndGet();
                }
                turn.self().stop();
            });
            exhausted.send("overflow");
            assertTrue(exhausted.awaitTermination(3, TimeUnit.SECONDS));
            assertInstanceOf(ActorFrameArena.BudgetExceeded.class,
                    exhausted.failure().orElseThrow());
            assertEquals(0, failed.get().usedBytes());
            healthy.send("alive");
            assertTrue(healthy.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(healthy.failure().isEmpty());
            assertEquals(1, sibling.get());
        }
    }

    @Test
    void frameLeaseCannotBeReusedAfterClose() {
        try (var frame = GuestInvocationFrame.enter(0, 0)) {
            assertEquals(0, frame.snapshotDescriptor().length,
                    "root code outside an actor has carrier guard only");
            assertThrows(IllegalArgumentException.class,
                    () -> frame.tailTransfer(1, 2, 0));
        }
        GuestInvocationFrame lease = GuestInvocationFrame.enter(0, 0);
        lease.close();
        assertThrows(IllegalStateException.class, lease::close);
        assertThrows(IllegalStateException.class, lease::snapshotDescriptor);
        int depth = GuestCallDepthGuard.enter();
        assertEquals(1, depth);
        GuestCallDepthGuard.leave(depth);
    }

    @Test
    void primitiveParametersLiveInActorOwnedFrameBytesAndSurviveTailRewrite() throws Exception {
        ActorRuntime.DispatcherConfig config = new ActorRuntime.DispatcherConfig(1, 1, 8);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            AtomicReference<ActorFrameArena> arena = new AtomicReference<>();
            var actor = runtime.<String>spawnShared(factory -> (message, turn) -> {
                arena.set(ActorRuntime.currentActorFrameArena());
                try (GuestInvocationFrame frame = GuestInvocationFrame.enter(0, 2)) {
                    assertTrue(frame.hasActorOwnedScalarSlots());
                    long[] input = new long[]{Long.MIN_VALUE, Long.MAX_VALUE};
                    frame.writeScalarArguments(input);
                    input[0] = 12;
                    assertEquals(Long.MIN_VALUE, frame.readScalarArgument(0));
                    assertEquals(Long.MAX_VALUE, frame.readScalarArgument(1));
                    assertEquals(GuestCallDepthGuard.MANAGED_FRAME_CHARGE_BYTES,
                            arena.get().usedBytes());
                    frame.tailTransfer(0, 1, 1);
                    frame.writeScalarArguments(new long[]{-7});
                    assertEquals(-7L, frame.readScalarArgument(0));
                    assertEquals(0L, frame.readScalarArgument(1),
                            "tail transfer must scrub the vacated parameter slot");
                    assertEquals(1, arena.get().liveFrames());
                    assertThrows(IllegalArgumentException.class,
                            () -> frame.readScalarArgument(2));
                    assertThrows(IllegalArgumentException.class,
                            () -> frame.writeScalarArguments(new long[]{1, 2, 3}));
                }
                assertEquals(0, arena.get().usedBytes());
                turn.self().stop();
            });
            actor.send("run");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty(), () -> "actor failed: " + actor.failure());
            assertTrue(arena.get().isRetired());
        }
    }

    @Test
    void nonActorScalarSlotAccessDoesNotPretendToAllocateAnActorStack() {
        try (GuestInvocationFrame frame = GuestInvocationFrame.enter(0, 2)) {
            assertFalse(frame.hasActorOwnedScalarSlots());
            frame.writeScalarArguments(new long[]{3, 4});
            assertThrows(IllegalStateException.class,
                    () -> frame.readScalarArgument(0));
        }
    }

    @Test
    void invalidFrameDescriptorsFailBeforeTouchingCarrierDepth() {
        assertThrows(IllegalArgumentException.class, () -> GuestInvocationFrame.enter(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> GuestInvocationFrame.enter(0, -1));
        int depth = GuestCallDepthGuard.enter();
        assertEquals(1, depth);
        GuestCallDepthGuard.leave(depth);
    }
}
