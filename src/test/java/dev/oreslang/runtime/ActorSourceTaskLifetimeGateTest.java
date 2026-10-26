package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
final class ActorSourceTaskLifetimeGateTest {

    @Test
    void privateActorKeepsArenaAndDonePendingUntilPhysicalTaskRetirement()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 2, 8, 64))) {
            CountDownLatch retirementHookEntered = new CountDownLatch(1);
            AtomicReference<Runnable> releaseRetirement = new AtomicReference<>();
            AtomicReference<ActorRuntime.PrivateMemoryBlock> memory = new AtomicReference<>();
            AtomicReference<OresFuture<Void>> sourceTask = new AtomicReference<>();

            ActorRuntime.ActorRef<String> actor = runtime.spawnPrivateTrusted(
                    actorContext -> {
                        ActorRuntime.PrivateMemoryBlock block =
                                actorContext.privateMemory()
                                        .orElseThrow()
                                        .allocatePrivateBytes(32);
                        block.writeInt(0, 42);
                        memory.set(block);

                        return (message, context) -> {
                            OresScheduler.Task<Void> child = new OresScheduler.Task<>() {
                                @Override
                                public OresScheduler.Step<Void> resume(
                                        OresScheduler.Resume resume) {
                                    return OresScheduler.done(null);
                                }

                                @Override
                                public void afterCarrierQuiescent(Runnable retire) {
                                    if (!releaseRetirement.compareAndSet(null, retire)) {
                                        throw new IllegalStateException(
                                                "physical retirement hook invoked twice");
                                    }
                                    retirementHookEntered.countDown();
                                }
                            };

                            // Deliberately do not call ownCurrentActorFuture().
                            // startActorTask() itself must bind the Future to the
                            // actor lifecycle before shutdown can begin.
                            sourceTask.set(context.runtime().startActorTask(child));
                        };
                    });

            try {
                actor.send("launch");
                assertTrue(
                        retirementHookEntered.await(5, TimeUnit.SECONDS),
                        "source Task never reached its post-carrier retirement hook");

                ActorRuntime.PrivateMemoryBlock block = memory.get();
                assertNotNull(block);
                assertFalse(block.closed());

                actor.stop();

                assertFalse(
                        actor.done().isDone(),
                        "actor done must not publish while a source Task lease is live");
                assertFalse(
                        actor.awaitTermination(100, TimeUnit.MILLISECONDS),
                        "actor must not finalize while post-carrier retirement is deferred");
                assertFalse(
                        block.closed(),
                        "actor-owned memory must remain live until physical retirement");
                assertEquals(1, runtime.actorCount());

                Runnable retire = releaseRetirement.getAndSet(null);
                assertNotNull(retire);
                retire.run();

                assertTrue(
                        actor.awaitTermination(5, TimeUnit.SECONDS),
                        "actor should finalize after the last source Task lease retires");
                assertTrue(block.closed());
                assertEquals(0, runtime.actorCount());
                assertNotNull(sourceTask.get());
            } finally {
                Runnable retire = releaseRetirement.getAndSet(null);
                if (retire != null) retire.run();
                actor.stop();
            }
        }
    }

    @Test
    void actorStopCancelsSuspendedSourceTaskWithoutExplicitOwnershipRegistration()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch admitted = new CountDownLatch(1);
            AtomicReference<OresFuture<Void>> sourceTask = new AtomicReference<>();

            ActorRuntime.ActorRef<String> actor = runtime.spawnPrivateTrusted(
                    actorContext -> (message, context) -> {
                        OresFuture<Void> future = context.runtime().startActorTask(
                                resume -> OresScheduler.await(new OresFuture<>()));
                        sourceTask.set(future);
                        admitted.countDown();
                    });

            actor.send("launch");
            assertTrue(admitted.await(5, TimeUnit.SECONDS));

            actor.stop();

            assertTrue(
                    actor.awaitTermination(5, TimeUnit.SECONDS),
                    "shutdown must revoke an abandoned suspended source Task");
            OresFuture<Void> future = sourceTask.get();
            assertNotNull(future);
            assertTrue(
                    future.isCancelled(),
                    "actor-owned source Task Future must be cancelled on actor stop");
            assertEquals(0, runtime.actorCount());
        }
    }

    @Test
    void selfStopRejectsLaterSourceTaskAdmissionWithoutLeakingLease()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch checked = new CountDownLatch(1);
            AtomicReference<Throwable> unexpected = new AtomicReference<>();

            ActorRuntime.ActorRef<String> actor = runtime.spawnPrivateTrusted(
                    actorContext -> (message, context) -> {
                        context.self().stop();
                        try {
                            context.runtime().startActorTask(
                                    resume -> OresScheduler.done(null));
                            unexpected.set(new AssertionError(
                                    "source Task admitted after actor stop"));
                        } catch (RejectedExecutionException expected) {
                            // expected: lifetime admission is sealed by stop()
                        } catch (Throwable failure) {
                            unexpected.set(failure);
                        } finally {
                            checked.countDown();
                        }
                    });

            actor.send("stop-then-spawn");
            assertTrue(checked.await(5, TimeUnit.SECONDS));
            assertNull(unexpected.get(), () -> "unexpected failure: " + unexpected.get());
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, runtime.actorCount());
        }
    }

    @Test
    void untrustedQuotaWrapperPreservesDeferredPhysicalRetirementHook()
            throws Exception {
        String key = UUID.randomUUID().toString();
        UntrustedProbe probe = new UntrustedProbe();
        UNTRUSTED_PROBES.put(key, probe);

        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 2, 8, 64))) {
            ActorRuntime.ActorRef<String> actor =
                    runtime.spawnUntrusted(new UntrustedFactory());
            try {
                actor.send(key);
                assertTrue(
                        probe.retirementHookEntered.await(5, TimeUnit.SECONDS),
                        "untrusted wrapper dropped Task.afterCarrierQuiescent");

                actor.stop();

                assertFalse(
                        actor.done().isDone(),
                        "untrusted actor finalized before delegated Task retirement");
                assertFalse(
                        actor.awaitTermination(25, TimeUnit.MILLISECONDS),
                        "untrusted actor must remain pinned behind deferred retirement");

                Runnable retire = probe.releaseRetirement.getAndSet(null);
                assertNotNull(retire);
                retire.run();

                assertTrue(
                        actor.awaitTermination(5, TimeUnit.SECONDS),
                        "untrusted actor should finalize after delegated retirement");
                assertEquals(0, runtime.actorCount());
            } finally {
                Runnable retire = probe.releaseRetirement.getAndSet(null);
                if (retire != null) retire.run();
                actor.stop();
            }
        } finally {
            UNTRUSTED_PROBES.remove(key);
        }
    }

    private static final ConcurrentHashMap<String, UntrustedProbe>
            UNTRUSTED_PROBES = new ConcurrentHashMap<>();

    private static final class UntrustedProbe {
        private final CountDownLatch retirementHookEntered = new CountDownLatch(1);
        private final AtomicReference<Runnable> releaseRetirement =
                new AtomicReference<>();
        private final AtomicReference<OresFuture<Void>> sourceTask =
                new AtomicReference<>();
    }

    /**
     * Intentionally has no instance fields: untrusted actor factories must be
     * stateless. Test coordination is looked up only after the data-only String
     * message crosses the actor boundary.
     */
    private static final class UntrustedFactory
            implements ActorRuntime.BehaviorFactory<String> {
        @Override
        public ActorRuntime.Behavior<String> create(
                ActorRuntime.ActorContext<String> ignored) {
            return (message, context) -> {
                UntrustedProbe probe = UNTRUSTED_PROBES.get(message);
                if (probe == null) {
                    throw new IllegalStateException("missing untrusted test probe");
                }

                OresScheduler.Task<Void> child = new OresScheduler.Task<>() {
                    @Override
                    public OresScheduler.Step<Void> resume(
                            OresScheduler.Resume resume) {
                        return OresScheduler.done(null);
                    }

                    @Override
                    public void afterCarrierQuiescent(Runnable retire) {
                        if (!probe.releaseRetirement.compareAndSet(null, retire)) {
                            throw new IllegalStateException(
                                    "untrusted retirement hook invoked twice");
                        }
                        probe.retirementHookEntered.countDown();
                    }
                };

                probe.sourceTask.set(context.runtime().startActorTask(child));
            };
        }
    }

}
