package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ActorGroupMailmanTest {
    @Test
    void concurrentEmittersUseOneSerializedControlMailmanWithOrderedSequence()
            throws Exception {
        try (OresVM vm = OresVM.create(Runnable::run);
             ActorRuntime runtime = new ActorRuntime(
                     IsolatePolicy.developer(),
                     new ActorRuntime.DispatcherConfig(2, 2, 8, 256),
                     ActorRuntime.TurnExecutor.direct(),
                     vm::executeControl)) {

            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();

            int messages = 96;
            CountDownLatch joined = new CountDownLatch(2);
            CountDownLatch delivered = new CountDownLatch(messages);
            AtomicInteger active = new AtomicInteger();
            AtomicInteger maxActive = new AtomicInteger();
            AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
            List<Long> sequences = Collections.synchronizedList(new ArrayList<>());
            Set<String> carrierNames = ConcurrentHashMap.newKeySet();

            ActorRuntime.ActorRef<Integer> sink = runtime.spawnShared(() -> (message, context) -> {
                delivered.countDown();
            });

            group.installMailman(128, (mail, context) -> {
                int now = active.incrementAndGet();
                maxActive.accumulateAndGet(now, Math::max);
                try {
                    if (!ActorRuntime.inActorGroupMailmanExecution()) {
                        callbackFailure.compareAndSet(
                                null,
                                new AssertionError("Mailman execution marker is missing"));
                    }
                    if (OresScheduler.current() != vm.rootScheduler()) {
                        callbackFailure.compareAndSet(
                                null,
                                new AssertionError("Mailman did not execute on VM root scheduler"));
                    }
                    carrierNames.add(Thread.currentThread().getName());
                    sequences.add(mail.sequence());
                    context.send(sink, (Integer) mail.message());
                } catch (Throwable failure) {
                    callbackFailure.compareAndSet(null, failure);
                } finally {
                    active.decrementAndGet();
                }
            });

            ActorRuntime.ActorRef<Integer> left = runtime.spawnShared(() -> (message, context) -> {
                if (message < 0) {
                    group.joinCurrent(join);
                    joined.countDown();
                } else {
                    group.emit(message);
                }
            });
            ActorRuntime.ActorRef<Integer> right = runtime.spawnShared(() -> (message, context) -> {
                if (message < 0) {
                    group.joinCurrent(join);
                    joined.countDown();
                } else {
                    group.emit(message);
                }
            });

            left.send(-1);
            right.send(-1);
            assertTrue(joined.await(5, TimeUnit.SECONDS));

            for (int i = 0; i < messages; i++) {
                (i % 2 == 0 ? left : right).send(i);
            }

            assertTrue(delivered.await(10, TimeUnit.SECONDS));
            assertNull(callbackFailure.get());
            assertEquals(1, maxActive.get(), "only one logical Mailman may execute at a time");
            assertEquals(messages, sequences.size());
            for (int i = 0; i < messages; i++) {
                assertEquals((long) i, sequences.get(i));
            }
            assertFalse(carrierNames.isEmpty());
            assertTrue(
                    carrierNames.stream().allMatch(name -> name.startsWith("ores-control-plane-")),
                    "Mailman must execute on CONTROL carriers, not actor dispatchers");
        }
    }

    @Test
    void controlMailmanDoesNotAcquireSupervisorOrGroupManagerAuthority()
            throws Exception {
        try (OresVM vm = OresVM.create(Runnable::run);
             ActorRuntime runtime = new ActorRuntime(
                     IsolatePolicy.developer(),
                     new ActorRuntime.DispatcherConfig(1, 1, 8, 64),
                     ActorRuntime.TurnExecutor.direct(),
                     vm::executeControl)) {

            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();
            group.events().defineTopic(
                    "mailman-authority",
                    ActorEventBus.DeliveryPolicy.LOSSY,
                    1);

            CountDownLatch joined = new CountDownLatch(1);
            CountDownLatch checked = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicReference<ActorRuntime.ActorRef<Integer>> emitterRef = new AtomicReference<>();

            group.installMailman(8, (mail, context) -> {
                try {
                    expectSecurity(
                            () -> runtime.controlHandle(emitterRef.get()),
                            "Mailman must not acquire supervisor ActorControlHandle authority");
                    expectSecurity(
                            runtime::createActorGroup,
                            "Mailman must not create ActorGroups as host/supervisor");
                    expectSecurity(
                            group::rotateJoinCapability,
                            "Mailman must not rotate ActorGroup authority");
                    expectSecurity(
                            () -> group.events().publishSystem("mailman-authority", 1),
                            "Mailman CONTROL carrier must not publish as system/supervisor");
                    expectSecurity(
                            () -> runtime.spawnShared(() -> (message, actor) -> { }),
                            "Mailman must not use host actor construction");
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                } finally {
                    checked.countDown();
                }
            });

            ActorRuntime.ActorRef<Integer> emitter = runtime.spawnShared(() -> (message, context) -> {
                if (message == 0) {
                    group.joinCurrent(join);
                    joined.countDown();
                } else {
                    group.emit(message);
                }
            });
            emitterRef.set(emitter);

            emitter.send(0);
            assertTrue(joined.await(5, TimeUnit.SECONDS));
            emitter.send(1);

            assertTrue(checked.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
        }
    }

    @Test
    void boundedOutboxFailsClosedAndGroupTeardownReleasesPayloadAccounting()
            throws Exception {
        AtomicReference<Runnable> admitted = new AtomicReference<>();
        AtomicReference<OresFuture<Void>> dispatch = new AtomicReference<>();

        ActorRuntime.ControlDispatcher pausedControl = task -> {
            if (!admitted.compareAndSet(null, task)) {
                throw new AssertionError("only one Mailman CONTROL task should be admitted");
            }
            OresFuture<Void> future = new OresFuture<>();
            dispatch.set(future);
            return future;
        };

        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8, 64),
                ActorRuntime.TurnExecutor.direct(),
                pausedControl)) {

            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();
            group.installMailman(2, (mail, context) -> { });

            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> expectedOverflow = new AtomicReference<>();

            ActorRuntime.ActorRef<Integer> emitter = runtime.spawnShared(() -> (message, context) -> {
                group.joinCurrent(join);
                try {
                    group.emit(List.of(1, 2, 3));
                    group.emit(List.of(4, 5, 6));
                    group.emit(List.of(7, 8, 9));
                } catch (IllegalStateException overflow) {
                    expectedOverflow.set(overflow);
                } finally {
                    context.self().stop();
                    done.countDown();
                }
            });

            emitter.send(1);
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertTrue(emitter.awaitTermination(5, TimeUnit.SECONDS));
            assertNotNull(expectedOverflow.get());
            assertTrue(expectedOverflow.get().getMessage().contains("outbox capacity"));
            assertEquals(2, group.mailmanOutboxSize());
            assertTrue(runtime.sharedMemoryBytes() > 0L);

            group.close();

            assertEquals(0, group.mailmanOutboxSize());
            assertEquals(
                    0L,
                    runtime.sharedMemoryBytes(),
                    "teardown must release all frozen queued Mailman payload reservations");

            // The admitted CONTROL task may arrive after close. It must be inert
            // and must not resurrect drained mail.
            Runnable task = admitted.get();
            assertNotNull(task);
            task.run();
            OresFuture<Void> future = dispatch.get();
            assertNotNull(future);
            future.completeFromRuntime(null);
            assertEquals(0L, runtime.sharedMemoryBytes());
        }
    }

    @Test
    void mixedSharedPrivateAndUntrustedMembersReachTheSameControlMailman() throws Exception {
        try (OresVM vm = OresVM.create(Runnable::run);
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                     new ActorRuntime.DispatcherConfig(1, 1, 8, 64),
                     ActorRuntime.TurnExecutor.direct(), vm::executeControl)) {
            var group = runtime.createActorGroup();
            var join = group.joinCapability();
            var shared = runtime.<ActorRuntime.ActorGroupId>spawnShared(
                    () -> ActorGroupMailmanTest::emitAndStop);
            var isolated = runtime.<ActorRuntime.ActorGroupId>spawnPrivate(
                    context -> ActorGroupMailmanTest::emitAndStop);
            var untrusted = runtime.<ActorRuntime.ActorGroupId>spawnUntrusted(
                    context -> ActorGroupMailmanTest::emitAndStop);
            Set<ActorRuntime.ActorId> senders = ConcurrentHashMap.newKeySet();
            CountDownLatch delivered = new CountDownLatch(3);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            group.installMailman(8, (mail, context) -> {
                try {
                    assertSame(vm.rootScheduler(), OresScheduler.current());
                    assertTrue(Thread.currentThread().getName().startsWith("ores-control-plane-"));
                    assertEquals(group.id(), mail.group());
                    senders.add(mail.actor());
                    assertEquals("delivered", mail.message());
                } catch (Throwable problem) { failure.compareAndSet(null, problem); }
                finally { delivered.countDown(); }
            });
            for (var actor : List.of(shared, isolated, untrusted)) {
                group.join(actor, join);
                actor.send(group.id());
            }
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertEquals(Set.of(shared.id(), isolated.id(), untrusted.id()), senders);
            for (var actor : List.of(shared, isolated, untrusted)) {
                assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
                assertTrue(actor.failure().isEmpty(), actor.failure().toString());
            }
            group.close();
            // Delivery is signalled inside the callback; the active envelope
            // releases its reservation in the runtime finally block afterward.
            // Close drains queued mail without interrupting an active callback.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (runtime.sharedMemoryBytes() != 0L && System.nanoTime() < deadline) {
                Thread.yield();
            }
            assertEquals(0L, runtime.sharedMemoryBytes(),
                    "active and queued Mailman reservations must eventually release");
        }
    }

    private static void emitAndStop(ActorRuntime.ActorGroupId id,
                                   ActorRuntime.ActorContext<ActorRuntime.ActorGroupId> context) {
        context.actorGroup(id).emit("delivered");
        context.self().stop();
    }

    private static void expectSecurity(Runnable operation, String message) {
        try {
            operation.run();
            throw new AssertionError(message);
        } catch (SecurityException expected) {
            // Expected fail-closed boundary.
        }
    }
}
