package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.ConcurrentLinkedQueue;
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
            actor.send("token-one");
            actor.send("token-two");
            actor.drain().get(5, TimeUnit.SECONDS);
            assertEquals(2, checked.get());
            actor.stop();
            actor.getDone().get(5, TimeUnit.SECONDS);
            assertThrows(SecurityException.class,
                    () -> runtime.requireCurrentHttpTransferToken("token-two"),
                    "callbacks outside an active mailbox do not carry request authority");
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
