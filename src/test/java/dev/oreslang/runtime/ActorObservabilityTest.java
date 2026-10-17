package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
final class ActorObservabilityTest {

    @Test
    void snapshotExplainsMailboxPressureAndRuntimeStatsCountOverloadExactly()
            throws Exception {
        IsolatePolicy base = IsolatePolicy.developer();
        IsolatePolicy oneQueuedUserMessage = new IsolatePolicy(
                base.capabilities(),
                base.maxHeapBytes(),
                1,
                Duration.ofSeconds(5),
                false);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime(base)) {
            var actor = runtime.<Integer>spawnShared(
                    oneQueuedUserMessage,
                    () -> (message, context) -> {
                        if (message == 1) {
                            entered.countDown();
                            if (!release.await(5, TimeUnit.SECONDS))
                                throw new AssertionError("held actor turn timed out");
                        }
                    });

            actor.send(1);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            actor.send(2);
            assertThrows(
                    ActorRuntime.ActorMailboxFullException.class,
                    () -> actor.send(3));

            ActorRuntime.ActorSnapshot snapshot =
                    runtime.snapshot(actor).orElseThrow();
            assertEquals(actor.id(), snapshot.actorId());
            assertEquals(ActorRuntime.ActorKind.SHARED, snapshot.actorKind());
            assertEquals(Optional.empty(), snapshot.parentId());
            assertTrue(snapshot.carrierActive());
            assertEquals(1, snapshot.activeTurns());
            assertEquals(1, snapshot.queuedUserMessages());
            assertEquals(0, snapshot.queuedContinuations());
            assertEquals(2L, snapshot.acceptedUserMessages());
            assertEquals(0L, snapshot.processedUserMessages());
            assertFalse(snapshot.stopped());
            assertFalse(snapshot.finalized());

            ActorRuntime.ActorRuntimeStats pressure = runtime.runtimeStats();
            assertEquals(1, pressure.liveActors());
            assertEquals(2L, pressure.userMessagesAccepted());
            assertEquals(1L, pressure.mailboxFullRejections());
            assertEquals(0L, pressure.continuationAdmissionFailures());

            release.countDown();
            actor.drain().get(5, TimeUnit.SECONDS);
            actor.stop();
            actor.getDone().get(5, TimeUnit.SECONDS);

            assertTrue(runtime.snapshot(actor).isEmpty(),
                    "removed actors cannot fabricate a stale live snapshot");
            ActorRuntime.ActorRuntimeStats terminal = runtime.runtimeStats();
            assertEquals(0, terminal.liveActors());
            assertEquals(1L, terminal.normalTerminations());
            assertEquals(0L, terminal.cancelledTerminations());
            assertEquals(0L, terminal.failedTerminations());

            ActorRuntime.ActorTerminationEvent event =
                    runtime.pollTerminationEvent().orElseThrow();
            assertEquals(ActorRuntime.ActorTerminationKind.NORMAL,
                    event.terminationKind());
            assertEquals(actor.id(), event.actorId());
            assertEquals(2L, event.acceptedUserMessages());
            assertEquals(2L, event.processedUserMessages());
            assertEquals(0, event.abandonedUserMessages());
            assertEquals(0, event.abandonedContinuations());
            assertEquals("", event.failureType());
            assertEquals("", event.failureMessage());
            assertTrue(runtime.pollTerminationEvent().isEmpty());
        } finally {
            release.countDown();
        }
    }

    @Test
    void failedActiveMessageAndQueuedTailAreDistinguishedInTerminalRecord()
            throws Exception {
        CountDownLatch active = new CountDownLatch(1);
        CountDownLatch failNow = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnShared(
                    () -> (message, context) -> {
                        active.countDown();
                        if (!failNow.await(5, TimeUnit.SECONDS))
                            throw new AssertionError("failure gate timed out");
                        throw new IllegalStateException("boom");
                    });

            actor.send(1);
            assertTrue(active.await(3, TimeUnit.SECONDS));
            actor.send(2);
            failNow.countDown();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));

            ActorRuntime.ActorTerminationEvent event =
                    runtime.pollTerminationEvent().orElseThrow();
            assertEquals(ActorRuntime.ActorTerminationKind.FAILED,
                    event.terminationKind());
            assertEquals(2L, event.acceptedUserMessages());
            assertEquals(0L, event.processedUserMessages(),
                    "the throwing active message is accepted but never marked processed");
            assertEquals(1, event.abandonedUserMessages(),
                    "queued tail is reported separately from the active failed message");
            assertEquals(IllegalStateException.class.getName(), event.failureType());
            assertEquals(1L, runtime.runtimeStats().failedTerminations());
        } finally {
            failNow.countDown();
        }
    }

    @Test
    void terminalDiagnosticsNeverInvokeHostileThrowableFormatting() throws Exception {
        AtomicInteger messageCalls = new AtomicInteger();
        final class HostileFailure extends RuntimeException {
            @Override public String getMessage() {
                messageCalls.incrementAndGet();
                throw new AssertionError(
                        "termination diagnostics must not execute Throwable formatting");
            }
            @Override public String toString() {
                messageCalls.incrementAndGet();
                throw new AssertionError(
                        "termination diagnostics must not execute Throwable.toString");
            }
        }

        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(
                    () -> (message, context) -> {
                        throw new HostileFailure();
                    });
            actor.send("fail");
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, messageCalls.get());

            ActorRuntime.ActorTerminationEvent event =
                    runtime.pollTerminationEvent().orElseThrow();
            assertEquals(ActorRuntime.ActorTerminationKind.FAILED,
                    event.terminationKind());
            assertEquals(HostileFailure.class.getName(), event.failureType());
            assertEquals("", event.failureMessage());
            assertEquals(0, messageCalls.get());

            ActorRuntime.ActorRuntimeStats stats = runtime.runtimeStats();
            assertEquals(1L, stats.failedTerminations());
            assertEquals(0L, stats.normalTerminations());
            assertEquals(0L, stats.cancelledTerminations());
        }
    }

    @Test
    void childCancellationEventPrecedesParentFinalizationAndKeepsParentIdentity()
            throws Exception {
        CountDownLatch childCreated = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<ActorRuntime.ActorRef<String>> child =
                new java.util.concurrent.atomic.AtomicReference<>();

        try (ActorRuntime runtime = new ActorRuntime()) {
            var parent = runtime.<String>spawnShared(
                    () -> (message, context) -> {
                        ActorRuntime.ActorRef<String> created =
                                context.runtime().spawnPrivate(
                                        () -> (childMessage, childContext) -> { });
                        child.set(created);
                        childCreated.countDown();
                        context.self().stop();
                    });

            parent.send("spawn");
            assertTrue(childCreated.await(3, TimeUnit.SECONDS));
            assertTrue(parent.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(child.get().awaitTermination(5, TimeUnit.SECONDS));

            ActorRuntime.ActorTerminationEvent first =
                    runtime.pollTerminationEvent().orElseThrow();
            ActorRuntime.ActorTerminationEvent second =
                    runtime.pollTerminationEvent().orElseThrow();
            assertTrue(first.sequence() < second.sequence());

            assertEquals(child.get().id(), first.actorId());
            assertEquals(Optional.of(parent.id()), first.parentId());
            assertEquals(ActorRuntime.ActorTerminationKind.CANCELLED,
                    first.terminationKind());

            assertEquals(parent.id(), second.actorId());
            assertEquals(Optional.empty(), second.parentId());
            assertEquals(ActorRuntime.ActorTerminationKind.NORMAL,
                    second.terminationKind());
            assertTrue(runtime.pollTerminationEvent().isEmpty());
        }
    }

    @Test
    void fullDiagnosticJournalDropsRecordWithoutDelayingActorFinalization()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            Field queueField = ActorRuntime.class.getDeclaredField("supervisionEvents");
            Field countField = ActorRuntime.class.getDeclaredField("supervisionEventsQueued");
            queueField.setAccessible(true);
            countField.setAccessible(true);

            @SuppressWarnings("unchecked")
            ConcurrentLinkedQueue<ActorRuntime.ActorTerminationEvent> queue =
                    (ConcurrentLinkedQueue<ActorRuntime.ActorTerminationEvent>)
                            queueField.get(runtime);
            AtomicInteger queued = (AtomicInteger) countField.get(runtime);

            for (int i = 0; i < 4_096; ++i) {
                queue.add(new ActorRuntime.ActorTerminationEvent(
                        i,
                        ActorRuntime.ActorId.create(),
                        1L,
                        ActorRuntime.ActorKind.SHARED,
                        Optional.empty(),
                        ActorRuntime.ActorTerminationKind.NORMAL,
                        0L,
                        0L,
                        0,
                        0,
                        "",
                        ""));
            }
            queued.set(4_096);

            var actor = runtime.<String>spawnShared(
                    () -> (message, context) -> { });
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS),
                    "diagnostics backpressure must never become lifecycle backpressure");
            assertEquals(1L, runtime.supervisionEventsDropped());
            assertEquals(1L, runtime.runtimeStats().normalTerminations(),
                    "termination accounting is authoritative even if its journal record is dropped");
        }
    }

    @Test
    void exhaustedDiagnosticSequenceFailsClosedWithoutWrappingOrBlockingTermination()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            Field sequenceField =
                    ActorRuntime.class.getDeclaredField("supervisionEventSequence");
            sequenceField.setAccessible(true);
            var sequence =
                    (java.util.concurrent.atomic.AtomicLong) sequenceField.get(runtime);
            sequence.set(Long.MAX_VALUE);

            var actor = runtime.<String>spawnShared(
                    () -> (message, context) -> { });
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(1L, runtime.supervisionEventsDropped());
            assertTrue(runtime.pollTerminationEvent().isEmpty(),
                    "sequence exhaustion must drop rather than wrap into negative ordering");
            assertEquals(1L, runtime.runtimeStats().normalTerminations());
        }
    }
}
