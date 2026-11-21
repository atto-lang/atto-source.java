package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Proves CONTROL progress despite an unrelated saturated actor carrier pool. */
@Timeout(25)
final class SupervisorControlLivenessTest {
    private static SupervisorFailureControl.Notice notice(
            ActorRuntime.SupervisorId supervisor,
            ActorRuntime.ActorGroupId group) {
        return new SupervisorFailureControl.Notice(
                supervisor, group, ActorRuntime.ActorId.create(),
                Optional.empty(), SupervisorFailureControl.Kind.TERMINAL_FAILURE,
                "Failure", "simulated");
    }

    private static void awaitResolved(SupervisorProcess process, long count)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (process.resolved() < count && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertEquals(count, process.resolved(),
                "supervisor callback signaled before its resolution was committed");
    }

    @Test
    void failingSharedActorNotifiesSupervisorWhileAllPrivateCarriersBlocked() throws Exception {
        CountDownLatch privateCarrierEntered = new CountDownLatch(1);
        CountDownLatch releasePrivateCarrier = new CountDownLatch(1);
        AtomicBoolean blockFirstTurn = new AtomicBoolean(true);
        ActorRuntime.TurnExecutor injectedCarrierGate = turn -> {
            if (blockFirstTurn.compareAndSet(true, false)) {
                privateCarrierEntered.countDown();
                try {
                    if (!releasePrivateCarrier.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("private carrier gate timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            turn.run();
        };
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8, 16),
                injectedCarrierGate)) {
            // The first private actor consumes the ONLY private actor carrier.
            ActorRuntime.ActorRef<String> busy = runtime.spawnPrivate(
                    factory -> (msg, context) -> { });
            // Actor initialization alone may be lazy. Deliver an actual
            // mailbox turn to occupy the private carrier deterministically.
            busy.send("occupy");
            assertTrue(privateCarrierEntered.await(5, TimeUnit.SECONDS));

            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            CountDownLatch notified = new CountDownLatch(1);
            AtomicReference<SupervisorProcess.Resolution> decision = new AtomicReference<>();
            AtomicReference<String> observedCarrier = new AtomicReference<>();
            try (SupervisorProcess process = group.installSupervisorProcess(
                    8, msg -> CompletableFuture.completedFuture(
                            SupervisorProcess.Directive.ESCALATE),
                    resolution -> {
                        observedCarrier.set(Thread.currentThread().getName());
                        decision.set(resolution);
                        notified.countDown();
                    })) {
                ActorRuntime.ActorRef<String> failing = group.spawnWorker(
                        ActorRuntime.ActorKind.SHARED,
                        IsolatePolicy.developer(),
                        factory -> (msg, context) -> {
                            throw new IllegalStateException("shared failed while private pool full");
                        });
                failing.getReady().get(5, TimeUnit.SECONDS);
                failing.send("fail");
                assertTrue(notified.await(5, TimeUnit.SECONDS),
                        "supervisor CONTROL starved behind saturated private worker");
                assertNotNull(decision.get());
                assertEquals("shared failed while private pool full",
                        decision.get().notice().diagnostic());
                assertEquals(group.supervisorId(),
                        decision.get().notice().supervisorId());
                assertEquals(SupervisorProcess.Directive.ESCALATE,
                        decision.get().directive());
                assertNotNull(observedCarrier.get());
                awaitResolved(process, 1);
            } finally {
                releasePrivateCarrier.countDown();
                group.close();
            }
        } finally {
            releasePrivateCarrier.countDown();
        }
    }

    @Test
    void suspendedSupervisorDoesNotStarveIndependentSupervisor() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8, 16))) {
            ActorRuntime.ActorGroup waitingGroup = runtime.createActorGroup();
            ActorRuntime.ActorGroup activeGroup = runtime.createActorGroup();
            CompletableFuture<SupervisorProcess.Directive> suspended = new CompletableFuture<>();
            CountDownLatch firstEntered = new CountDownLatch(1);
            CountDownLatch otherResolved = new CountDownLatch(1);
            CountDownLatch firstResolved = new CountDownLatch(1);

            SupervisorProcess waiting = waitingGroup.installSupervisorProcess(
                    2, notice -> {
                        firstEntered.countDown();
                        return suspended;
                    }, resolution -> firstResolved.countDown());
            SupervisorProcess active = activeGroup.installSupervisorProcess(
                    2, notice -> CompletableFuture.completedFuture(
                            SupervisorProcess.Directive.STOP),
                    resolution -> otherResolved.countDown());

            // Host-side process admission is package-scoped to trusted runtime.
            waiting.accept(notice(waitingGroup.supervisorId(), waitingGroup.id()));
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            active.accept(notice(activeGroup.supervisorId(), activeGroup.id()));
            assertTrue(otherResolved.await(5, TimeUnit.SECONDS),
                    "suspended supervisor occupied another supervisor's CONTROL carrier");
            awaitResolved(active, 1);
            assertEquals(0, waiting.resolved());
            suspended.complete(SupervisorProcess.Directive.ESCALATE);
            assertTrue(firstResolved.await(5, TimeUnit.SECONDS));
            awaitResolved(waiting, 1);
            waiting.close();
            active.close();
            waitingGroup.close();
            activeGroup.close();
        }
    }

    @Test
    void shutdownRaceDoesNotApplyDelayedDecisionOrClaimSuccess() throws Exception {
        try (ExecutorService control = Executors.newFixedThreadPool(2)) {
            var supervisorId = ActorRuntime.SupervisorId.create();
            var groupId = ActorRuntime.ActorGroupId.create();
            CompletableFuture<SupervisorProcess.Directive> awaited = new CompletableFuture<>();
            CountDownLatch entered = new CountDownLatch(1);
            AtomicInteger decisions = new AtomicInteger();
            try (SupervisorProcess process = new SupervisorProcess(
                    supervisorId, groupId, control, 2,
                    n -> { entered.countDown(); return awaited; },
                    r -> decisions.incrementAndGet())) {
                process.accept(notice(supervisorId, groupId));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                process.close();
                awaited.complete(SupervisorProcess.Directive.RESTART);
                control.submit(() -> { }).get(5, TimeUnit.SECONDS);
                assertEquals(0, decisions.get(), "late await restarted after close");
                assertEquals(0, process.resolved());
                assertEquals(1, process.undelivered(),
                        "unresolved CONTROL must be accounted exactly once");
                assertFalse(process.healthy());
            }
        }
    }

    @Test
    void exhaustedControlDispatcherFailsClosedRatherThanClaimingDelivery() {
        ExecutorService control = Executors.newSingleThreadExecutor();
        control.shutdownNow();
        var supervisor = ActorRuntime.SupervisorId.create();
        var group = ActorRuntime.ActorGroupId.create();
        try (SupervisorProcess process = new SupervisorProcess(
                supervisor, group, control, 2,
                n -> CompletableFuture.completedFuture(
                        SupervisorProcess.Directive.RESTART),
                r -> fail("rejected CONTROL cannot apply lifecycle decision"))) {
            assertThrows(IllegalStateException.class,
                    () -> process.accept(notice(supervisor, group)),
                    "rejected CONTROL dispatch must signal failed admission");
            assertFalse(process.healthy());
            assertTrue(process.failure().isPresent());
            assertEquals(1, process.undelivered());
            assertEquals(0, process.resolved());
        }
    }
}
