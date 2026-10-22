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
            // A failed observer is not a successful cleanup receipt. The
            // stalled guest hook must not be silently counted as completed.
            assertFalse(never.isDone());
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
