package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
final class SupervisorFailureControlTest {
    @Test
    void nestedGrandchildFailureIsOneSupervisorControlNotice() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            CountDownLatch reported = new CountDownLatch(1);
            AtomicReference<SupervisorFailureControl.Notice> event = new AtomicReference<>();
            AtomicInteger fatalCount = new AtomicInteger();
            AtomicReference<Boolean> ranOnActor = new AtomicReference<>();

            group.installSupervisorFailureHandler(notice -> {
                if (notice.kind() == SupervisorFailureControl.Kind.TERMINAL_FAILURE) {
                    ranOnActor.set(ActorRuntime.inActorExecution());
                    event.set(notice);
                    fatalCount.incrementAndGet();
                    reported.countDown();
                }
            });

            ActorRuntime.ActorRef<String> a = group.spawnWorker(
                    ActorRuntime.ActorKind.PRIVATE,
                    IsolatePolicy.developer(),
                    factory -> (message, ctx) -> {
                        if (!message.equals("spawn-b")) return;
                        ActorRuntime.ActorRef<String> b = ctx.runtime().spawnPrivate(
                                bf -> (childMessage, bc) -> {
                                    if (!childMessage.equals("spawn-c")) return;
                                    ActorRuntime.ActorRef<String> c = bc.runtime().spawnPrivate(
                                            cf -> (grandMessage, cc) -> {
                                                throw new IllegalStateException("C terminal");
                                            });
                                    c.send("fail");
                                });
                        b.send("spawn-c");
                    });
            a.getReady().get(5, TimeUnit.SECONDS);
            assertEquals(group.supervisorId(), a.supervisorId().orElseThrow());
            a.send("spawn-b");

            assertTrue(reported.await(10, TimeUnit.SECONDS),
                    "C termination must reach its inherited supervisor");
            var notice = event.get();
            assertNotNull(notice);
            assertEquals(group.supervisorId(), notice.supervisorId());
            assertEquals(group.id(), notice.groupId());
            assertTrue(notice.spawnedBy().isPresent(),
                    "grandchild must preserve spawning parent lineage");
            assertNotEquals(a.id(), notice.actorId());
            assertNotEquals(a.id(), notice.spawnedBy().orElseThrow());
            assertEquals("java.lang.IllegalStateException", notice.failureType());
            assertEquals("C terminal", notice.diagnostic());
            assertEquals(Boolean.FALSE, ranOnActor.get(),
                    "CONTROL handler must not execute inline on child carrier");
            assertEquals(1, fatalCount.get(), "terminal notice must be exactly once");
            assertTrue(group.undeliveredSupervisorFailures() == 0);
            assertTrue(group.supervisorHandlerFailure().isEmpty());
            group.close();
        }
    }

    @Test
    void controlLaneIsBoundedSingleFlightAndRecordsHandlerFailure() throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(3)) {
            ActorRuntime.SupervisorId supervisorId = ActorRuntime.SupervisorId.create();
            ActorRuntime.ActorGroupId groupId = ActorRuntime.ActorGroupId.create();
            ActorRuntime.ActorId actorId = ActorRuntime.ActorId.create();
            SupervisorFailureControl lane =
                    new SupervisorFailureControl(supervisorId, groupId, pool, 2);
            AtomicInteger active = new AtomicInteger();
            AtomicInteger maximum = new AtomicInteger();
            AtomicInteger delivered = new AtomicInteger();
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch twoDelivered = new CountDownLatch(2);

            lane.install(notice -> {
                int concurrency = active.incrementAndGet();
                maximum.accumulateAndGet(concurrency, Math::max);
                entered.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    delivered.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                } finally {
                    active.decrementAndGet();
                    twoDelivered.countDown();
                }
            });

            SupervisorFailureControl.Notice notice = new SupervisorFailureControl.Notice(
                    supervisorId, groupId, actorId, java.util.Optional.empty(),
                    SupervisorFailureControl.Kind.TERMINAL_FAILURE, "Failure", "boom");
            lane.publish(notice);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            lane.publish(notice);
            lane.publish(notice);
            assertEquals(1, lane.undelivered(), "overflow must be visible");
            release.countDown();
            assertTrue(twoDelivered.await(5, TimeUnit.SECONDS));
            assertEquals(2, delivered.get());
            assertEquals(1, maximum.get(), "supervisor callbacks must not overlap");

            assertThrows(SecurityException.class, () -> lane.publish(
                    new SupervisorFailureControl.Notice(
                            ActorRuntime.SupervisorId.create(), groupId, actorId,
                            java.util.Optional.empty(),
                            SupervisorFailureControl.Kind.TERMINAL_FAILURE,
                            "Failure", "foreign")));
        }
    }


    @Test
    void handlerFailureRetiresQueuedNoticesAndRejectsLateDeliveries() throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            var supervisor = ActorRuntime.SupervisorId.create();
            var group = ActorRuntime.ActorGroupId.create();
            var actor = ActorRuntime.ActorId.create();
            var lane = new SupervisorFailureControl(supervisor, group, pool, 3);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var calls = new AtomicInteger();
            var notice = new SupervisorFailureControl.Notice(
                    supervisor, group, actor, java.util.Optional.empty(),
                    SupervisorFailureControl.Kind.TERMINAL_FAILURE,
                    "Panic", "failure payload");
            lane.install(n -> {
                calls.incrementAndGet();
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out waiting for failure");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                throw new IllegalStateException("supervisor policy crashed");
            });
            lane.publish(notice);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            lane.publish(notice);
            assertEquals(2, lane.queued());
            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (lane.failure().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertTrue(lane.failure().orElse("").contains("supervisor policy crashed"));
            assertEquals(2, lane.undelivered(), "failed callback and pending notice both lost");
            assertEquals(0, lane.queued(), "terminal failure retained owned notices");
            assertEquals(1, calls.get(), "policy retried after terminal failure");
            assertFalse(lane.healthy());
            lane.publish(notice);
            assertEquals(3, lane.undelivered(), "late notice must be rejected and counted");
            assertEquals(0, lane.queued());
        }
    }

    @Test
    void failureHandlerMayNotBeInstalledFromActorCode() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            CountDownLatch attempted = new CountDownLatch(1);
            AtomicReference<Throwable> rejection = new AtomicReference<>();

            // The group handle is captured only in trusted host behavior.
            ActorRuntime.ActorRef<String> actor = runtime.spawnShared(
                    () -> (message, ctx) -> {
                        try {
                            group.installSupervisorFailureHandler(ignored -> { });
                        } catch (Throwable e) {
                            rejection.set(e);
                        } finally {
                            attempted.countDown();
                        }
                    });
            actor.send("attempt");
            assertTrue(attempted.await(5, TimeUnit.SECONDS));
            assertInstanceOf(SecurityException.class, rejection.get());
            actor.stop();
            actor.getDone().get(5, TimeUnit.SECONDS);
            group.close();
        }
    }
}
