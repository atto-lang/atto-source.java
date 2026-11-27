package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(25)
final class SupervisorProcessTest {
    private static SupervisorFailureControl.Notice notice(
            ActorRuntime.SupervisorId supervisor,
            ActorRuntime.ActorGroupId group, String detail) {
        return new SupervisorFailureControl.Notice(
                supervisor, group, ActorRuntime.ActorId.create(),
                Optional.empty(), SupervisorFailureControl.Kind.TERMINAL_FAILURE,
                "java.lang.IllegalStateException", detail);
    }

    @Test
    void awaitReleasesCarrierButNotTheExclusiveSupervisorTurn() throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(3)) {
            var supervisor = ActorRuntime.SupervisorId.create();
            var group = ActorRuntime.ActorGroupId.create();
            CompletableFuture<SupervisorProcess.Directive> first =
                    new CompletableFuture<>();
            CompletableFuture<SupervisorProcess.Directive> second =
                    new CompletableFuture<>();
            CountDownLatch firstEntered = new CountDownLatch(1);
            CountDownLatch secondEntered = new CountDownLatch(1);
            CountDownLatch bothResolved = new CountDownLatch(2);
            AtomicInteger active = new AtomicInteger();
            AtomicInteger maximum = new AtomicInteger();
            List<String> admitted = new CopyOnWriteArrayList<>();
            List<String> decisions = new CopyOnWriteArrayList<>();
            AtomicReference<String> continuationThread = new AtomicReference<>();
            AtomicReference<String> completingThread = new AtomicReference<>();

            try (SupervisorProcess process = new SupervisorProcess(
                    supervisor, group, pool, 3,
                    n -> {
                        int concurrency = active.incrementAndGet();
                        maximum.accumulateAndGet(concurrency, Math::max);
                        admitted.add(n.diagnostic());
                        if ("one".equals(n.diagnostic())) {
                            firstEntered.countDown();
                            return first;
                        }
                        secondEntered.countDown();
                        return second;
                    },
                    r -> {
                        continuationThread.set(Thread.currentThread().getName());
                        decisions.add(r.notice().diagnostic() + ":" + r.directive());
                        active.decrementAndGet();
                        bothResolved.countDown();
                    })) {
                process.accept(notice(supervisor, group, "one"));
                assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
                process.accept(notice(supervisor, group, "two"));
                assertFalse(secondEntered.await(150, TimeUnit.MILLISECONDS),
                        "second supervisor callback started while first awaited");
                assertEquals(1, process.queued() - 1);
                assertTrue(process.callbackActive());
                Thread completionCarrier = new Thread(() -> {
                    completingThread.set(Thread.currentThread().getName());
                    first.complete(SupervisorProcess.Directive.ESCALATE);
                }, "foreign-await-completion");
                completionCarrier.start();
                completionCarrier.join(5000);
                assertTrue(secondEntered.await(5, TimeUnit.SECONDS));
                assertEquals(List.of("one", "two"), admitted);
                second.complete(SupervisorProcess.Directive.STOP);
                assertTrue(bothResolved.await(5, TimeUnit.SECONDS));
                assertEquals(
                        List.of("one:ESCALATE", "two:STOP"), decisions);
                assertNotEquals(completingThread.get(), continuationThread.get(),
                        "future completion thread executed a supervisor decision");
                assertEquals(1, maximum.get(),
                        "suspended supervisor callbacks overlapped");
                assertEquals(2, process.resolved());
                assertEquals(0, process.undelivered());
                assertTrue(process.healthy());
            }
        }
    }

    @Test
    void decisionFailureFailsClosedWithoutClaimingRestart() throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            var supervisor = ActorRuntime.SupervisorId.create();
            var group = ActorRuntime.ActorGroupId.create();
            CompletableFuture<SupervisorProcess.Directive> pending =
                    new CompletableFuture<>();
            CountDownLatch entered = new CountDownLatch(1);
            AtomicInteger resolved = new AtomicInteger();
            try (SupervisorProcess process = new SupervisorProcess(
                    supervisor, group, pool, 2,
                    n -> { entered.countDown(); return pending; },
                    r -> resolved.incrementAndGet())) {
                process.accept(notice(supervisor, group, "a"));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                process.accept(notice(supervisor, group, "b"));
                assertThrows(IllegalStateException.class,
                        () -> process.accept(notice(supervisor, group, "overflow")));
                pending.completeExceptionally(new IllegalArgumentException("policy-failed"));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (process.failure().isEmpty() && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
                assertTrue(process.failure().orElse("").contains("policy-failed"));
                assertFalse(process.healthy());
                assertEquals(0, resolved.get());
                assertTrue(process.undelivered() >= 3,
                        "overflow and failed queued notices must remain visible");
            }
        }
    }

    @Test
    void realManagedGrandchildFailureRequestsSupervisorDecision() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup();
            CountDownLatch resolved = new CountDownLatch(1);
            AtomicReference<SupervisorProcess.Resolution> decision = new AtomicReference<>();
            AtomicReference<Boolean> ranInActor = new AtomicReference<>();
            SupervisorProcess process = group.installSupervisorProcess(
                    8,
                    n -> CompletableFuture.completedFuture(
                            SupervisorProcess.Directive.ESCALATE),
                    r -> {
                        ranInActor.set(ActorRuntime.inActorExecution());
                        decision.set(r);
                        resolved.countDown();
                    });
            var root = group.spawnWorker(
                    ActorRuntime.ActorKind.PRIVATE,
                    IsolatePolicy.developer(),
                    ctx -> (message, actor) -> {
                        var child = actor.runtime().spawnPrivate(bctx -> (body, receiver) -> {
                            var grandchild = receiver.runtime().spawnPrivate(
                                    cctx -> (data, grandContext) -> {
                                        throw new IllegalStateException("grandchild failure");
                                    });
                            grandchild.send("terminal");
                        });
                        child.send("nested");
                    });
            root.getReady().get(5, TimeUnit.SECONDS);
            root.send("start");
            assertTrue(resolved.await(10, TimeUnit.SECONDS));
            assertNotNull(decision.get());
            assertEquals(SupervisorProcess.Directive.ESCALATE,
                    decision.get().directive());
            assertEquals(group.supervisorId(),
                    decision.get().notice().supervisorId());
            assertEquals(group.id(), decision.get().notice().groupId());
            assertEquals("grandchild failure",
                    decision.get().notice().diagnostic());
            assertEquals(Boolean.FALSE, ranInActor.get());
            assertEquals(1, process.resolved());
            assertTrue(process.healthy());
            group.close();
            process.close();
        }
    }
}
