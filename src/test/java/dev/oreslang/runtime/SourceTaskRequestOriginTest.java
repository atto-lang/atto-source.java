package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host-level regression for request origin provenance, NOT real .atto HTTP
 * guest execution. The scheduler may enqueue on a foreign producer but must
 * never capture that producer's request identity as the suspended Task's own.
 */
@Timeout(20)
final class SourceTaskRequestOriginTest {
    @Test
    void originSurvivesAwaitAndForeignProducerDoesNotOverwriteIt() throws Exception {
        ConcurrentLinkedQueue<Runnable> work = new ConcurrentLinkedQueue<>();
        AtomicLong admittedMail = new AtomicLong(11);
        AtomicInteger turns = new AtomicInteger();
        try (OresScheduler scheduler = OresScheduler.runtimeOwned(
                "origin-test", 1, work::add, Runnable::run, admittedMail::get)) {
            OresFuture<Integer> gate = new OresFuture<>();
            OresFuture<Integer> completed = scheduler.start(resume -> {
                if (turns.getAndIncrement() == 0) return OresScheduler.await(gate);
                assertEquals(123, resume.value());
                return OresScheduler.done(456);
            });

            Runnable first = work.poll();
            assertInstanceOf(OresScheduler.RuntimeTaskTurn.class, first);
            var original = (OresScheduler.RuntimeTaskTurn) first;
            assertTrue(original.issuedBy(scheduler));
            assertEquals(11, original.admittedMailSequence());
            long logicalExecution = original.logicalExecution().id().execution();
            original.run();
            assertThrows(IllegalStateException.class, original::run,
                    "a source turn is admitted at most once");

            Thread producer = Thread.ofPlatform().start(() -> {
                admittedMail.set(99);
                gate.completeFromRuntime(123);
            });
            producer.join(5000);
            assertFalse(producer.isAlive());

            Runnable next = work.poll();
            assertInstanceOf(OresScheduler.RuntimeTaskTurn.class, next);
            var resumed = (OresScheduler.RuntimeTaskTurn) next;
            assertTrue(resumed.issuedBy(scheduler));
            assertEquals(11, resumed.admittedMailSequence(),
                    "callback's current mail must not overwrite the admitted origin");
            assertEquals(logicalExecution, resumed.logicalExecution().id().execution(),
                    "logical Task identity must survive await");
            resumed.run();
            assertEquals(456, completed.get(5, TimeUnit.SECONDS));
            assertEquals(2, turns.get());
            assertTrue(work.isEmpty());
        }
    }

