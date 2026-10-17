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
            ActorRuntime.ActorId actorId2 = ActorRuntime.ActorId.create();
            ActorRuntime.ActorId actorId3 = ActorRuntime.ActorId.create();
            SupervisorFailureControl lane =
                    new SupervisorFailureControl(supervisorId, groupId, pool, 2);
            lane.reserve(actorId);
            lane.reserve(actorId2);
            assertEquals(2, lane.reserved());
            assertThrows(IllegalStateException.class, () -> lane.reserve(actorId3));

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
            SupervisorFailureControl.Notice notice2 = new SupervisorFailureControl.Notice(
                    supervisorId, groupId, actorId2, java.util.Optional.empty(),
                    SupervisorFailureControl.Kind.TERMINAL_FAILURE, "Failure", "boom2");
            lane.publish(notice);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            lane.publish(notice2);
            assertEquals(2, lane.queued(), "both admitted terminal records remain durable");
            assertEquals(0, lane.undelivered(), "reserved terminal state must not overflow");
            assertThrows(IllegalStateException.class, () -> lane.reserve(actorId3));
            release.countDown();
            assertTrue(twoDelivered.await(5, TimeUnit.SECONDS));
            assertEquals(2, delivered.get());
            assertEquals(1, maximum.get(), "supervisor callbacks must not overlap");
            assertEquals(0, lane.queued());
            assertEquals(0, lane.reserved());
            lane.reserve(actorId3);

            assertThrows(SecurityException.class, () -> lane.publish(
                    new SupervisorFailureControl.Notice(
                            ActorRuntime.SupervisorId.create(), groupId, actorId,
                            java.util.Optional.empty(),
                            SupervisorFailureControl.Kind.TERMINAL_FAILURE,
                            "Failure", "foreign")));
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
