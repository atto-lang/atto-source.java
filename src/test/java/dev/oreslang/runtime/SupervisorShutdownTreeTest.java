package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
final class SupervisorShutdownTreeTest {
    private static SupervisorShutdownTree.Hooks hooks(
            String name, List<String> events) {
        return new SupervisorShutdownTree.Hooks() {
            @Override
            public OresFuture<Void> onShutdown(SupervisorShutdownTree.Reason reason) {
                events.add(name + ":pre:" + reason);
                return OresFuture.completed(null);
            }

            @Override
            public OresFuture<Void> onStopped(SupervisorShutdownTree.Reason reason) {
                events.add(name + ":post:" + reason);
                return OresFuture.completed(null);
            }
        };
    }

    @Test
    void parentPrecedesChildrenAndChildrenDrainInReverseRegistrationOrder() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             var control = Executors.newSingleThreadExecutor()) {
            List<String> events = new CopyOnWriteArrayList<>();
            SupervisorShutdownTree tree = new SupervisorShutdownTree(runtime, control);
            var root = tree.setRoot("root", runtime.createActorGroup(), hooks("root", events));
            root.addChild("first", runtime.createActorGroup(), hooks("first", events));
            root.addChild("second", runtime.createActorGroup(), hooks("second", events));

            OresFuture<Void> done = tree.shutdown(SupervisorShutdownTree.Reason.REQUESTED);
            assertSame(done, tree.shutdown(SupervisorShutdownTree.Reason.RUNTIME_EXIT));
            assertFalse(done.cancel(false), "observer must not cancel shutdown");
            done.get(5, TimeUnit.SECONDS);

            assertEquals(List.of(
                    "root:pre:REQUESTED",
                    "second:pre:PARENT_EXIT",
                    "second:post:PARENT_EXIT",
                    "first:pre:PARENT_EXIT",
                    "first:post:PARENT_EXIT",
                    "root:post:REQUESTED"), events);
            assertThrows(IllegalStateException.class, () ->
                    root.addChild("late", runtime.createActorGroup(), hooks("late", events)));
        }
    }

    @Test
    void awaitingHookDoesNotPretendDescendantOrRootIsStopped() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             var control = Executors.newSingleThreadExecutor()) {
            OresFuture<Void> gate = new OresFuture<>();
            CountDownLatch entered = new CountDownLatch(1);
            SupervisorShutdownTree.Hooks waiting = new SupervisorShutdownTree.Hooks() {
                @Override
                public OresFuture<Void> onShutdown(SupervisorShutdownTree.Reason reason) {
                    entered.countDown();
                    return gate;
                }
            };
            SupervisorShutdownTree tree = new SupervisorShutdownTree(runtime, control);
            var root = tree.setRoot("root", runtime.createActorGroup(), new SupervisorShutdownTree.Hooks() {});
            var child = root.addChild("child", runtime.createActorGroup(), waiting);
            OresFuture<Void> done = tree.shutdown(SupervisorShutdownTree.Reason.NORMAL);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertFalse(child.terminated().isDone());
            assertFalse(done.isDone());
            gate.completeFromRuntime(null);
            done.get(5, TimeUnit.SECONDS);
            assertTrue(child.terminated().isDone());
        }
    }

    @Test
    void treeAdmissionFencePrecedesEvenAnAwaitingParentShutdownHook() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             var control = Executors.newSingleThreadExecutor()) {
            OresFuture<Void> hookGate = new OresFuture<>();
            CountDownLatch entered = new CountDownLatch(1);
            ActorRuntime.ActorGroup parentGroup = runtime.createActorGroup();
            ActorRuntime.ActorGroup childGroup = runtime.createActorGroup();
            SupervisorShutdownTree tree = new SupervisorShutdownTree(runtime, control);
            var root = tree.setRoot("root", parentGroup, new SupervisorShutdownTree.Hooks() {
                @Override public OresFuture<Void> onShutdown(
                        SupervisorShutdownTree.Reason reason) {
                    entered.countDown();
                    return hookGate;
                }
            });
            root.addChild("child", childGroup, new SupervisorShutdownTree.Hooks() {});
            OresFuture<Void> done = tree.shutdown(SupervisorShutdownTree.Reason.REQUESTED);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertFalse(childGroup.closed(), "child close has not started");
            assertThrows(IllegalStateException.class, () ->
                    childGroup.spawnWorker(ActorRuntime.ActorKind.PRIVATE,
                            IsolatePolicy.developer(), ctx -> (message, actor) -> {}));
            assertFalse(done.isDone());
            hookGate.completeFromRuntime(null);
            done.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void hookFailureDoesNotSkipOtherChildrenOrParentPostHook() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             var control = Executors.newSingleThreadExecutor()) {
            List<String> events = new CopyOnWriteArrayList<>();
            SupervisorShutdownTree tree = new SupervisorShutdownTree(runtime, control);
            var root = tree.setRoot("root", runtime.createActorGroup(), hooks("root", events));
            root.addChild("healthy", runtime.createActorGroup(), hooks("healthy", events));
            root.addChild("failing", runtime.createActorGroup(), new SupervisorShutdownTree.Hooks() {
                @Override
                public OresFuture<Void> onShutdown(SupervisorShutdownTree.Reason reason) {
                    throw new IllegalStateException("injected pre-hook failure");
                }
            });

            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> tree.shutdown(SupervisorShutdownTree.Reason.REQUESTED)
                            .get(5, TimeUnit.SECONDS));
            assertTrue(error.getCause().getMessage().contains("root shutdown failed"));
            assertTrue(events.contains("healthy:post:PARENT_EXIT"));
            assertTrue(events.contains("root:post:REQUESTED"));
        }
    }

    @Test
    void rejectsForeignOrReusedGroup() throws Exception {
        try (ActorRuntime a = new ActorRuntime();
             ActorRuntime b = new ActorRuntime();
             var control = Executors.newSingleThreadExecutor()) {
            SupervisorShutdownTree tree = new SupervisorShutdownTree(a, control);
            ActorRuntime.ActorGroup group = a.createActorGroup();
            var root = tree.setRoot("root", group, new SupervisorShutdownTree.Hooks() {});
            assertThrows(IllegalArgumentException.class, () ->
                    root.addChild("duplicate", group, new SupervisorShutdownTree.Hooks() {}));
            assertThrows(IllegalArgumentException.class, () ->
                    root.addChild("foreign", b.createActorGroup(), new SupervisorShutdownTree.Hooks() {}));
            tree.shutdown(SupervisorShutdownTree.Reason.NORMAL).get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void expiredDeadlineFailsObserverAndDoesNotForgeCleanup() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             var control = Executors.newSingleThreadExecutor();
             var watchdog = Executors.newSingleThreadScheduledExecutor()) {
            OresFuture<Void> never = new OresFuture<>();
            CountDownLatch entered = new CountDownLatch(1);
            SupervisorShutdownTree tree = new SupervisorShutdownTree(runtime, control);
            var root = tree.setRoot("root", runtime.createActorGroup(),
                    new SupervisorShutdownTree.Hooks() {
                        @Override public OresFuture<Void> onShutdown(
                                SupervisorShutdownTree.Reason reason) {
                            entered.countDown();
                            return never;
                        }
                    });
            OresFuture<Void> done = tree.shutdown(
                    SupervisorShutdownTree.Reason.REQUESTED,
                    Duration.ofMillis(100), watchdog);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> done.get(5, TimeUnit.SECONDS));
            assertInstanceOf(TimeoutException.class, error.getCause());
            assertFalse(root.terminated().cancel(false));
            assertFalse(root.terminated().isDone(),
                    "deadline observer failure cannot settle physical termination");
            // A failed observer is not a successful cleanup receipt. The
            // stalled guest hook must not be silently counted as completed.
            assertFalse(never.isDone());
        }
    }


    @Test
    void exclusiveGroupBindingRejectsCompetingSupervisorsAcrossTrees() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             var control = Executors.newSingleThreadExecutor()) {
            var group = runtime.createActorGroup();
            var one = new SupervisorShutdownTree(runtime, control);
            var two = new SupervisorShutdownTree(runtime, control);
            one.setRoot("one", group, new SupervisorShutdownTree.Hooks() {});
            assertThrows(IllegalStateException.class, () ->
                    two.setRoot("two", group, new SupervisorShutdownTree.Hooks() {}));
            one.shutdown(SupervisorShutdownTree.Reason.NORMAL).get(5, TimeUnit.SECONDS);
            assertThrows(IllegalStateException.class, () ->
                    two.setRoot("late", group, new SupervisorShutdownTree.Hooks() {}));
        }
    }

    @Test
    void directExecutorIsRejectedWithoutExecutingGuestShutdownHook() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicInteger invoked = new AtomicInteger();
            var tree = new SupervisorShutdownTree(runtime, Runnable::run);
            var group = runtime.createActorGroup();
            var root = tree.setRoot("root", group, new SupervisorShutdownTree.Hooks() {
                @Override public OresFuture<Void> onShutdown(
                        SupervisorShutdownTree.Reason reason) {
                    invoked.incrementAndGet();
                    return OresFuture.completed(null);
                }
            });
            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> tree.shutdown(SupervisorShutdownTree.Reason.REQUESTED)
                            .get(5, TimeUnit.SECONDS));
            assertTrue(error.getCause().getMessage().contains("CONTROL unavailable"));
            assertEquals(0, invoked.get(), "no guest hook may run inline");
            group.closedDone().get(5, TimeUnit.SECONDS);
            assertTrue(group.closed());
            assertFalse(root.terminated().cancel(false));
        }
    }

    @Test
    void rejectedControlExecutorFailsClosedAndClosesGroups() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup();
            var tree = new SupervisorShutdownTree(runtime, command -> {
                throw new java.util.concurrent.RejectedExecutionException("CONTROL stopped");
            });
            tree.setRoot("root", group, new SupervisorShutdownTree.Hooks() {});
            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> tree.shutdown(SupervisorShutdownTree.Reason.RUNTIME_EXIT)
                            .get(5, TimeUnit.SECONDS));
            assertTrue(error.getCause().getMessage().contains("CONTROL unavailable"));
            group.closedDone().get(5, TimeUnit.SECONDS);
            assertTrue(group.closed());
        }
    }

    @Test
    void distinctBoundedObserversDoNotFalsifyUnderlyingShutdown() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             var control = Executors.newSingleThreadExecutor();
             var watchdog = Executors.newSingleThreadScheduledExecutor()) {
            OresFuture<Void> gate = new OresFuture<>();
            CountDownLatch entered = new CountDownLatch(1);
            var tree = new SupervisorShutdownTree(runtime, control);
            var root = tree.setRoot("root", runtime.createActorGroup(),
                    new SupervisorShutdownTree.Hooks() {
                        @Override public OresFuture<Void> onShutdown(
                                SupervisorShutdownTree.Reason reason) {
                            entered.countDown();
                            return gate;
                        }
                    });
            OresFuture<Void> bounded = tree.shutdown(
                    SupervisorShutdownTree.Reason.REQUESTED, Duration.ofMillis(100), watchdog);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> bounded.get(5, TimeUnit.SECONDS));
            assertFalse(root.terminated().isDone());
            gate.completeFromRuntime(null);
            root.terminated().get(5, TimeUnit.SECONDS);
            assertTrue(root.terminated().isDone(), "original physical barrier may complete later");
            assertThrows(ExecutionException.class, () -> bounded.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentShutdownIsSingleFlightWithOneHookInvocation() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             var control = Executors.newSingleThreadExecutor()) {
            AtomicInteger calls = new AtomicInteger();
            var tree = new SupervisorShutdownTree(runtime, control);
            tree.setRoot("root", runtime.createActorGroup(), new SupervisorShutdownTree.Hooks() {
                @Override public OresFuture<Void> onShutdown(
                        SupervisorShutdownTree.Reason reason) {
                    calls.incrementAndGet();
                    return OresFuture.completed(null);
                }
            });
            var participants = Executors.newFixedThreadPool(8);
            try {
                var attempts = new java.util.ArrayList<
                        java.util.concurrent.Future<OresFuture<Void>>>();
                for (int i = 0; i < 32; i++) {
                    attempts.add(participants.submit(() ->
                            tree.shutdown(SupervisorShutdownTree.Reason.REQUESTED)));
                }
                OresFuture<Void> same = attempts.get(0).get(5, TimeUnit.SECONDS);
                for (var attempt : attempts) assertSame(same, attempt.get(5, TimeUnit.SECONDS));
                same.get(5, TimeUnit.SECONDS);
                assertEquals(1, calls.get());
            } finally {
                participants.shutdownNow();
            }
        }
    }

    private static volatile SupervisorShutdownTree actorVisibleTree;
    private static volatile CountDownLatch coordinatorActorEntered;
    private static volatile CountDownLatch coordinatorActorRelease;

    @Test
    void actorCodeCannotExerciseHostSupervisorShutdownAuthority() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             var control = Executors.newSingleThreadExecutor()) {
            SupervisorShutdownTree tree = new SupervisorShutdownTree(runtime, control);
            tree.setRoot("root", runtime.createActorGroup(), new SupervisorShutdownTree.Hooks() {});
            actorVisibleTree = tree;
            CountDownLatch checked = new CountDownLatch(1);
            ActorRuntime.ActorRef<Integer> worker = runtime.spawnShared(() ->
                    (message, context) -> {
                        assertThrows(SecurityException.class, () ->
                                actorVisibleTree.shutdown(SupervisorShutdownTree.Reason.REQUESTED));
                        checked.countDown();
                        context.self().stop();
                    });
            worker.send(1);
            assertTrue(checked.await(3, TimeUnit.SECONDS));
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            tree.shutdown(SupervisorShutdownTree.Reason.NORMAL).get(5, TimeUnit.SECONDS);
        } finally {
            actorVisibleTree = null;
        }
    }

    @Test
    void brokenInlineControlExecutorDoesNotReportTerminationBeforeWorkerExit() throws Exception {
        coordinatorActorEntered = new CountDownLatch(1);
        coordinatorActorRelease = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorRef<Integer> worker = group.spawnWorker(
                    ActorRuntime.ActorKind.PRIVATE,
                    IsolatePolicy.developer(),
                    ctx -> (message, actorContext) -> {
                        coordinatorActorEntered.countDown();
                        // A cancellation request cannot manufacture physical
                        // exit while the active Java carrier is still running.
                        while (coordinatorActorRelease.getCount() > 0) {
                            try {
                                coordinatorActorRelease.await(1, TimeUnit.SECONDS);
                            } catch (InterruptedException ignored) {
                                // Intentionally keep the lease until release.
                            }
                        }
                    });
            worker.send(1);
            assertTrue(coordinatorActorEntered.await(3, TimeUnit.SECONDS));
            AtomicInteger hooksInvoked = new AtomicInteger();
            SupervisorShutdownTree tree = new SupervisorShutdownTree(runtime, Runnable::run);
            tree.setRoot("root", group, new SupervisorShutdownTree.Hooks() {
                @Override public OresFuture<Void> onShutdown(
                        SupervisorShutdownTree.Reason reason) {
                    hooksInvoked.incrementAndGet();
                    return OresFuture.completed(null);
                }
            });
            OresFuture<Void> done = tree.shutdown(SupervisorShutdownTree.Reason.REQUESTED);
            assertEquals(0, hooksInvoked.get(), "invalid CONTROL executor cannot execute guest hooks");
            assertFalse(done.isDone(), "emergency fallback must wait for physical actor finalization");
            try {
                assertFalse(worker.done().isDone(), "active actor has not finished");
            } finally {
                coordinatorActorRelease.countDown();
            }
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> done.get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause().getMessage().contains("CONTROL"));
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            coordinatorActorRelease.countDown();
        }
    }

    private static volatile CountDownLatch firstEntered;
    private static volatile CountDownLatch firstMayExit;
    private static final AtomicInteger processed = new AtomicInteger();

    @Test
    void gracefulGroupCloseFencesAdmissionsButDrainsAcceptedActorMail() throws Exception {
        processed.set(0);
        firstEntered = new CountDownLatch(1);
        firstMayExit = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorRef<Integer> worker = group.spawnWorker(
                    ActorRuntime.ActorKind.PRIVATE,
                    IsolatePolicy.developer(),
                    context -> (message, actorContext) -> {
                        if (message == 1) {
                            firstEntered.countDown();
                            firstMayExit.await(5, TimeUnit.SECONDS);
                        }
                        processed.incrementAndGet();
                    });
            worker.send(1);
            assertTrue(firstEntered.await(3, TimeUnit.SECONDS));
            worker.send(2);
            OresFuture<Void> done = group.gracefulCloseAsync();
            assertTrue(group.closed());
            assertFalse(done.isDone(), "active actor turn has not finalized");
            assertSame(done, group.gracefulCloseAsync());
            try {
                assertThrows(IllegalStateException.class, () ->
                        group.spawnWorker(ActorRuntime.ActorKind.PRIVATE,
                                IsolatePolicy.developer(), ctx -> (m, ac) -> {}));
            } finally {
                firstMayExit.countDown();
            }
            done.get(5, TimeUnit.SECONDS);
            assertEquals(2, processed.get(), "accepted mail must drain before done");
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
