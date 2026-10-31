package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
final class ActorScopedTaskPhysicalRetirementTest {

    @Test
    void nestedScopedChildPinsReceiptArenaAndDoneUntilPhysicalRetirement()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 2, 8, 64))) {
            CountDownLatch childRetirementHookEntered = new CountDownLatch(1);
            CountDownLatch childTerminalAuthorityObserved = new CountDownLatch(1);
            AtomicReference<Runnable> releaseChildRetirement =
                    new AtomicReference<>();
            AtomicReference<ActorRuntime.PrivateMemoryBlock> memory =
                    new AtomicReference<>();

            ActorRuntime.ActorRef<String> actor = runtime.spawnPrivateTrusted(
                    actorContext -> {
                        ActorRuntime.PrivateMemoryBlock block =
                                actorContext.privateMemory()
                                        .orElseThrow()
                                        .allocatePrivateBytes(32);
                        block.writeInt(0, 1234);
                        memory.set(block);

                        return (message, context) -> {
                            OresScheduler.Task<Void> parent =
                                    new OresScheduler.Task<>() {
                                        private final TaskScope scope =
                                                new TaskScope();
                                        private boolean started;

                                        @Override
                                        public OresScheduler.Step<Void> resume(
                                                OresScheduler.Resume resume) {
                                            if (!resume.initial() || started) {
                                                throw new IllegalStateException(
                                                        "scoped parent resumed unexpectedly");
                                            }
                                            started = true;

                                            TaskScope.Registration registration =
                                                    scope.reserve();
                                            OresScheduler.Task<Void> child =
                                                    new OresScheduler.Task<>() {
                                                        @Override
                                                        public OresScheduler.Step<Void> resume(
                                                                OresScheduler.Resume childResume) {
                                                            if (childResume.initial()) {
                                                                registration.beganExecution();
                                                            }
                                                            return OresScheduler.done(null);
                                                        }

                                                        @Override
                                                        public void afterCarrierQuiescent(
                                                                Runnable retire) {
                                                            if (!releaseChildRetirement
                                                                    .compareAndSet(null, retire)) {
                                                                throw new IllegalStateException(
                                                                        "child retirement hook invoked twice");
                                                            }
                                                            childRetirementHookEntered.countDown();
                                                        }
                                                    };

                                            OresFuture<Void> childFuture =
                                                    context.runtime().startActorTask(
                                                            child,
                                                            registration::quiesced,
                                                            terminal -> {
                                                                childTerminalAuthorityObserved
                                                                        .countDown();
                                                                registration.terminalReady(
                                                                        terminal);
                                                            });
                                            registration.attach(childFuture);
                                            return OresScheduler.done(null);
                                        }

                                        @Override
                                        public void afterCarrierQuiescent(
                                                Runnable retire) {
                                            OresFuture<Void> drained =
                                                    scope.cancelAndDrain();
                                            drained.whenCompleteRuntime(
                                                    (ignored, failure) ->
                                                            retire.run());
                                        }
                                    };

                            context.runtime().startActorTask(parent);
                        };
                    });

            try {
                actor.send("launch");

                assertTrue(
                        childTerminalAuthorityObserved.await(
                                5, TimeUnit.SECONDS),
                        "TaskScope did not receive child terminal authority");
                assertTrue(
                        childRetirementHookEntered.await(
                                5, TimeUnit.SECONDS),
                        "scoped child never reached physical-retirement barrier");

                ActorRuntime.PrivateMemoryBlock block = memory.get();
                assertNotNull(block);
                assertFalse(block.closed());

                actor.stop();

                assertFalse(
                        actor.done().isDone(),
                        "actor DONE outran nested scoped-child retirement");
                assertFalse(
                        actor.awaitTermination(100, TimeUnit.MILLISECONDS),
                        "actor finalized before nested scoped child physically retired");
                assertFalse(
                        block.closed(),
                        "actor arena closed while TaskScope child remained physical");
                assertEquals(
                        1,
                        runtime.actorCount(),
                        "actor registration must remain pinned by the receipt");

                Runnable retire = releaseChildRetirement.getAndSet(null);
                assertNotNull(retire);
                retire.run();

                assertTrue(
                        actor.awaitTermination(5, TimeUnit.SECONDS),
                        "actor did not finalize after nested scope drained");
                assertTrue(block.closed());
                assertTrue(actor.done().isDone());
                assertEquals(0, runtime.actorCount());
            } finally {
                Runnable retire = releaseChildRetirement.getAndSet(null);
                if (retire != null) retire.run();
                actor.stop();
            }
        }
    }
}
