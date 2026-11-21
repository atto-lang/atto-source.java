package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
final class OresSchedulerPhysicalRetirementTest {

    @Test
    void deferredRetirementIsOnceOnlyAndPrecedesFuturePublication() throws Exception {
        CountDownLatch hookEntered = new CountDownLatch(1);
        AtomicReference<Runnable> releaseRetirement = new AtomicReference<>();
        AtomicInteger retired = new AtomicInteger();

        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> result = scheduler.start(
                    new OresScheduler.Task<Integer>() {
                        @Override
                        public OresScheduler.Step<Integer> resume(
                                OresScheduler.Resume resume) {
                            return OresScheduler.done(42);
                        }

                        @Override
                        public void afterCarrierQuiescent(Runnable retire) {
                            assertTrue(releaseRetirement.compareAndSet(null, retire));
                            hookEntered.countDown();
                        }
                    },
                    retired::incrementAndGet);

            assertTrue(hookEntered.await(5, TimeUnit.SECONDS));
            assertFalse(
                    result.isDone(),
                    "terminal Future publication must not outrun physical retirement");
            assertEquals(0, retired.get());

            Runnable retire = releaseRetirement.get();
            assertNotNull(retire);
            retire.run();
            retire.run();

            assertEquals(42, result.get(5, TimeUnit.SECONDS));
            assertEquals(1, retired.get(), "physical retirement callback must be once-only");
        }
    }

    @Test
    void wallDeadlineExpiryCannotBypassDeferredPhysicalRetirement() throws Exception {
        CountDownLatch taskEntered = new CountDownLatch(1);
        CountDownLatch retirementHookEntered = new CountDownLatch(1);
        CountDownLatch notificationsDrained = new CountDownLatch(1);
        AtomicReference<Runnable> releaseRetirement = new AtomicReference<>();
        AtomicInteger quiescent = new AtomicInteger();

        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Void> never = new OresFuture<>();
            ActorRuntime.UntrustedActorQuotaExceededException deadlineFailure =
                    new ActorRuntime.UntrustedActorQuotaExceededException(
                            ActorRuntime.UntrustedActorQuotaExceededException.Resource.WALL_TIME,
                            "test wall deadline");

            OresFuture<Void> result = scheduler.startWithWallDeadline(
                    new OresScheduler.Task<Void>() {
                        @Override
                        public OresScheduler.Step<Void> resume(
                                OresScheduler.Resume resume) {
                            taskEntered.countDown();
                            return OresScheduler.await(never);
                        }

                        @Override
                        public void afterCarrierQuiescent(Runnable retire) {
                            assertTrue(releaseRetirement.compareAndSet(null, retire));
                            retirementHookEntered.countDown();
                        }
                    },
                    quiescent::incrementAndGet,
                    TimeUnit.MILLISECONDS.toNanos(100),
                    deadlineFailure,
                    notificationsDrained::countDown);

            assertTrue(taskEntered.await(5, TimeUnit.SECONDS));
            assertTrue(
                    retirementHookEntered.await(5, TimeUnit.SECONDS),
                    "wall deadline never reached physical-retirement barrier");

            assertFalse(
                    result.isDone(),
                    "deadline Future must remain unpublished until physical retirement");
            assertEquals(
                    0,
                    quiescent.get(),
                    "deadline expiry is not a physical-quiescence receipt");
            assertEquals(
                    1L,
                    notificationsDrained.getCount(),
                    "terminal subscriber drain must not precede retirement");

            Runnable retire = releaseRetirement.get();
            assertNotNull(retire);
            retire.run();

            ExecutionException observed = assertThrows(
                    ExecutionException.class,
                    () -> result.get(5, TimeUnit.SECONDS));
            assertSame(deadlineFailure, OresFuture.unwrap(observed));
            assertEquals(1, quiescent.get());
            assertTrue(
                    notificationsDrained.await(5, TimeUnit.SECONDS),
                    "terminal notification drain did not finish after retirement");
        }
    }

    @Test
    void timedTerminalReadyPrecedesOrdinarySubscribersAndNotificationDrain()
            throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Void> resumeGate = new OresFuture<>();
            CopyOnWriteArrayList<String> order = new CopyOnWriteArrayList<>();

            OresFuture<Integer> result = scheduler.startWithWallDeadline(
                    resume -> resume.initial()
                            ? OresScheduler.await(resumeGate)
                            : OresScheduler.done(7),
                    () -> { },
                    TimeUnit.SECONDS.toNanos(5),
                    new ActorRuntime.UntrustedActorQuotaExceededException(
                            ActorRuntime.UntrustedActorQuotaExceededException.Resource.WALL_TIME,
                            "unexpected test deadline"),
                    terminal -> order.add("terminal-ready"),
                    () -> order.add("notifications-drained"));

            result.whenCompleteRuntime(
                    (value, failure) -> order.add("subscriber"));

            resumeGate.completeFromRuntime(null);

            assertEquals(7, result.get(5, TimeUnit.SECONDS));
            assertEquals(
                    List.of(
                            "terminal-ready",
                            "subscriber",
                            "notifications-drained"),
                    order,
                    "timed actor Task path must preserve TaskScope terminal ordering");
        }
    }


}
