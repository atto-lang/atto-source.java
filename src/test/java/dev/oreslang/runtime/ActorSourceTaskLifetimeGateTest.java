package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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
        java.util.concurrent.atomic.AtomicLong wall =
                new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicLong cpu =
                new java.util.concurrent.atomic.AtomicLong();
        ActorRuntime.UntrustedLogicalQuota quota =
                new ActorRuntime.UntrustedLogicalQuota(
                        TimeUnit.SECONDS.toNanos(1),
                        1_000,
                        0L,
                        0L,
                        wall::get,
                        cpu::get);

        AtomicReference<Runnable> delegatedRetirement = new AtomicReference<>();
        OresScheduler.Task<Void> source = new OresScheduler.Task<>() {
            @Override
            public OresScheduler.Step<Void> resume(
                    OresScheduler.Resume resume) {
                return OresScheduler.done(null);
            }

            @Override
            public void afterCarrierQuiescent(Runnable retire) {
                assertTrue(delegatedRetirement.compareAndSet(null, retire));
            }
        };

        OresScheduler.Task<Void> wrapped =
                ActorRuntime.wrapUntrustedTaskForQuota(source, quota);
        OresScheduler.Step<Void> step =
                wrapped.resume(new OresScheduler.Resume(true, null, null));
        assertInstanceOf(OresScheduler.Done.class, step);

        java.util.concurrent.atomic.AtomicBoolean retired =
                new java.util.concurrent.atomic.AtomicBoolean();
        wrapped.afterCarrierQuiescent(() -> retired.set(true));

        assertFalse(retired.get(),
                "quota wrapper must not bypass the source Task retirement barrier");
        Runnable release = delegatedRetirement.get();
        assertNotNull(release,
                "quota wrapper must delegate Task.afterCarrierQuiescent");
        release.run();
        assertTrue(retired.get());
    }

}
