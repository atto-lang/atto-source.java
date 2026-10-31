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


    @Test
    void runtimeCloseWaitsForDeferredSourceTaskRetirementBeforeClosingArena()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 2, 8, 64))) {
            CountDownLatch retirementHookEntered = new CountDownLatch(1);
            CountDownLatch closeStarted = new CountDownLatch(1);
            AtomicReference<Runnable> releaseRetirement = new AtomicReference<>();
            AtomicReference<ActorRuntime.PrivateMemoryBlock> memory = new AtomicReference<>();
            AtomicReference<Throwable> closeFailure = new AtomicReference<>();

            ActorRuntime.ActorRef<String> actor = runtime.spawnPrivateTrusted(
                    actorContext -> {
                        ActorRuntime.PrivateMemoryBlock block =
                                actorContext.privateMemory()
                                        .orElseThrow()
                                        .allocatePrivateBytes(32);
                        block.writeInt(0, 73);
                        memory.set(block);

                        return (message, context) -> context.runtime().startActorTask(
                                new OresScheduler.Task<Void>() {
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
                                });
                    });

            Thread closer = null;
            try {
                actor.send("launch");
                assertTrue(
                        retirementHookEntered.await(5, TimeUnit.SECONDS),
                        "source Task never reached deferred physical retirement");

                ActorRuntime.PrivateMemoryBlock block = memory.get();
                assertNotNull(block);
                assertFalse(block.closed());

                closer = Thread.ofVirtual()
                        .name("actor-runtime-close-quiescence-test")
                        .start(() -> {
                            closeStarted.countDown();
                            try {
                                runtime.close();
                            } catch (Throwable failure) {
                                closeFailure.set(failure);
                            }
                        });

                assertTrue(closeStarted.await(5, TimeUnit.SECONDS));
                closer.join(100);
                assertTrue(
                        closer.isAlive(),
                        "runtime close returned before source Task physical retirement");
                assertFalse(
                        actor.done().isDone(),
                        "actor done published while runtime close was waiting on Task retirement");
                assertFalse(
                        block.closed(),
                        "runtime close reclaimed actor memory before physical retirement");

                Runnable retire = releaseRetirement.getAndSet(null);
                assertNotNull(retire);
                retire.run();

                closer.join(5_000);
                assertFalse(closer.isAlive(), "runtime close did not finish after retirement");
                assertNull(closeFailure.get(), () -> "runtime close failed: " + closeFailure.get());
                assertTrue(block.closed(), "actor memory must close after retirement");
                assertTrue(actor.done().isDone(), "actor done must publish after retirement");
                assertEquals(0, runtime.actorCount());
            } finally {
                Runnable retire = releaseRetirement.getAndSet(null);
                if (retire != null) retire.run();
                if (closer != null && closer.isAlive()) {
                    closer.join(5_000);
                }
            }
        }
    }



    @Test
    void failedPhysicalRetirementQuarantinesArenaAndWithholdsDone()
            throws Exception {
        ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 2, 8, 64));
        CountDownLatch retirementAttempted = new CountDownLatch(1);
        AtomicReference<ActorRuntime.PrivateMemoryBlock> memory = new AtomicReference<>();

        ActorRuntime.ActorRef<String> actor = runtime.spawnPrivateTrusted(
                actorContext -> {
                    ActorRuntime.PrivateMemoryBlock block =
                            actorContext.privateMemory()
                                    .orElseThrow()
                                    .allocatePrivateBytes(32);
                    block.writeInt(0, 91);
                    memory.set(block);

                    return (message, context) ->
                            context.runtime().startActorTask(
                                    resume -> OresScheduler.done(null),
                                    () -> {
                                        retirementAttempted.countDown();
                                        throw new IllegalStateException(
                                                "injected physical retirement failure");
                                    });
                });

        actor.send("launch");
        assertTrue(
                retirementAttempted.await(5, TimeUnit.SECONDS),
                "physical retirement callback was not attempted");

        ActorRuntime.PrivateMemoryBlock block = memory.get();
        assertNotNull(block);
        assertFalse(block.closed());

        actor.stop();

        assertFalse(
                actor.awaitTermination(100, TimeUnit.MILLISECONDS),
                "failed retirement must quarantine the actor generation");
        assertFalse(
                actor.done().isDone(),
                "failed retirement must withhold actor DONE publication");
        assertFalse(
                block.closed(),
                "failed retirement must not authorize arena reclamation");
        assertEquals(1, runtime.actorCount());

        IllegalStateException closeFailure = assertThrows(
                IllegalStateException.class,
                runtime::close);
        assertTrue(
                closeFailure.getMessage().contains(
                        "did not observe full actor termination/carrier exit"));
        assertFalse(block.closed());
        assertFalse(actor.done().isDone());
    }


    @Test
    void privateActorWaitsForPhysicallyRunningScopedDescendantBeforeArenaRelease()
            throws Exception {
        CountDownLatch childStarted = new CountDownLatch(1);
        CountDownLatch releaseChild = new CountDownLatch(1);
        CountDownLatch parentWaitingForScope = new CountDownLatch(1);
        TaskScope scope = new TaskScope();
        TaskScope.Registration ticket = scope.reserve();
        AtomicReference<OresFuture<Void>> childFuture = new AtomicReference<>();

        try (OresScheduler descendantScheduler = new OresScheduler(1);
             ActorRuntime runtime = new ActorRuntime(
                     IsolatePolicy.developer(),
                     new ActorRuntime.DispatcherConfig(2, 2, 8, 64))) {
            OresFuture<Void> child = descendantScheduler.start(
                    resume -> {
                        ticket.beganExecution();
                        childStarted.countDown();
                        if (!releaseChild.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError(
                                    "scoped descendant did not receive physical-release signal");
                        }
                        return OresScheduler.done(null);
                    },
                    ticket::quiesced);
            ticket.attach(child);
            childFuture.set(child);

            AtomicReference<ActorRuntime.PrivateMemoryBlock> memory =
                    new AtomicReference<>();

            ActorRuntime.ActorRef<String> actor = runtime.spawnPrivateTrusted(
                    actorContext -> {
                        ActorRuntime.PrivateMemoryBlock block =
                                actorContext.privateMemory()
                                        .orElseThrow()
                                        .allocatePrivateBytes(32);
                        block.writeInt(0, 117);
                        memory.set(block);

                        return (message, context) -> {
                            OresScheduler.Task<Void> parent =
                                    new OresScheduler.Task<>() {
                                        @Override
                                        public OresScheduler.Step<Void> resume(
                                                OresScheduler.Resume resume) {
                                            return OresScheduler.done(null);
                                        }

                                        @Override
                                        public void afterCarrierQuiescent(
                                                Runnable retire) {
                                            OresFuture<Void> drained =
                                                    scope.cancelAndDrain();
                                            parentWaitingForScope.countDown();
                                            drained.whenCompleteRuntime(
                                                    (ignored, failure) -> retire.run());
                                        }
                                    };
                            context.runtime().startActorTask(parent);
                        };
                    });

            try {
                assertTrue(
                        childStarted.await(5, TimeUnit.SECONDS),
                        "scoped descendant never entered physical execution");

                actor.send("launch");
                assertTrue(
                        parentWaitingForScope.await(5, TimeUnit.SECONDS),
                        "parent SourceTask never entered descendant drain");

                assertTrue(
                        childFuture.get().isCancelled(),
                        "scope cancellation should settle the child Future");
                ActorRuntime.PrivateMemoryBlock block = memory.get();
                assertNotNull(block);
                assertFalse(block.closed());

                actor.stop();

                assertFalse(
                        actor.done().isDone(),
                        "terminal child Future must not authorize actor DONE");
                assertFalse(
                        actor.awaitTermination(100, TimeUnit.MILLISECONDS),
                        "actor finalized while scoped descendant still owned a carrier");
                assertFalse(
                        block.closed(),
                        "actor arena closed before descendant physical quiescence");
                assertEquals(1, runtime.actorCount());

                releaseChild.countDown();

                assertTrue(
                        actor.awaitTermination(5, TimeUnit.SECONDS),
                        "actor did not finalize after descendant physical retirement");
                assertTrue(block.closed());
                assertTrue(actor.done().isDone());
                assertEquals(0, runtime.actorCount());
            } finally {
                releaseChild.countDown();
                actor.stop();
            }
        } finally {
            releaseChild.countDown();
        }
    }


}