    @Test
    void nestedTaskAdmissionDoesNotBorrowParentRequestOrigin() throws Exception {
        ConcurrentLinkedQueue<Runnable> work = new ConcurrentLinkedQueue<>();
        AtomicReference<OresFuture<Integer>> child = new AtomicReference<>();
        try (OresScheduler scheduler = OresScheduler.runtimeOwned(
                "no-implicit-inherit", 1, work::add, Runnable::run, () -> 42L)) {
            OresFuture<Integer> parent = scheduler.start(resume -> {
                child.set(scheduler.start(other -> OresScheduler.done(7)));
                return OresScheduler.done(3);
            });
            var parentTurn = (OresScheduler.RuntimeTaskTurn) work.remove();
            assertEquals(42L, parentTurn.admittedMailSequence());
            parentTurn.run();
            var childTurn = (OresScheduler.RuntimeTaskTurn) work.remove();
            assertEquals(-1L, childTurn.admittedMailSequence(),
                    "new child Task needs an explicit snapshot/lease");
            assertNotEquals(parentTurn.logicalExecution().id().execution(),
                    childTurn.logicalExecution().id().execution());
            childTurn.run();
            assertEquals(3, parent.get(5, TimeUnit.SECONDS));
            assertEquals(7, child.get().get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void onlyExactMailboxPayloadCanBeUsedAsHttpClaimToken() throws Exception {
        AtomicReference<String> previous = new AtomicReference<>();
        AtomicInteger checked = new AtomicInteger();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (payload, context) -> {
                assertThrows(SecurityException.class,
                        () -> runtime.requireCurrentHttpTransferToken("different-mail"));
                String old = previous.get();
                if (old != null) {
                    assertThrows(SecurityException.class,
                            () -> runtime.requireCurrentHttpTransferToken(old));
                }
                runtime.requireCurrentHttpTransferToken(payload);
                previous.set(payload);
                checked.incrementAndGet();
            });
            runtime.registerPendingHttpTransfer("token-one", actor);
            runtime.registerPendingHttpTransfer("token-two", actor);
            actor.send("token-one");
            actor.send("token-two");
            actor.drain().get(5, TimeUnit.SECONDS);
            assertEquals(2, checked.get());
            // A token is irrevocably bound to its original mailbox delivery,
            // even if its exchange has not yet been claimed/released.
            assertThrows(SecurityException.class, () -> actor.send("token-one"));
            runtime.retirePendingHttpTransfer("token-one");
            runtime.retirePendingHttpTransfer("token-two");
            actor.stop();
            actor.getDone().get(5, TimeUnit.SECONDS);
            assertThrows(SecurityException.class,
                    () -> runtime.requireCurrentHttpTransferToken("token-two"),
                    "callbacks outside an active mailbox do not carry request authority");
        }
    }

    @Test
    void wrongActorDeliveryCannotStealOriginalHttpTransferSequence() throws Exception {
        CountDownLatch wrongActorMail = new CountDownLatch(1);
        CountDownLatch intendedMail = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var intended = runtime.<String>spawnShared(() -> (token, ctx) -> {
                try {
                    runtime.requireCurrentHttpTransferToken(token);
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    intendedMail.countDown();
                }
            });
            var wrongActor = runtime.<String>spawnShared(() -> (token, ctx) -> {
                try {
                    assertThrows(SecurityException.class,
                            () -> runtime.requireCurrentHttpTransferToken(token));
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    wrongActorMail.countDown();
                }
            });
            runtime.registerPendingHttpTransfer("one-shot", intended);
            // A token string sent to another actor must NOT consume the
            // transfer's original destination/sequence admission.
            wrongActor.send("one-shot");
            assertTrue(wrongActorMail.await(5, TimeUnit.SECONDS));
            intended.send("one-shot");
            assertTrue(intendedMail.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertThrows(SecurityException.class, () -> intended.send("one-shot"),
                    "a later receive must never reuse the same unclaimed token");
            runtime.retirePendingHttpTransfer("one-shot");
            wrongActor.stop();
            intended.stop();
            wrongActor.getDone().get(5, TimeUnit.SECONDS);
            intended.getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void actorCannotForgeTheFirstSupervisorHttpTransferDelivery() throws Exception {
        CountDownLatch forgedAttempt = new CountDownLatch(1);
        CountDownLatch genuineDelivery = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var destination = runtime.<String>spawnShared(() -> (token, ctx) -> {
                try {
                    runtime.requireCurrentHttpTransferToken(token);
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    genuineDelivery.countDown();
                }
            });
            var forger = runtime.<String>spawnShared(() -> (token, ctx) -> {
                try {
                    assertThrows(SecurityException.class,
                            () -> destination.send(token),
                            "only a supervisor may enqueue the first HTTP transfer");
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    forgedAttempt.countDown();
                }
            });
            runtime.registerPendingHttpTransfer("supervisor-token", destination);
            forger.send("supervisor-token");
            assertTrue(forgedAttempt.await(5, TimeUnit.SECONDS));
            // A rejected actor-origin attempt must not consume the admission.
            destination.send("supervisor-token");
            assertTrue(genuineDelivery.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            runtime.retirePendingHttpTransfer("supervisor-token");
            forger.stop();
            destination.stop();
            forger.getDone().get(5, TimeUnit.SECONDS);
            destination.getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void genericFutureContinuationNeverReceivesHttpMailDispatchMarker() throws Exception {
        // Package-private host test introspection only. This ThreadLocal is a
        // temporary DISPATCH marker, never request-local authority or a guest
        // API. Generic Future continuations must not receive it simply
        // because ContinuationTarget saved the originating mail sequence.
        Field field = ActorRuntime.class.getDeclaredField("CURRENT_HTTP_MAIL_SEQUENCE");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        ThreadLocal<Long> marker = (ThreadLocal<Long>) field.get(null);
        CountDownLatch registered = new CountDownLatch(1);
        CountDownLatch callbackObserved = new CountDownLatch(1);
        OresFuture<Integer> producer = new OresFuture<>();
        AtomicReference<Long> realMail = new AtomicReference<>();
        AtomicReference<Long> callbackMail = new AtomicReference<>();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (payload, context) -> {
                realMail.set(marker.get());
                var target = runtime.captureCurrentContinuationTarget();
                runtime.enqueueOnCompletion(producer, target, (value, failure) -> {
                    try {
                        callbackMail.set(marker.get());
                        assertNull(failure);
                        assertEquals(7, value);
                    } catch (Throwable error) {
                        callbackFailure.set(error);
                    } finally {
                        callbackObserved.countDown();
                    }
                });
                registered.countDown();
            });
            actor.send("user-mail");
            assertTrue(registered.await(5, TimeUnit.SECONDS));
            assertNotNull(realMail.get(), "original user mail carries its real sequence");
            Thread foreignProducer = Thread.ofPlatform().start(
                    () -> producer.completeFromRuntime(7));
            foreignProducer.join(5000);
            assertFalse(foreignProducer.isAlive());
            assertTrue(callbackObserved.await(5, TimeUnit.SECONDS),
                    "generic continuation was not admitted");
            assertNull(callbackMail.get(),
                    "generic callback may not inherit HTTP dispatch authority");
            assertNull(callbackFailure.get());
            actor.stop();
            actor.getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void unboundTaskRemainsUnboundAcrossAwaitAndProducerChanges() throws Exception {
        ConcurrentLinkedQueue<Runnable> work = new ConcurrentLinkedQueue<>();
        AtomicLong admission = new AtomicLong(-1);
        try (OresScheduler scheduler = OresScheduler.runtimeOwned(
                "unbound", 1, work::add, Runnable::run, admission::get)) {
            OresFuture<Void> gate = new OresFuture<>();
            AtomicInteger turns = new AtomicInteger();
            OresFuture<Integer> result = scheduler.start(resume -> {
                if (turns.getAndIncrement() == 0) return OresScheduler.await(gate);
                return OresScheduler.done(8);
            });
            var first = (OresScheduler.RuntimeTaskTurn) work.remove();
            assertEquals(-1L, first.admittedMailSequence());
            first.run();
            admission.set(876);
            gate.completeFromRuntime(null);
            var second = (OresScheduler.RuntimeTaskTurn) work.remove();
            assertEquals(-1L, second.admittedMailSequence());
            second.run();
            assertEquals(8, result.get(5, TimeUnit.SECONDS));
        }
    }
}
