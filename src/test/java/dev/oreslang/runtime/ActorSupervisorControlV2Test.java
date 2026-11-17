package dev.oreslang.runtime;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
final class ActorSupervisorControlV2Test {
    private static ActorRuntime.ActorFailureNotice notice(
            ActorRuntime.ActorId child,
            long generation,
            long sequence) {
        return new ActorRuntime.ActorFailureNotice(
                child,
                generation,
                Optional.empty(),
                ActorRuntime.ActorKind.SHARED,
                ActorRuntime.ActorFailureKind.FAILURE,
                sequence,
                IllegalStateException.class.getName());
    }

    @Test
    void laneIsBoundedGenerationFencedAndObservable() {
        var lane = new ActorSupervisorControlLane(1);
        var first = ActorRuntime.ActorId.create();
        var other = ActorRuntime.ActorId.create();

        lane.reserve(first, 7);
        assertThrows(IllegalStateException.class,
                () -> lane.publish(notice(first, 8, 1)));
        assertThrows(java.util.concurrent.RejectedExecutionException.class,
                () -> lane.reserve(other, 1));

        assertTrue(lane.publish(notice(first, 7, 2)));
        assertFalse(lane.publish(notice(first, 7, 3)));

        var queued = lane.snapshot();
        assertEquals(1, queued.reserved());
        assertEquals(1, queued.pending());
        assertEquals(1, queued.published());
        assertEquals(1, queued.reservationRejected());
        assertEquals(1, queued.duplicatePublishRejected());

        var event = lane.poll().orElseThrow();
        assertEquals(2, event.sequence());
        assertThrows(IllegalStateException.class, lane::poll);

        lane.retry(first, 7);
        assertEquals(1, lane.snapshot().retried());
        assertEquals(2, lane.poll().orElseThrow().sequence());
        lane.ack(first, 7);

        var done = lane.snapshot();
        assertEquals(0, done.reserved());
        assertEquals(1, done.acknowledged());
    }

