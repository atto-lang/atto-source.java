package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
final class ActorSourceTearDownTest {
    private static final class State implements ActorRuntime.ActorOwnedGuestState {
        final ActorRuntime.ActorId owner;

        State(ActorRuntime.ActorId owner) {
            this.owner = owner;
        }

        @Override
        public ActorRuntime.ActorId ownerActorId() {
            return owner;
        }
    }

    private static final class Executor implements ActorRuntime.ActorCodeExecutor {
        private final SharedCodeImageStore.CodeImage image;
        private final AtomicInteger receives = new AtomicInteger();
        private final AtomicInteger teardowns = new AtomicInteger();
        private final AtomicInteger finalCleanups = new AtomicInteger();
        private final AtomicInteger teardownOutputsAccepted = new AtomicInteger();
        private final boolean teardownFails;
        private boolean teardownEmitsOutput;
        private volatile CountDownLatch teardownEntered;
        private volatile CountDownLatch releaseTeardown;
        private volatile CountDownLatch receiveEntered;
        private volatile CountDownLatch releaseReceive;
        private volatile CountDownLatch startupEntered;
        private volatile CountDownLatch releaseStartup;

        Executor(SharedCodeImageStore.CodeImage image, boolean teardownFails) {
            this.image = image;
            this.teardownFails = teardownFails;
        }

        @Override
        public SharedCodeImageStore.CodeImage codeImage() {
            return image;
        }

        @Override
        public ActorRuntime.ActorOwnedGuestState initializeActor(
                String actorName, ActorRuntime.ActorContext<Object> context)
                throws Exception {
            assertEquals("Worker", actorName);
            if (startupEntered != null) {
                startupEntered.countDown();
                if (releaseStartup == null
                        || !releaseStartup.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test never released source initializer");
                }
            }
            return new State(context.self().id());
        }

        @Override
        public void receiveActor(
                String actorName,
                ActorRuntime.ActorOwnedGuestState state,
                ActorRuntime.ActorInboxMail<Object> mail,
                ActorRuntime.ActorContext<Object> context) throws Exception {
            assertEquals(context.self().id(), state.ownerActorId());
            receives.incrementAndGet();
            if (receiveEntered != null) {
                receiveEntered.countDown();
                if (!releaseReceive.await(3, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test did not release active receive");
                }
            }
            if ("self".equals(mail.value())) {
                // Queue a second envelope deterministically before fencing
                // mailbox admission. It must be discarded without dispatch.
                context.self().send("queued-after-self");
                context.self().stop();
            } else if ("self-with-cleanup".equals(mail.value())) {
                context.runtime().endCurrentActorWithCleanup(finalCleanups::incrementAndGet);
            } else if ("fail".equals(mail.value())) {
                throw new IllegalStateException("receive failure");
            }
        }

        @Override
        public void tearDownActor(
                String actorName,
                ActorRuntime.ActorOwnedGuestState state,
                ActorRuntime.ActorContext<Object> context) throws Exception {
            assertEquals("Worker", actorName);
            assertEquals(context.self().id(), state.ownerActorId());
            assertTrue(context.currentMail().isEmpty(),
                    "tear_down must not inherit receive envelope authority");
            teardowns.incrementAndGet();
            CountDownLatch entered = teardownEntered;
            CountDownLatch release = releaseTeardown;
            if (entered != null && release != null) {
                entered.countDown();
                if (!release.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test teardown release timed out");
                }
            }
            if (teardownEmitsOutput) {
                context.runtime().emitCurrentActorOutput("teardown-output");
                teardownOutputsAccepted.incrementAndGet();
            }
            if (teardownFails) throw new IllegalStateException("teardown failure");
        }
    }

    private static SharedCodeImageStore.CodeImage image(SharedCodeImageStore store) {
        Ast.Program program = Parser.parse("pub routine main(): void { return; }");
        return store.publish("teardown/source.ores", program);
    }

