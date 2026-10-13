package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static dev.oreslang.runtime.ExceptionBoundaryPolicy.Effect.*;
import static org.junit.jupiter.api.Assertions.*;

/** Runtime CONTROL admission (not source compiler lowering). */
@Timeout(20)
final class ActorSupervisorControlIntegrationTest {
    private static ActorRuntime.ActorFailureNotice notice(
            ActorRuntime.ActorId child, long code) {
        return new ActorRuntime.ActorFailureNotice(
                child, Optional.empty(), ActorRuntime.ActorKind.SHARED,
                ExceptionBoundaryPolicy.actorTerminal(THROW, code),
                Optional.empty(), ActorRuntime.GuestEffectSignal.class.getName());
    }

    @Test
    void reservedLaneIsBoundedIdempotentAndGenerationSafe() {
        var lane = new ActorSupervisorControlLane(1);
        var first = ActorRuntime.ActorId.create();
        var other = ActorRuntime.ActorId.create();
        lane.reserve(first);
        assertThrows(java.util.concurrent.RejectedExecutionException.class,
                () -> lane.reserve(other));
        assertThrows(IllegalStateException.class, () -> lane.publish(notice(other, 3)));
        assertTrue(lane.publish(notice(first, 9)));
        assertFalse(lane.publish(notice(first, 11)));
        assertEquals(1, lane.pendingCount());
        assertEquals(9, lane.poll().orElseThrow().terminal().originCode());
        assertTrue(lane.poll().isEmpty());
        assertThrows(IllegalStateException.class, lane::poll,
                "single-flight CONTROL must reject an overlapping admission");
        lane.retry(first);
        assertEquals(9, lane.poll().orElseThrow().terminal().originCode());
        lane.ack(first);
        assertEquals(0, lane.reservedCount());
        assertThrows(IllegalStateException.class, () -> lane.publish(notice(first, 1)));
        lane.reserve(other);
        lane.retireGracefully(other);
    }

    @Test
    void topLevelActorPanicReachesRootControlExactlyOnce() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var child = runtime.<String>spawnShared(
                    () -> (message, context) -> {
                        throw ActorRuntime.GuestEffectSignal.raising(711);
                    });
            child.send("panic");
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> child.done().get(5, TimeUnit.SECONDS));
            var record = runtime.pollRootSupervisionControl().orElseThrow();
            assertEquals(child.id(), record.actor());
            assertEquals(PANIC, record.terminal().delivered());
            assertEquals(RAISE, record.terminal().origin());
            assertEquals(711, record.terminal().originCode());
            assertTrue(runtime.pollRootSupervisionControl().isEmpty());
            assertEquals(0, runtime.rootSupervisionPendingCount());
        }
    }

    @Test
    void childControlOutranksQueuedUserMailAndRunsOnParentCarrier() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch spawned = new CountDownLatch(1);
            CountDownLatch releaseParent = new CountDownLatch(1);
            CountDownLatch observed = new CountDownLatch(1);
            AtomicReference<ActorRuntime.ActorRef<String>> childRef = new AtomicReference<>();
            List<String> order = java.util.Collections.synchronizedList(new ArrayList<>());

            var parent = runtime.<String>spawnShared(
                    () -> new ActorRuntime.Behavior<String>() {
                        @Override public void onMessage(
                                String message, ActorRuntime.ActorContext<String> context)
                                throws Exception {
                            if (message.equals("spawn")) {
                                var child = context.runtime().<String>spawnShared(
                                        () -> (m, childContext) -> {
                                            throw ActorRuntime.GuestEffectSignal.throwing(712);
                                        });
                                childRef.set(child);
                                spawned.countDown();
                                assertTrue(releaseParent.await(5, TimeUnit.SECONDS));
                            } else if (message.equals("ordinary")) {
                                order.add("user");
                                context.self().stop();
                            }
                        }
                        @Override public void onChildFailure(
                                ActorRuntime.ActorFailureNotice notice,
                                ActorRuntime.ActorContext<String> context) {
                            assertTrue(context.runtime().currentThreadOwnsActorExecutionLease());
                            assertEquals(THROW, notice.terminal().origin());
                            assertEquals(712, notice.terminal().originCode());
                            order.add("CONTROL");
                            observed.countDown();
                        }
                    });

            parent.send("spawn");
            assertTrue(spawned.await(5, TimeUnit.SECONDS));
            parent.send("ordinary"); // regular mailbox already contains work
            childRef.get().send("fail");
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> childRef.get().done().get(5, TimeUnit.SECONDS));
            // Child.done is settled only AFTER its parent CONTROL publication;
            // awaitTermination alone could race with parent childFinalized().
            releaseParent.countDown();
            assertTrue(observed.await(5, TimeUnit.SECONDS));
            assertTrue(parent.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(List.of("CONTROL", "user"), order);
            assertTrue(parent.failure().isEmpty());
            assertTrue(runtime.pollRootSupervisionControl().isEmpty());
        }
    }

    @Test
    void stoppedParentForwardsCancelledChildToReservedRootControl() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch spawned = new CountDownLatch(1);
            AtomicReference<ActorRuntime.ActorRef<String>> childRef = new AtomicReference<>();
            var parent = runtime.<String>spawnShared(
                    () -> (message, context) -> {
                        childRef.set(context.runtime().spawnShared(
                                () -> (m, c) -> { }));
                        spawned.countDown();
                        context.self().stop();
                    });
            parent.send("spawn");
            assertTrue(spawned.await(5, TimeUnit.SECONDS));
            assertTrue(parent.awaitTermination(5, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> childRef.get().done().get(5, TimeUnit.SECONDS));
            var root = runtime.pollRootSupervisionControl().orElseThrow();
            assertEquals(childRef.get().id(), root.actor());
            assertEquals(CANCEL, root.terminal().delivered());
            assertTrue(runtime.pollRootSupervisionControl().isEmpty());
        }
    }

    @Test
    void missingParentHandlerEscalatesChildFailureToRoot() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch spawned = new CountDownLatch(1);
            AtomicReference<ActorRuntime.ActorRef<String>> childRef = new AtomicReference<>();
            var parent = runtime.<String>spawnShared(
                    () -> (message, context) -> {
                        var child = context.runtime().<String>spawnShared(
                                () -> (m, c) -> {
                                    throw ActorRuntime.GuestEffectSignal.throwing(713);
                                });
                        childRef.set(child);
                        spawned.countDown();
                        child.send("fail");
                    });
            parent.send("spawn");
            assertTrue(spawned.await(5, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> childRef.get().done().get(5, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> parent.done().get(5, TimeUnit.SECONDS));
            assertInstanceOf(ActorRuntime.ActorFailureSnapshotException.class,
                    parent.failure().orElseThrow());
            List<ActorRuntime.ActorFailureNotice> failures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                failures.add(runtime.pollRootSupervisionControl().orElseThrow());
            }
            assertTrue(failures.stream().anyMatch(n -> n.actor().equals(childRef.get().id())));
            assertTrue(failures.stream().anyMatch(n -> n.actor().equals(parent.id())));
            assertTrue(runtime.pollRootSupervisionControl().isEmpty());
        }
    }
}
