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
    void failedFinalizationBarrierRetiresInFlightControlWithoutQueueUnderflow()
            throws Exception {
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            var supervisor = ActorRuntime.SupervisorId.create();
            var group = ActorRuntime.ActorGroupId.create();
            var lane = new SupervisorFailureControl(supervisor, group, pool, 4);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch returned = new CountDownLatch(1);
            AtomicInteger callbacks = new AtomicInteger();
            lane.install(notice -> {
                callbacks.incrementAndGet();
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS))
                        throw new IllegalStateException("CONTROL never released");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                } finally {
                    returned.countDown();
                }
            });
            var notice = new SupervisorFailureControl.Notice(
                    supervisor, group, ActorRuntime.ActorId.create(),
                    java.util.Optional.empty(),
                    SupervisorFailureControl.Kind.TERMINAL_FAILURE,
                    "Panic", "failed child");
            lane.publish(notice);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            lane.publish(notice);
            lane.rejectUnfinalizedFailure();
            release.countDown();
            assertTrue(returned.await(5, TimeUnit.SECONDS));
            pool.submit(() -> { }).get(5, TimeUnit.SECONDS);
            assertEquals(1, callbacks.get(), "retired CONTROL invoked next callback");
            assertEquals(0, lane.queued(), "terminal CONTROL retained notices");
            assertEquals(3, lane.undelivered(),
                    "active, queued and failed-finalization notices must all be accounted");
            assertFalse(lane.healthy());
            assertTrue(lane.failure().orElse("").contains("physical finalization"));
        }
    }

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
    void inlineDispatcherRejectsControlWithoutRetainingNotice() {
        var supervisor = ActorRuntime.SupervisorId.create();
        var group = ActorRuntime.ActorGroupId.create();
        var actor = ActorRuntime.ActorId.create();
        var lane = new SupervisorFailureControl(supervisor, group,
                Runnable::run, 1);
        var calls = new AtomicInteger();
        lane.install(n -> calls.incrementAndGet());
        lane.publish(new SupervisorFailureControl.Notice(
                supervisor, group, actor, java.util.Optional.empty(),
                SupervisorFailureControl.Kind.TERMINAL_FAILURE, "Panic", "inline"));
        assertEquals(0, calls.get(), "CONTROL must never execute under admission lock");
        assertEquals(1, lane.undelivered());
        assertEquals(0, lane.queued(), "failed dispatch retained a doomed notice");
        assertTrue(lane.failure().isPresent());
        assertFalse(lane.healthy());
    }

    @Test
    void diagnosticLimitUsesUtf8BytesAndDoesNotSplitUnicodeScalars() {
        var id = ActorRuntime.SupervisorId.create();
        var group = ActorRuntime.ActorGroupId.create();
        var actor = ActorRuntime.ActorId.create();
        java.util.function.Function<String, SupervisorFailureControl.Notice> notice = text ->
                new SupervisorFailureControl.Notice(
                        id, group, actor, java.util.Optional.empty(),
                        SupervisorFailureControl.Kind.TERMINAL_FAILURE, text, text);

        // Two UTF-8 bytes per Latin-1 scalar, four per supplementary scalar.
        var twoByte = notice.apply("é".repeat(600));
        assertEquals(512, twoByte.diagnostic().length());
        assertEquals(1024, twoByte.diagnostic()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        var fourByte = notice.apply("😀".repeat(300));
        assertEquals(256 * 2, fourByte.diagnostic().length());
        assertEquals(1024, fourByte.diagnostic()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8).length);

        // A boundary at 1,023 bytes cannot admit the next complete 4-byte
        // scalar. Do not skip ahead to later scalars, as native truncates here.
        var boundary = notice.apply("a".repeat(1023) + "😀" + "b");
        assertEquals("a".repeat(1023), boundary.diagnostic());
        assertEquals(1023, boundary.diagnostic()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8).length);

        // Malformed Java UTF-16 must never survive into a failure envelope.
        var malformed = notice.apply("A\uD800B\uDC00C");
        assertEquals("A?B?C", malformed.diagnostic());
        assertEquals("A?B?C", malformed.failureType());
        assertEquals("A😀B", notice.apply("A\uD83D\uDE00B").diagnostic());

        var ascii = notice.apply("X".repeat(1024));
        assertEquals(1024, ascii.diagnostic().length());
        assertEquals(ascii.diagnostic(), notice.apply(ascii.diagnostic()).diagnostic(),
                "sanitization should be stable when envelopes are reconstructed");
    }

    @Test
    void duplicateExecutorCallbacksCannotReplayControlOrCrossBatchBoundaries() {
        java.util.ArrayDeque<Runnable> jobs = new java.util.ArrayDeque<>();
        java.util.concurrent.Executor duplicate = callback -> {
            jobs.addLast(callback);
            jobs.addLast(callback); // Broken executor replays identical runnable.
        };
        var supervisor = ActorRuntime.SupervisorId.create();
        var group = ActorRuntime.ActorGroupId.create();
        var actor = ActorRuntime.ActorId.create();
        var lane = new SupervisorFailureControl(supervisor, group, duplicate, 40);
        var invocations = new AtomicInteger();
        lane.install(notice -> invocations.incrementAndGet());
        for (int i = 0; i < 34; i++) {
            lane.publish(new SupervisorFailureControl.Notice(
                    supervisor, group, actor, java.util.Optional.empty(),
                    SupervisorFailureControl.Kind.TERMINAL_FAILURE, "Panic", "test"));
        }
        assertEquals(2, jobs.size());
        jobs.removeFirst().run(); // First 32-notice CONTROL batch.
        assertEquals(32, invocations.get());
        assertEquals(2, lane.queued());

        jobs.removeFirst().run(); // Late duplicate for previous generation.
        assertEquals(32, invocations.get(), "old batch drained current notices");
        assertEquals(2, lane.queued());
        assertEquals(2, jobs.size(), "next batch was never scheduled");

        jobs.removeFirst().run();
        jobs.removeFirst().run(); // Duplicate of latest batch.
        assertEquals(34, invocations.get(), "CONTROL delivered a notice twice");
        assertEquals(0, lane.queued());
        assertEquals(0, lane.undelivered());
        assertTrue(lane.healthy());
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