    @Test
    void stopDuringSourceInitializationIsExpectedCancellationNotGuestFailure()
            throws Exception {
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            Executor executor = new Executor(image(store), false);
            executor.startupEntered = new CountDownLatch(1);
            executor.releaseStartup = new CountDownLatch(1);
            var ref = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED, executor, "Worker");
            assertTrue(executor.startupEntered.await(3, TimeUnit.SECONDS),
                    "initializer never entered");
            // The public host stop() is a synchronous finalization barrier.
            // Never call it on the thread that must release the initializer:
            // that would make the test deadlock independently of the runtime.
            AtomicReference<Throwable> stopFailure = new AtomicReference<>();
            Thread stopper = Thread.ofVirtual().start(() -> {
                try {
                    ref.stop();
                } catch (Throwable failure) {
                    stopFailure.set(failure);
                }
            });
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (ref.isAlive() && System.nanoTime() < deadline) {
                    Thread.yield();
                }
                assertFalse(ref.isAlive(), "host STOP must seal initialization");
            } finally {
                executor.releaseStartup.countDown();
            }
            stopper.join(5_000);
            assertFalse(stopper.isAlive(), "stopper must finish physical finalization");
            assertNull(stopFailure.get(), () -> "host stop failed: " + stopFailure.get());
            ref.done().get(5, TimeUnit.SECONDS);
            assertThrows(CompletionException.class, () -> ref.ready().join());
            assertTrue(ref.failure().isEmpty(),
                    "normal stop during initialization must not poison actor DONE");
            assertEquals(0, executor.receives.get());
            assertEquals(0, executor.teardowns.get(),
                    "an actor stopped before READY has no guest teardown authority");
            assertFalse(ref.isAlive());
        }
    }

    @Test
    void externalStopInvokesOnceOnlyActorAffineTeardownForAllActorKinds() {
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            SharedCodeImageStore.CodeImage codeImage = image(store);
            for (ActorRuntime.ActorKind kind : ActorRuntime.ActorKind.values()) {
                Executor executor = new Executor(codeImage, false);
                ActorRuntime.ActorRef<Object> ref =
                        runtime.spawnCodeActor(kind, IsolatePolicy.developer(), executor, "Worker");
                ref.ready().join();
                ref.stop();
                ref.done().join();
                ref.stop();
                assertEquals(0, executor.receives.get());
                assertEquals(1, executor.teardowns.get(), "actor kind " + kind);
                assertFalse(ref.isAlive());
            }
        }
    }

    @Test
    void externallyStoppedTeardownMayStillEmitAuthorizedFinalOutput()
            throws Exception {
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            Executor executor = new Executor(image(store), false);
            executor.teardownEmitsOutput = true;
            var ref = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED, executor, "Worker");
            ref.ready().get(2, TimeUnit.SECONDS);
            ref.stop();
            ref.done().get(2, TimeUnit.SECONDS);
            assertEquals(1, executor.teardowns.get());
            assertEquals("teardown-output",
                    ref.outputs().next().get(2, TimeUnit.SECONDS).value().value());
            assertTrue(ref.outputs().next().get(2, TimeUnit.SECONDS).done());
        }
    }

    @Test
    void stopAfterGuestTurnButBeforeTurnExecutorReturnReschedulesTeardown()
            throws Exception {
        CountDownLatch returnedFromGuest = new CountDownLatch(1);
        CountDownLatch releaseTurnExecutor = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean();
        AtomicReference<Throwable> stopFailure = new AtomicReference<>();
        ActorRuntime.TurnExecutor delayedBoundary = turn -> {
            turn.run();
            if (first.compareAndSet(false, true)) {
                returnedFromGuest.countDown();
                try {
                    if (!releaseTurnExecutor.await(3, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "test did not release first TurnExecutor boundary");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("test carrier interrupted", interrupted);
                }
            }
        };
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(
                     IsolatePolicy.developer(),
                     ActorRuntime.DispatcherConfig.defaults(),
                     delayedBoundary)) {
            Executor executor = new Executor(image(store), false);
            var ref = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED, executor, "Worker");
            ref.ready().get(2, TimeUnit.SECONDS);
            assertTrue(returnedFromGuest.await(2, TimeUnit.SECONDS));
            Thread stopping = new Thread(() -> {
                try {
                    ref.stop();
                } catch (Throwable failure) {
                    stopFailure.set(failure);
                }
            }, "actor-stop-at-carrier-exit");
            stopping.start();
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (ref.isAlive() && System.nanoTime() < deadline) {
                    Thread.yield();
                }
                assertFalse(ref.isAlive(), "host stop must seal admission");
            } finally {
                releaseTurnExecutor.countDown();
            }
            ref.done().get(3, TimeUnit.SECONDS);
            stopping.join(TimeUnit.SECONDS.toMillis(3));
            assertFalse(stopping.isAlive(), "host stop must not stay parked");
            assertNull(stopFailure.get(), () -> "host stop failure: " + stopFailure.get());
            assertEquals(1, executor.teardowns.get(),
                    "reserved teardown wake must survive TurnExecutor exit");
            assertEquals(0, executor.receives.get());
        }
    }

    @Test
    void selfStopRunsTeardownAndRejectsQueuedMessages() {
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            Executor executor = new Executor(image(store), false);
            var ref = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED, executor, "Worker");
            ref.ready().join();
            ref.send("self");
            ref.done().join();
            assertEquals(1, executor.receives.get());
            assertEquals(1, executor.teardowns.get());
        }
    }

    @Test
    void failedTrustedExitHookMustMakeDoneExceptional() throws Exception {
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            Executor executor = new Executor(image(store), false);
            runtime.setActorExitHook(domain -> {
                throw new IllegalStateException("native isolate exit cleanup failed");
            });
            var ref = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED, executor, "Worker");
            ref.ready().get(2, TimeUnit.SECONDS);
            ref.stop();
            CompletionException rejected = assertThrows(
                    CompletionException.class, () -> ref.done().join());
            assertEquals("native isolate exit cleanup failed",
                    rejected.getCause().getMessage());
            assertEquals(1, executor.teardowns.get(),
                    "guest teardown remains exactly once even when trusted close fails");
            assertFalse(ref.isAlive());
        }
    }

    @Test
    void unhandledFailureDoesNotRunGuestTeardown() {
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            Executor executor = new Executor(image(store), false);
            var ref = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED, executor, "Worker");
            ref.ready().join();
            ref.send("fail");
            assertThrows(CompletionException.class, () -> ref.done().join());
            assertEquals(0, executor.teardowns.get());
            assertEquals("receive failure", ref.failure().orElseThrow().getMessage());
        }
    }

    @Test
    void externalStopCannotRunTeardownConcurrentlyWithAnActiveReceive()
            throws Exception {
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            Executor executor = new Executor(image(store), false);
            executor.receiveEntered = new CountDownLatch(1);
            executor.releaseReceive = new CountDownLatch(1);
            var ref = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED, executor, "Worker");
            ref.ready().get(2, TimeUnit.SECONDS);
            ref.send("active");
            assertTrue(executor.receiveEntered.await(2, TimeUnit.SECONDS));
            AtomicReference<Throwable> stopFailure = new AtomicReference<>();
            Thread stopper = new Thread(() -> {
                try {
                    ref.stop();
                } catch (Throwable problem) {
                    stopFailure.set(problem);
                }
            }, "actor-stop-during-admitted-receive");
            stopper.start();
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (ref.isAlive() && System.nanoTime() < deadline) {
                    Thread.yield();
                }
                assertFalse(ref.isAlive(), "external stop seals the inbox");
                assertEquals(0, executor.teardowns.get(),
                        "teardown must wait for the active exclusive guest turn");
            } finally {
                executor.releaseReceive.countDown();
            }
            ref.done().get(3, TimeUnit.SECONDS);
            stopper.join(TimeUnit.SECONDS.toMillis(3));
            assertFalse(stopper.isAlive());
            assertNull(stopFailure.get());
            assertEquals(1, executor.receives.get());
            assertEquals(1, executor.teardowns.get());
        }
    }

    @Test
    void runtimeWideCloseNeverAdmitsGuestTeardownOrOutput() throws Exception {
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            Executor executor = new Executor(image(store), false);
            executor.teardownEmitsOutput = true;
            var ref = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED, executor, "Worker");
            ref.ready().get(2, TimeUnit.SECONDS);
            runtime.close();
            ref.done().get(2, TimeUnit.SECONDS);
            assertEquals(0, executor.teardowns.get(),
                    "runtime close must use trusted resource cleanup only");
            assertEquals(0, executor.teardownOutputsAccepted.get(),
                    "runtime close revokes actor output authority");
        }
    }

    @Test
    void cancellationDuringTeardownRevokesTheFinalGuestCallback() throws Exception {
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            Executor executor = new Executor(image(store), false);
            executor.teardownEmitsOutput = true;
            executor.teardownEntered = new CountDownLatch(1);
            executor.releaseTeardown = new CountDownLatch(1);
            var ref = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED, executor, "Worker");
            ref.ready().join();
            ref.send("self-with-cleanup");
            assertTrue(executor.teardownEntered.await(2, TimeUnit.SECONDS));
            try {
                assertTrue(ref.cancel(), "cancellation must reach a stopping actor");
            } finally {
                executor.releaseTeardown.countDown();
            }
            assertThrows(CompletionException.class, () -> ref.done().join());
            assertEquals(1, executor.teardowns.get());
            assertEquals(0, executor.finalCleanups.get(),
                    "cancel must not enter the last guest callback");
            assertEquals(0, executor.teardownOutputsAccepted.get(),
                    "cancellation must revoke teardown output authority");
        }
    }

    @Test
    void teardownFailureStillRunsRegisteredLastCleanup() {
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            Executor executor = new Executor(image(store), true);
            var ref = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED, executor, "Worker");
            ref.ready().join();
            ref.send("self-with-cleanup");
            assertThrows(CompletionException.class, () -> ref.done().join());
            assertEquals(1, executor.teardowns.get());
            assertEquals(1, executor.finalCleanups.get());
        }
    }

    @Test
    void teardownFailureStillCompletesPhysicalFinalizationExceptionally() {
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            Executor executor = new Executor(image(store), true);
            var ref = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED, executor, "Worker");
            ref.ready().join();
            ref.stop();
            CompletionException failure =
                    assertThrows(CompletionException.class, () -> ref.done().join());
            assertEquals("teardown failure", failure.getCause().getMessage());
            assertFalse(ref.isAlive());
            assertEquals(1, executor.teardowns.get());
        }
    }

    @Test
    void stopRacingSourceMailboxDispatchIsNotAReceiveAdmissionFailure()
            throws Exception {
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            SharedCodeImageStore.CodeImage codeImage = image(store);
            // Host scheduling deliberately varies from round to round.
            // Whatever the outcome, STOP must not turn a not-yet-admitted
            // receive into an actor failure when the runtime wins the race.
            for (int round = 0; round < 24; round++) {
                Executor executor = new Executor(codeImage, false);
                var ref = runtime.spawnCodeActor(
                        ActorRuntime.ActorKind.SHARED, executor, "Worker");
                ref.ready().get(2, TimeUnit.SECONDS);
                for (int message = 0; message < 4; message++) {
                    ref.send("ordinary-" + message);
                }
                Thread stopper = Thread.ofVirtual().start(ref::stop);
                stopper.join(2_000);
                assertFalse(stopper.isAlive(), "STOP must not strand the carrier");
                ref.done().get(2, TimeUnit.SECONDS);
                assertTrue(ref.failure().isEmpty(),
                        "a normal STOP racing receive admission must not poison DONE");
                assertEquals(1, executor.teardowns.get(),
                        "graceful teardown is exactly once in round " + round);
            }
        }
    }
}
