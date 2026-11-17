package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Actor turns carry the shared pointer-free {@link ExecutionContext}, whose
 * ACTOR allocation domain names the actor's heap owner and is fenced by the
 * incarnation generation.
 */
@Timeout(20)
final class ActorExecutionContextTest {
    private record Observed(ExecutionContext context, ActorRuntime.ActorId actor, long generation) { }

    @Test
    void outsideActorTurnThereIsNoActorExecutionContext() {
        assertNull(ActorRuntime.currentActorExecutionContext());
        assertNull(ActorRuntime.currentExecutionContext());
    }

    @Test
    void actorTurnsMintActorAllocationDomainFencedByGeneration() throws Exception {
        List<Observed> seen = new CopyOnWriteArrayList<>();
        CountDownLatch delivered = new CountDownLatch(4);
        try (var runtime = new ActorRuntime(IsolatePolicy.developer())) {
            ActorRuntime.ActorRef<String> first = runtime.spawnPrivate(() -> (message, turn) -> {
                ExecutionContext context = ActorRuntime.currentActorExecutionContext();
                assertSame(context, ActorRuntime.currentExecutionContext(),
                        "an actor turn is the innermost logical execution");
                seen.add(new Observed(context, turn.self().id(), turn.self().generation()));
                delivered.countDown();
            });
            ActorRuntime.ActorRef<String> second = runtime.spawnPrivate(() -> (message, turn) -> {
                seen.add(new Observed(ActorRuntime.currentActorExecutionContext(),
                        turn.self().id(), turn.self().generation()));
                delivered.countDown();
            });
            first.send("a");
            assertTrue(waitFor(seen, 1));
            first.send("b");
            assertTrue(waitFor(seen, 2));
            second.send("c");
            assertTrue(waitFor(seen, 3));
            second.send("d");
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
        }

        assertEquals(4, seen.size());
        for (Observed observed : seen) {
            ExecutionContext context = observed.context();
            assertNotNull(context, "every actor turn must carry an ExecutionContext");
            ExecutionContext.AllocationDomain domain = context.allocationDomain();
            assertEquals(ExecutionContext.AllocationKind.ACTOR, domain.kind());
            assertEquals(0L, domain.owner());
            assertEquals(observed.generation(), domain.generation(),
                    "allocation domain must be fenced by the actor incarnation generation");
            assertEquals(0L, context.parentExecution());
            assertEquals(0L, context.continuation());
        }

        Observed a = seen.get(0), b = seen.get(1), c = seen.get(2), d = seen.get(3);
        assertEquals(a.actor(), b.actor());
        assertNotEquals(a.actor(), c.actor());
        // Same actor, separate dispatches: same heap owner, distinct executions.
        assertEquals(a.context().allocationDomain(), b.context().allocationDomain());
        assertNotEquals(a.context().id(), b.context().id());
        assertEquals(c.context().allocationDomain(), d.context().allocationDomain());
        // Different actors never share an allocation domain.
        assertNotEquals(a.context().allocationDomain().domain(),
                c.context().allocationDomain().domain());
        // One runtime contributes one ExecutionId domain.
        assertEquals(a.context().id().runtimeDomain(), c.context().id().runtimeDomain());
    }

    @Test
    void actorAndSchedulerExecutionDomainsNeverCollide() throws Exception {
        ExecutionContext[] actorContext = new ExecutionContext[1];
        CountDownLatch delivered = new CountDownLatch(1);
        try (var runtime = new ActorRuntime(IsolatePolicy.developer());
             var scheduler = new OresScheduler(1)) {
            runtime.<String>spawnPrivate(() -> (message, turn) -> {
                actorContext[0] = ActorRuntime.currentActorExecutionContext();
                delivered.countDown();
            }).send("x");
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            ExecutionContext task = scheduler.startSync(ActorRuntime::currentExecutionContext)
                    .get(5, TimeUnit.SECONDS);
            assertNotNull(task);
            assertEquals(ExecutionContext.AllocationKind.NONE, task.allocationDomain().kind(),
                    "outside an actor turn the resolver falls back to the scheduler task");
            assertNotEquals(actorContext[0].id().runtimeDomain(), task.id().runtimeDomain());
        }
    }

    /**
     * Regression: minting used to run after currentActor was set and before
     * the clearing try/finally, so a mint failure leaked actor identity onto
     * the carrier and every later dispatch on it failed nested admission.
     */
    @Test
    void mintFailureFailsOnlyThatActorAndLeavesTheCarrierClean() throws Exception {
        try (var runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 1, 8))) {
            Field field = ActorRuntime.class.getDeclaredField("nextActorExecution");
            field.setAccessible(true);
            AtomicLong executions = (AtomicLong) field.get(runtime);

            ActorRuntime.ActorRef<String> doomed = runtime.spawnPrivate(
                    () -> (message, turn) -> fail("turn must not run after a mint failure"));
            executions.set(Long.MAX_VALUE);
            doomed.send("x");
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> doomed.done().get(5, TimeUnit.SECONDS));
            // Observers get a cause-free snapshot (#1223); the raw cause is host-gated.
            assertTrue(failure.getCause() instanceof ActorRuntime.ActorFailureSnapshotException,
                    () -> "unexpected failure: " + failure.getCause());
            assertTrue(String.valueOf(doomed.failure().orElse(null))
                            .contains("actor execution id space exhausted"),
                    () -> "unexpected raw failure: " + doomed.failure());

            executions.set(0L);
            AtomicReference<ExecutionContext> seen = new AtomicReference<>();
            CountDownLatch ran = new CountDownLatch(1);
            ActorRuntime.ActorRef<String> healthy = runtime.spawnPrivate(() -> (message, turn) -> {
                seen.set(ActorRuntime.currentActorExecutionContext());
                ran.countDown();
            });
            healthy.send("y");
            assertTrue(ran.await(5, TimeUnit.SECONDS),
                    "the single private carrier must still admit a new actor turn");
            assertNotNull(seen.get());
            assertEquals(healthy.generation(), seen.get().allocationDomain().generation());
        }
    }

    private static boolean waitFor(List<?> list, int size) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (list.size() < size) {
            if (System.nanoTime() > deadline) return false;
            Thread.sleep(5);
        }
        return true;
    }
}