    @Test
    void failedTopLevelActorConsumesReservedRootSlotUntilObserved() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), 1)) {
            var failed = runtime.<String>spawnShared(
                    () -> (message, context) -> {
                        throw new IllegalStateException("boom");
                    });
            failed.send("fail");
            assertThrows(ExecutionException.class,
                    () -> failed.done().get(5, TimeUnit.SECONDS));

            var before = runtime.rootSupervisionStats();
            assertEquals(1, before.reserved());
            assertEquals(1, before.pending());
            assertEquals(1, before.published());

            assertThrows(java.util.concurrent.RejectedExecutionException.class,
                    () -> runtime.spawnShared(
                            () -> (String message, ActorRuntime.ActorContext<String> context) -> { }),
                    "root CONTROL backpressure must prevent failure loss");

            // Polling does NOT release admission pressure. An unsettled lease
            // retains the reserved generation and close() retries by default.
            try (var lease = runtime.pollRootSupervisionControl().orElseThrow()) {
                var event = lease.notice();
                assertEquals(failed.id(), event.actor());
                assertEquals(failed.generation(), event.generation());
                assertEquals(ActorRuntime.ActorFailureKind.FAILURE, event.failureKind());
                assertThrows(IllegalStateException.class,
                        runtime::pollRootSupervisionControl,
                        "only one root CONTROL delivery may be in flight");
                assertThrows(java.util.concurrent.RejectedExecutionException.class,
                        () -> runtime.spawnShared(
                                () -> (String message, ActorRuntime.ActorContext<String> context) -> { }),
                        "poll without ack must retain root backpressure");
                // Intentionally do not ack: close() must retry losslessly.
            }

            assertEquals(1, runtime.rootSupervisionStats().retried());
            try (var lease = runtime.pollRootSupervisionControl().orElseThrow()) {
                assertEquals(failed.id(), lease.notice().actor());
                lease.ack();
            }
            assertTrue(runtime.pollRootSupervisionControl().isEmpty());

            var next = runtime.<String>spawnShared(
                    () -> (message, context) -> { });
            next.stop();
            assertTrue(next.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void rootLeaseExplicitRetryPreservesNoticeIdentityAndSequence() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), 2)) {
            var failed = runtime.<String>spawnShared(
                    () -> (message, context) -> {
                        throw new IllegalStateException("boom");
                    });
            failed.send("fail");
            assertThrows(ExecutionException.class,
                    () -> failed.done().get(5, TimeUnit.SECONDS));

            ActorRuntime.ActorFailureNotice first;
            try (var lease = runtime.pollRootSupervisionControl().orElseThrow()) {
                first = lease.notice();
                lease.retry();
            }
            try (var lease = runtime.pollRootSupervisionControl().orElseThrow()) {
                assertEquals(first, lease.notice(),
                        "retry must redeliver the exact immutable notice");
                assertEquals(first.sequence(), lease.notice().sequence());
                lease.ack();
            }

            var stats = runtime.rootSupervisionStats();
            assertEquals(1, stats.published());
            assertEquals(1, stats.retried());
            assertEquals(1, stats.acknowledged());
            assertEquals(0, stats.pending());
            assertEquals(0, stats.reserved());
        }
    }

    @Test
    void childFailureControlOutranksAlreadyQueuedUserMailWithoutBorrowingMailbox() throws Exception {
        IsolatePolicy base = IsolatePolicy.developer();
        IsolatePolicy oneMessage = new IsolatePolicy(
                base.capabilities(),
                base.maxHeapBytes(),
                1,
                base.maxWallTime(),
                base.adversarial());

        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch spawned = new CountDownLatch(1);
            CountDownLatch releaseParent = new CountDownLatch(1);
            CountDownLatch controlSeen = new CountDownLatch(1);
            CountDownLatch userSeen = new CountDownLatch(1);
            AtomicReference<ActorRuntime.ActorRef<String>> childRef = new AtomicReference<>();
            List<String> order = new CopyOnWriteArrayList<>();

            var parent = runtime.<String>spawnShared(
                    oneMessage,
                    () -> new ActorRuntime.Behavior<String>() {
                        @Override
                        public void onMessage(
                                String message,
                                ActorRuntime.ActorContext<String> context) throws Exception {
                            if ("spawn".equals(message)) {
                                childRef.set(context.runtime().spawnPrivate(
                                        childContext -> (m, c) -> {
                                            throw new IllegalArgumentException("child");
                                        }));
                                spawned.countDown();
                                assertTrue(releaseParent.await(5, TimeUnit.SECONDS));
                            } else if ("ordinary".equals(message)) {
                                order.add("user");
                                userSeen.countDown();
                            }
                        }

                        @Override
                        public void onChildFailure(
                                ActorRuntime.ActorFailureNotice failure,
                                ActorRuntime.ActorContext<String> context) {
                            assertEquals(childRef.get().id(), failure.actor());
                            assertEquals(ActorRuntime.ActorFailureKind.FAILURE,
                                    failure.failureKind());
                            assertThrows(SecurityException.class,
                                    () -> childRef.get().failure(),
                                    "parent actors must not import raw child Throwable graphs");
                            assertEquals(
                                    failure,
                                    childRef.get().terminalFailureNotice().orElseThrow());
                            order.add("CONTROL");
                            controlSeen.countDown();
                        }
                    });

            parent.send("spawn");
            assertTrue(spawned.await(5, TimeUnit.SECONDS));

            // This fills the actor's configured one-message USER mailbox.
            parent.send("ordinary");
            assertThrows(ActorRuntime.ActorMailboxFullException.class,
                    () -> parent.send("overflow"));

            // CONTROL has its own pre-reserved transport and must still arrive.
            childRef.get().send("fail");
            assertThrows(ExecutionException.class,
                    () -> childRef.get().done().get(5, TimeUnit.SECONDS));

            var pressured = runtime.actorDiagnostics(parent);
            assertEquals(ActorRuntime.ActorLifecycleState.RUNNING, pressured.lifecycle());
            assertEquals(1, pressured.userMailboxCapacity());
            assertEquals(1, pressured.queuedUserMessages());
            assertEquals(0, pressured.queuedContinuations());
            assertEquals(1, pressured.supervisorControl().pending());
            assertEquals(2, pressured.nextUserMessageSequence());
            assertEquals(-1, pressured.lastProcessedUserMessageSequence());
            assertEquals(1, runtime.runtimeDiagnostics().activeActors());
            assertEquals(2, runtime.runtimeDiagnostics()
                    .rootSupervisorControl().reserved(),
                    "parent plus failed-child root fallback slots stay reserved");

            releaseParent.countDown();
            assertTrue(controlSeen.await(5, TimeUnit.SECONDS));
            assertTrue(userSeen.await(5, TimeUnit.SECONDS));
            assertEquals(List.of("CONTROL", "user"), order);

            var stats = runtime.supervisionStatsFor(parent);
            assertEquals(1, stats.published());
            assertEquals(1, stats.acknowledged());
            assertEquals(0, stats.pending());

            var drained = runtime.actorDiagnostics(parent);
            assertEquals(0, drained.queuedUserMessages());
            assertEquals(0, drained.supervisorControl().pending());
            assertEquals(2, drained.nextUserMessageSequence());
            assertEquals(1, drained.lastProcessedUserMessageSequence());

            parent.stop();
            assertTrue(parent.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(runtime.pollRootSupervisionControl().isEmpty());
        }
    }

    @Test
    void defaultChildFailurePolicyEscalatesWithoutRawChildThrowable() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch spawned = new CountDownLatch(1);
            AtomicReference<ActorRuntime.ActorRef<String>> childRef = new AtomicReference<>();

            var parent = runtime.<String>spawnShared(
                    () -> (message, context) -> {
                        var child = context.runtime().<String>spawnShared(
                                childContext -> (m, c) -> {
                                    throw new IllegalStateException("secret child diagnostic");
                                });
                        childRef.set(child);
                        spawned.countDown();
                        child.send("fail");
                    });

            parent.send("spawn");
            assertTrue(spawned.await(5, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class,
                    () -> childRef.get().done().get(5, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class,
                    () -> parent.done().get(5, TimeUnit.SECONDS));

            List<ActorRuntime.ActorFailureNotice> root = new ArrayList<>();
            for (int i = 0; i < 2; ++i) {
                try (var lease = runtime.pollRootSupervisionControl().orElseThrow()) {
                    root.add(lease.notice());
                    lease.ack();
                }
            }
            assertTrue(root.stream().anyMatch(n -> n.actor().equals(childRef.get().id())));
            assertTrue(root.stream().anyMatch(n -> n.actor().equals(parent.id())));
            assertTrue(root.stream().noneMatch(
                    n -> n.diagnosticType().contains("secret child diagnostic")));
            assertTrue(runtime.pollRootSupervisionControl().isEmpty());
        }
    }

    @Test
    @org.junit.jupiter.api.Disabled("Deferred: main (#2033 and later) keeps the raw terminal cause on host"
            + " ready/done observers; cause-free observers need an owner decision (see #1223 port notes)")
    void lifecycleObserversNeverExportRawTerminalThrowableGraphs() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            var actor = runtime.<String>spawnShared(
                    () -> (message, context) -> {
                        IllegalStateException secret =
                                new IllegalStateException(
                                        "secret-token=do-not-cross",
                                        new RuntimeException("secret-cause"));
                        secret.addSuppressed(
                                new RuntimeException("secret-suppressed"));
                        thrown.set(secret);
                        throw secret;
                    });

            var output = actor.outputs().next();
            actor.send("fail");

            ExecutionException doneFailure = assertThrows(
                    ExecutionException.class,
                    () -> actor.done().get(5, TimeUnit.SECONDS));
            var doneSnapshot = assertInstanceOf(
                    ActorRuntime.ActorFailureSnapshotException.class,
                    doneFailure.getCause());
            assertNull(doneSnapshot.getCause());
            assertEquals(0, doneSnapshot.getSuppressed().length);
            assertFalse(doneSnapshot.getMessage().contains("secret-token"));
            assertFalse(doneSnapshot.getMessage().contains("secret-cause"));
            assertEquals(
                    IllegalStateException.class.getName(),
                    doneSnapshot.notice().diagnosticType());

            ExecutionException outputFailure = assertThrows(
                    ExecutionException.class,
                    () -> output.get(5, TimeUnit.SECONDS));
            var outputSnapshot = assertInstanceOf(
                    ActorRuntime.ActorFailureSnapshotException.class,
                    outputFailure.getCause());
            assertEquals(doneSnapshot.notice(), outputSnapshot.notice());

            // Trusted host diagnostics still retain the original failure.
            assertSame(thrown.get(), actor.failure().orElseThrow());

            var terminated = assertThrows(
                    ActorRuntime.ActorTerminatedException.class,
                    () -> actor.send("late"));
            assertNull(terminated.getCause(),
                    "send-to-dead must not smuggle the raw actor cause");

            try (var lease = runtime.pollRootSupervisionControl().orElseThrow()) {
                assertEquals(doneSnapshot.notice(), lease.notice());
                lease.ack();
            }
        }
    }

    @Test
    @org.junit.jupiter.api.Disabled("Deferred: main (#2033 and later) keeps the raw terminal cause on host"
            + " ready/done observers; cause-free observers need an owner decision (see #1223 port notes)")
    void startupFailureReadyFutureIsCauseFreeButHostDiagnosticRemainsRaw() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            var actor = runtime.<String>spawnShared(() -> {
                IllegalArgumentException secret =
                        new IllegalArgumentException("secret-constructor-detail");
                thrown.set(secret);
                throw secret;
            });

            ExecutionException readyFailure = assertThrows(
                    ExecutionException.class,
                    () -> actor.ready().get(5, TimeUnit.SECONDS));
            var snapshot = assertInstanceOf(
                    ActorRuntime.ActorFailureSnapshotException.class,
                    readyFailure.getCause());
            assertNull(snapshot.getCause());
            assertFalse(snapshot.getMessage().contains("secret-constructor-detail"));
            assertEquals(
                    IllegalArgumentException.class.getName(),
                    snapshot.notice().diagnosticType());
            assertSame(thrown.get(), actor.failure().orElseThrow());

            try (var lease = runtime.pollRootSupervisionControl().orElseThrow()) {
                assertEquals(snapshot.notice(), lease.notice());
                lease.ack();
            }
        }
    }

    @Test
    void failureStormGetsPriorityButCannotStarveHealthyMailForever() throws Exception {
        final int children = 70;
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(4, 8, 64, 256))) {
            CountDownLatch spawned = new CountDownLatch(1);
            CountDownLatch releaseParent = new CountDownLatch(1);
            CountDownLatch allControl = new CountDownLatch(children);
            CountDownLatch userSeen = new CountDownLatch(1);
            List<ActorRuntime.ActorRef<String>> childRefs =
                    new CopyOnWriteArrayList<>();
            List<String> order = new CopyOnWriteArrayList<>();

            var parent = runtime.<String>spawnShared(
                    () -> new ActorRuntime.Behavior<String>() {
                        @Override
                        public void onMessage(
                                String message,
                                ActorRuntime.ActorContext<String> context) throws Exception {
                            if ("spawn".equals(message)) {
                                for (int i = 0; i < children; ++i) {
                                    childRefs.add(context.runtime().spawnPrivate(
                                            childContext -> (m, c) -> {
                                                throw new IllegalStateException("child");
                                            }));
                                }
                                spawned.countDown();
                                assertTrue(releaseParent.await(10, TimeUnit.SECONDS));
                            } else if ("ordinary".equals(message)) {
                                order.add("U");
                                userSeen.countDown();
                            }
                        }

                        @Override
                        public void onChildFailure(
                                ActorRuntime.ActorFailureNotice failure,
                                ActorRuntime.ActorContext<String> context) {
                            order.add("C");
                            allControl.countDown();
                        }
                    });

            parent.send("spawn");
            assertTrue(spawned.await(5, TimeUnit.SECONDS));
            parent.send("ordinary");

            for (var child : childRefs) {
                child.send("fail");
                assertThrows(ExecutionException.class,
                        () -> child.done().get(5, TimeUnit.SECONDS));
            }

            releaseParent.countDown();
            assertTrue(userSeen.await(10, TimeUnit.SECONDS));
            assertTrue(allControl.await(10, TimeUnit.SECONDS));

            int userIndex = order.indexOf("U");
            assertEquals(64, userIndex,
                    "exactly one healthy turn must run after the bounded 64-CONTROL burst");
            assertEquals(children + 1, order.size());

            var stats = runtime.supervisionStatsFor(parent);
            assertEquals(children, stats.published());
            assertEquals(children, stats.acknowledged());
            assertEquals(1, stats.fairnessYields());

            parent.stop();
            assertTrue(parent.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(runtime.pollRootSupervisionControl().isEmpty());
        }
    }

    @Test
    void cancellationNoticeIsClassifiedWithoutThrowableTransport() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch spawned = new CountDownLatch(1);
            CountDownLatch cancellationSeen = new CountDownLatch(1);
            AtomicReference<ActorRuntime.ActorRef<String>> childRef = new AtomicReference<>();

            var parent = runtime.<String>spawnShared(
                    () -> new ActorRuntime.Behavior<String>() {
                        @Override
                        public void onMessage(
                                String message,
                                ActorRuntime.ActorContext<String> context) {
                            childRef.set(context.runtime().spawnShared(
                                    childContext -> (m, c) -> { }));
                            spawned.countDown();
                        }

                        @Override
                        public void onChildFailure(
                                ActorRuntime.ActorFailureNotice failure,
                                ActorRuntime.ActorContext<String> context) {
                            if (failure.failureKind()
                                    == ActorRuntime.ActorFailureKind.CANCELLED) {
                                cancellationSeen.countDown();
                            }
                        }
                    });

            parent.send("spawn");
            assertTrue(spawned.await(5, TimeUnit.SECONDS));
            assertTrue(childRef.get().cancel());
            assertThrows(ExecutionException.class,
                    () -> childRef.get().done().get(5, TimeUnit.SECONDS));
            assertTrue(cancellationSeen.await(5, TimeUnit.SECONDS));

            parent.stop();
            assertTrue(parent.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
