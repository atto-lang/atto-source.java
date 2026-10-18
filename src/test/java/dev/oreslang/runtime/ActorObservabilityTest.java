package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Optional;
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
            assertEquals(0L, pressure.userMessagesProcessed());
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
            assertEquals(2L, terminal.userMessagesProcessed());
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
            ActorRuntime.ActorTerminalState retained =
                    actor.terminalState().orElseThrow();
            assertEquals(event.actorId(), retained.actorId());
            assertEquals(event.terminationKind(), retained.terminationKind());
            assertEquals(event.acceptedUserMessages(), retained.acceptedUserMessages());
            assertEquals(event.processedUserMessages(), retained.processedUserMessages());
            assertTrue(runtime.pollTerminationEvent().isEmpty());
        } finally {
            release.countDown();
        }
    }

    @Test
    void terminalJournalStatsAccountPublishedRecordsWithoutMutatingDrainOrder()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var first = runtime.<String>spawnShared(
                    () -> (message, context) -> { });
            var second = runtime.<String>spawnShared(
                    () -> (message, context) -> { });
            first.stop();
            second.stop();
            assertTrue(first.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(second.awaitTermination(5, TimeUnit.SECONDS));

            var before = runtime.terminalJournalStats();
            assertEquals(2L, before.published());
            assertEquals(2, before.backlog());
            assertEquals(0L, before.nextSequence());
            assertEquals(0L, before.droppedTotal());

            assertEquals(0L, runtime.pollTerminationEvent().orElseThrow().sequence());
            var middle = runtime.terminalJournalStats();
            assertEquals(2L, middle.published(),
                    "published count is lifetime admission, not current backlog");
            assertEquals(1, middle.backlog());
            assertEquals(1L, middle.nextSequence());

            assertEquals(1L, runtime.pollTerminationEvent().orElseThrow().sequence());
            var after = runtime.terminalJournalStats();
            assertEquals(0, after.backlog());
            assertEquals(2L, after.nextSequence());
            assertEquals(2L, after.published());
            assertEquals(0L, after.droppedTotal());
        }
    }

    @Test
    void sendRejectionTelemetrySeparatesSealedTerminatedAndForeignRefs()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             ActorRuntime foreign = new ActorRuntime()) {
            var sealed = runtime.<String>spawnShared(
                    () -> (message, context) -> { });
            sealed.getReady().get(3, TimeUnit.SECONDS);
            sealed.gracefulStop();
            assertThrows(ActorRuntime.ActorSealedException.class,
                    () -> sealed.send("late"));
            sealed.getDone().get(3, TimeUnit.SECONDS);

            var dead = runtime.<String>spawnShared(
                    () -> (message, context) -> { });
            dead.stop();
            assertTrue(dead.awaitTermination(3, TimeUnit.SECONDS));
            assertThrows(ActorRuntime.ActorTerminatedException.class,
                    () -> dead.send("late"));

            var foreignRef = foreign.<String>spawnShared(
                    () -> (message, context) -> { });
            assertThrows(ActorRuntime.ActorInvalidReferenceException.class,
                    () -> runtime.send(foreignRef, "wrong runtime"));
            foreignRef.stop();

            ActorRuntime.ActorRuntimeStats stats = runtime.runtimeStats();
            assertEquals(1L, stats.sealedSendRejections());
            assertEquals(1L, stats.terminatedSendRejections());
            assertEquals(1L, stats.invalidReferenceSendRejections());
            assertEquals(0L, stats.lifecycleFenceSendRejections());
            assertEquals(0L, stats.mailboxFullRejections());
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
    void childFailureVisibilityDoesNotDependOnParentMailboxProgress()
            throws Exception {
        CountDownLatch childCreated = new CountDownLatch(1);
        CountDownLatch parentBlocked = new CountDownLatch(1);
        CountDownLatch releaseParent = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<ActorRuntime.ActorRef<String>> child =
                new java.util.concurrent.atomic.AtomicReference<>();

        try (ActorRuntime runtime = new ActorRuntime()) {
            var parent = runtime.<String>spawnShared(
                    () -> (message, context) -> {
                        if ("setup".equals(message)) {
                            child.set(context.runtime().spawnPrivate(
                                    () -> (childMessage, childContext) -> {
                                        throw new IllegalStateException("child boom");
                                    }));
                            childCreated.countDown();
                            return;
                        }
                        if ("hold".equals(message)) {
                            parentBlocked.countDown();
                            if (!releaseParent.await(5, TimeUnit.SECONDS))
                                throw new AssertionError("parent hold timed out");
                        }
                    });

            parent.send("setup");
            assertTrue(childCreated.await(3, TimeUnit.SECONDS));
            parent.drain().get(3, TimeUnit.SECONDS);

            parent.send("hold");
            assertTrue(parentBlocked.await(3, TimeUnit.SECONDS));
            child.get().send("fail");
            assertTrue(child.get().awaitTermination(3, TimeUnit.SECONDS));

            ActorRuntime.ActorTerminationEvent event =
                    runtime.pollTerminationEvent().orElseThrow();
            assertEquals(child.get().id(), event.actorId());
            assertEquals(Optional.of(parent.id()), event.parentId());
            assertEquals(ActorRuntime.ActorTerminationKind.FAILED,
                    event.terminationKind());
            assertTrue(parent.isAlive(),
                    "child terminal observability cannot require parent mailbox progress");
            assertTrue(runtime.snapshot(parent).orElseThrow().carrierActive());

            releaseParent.countDown();
            parent.stop();
            assertTrue(parent.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            releaseParent.countDown();
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
            // Publish one real immutable payload, then use the exact same
            // object as inert filler. The production journal enforces its
            // bound under its own try-lock admission; this fixture fills the
            // quiescent deque directly to exercise the full-capacity path.
            var seed = runtime.<String>spawnShared(
                    () -> (message, context) -> { });
            seed.stop();
            assertTrue(seed.awaitTermination(5, TimeUnit.SECONDS));

            Field eventsField =
                    ActorRuntime.class.getDeclaredField("supervisionEvents");
            eventsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            var events = (java.util.ArrayDeque<Object>)
                    eventsField.get(runtime);
            Object payload = events.peekFirst();
            assertNotNull(payload);
            while (events.size() < 4_096) {
                events.addLast(payload);
            }
            assertEquals(4_096, events.size());

            var actor = runtime.<String>spawnShared(
                    () -> (message, context) -> { });
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS),
                    "diagnostics backpressure must never become lifecycle backpressure");
            assertEquals(1L, runtime.supervisionEventsDropped());
            var journal = runtime.terminalJournalStats();
            assertEquals(4_096, journal.backlog());
            assertEquals(4_096, journal.capacity());
            assertEquals(1L, journal.droppedCapacity());
            assertEquals(0L, journal.droppedContention());
            assertEquals(0L, journal.droppedSequenceExhaustion());
            assertEquals(journal.droppedTotal(), runtime.supervisionEventsDropped());
            assertEquals(2L, runtime.runtimeStats().normalTerminations(),
                    "termination accounting is authoritative even if its journal record is dropped");
            ActorRuntime.ActorTerminalState retained =
                    actor.terminalState().orElseThrow();
            assertEquals(actor.id(), retained.actorId());
            assertEquals(ActorRuntime.ActorTerminationKind.NORMAL,
                    retained.terminationKind());
            assertEquals(0L, retained.acceptedUserMessages());
            assertEquals(0L, retained.processedUserMessages());
        }
    }

    @Test
    void concurrentActorDeathsDrainInExactMonotonicJournalOrder()
            throws Exception {
        final int count = 128;
        try (ActorRuntime runtime = new ActorRuntime();
             var killers = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var actors = new java.util.ArrayList<ActorRuntime.ActorRef<String>>(count);
            for (int i = 0; i < count; ++i) {
                actors.add(runtime.<String>spawnShared(
                        () -> (message, context) -> { }));
            }

            var start = new CountDownLatch(1);
            var stopped = new CountDownLatch(count);
            for (var actor : actors) {
                killers.execute(() -> {
                    try {
                        start.await();
                        actor.stop();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        stopped.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(stopped.await(5, TimeUnit.SECONDS));
            for (var actor : actors)
                assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));

            var seenActors = new java.util.HashSet<ActorRuntime.ActorId>();
            for (long expected = 0; expected < count; ++expected) {
                ActorRuntime.ActorTerminationEvent event =
                        runtime.pollTerminationEvent().orElseThrow();
                assertEquals(expected, event.sequence(),
                        "terminal journal must never expose a later published record first");
                assertTrue(seenActors.add(event.actorId()),
                        "one actor must produce exactly one terminal record");
            }
            assertEquals(count, seenActors.size());
            assertTrue(runtime.pollTerminationEvent().isEmpty());
            assertEquals(0L, runtime.supervisionEventsDropped());
        }
    }


    @Test
    void schedulerPressureSnapshotSeparatesPhysicalAndLogicalBacklog() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 1, 8))) {
            var first = runtime.<String>spawnShared(() -> (message, context) -> {
                entered.countDown();
                release.await(5, TimeUnit.SECONDS);
            });
            var second = runtime.<String>spawnShared(() -> (message, context) -> { });
            first.send("hold");
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            second.send("wait");

            ActorRuntime.ActorSchedulerSnapshot pressure = runtime.schedulerSnapshot();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (pressure.sharedDomain().queuedTasks() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(1);
                pressure = runtime.schedulerSnapshot();
            }
            assertEquals(1, pressure.sharedDomain().maximumCarriers());
            assertEquals(1, pressure.sharedDomain().startedCarriers());
            assertTrue(pressure.sharedDomain().activeCarriers() >= 1);
            assertTrue(pressure.sharedDomain().queuedTasks() >= 1);
            assertEquals(1, runtime.snapshot(second).orElseThrow().queuedUserMessages());
            assertEquals(0, pressure.lifecycleObserverCarriers());
            assertEquals(4_096, pressure.terminalJournalCapacity());
            assertEquals(0, pressure.terminalJournalBacklog());
            assertEquals(1_024, pressure.watchdogNoticeCapacity());
            assertEquals(0, pressure.watchdogNoticeBacklog());

            release.countDown();
            first.stop();
            second.stop();
            assertTrue(first.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(second.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    @Test
    void schedulerSnapshotRemainsSafeDuringConcurrentActorChurn() throws Exception {
        final int actorTotal = 256;
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 2, 8, actorTotal + 32));
             var workers = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var start = new CountDownLatch(1);
            var finished = new CountDownLatch(actorTotal);
            var failures = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
            var sampling = new java.util.concurrent.atomic.AtomicBoolean(true);
            Thread sampler = Thread.ofPlatform().daemon(true).start(() -> {
                try {
                    while (sampling.get()) {
                        var snap = runtime.schedulerSnapshot();
                        assertTrue(snap.privateDomain().queuedTasks()
                                <= snap.privateDomain().queueCapacity());
                        assertTrue(snap.sharedDomain().queuedTasks()
                                <= snap.sharedDomain().queueCapacity());
                        assertTrue(snap.untrustedDomain().queuedTasks()
                                <= snap.untrustedDomain().queueCapacity());
                        assertTrue(snap.terminalJournalBacklog()
                                <= snap.terminalJournalCapacity());
                        assertTrue(snap.watchdogNoticeBacklog()
                                <= snap.watchdogNoticeCapacity());
                    }
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            });

            for (int i = 0; i < actorTotal; ++i) {
                workers.execute(() -> {
                    try {
                        start.await();
                        var actor = runtime.<Integer>spawnShared(
                                () -> (message, context) -> context.self().stop());
                        actor.send(1);
                        if (!actor.awaitTermination(5, TimeUnit.SECONDS))
                            throw new AssertionError("actor churn termination timed out");
                    } catch (Throwable failure) {
                        failures.add(failure);
                    } finally {
                        finished.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(finished.await(15, TimeUnit.SECONDS));
            sampling.set(false);
            sampler.join(3_000L);
            assertFalse(sampler.isAlive());
            assertTrue(failures.isEmpty(), () -> "snapshot/churn failures: " + failures);
            assertEquals(0, runtime.runtimeStats().liveActors());
            assertEquals(actorTotal, runtime.runtimeStats().normalTerminations());
        }
    }

    @Test
    void concurrentDiagnosticConsumersPreservePublishedFifoSequenceOrder()
            throws Exception {
        final int count = 512;
        final int consumers = 32;
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 2, 8, count + 32))) {
            var actors = new java.util.ArrayList<ActorRuntime.ActorRef<String>>(count);
            for (int i = 0; i < count; ++i) {
                var actor = runtime.<String>spawnShared(
                        () -> (message, context) -> { });
                actors.add(actor);
                actor.stop();
            }
            for (var actor : actors)
                assertTrue(actor.awaitTermination(8, TimeUnit.SECONDS));

            Field eventsField =
                    ActorRuntime.class.getDeclaredField("supervisionEvents");
            eventsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            var events = (java.util.ArrayDeque<Object>)
                    eventsField.get(runtime);
            assertEquals(count, events.size());

            Object firstPayload = events.peekFirst();
            assertNotNull(firstPayload);
            var actorIdAccessor = firstPayload.getClass().getDeclaredMethod("actorId");
            actorIdAccessor.setAccessible(true);
            var expected = new java.util.ArrayList<ActorRuntime.ActorId>(count);
            for (Object payload : events) {
                expected.add((ActorRuntime.ActorId) actorIdAccessor.invoke(payload));
            }

            var observed = new java.util.concurrent.ConcurrentHashMap<
                    Long, ActorRuntime.ActorId>();
            var remaining = new java.util.concurrent.atomic.AtomicInteger(count);
            var start = new CountDownLatch(1);
            var finished = new CountDownLatch(consumers);
            var failures =
                    new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
            try (var drainers =
                    java.util.concurrent.Executors.newFixedThreadPool(consumers)) {
                for (int i = 0; i < consumers; ++i) {
                    drainers.execute(() -> {
                        try {
                            start.await();
                            while (remaining.get() > 0) {
                                Optional<ActorRuntime.ActorTerminationEvent> next =
                                        runtime.pollTerminationEvent();
                                if (next.isEmpty()) {
                                    Thread.yield();
                                    continue;
                                }
                                var event = next.orElseThrow();
                                if (observed.putIfAbsent(
                                        event.sequence(), event.actorId()) != null) {
                                    throw new AssertionError(
                                            "duplicate terminal sequence "
                                                    + event.sequence());
                                }
                                remaining.decrementAndGet();
                            }
                        } catch (Throwable failure) {
                            failures.add(failure);
                        } finally {
                            finished.countDown();
                        }
                    });
                }
                start.countDown();
                assertTrue(finished.await(10, TimeUnit.SECONDS));
            }

            assertTrue(failures.isEmpty(),
                    () -> "concurrent journal drain failures: " + failures);
            assertEquals(0, remaining.get());
            assertEquals(count, observed.size());
            for (long sequence = 0; sequence < count; ++sequence) {
                assertEquals(
                        expected.get((int) sequence),
                        observed.get(sequence),
                        "sequence order must match FIFO publication order even "
                                + "with multiple supervisor consumers");
            }
            assertTrue(runtime.pollTerminationEvent().isEmpty());
            assertEquals(0L, runtime.supervisionEventsDropped());
        }
    }

    @Test
    void contendedTerminalDiagnosticLockDropsWithoutStallingFinalization()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            Field lockField =
                    ActorRuntime.class.getDeclaredField("supervisionEventQueueLock");
            lockField.setAccessible(true);
            var diagnosticLock =
                    (java.util.concurrent.locks.ReentrantLock) lockField.get(runtime);
            var held = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            Thread holder = new Thread(() -> {
                diagnosticLock.lock();
                try {
                    held.countDown();
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    diagnosticLock.unlock();
                }
            }, "terminal-diagnostic-lock-holder");
            holder.start();
            assertTrue(held.await(3, TimeUnit.SECONDS));

            try {
                var actor = runtime.<String>spawnShared(
                        () -> (message, context) -> { });
                actor.stop();
                assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS),
                        "diagnostic lock contention must not stall actor finalization");
                assertTrue(actor.terminalState().isPresent(),
                        "authoritative per-ref terminal state must survive a dropped journal event");
                assertEquals(1L, runtime.runtimeStats().normalTerminations());
                assertTrue(runtime.supervisionEventsDropped() >= 1L,
                        "contended diagnostic publication must be counted as a drop");
                var journal = runtime.terminalJournalStats();
                assertTrue(journal.droppedContention() >= 1L,
                        "operators must be able to distinguish producer contention from capacity pressure");
                assertEquals(0L, journal.droppedCapacity());
                assertEquals(0L, journal.droppedSequenceExhaustion());
                assertEquals(journal.droppedTotal(), runtime.supervisionEventsDropped());
            } finally {
                release.countDown();
                holder.join(3_000L);
            }
            assertFalse(holder.isAlive());
            assertTrue(runtime.pollTerminationEvent().isEmpty(),
                    "failed publication must not fabricate a journal entry");
        }
    }

    @Test
    void exhaustedDiagnosticSequenceFailsClosedWithoutWrappingOrBlockingTermination()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            Field sequenceField =
                    ActorRuntime.class.getDeclaredField("supervisionEventSequence");
            sequenceField.setAccessible(true);
            var sequence = (java.util.concurrent.atomic.AtomicLong)
                    sequenceField.get(runtime);
            sequence.set(Long.MAX_VALUE);

            var actor = runtime.<String>spawnShared(
                    () -> (message, context) -> { });
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(1L, runtime.supervisionEventsDropped());
            var journal = runtime.terminalJournalStats();
            assertEquals(Long.MAX_VALUE, journal.nextSequence());
            assertEquals(1L, journal.droppedSequenceExhaustion());
            assertEquals(0L, journal.droppedContention());
            assertEquals(0L, journal.droppedCapacity());
            assertEquals(1L, journal.droppedTotal());
            assertTrue(runtime.pollTerminationEvent().isEmpty(),
                    "sequence exhaustion must drop rather than wrap into negative ordering");
            assertEquals(1L, runtime.runtimeStats().normalTerminations());
            assertTrue(actor.terminalState().isPresent(),
                    "per-ref post-mortem survives global journal exhaustion");
        }
    }
}
