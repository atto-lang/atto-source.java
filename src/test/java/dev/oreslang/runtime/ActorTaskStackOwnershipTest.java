package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host actor scheduler tests. These do NOT establish compiler-lowered physical
 * stack isolation, native stack overflow containment, or cross-backend parity.
 */
final class ActorTaskStackOwnershipTest {
    @Test
    void aSuspendedTaskRetainsItsActorStackAndExecutionIdentity() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 1, 16);
        try (var runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            var awaited = new OresFuture<Integer>();
            var enteredAwait = new CountDownLatch(1);
            var owner = new AtomicReference<ActorGuestStack>();
            var taskFuture = new AtomicReference<OresFuture<Integer>>();

            var actor = runtime.<String>spawnShared(factory -> (message, turn) -> {
                ActorGuestStack stack = ActorRuntime.currentActorGuestStack();
                assertNotNull(stack);
                owner.set(stack);
                OresScheduler.Task<Integer> task = new OresScheduler.Task<>() {
                    int state;
                    ExecutionContext original;

                    @Override
                    public OresScheduler.Step<Integer> resume(OresScheduler.Resume resume) {
                        assertSame(stack, ActorRuntime.currentActorGuestStack());
                        assertEquals(turn.self().generation(),
                                ActorRuntime.currentActorGeneration());
                        ExecutionContext context = OresScheduler.currentExecutionContext();
                        assertNotNull(context);
                        assertEquals(ExecutionContext.AllocationKind.ACTOR,
                                context.allocationDomain().kind());
                        assertEquals(turn.self().generation(),
                                context.allocationDomain().generation());
                        if (state++ == 0) {
                            original = context;
                            enteredAwait.countDown();
                            return new OresScheduler.Await<>(awaited);
                        }
                        assertSame(original, context,
                                "await must not mint a new logical execution");
                        assertEquals(1, stack.activeTaskRoots(),
                                "task root survives suspension and callback scheduling");
                        assertEquals(42, resume.value());
                        return new OresScheduler.Done<>(42);
                    }
                };
                taskFuture.set(runtime.startActorTask(task));
            });

            actor.send("launch");
            assertTrue(enteredAwait.await(3, TimeUnit.SECONDS));
            assertEquals(1, owner.get().activeTaskRoots(),
                    "awaited Future completion is not a stack-retirement receipt");
            // The sole carrier must be available to a sibling while A awaits.
            var siblingFinished = new CountDownLatch(1);
            var sibling = runtime.<String>spawnShared(factory -> (message, turn) -> {
                assertNotSame(owner.get(), ActorRuntime.currentActorGuestStack());
                siblingFinished.countDown();
                turn.self().stop();
            });
            sibling.send("run");
            assertTrue(siblingFinished.await(3, TimeUnit.SECONDS),
                    "a suspended Task must not pin the shared carrier");
            assertTrue(sibling.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(awaited.completeFromRuntime(42));
            assertEquals(42, taskFuture.get().get(3, TimeUnit.SECONDS));
            actor.stop();
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertEquals(0, owner.get().activeTaskRoots(),
                    "actor cleanup must retire the stack root after quiescence");
        }
    }

    @Test
    void anotherActorCannotUseAnOwnedSuspendedRoot() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 1, 16);
        try (var runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            var stolen = new AtomicReference<ActorGuestStack.TaskRoot>();
            var aObserved = new CountDownLatch(1);
            var bObserved = new CountDownLatch(1);
            var a = runtime.<String>spawnShared(factory -> (message, turn) -> {
                if ("own".equals(message)) {
                    stolen.set(ActorRuntime.currentActorGuestStack()
                            .reserveTaskRoot(turn.self().generation()));
                    aObserved.countDown();
                } else if ("retire".equals(message)) {
                    stolen.get().retire();
                    turn.self().stop();
                }
            });
            a.send("own");
            assertTrue(aObserved.await(3, TimeUnit.SECONDS));

            var b = runtime.<String>spawnShared(factory -> (message, turn) -> {
                assertThrows(SecurityException.class, stolen.get()::requireCurrentOwner);
                bObserved.countDown();
                turn.self().stop();
            });
            b.send("foreign");
            assertTrue(bObserved.await(3, TimeUnit.SECONDS));
            assertTrue(b.awaitTermination(3, TimeUnit.SECONDS));
            a.send("retire");
            assertTrue(a.awaitTermination(3, TimeUnit.SECONDS));
            assertThrows(SecurityException.class, stolen.get()::requireCurrentOwner,
                    "retired generation cannot be reactivated by a stale callback");
        }
    }

    @Test
    void taskRootQuotaIsBoundedAndRetirementIsIdempotent() {
        var stack = new ActorGuestStack();
        var roots = new java.util.ArrayList<ActorGuestStack.TaskRoot>();
        for (int i = 0; i < ActorGuestStack.MAX_TASK_ROOTS; i++) {
            roots.add(stack.reserveTaskRoot(7L));
        }
        assertEquals(ActorGuestStack.MAX_TASK_ROOTS, stack.activeTaskRoots());
        assertThrows(java.util.concurrent.RejectedExecutionException.class,
                () -> stack.reserveTaskRoot(7L));
        roots.forEach(root -> {
            root.retire();
            root.retire();
        });
        assertEquals(0, stack.activeTaskRoots());
        assertEquals(ActorGuestStack.MAX_TASK_ROOTS, stack.highWaterTaskRoots());
        assertThrows(IllegalArgumentException.class, () -> stack.reserveTaskRoot(0L));
    }
}
