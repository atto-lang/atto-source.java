package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Uses the real ActorRuntime and mailbox lifecycle, but executes trusted host
 * primitive operations, NOT source-compiled AttoLang guest inference.
 */
@Timeout(20)
final class NeuronActorScalarTurnStateV0Test {
    @Test void realActorActivationAndNextEnvelopeReadAreTurnFenced()
            throws Exception {
        var stateRef = new AtomicReference<NeuronActorScalarTurnStateV0>();
        var leakedPreviousRead =
                new AtomicReference<NeuronActorScalarTurnStateV0.ReadScope>();
        var observed = new AtomicReference<Double>();
        var failed = new AtomicReference<Throwable>();
        var initialized = new CountDownLatch(1);
        var completed = new CountDownLatch(1);

        // A throughput batch can process both mailbox messages. Identity
        // validation must use individual envelopes rather than batch context.
        try (var runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 2, 8, 4),
                ActorRuntime.TurnExecutor.direct())) {
            var actor = runtime.<Integer>spawnShared(() -> {
                var owned = NeuronActorScalarTurnStateV0.createOnCurrentTurn(
                        4, 2, List.of("weight", "bias"));
                stateRef.set(owned);
                assertThrows(IllegalStateException.class,
                        () -> NeuronActorScalarTurnStateV0.createOnCurrentTurn(
                                4, 2, List.of("weight", "bias")),
                        "one actor activation owns only one protected neuron state");
                return (message, context) -> {
                    try {
                        if (message == 1) {
                            try (var write = owned.tryWrite()) {
                                assertNotNull(write);
                                write.writeFloat("weight", 2.0);
                                write.writeFloat("bias", 1.0);
                                assertEquals(2.0, write.readFloat("weight"));
                                assertThrows(IllegalStateException.class, owned::tryRead,
                                        "same envelope cannot reenter an already-held writer");
                            }
                            var read = owned.tryRead();
                            assertNotNull(read);
                            leakedPreviousRead.set(read);
                            initialized.countDown();
                            context.self().send(2);
                        } else if (message == 2) {
                            var stale = leakedPreviousRead.get();
                            assertNotNull(stale);
                            assertThrows(SecurityException.class,
                                    () -> stale.readFloat("weight"),
                                    "previous mailbox envelope is not a state capability");
                            stale.close();
                            try (var read = owned.tryRead()) {
                                assertNotNull(read);
                                observed.set(3.0 * read.readFloat("weight")
                                        + read.readFloat("bias"));
                            }
                            assertEquals(0, owned.activeLoanCount());
                            completed.countDown();
                            context.self().stop();
                        } else {
                            throw new AssertionError("unexpected message " + message);
                        }
                    } catch (Throwable problem) {
                        failed.set(problem);
                        initialized.countDown();
                        completed.countDown();
                        context.self().stop();
                    }
                };
            });

            actor.send(1);
            assertTrue(initialized.await(5, TimeUnit.SECONDS));
            if (failed.get() != null)
                throw new AssertionError("neuron host initialization failed", failed.get());
            assertNotNull(stateRef.get());
            assertEquals(actor.generation(), stateRef.get().generation(),
                    "state epoch must derive from the actual ActorRef");

            assertThrows(SecurityException.class, stateRef.get()::tryRead,
                    "host code outside the admitted actor cannot borrow its state");
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            if (failed.get() != null)
                throw new AssertionError("neuron envelope-state fencing failed", failed.get());
            assertNotNull(observed.get());
            assertEquals(7.0, observed.get().doubleValue());

            var state = stateRef.get();
            assertThrows(SecurityException.class, state::tryRead);
            assertTrue(state.tryClearAfterQuiescence(),
                    "ActorRuntime STOP must automatically retire and zeroize"
                            + " protected state after physical loans drain");
            state.close(); // idempotent after runtime-owned finalization
            assertEquals(0, state.activeLoanCount());
        }
    }

    @Test void stopWaitsForOutstandingPhysicalLoanAndFinalizesAfterLateRelease()
            throws Exception {
        var ownedState = new AtomicReference<NeuronActorScalarTurnStateV0>();
        var lateLoan = new AtomicReference<NeuronActorScalarTurnStateV0.ReadScope>();
        var error = new AtomicReference<Throwable>();
        var armed = new CountDownLatch(1);

        try (var runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 2, 1, 2),
                ActorRuntime.TurnExecutor.direct())) {
            var actor = runtime.<Integer>spawnShared(() -> {
                var state = NeuronActorScalarTurnStateV0.createOnCurrentTurn(
                        2, 1, List.of("weight"));
                ownedState.set(state);
                return (message, context) -> {
                    try {
                        assertEquals(Integer.valueOf(1), message);
                        var read = state.tryRead();
                        assertNotNull(read);
                        lateLoan.set(read);
                        context.self().stop();
                    } catch (Throwable failure) {
                        error.set(failure);
                        context.self().stop();
                    } finally {
                        armed.countDown();
                    }
                };
            });

            try {
                actor.send(1);
                assertTrue(armed.await(5, TimeUnit.SECONDS));
                if (error.get() != null)
                    throw new AssertionError("loan setup failed", error.get());
                assertNotNull(ownedState.get());
                assertNotNull(lateLoan.get());
                assertFalse(actor.awaitTermination(200, TimeUnit.MILLISECONDS),
                        "STOP cannot finalize while a physical neuron loan is held");
                assertFalse(ownedState.get().tryClearAfterQuiescence(),
                        "logical STOP must not reclaim a live loan");
            } finally {
                var loan = lateLoan.get();
                if (loan != null) loan.close();
            }

            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS),
                    "last physical loan release should wake actor finalization");
            assertTrue(ownedState.get().tryClearAfterQuiescence());
            assertEquals(0, ownedState.get().activeLoanCount());
        }
    }

    @Test void concurrentLateCloseCannotDoubleReleaseOrDoubleFinalize() throws Exception {
        var stateRef = new AtomicReference<NeuronActorScalarTurnStateV0>();
        var scopeRef = new AtomicReference<NeuronActorScalarTurnStateV0.ReadScope>();
        var error = new AtomicReference<Throwable>();
        var stopped = new CountDownLatch(1);

        try (var runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 2, 1, 2),
                ActorRuntime.TurnExecutor.direct())) {
            var actor = runtime.<Integer>spawnShared(() -> {
                var state = NeuronActorScalarTurnStateV0.createOnCurrentTurn(
                        4, 2, List.of("weight"));
                stateRef.set(state);
                return (message, context) -> {
                    try {
                        assertEquals(Integer.valueOf(1), message);
                        var scope = state.tryRead();
                        assertNotNull(scope);
                        scopeRef.set(scope);
                        context.self().stop();
                    } catch (Throwable problem) {
                        error.compareAndSet(null, problem);
                        context.self().stop();
                    } finally {
                        stopped.countDown();
                    }
                };
            });
            actor.send(1);
            assertTrue(stopped.await(5, TimeUnit.SECONDS));
            if (error.get() != null) throw new AssertionError("initial actor turn", error.get());
            assertNotNull(scopeRef.get());
            assertFalse(actor.awaitTermination(100, TimeUnit.MILLISECONDS),
                    "STOP cannot finalize with the last loan physically held");
            var start = new CountDownLatch(1);
            var finished = new CountDownLatch(8);
            for (int i = 0; i < 8; i++) {
                Thread.ofPlatform().name("neuron-close-racer-" + i).start(() -> {
                    try {
                        assertTrue(start.await(5, TimeUnit.SECONDS));
                        for (int repeat = 0; repeat < 50; repeat++)
                            scopeRef.get().close();
                    } catch (Throwable problem) {
                        error.compareAndSet(null, problem);
                    } finally {
                        finished.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS),
                    "all host cleanup racers must exit");
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS),
                    "exactly one physical loan close wakes STOP finalization");
            if (error.get() != null) throw new AssertionError("scope close race", error.get());
            assertEquals(0, stateRef.get().activeLoanCount());
            assertTrue(stateRef.get().tryClearAfterQuiescence());
            assertThrows(IllegalStateException.class,
                    () -> scopeRef.get().readFloat("weight"),
                    "a physically released loan cannot return data after STOP");
        }
    }

    @Test void differentActorCannotBorrowCapturedProtectedState() throws Exception {
        var stateRef = new AtomicReference<NeuronActorScalarTurnStateV0>();
        var failure = new AtomicReference<Throwable>();
        var ready = new CountDownLatch(1);
        var checked = new CountDownLatch(1);

        try (var runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 2, 1, 4),
                ActorRuntime.TurnExecutor.direct())) {
            var first = runtime.<Integer>spawnShared(() -> {
                var owned = NeuronActorScalarTurnStateV0.createOnCurrentTurn(
                        4, 2, List.of("weight"));
                stateRef.set(owned);
                return (message, context) -> {
                    try {
                        if (message != 1) throw new AssertionError("unexpected source message");
                        try (var write = owned.tryWrite()) {
                            assertNotNull(write);
                            write.writeFloat("weight", 42.0);
                        }
                        ready.countDown();
                        context.self().stop();
                    } catch (Throwable problem) {
                        failure.set(problem);
                        ready.countDown();
                        context.self().stop();
                    }
                };
            });
            var second = runtime.<Integer>spawnShared(() -> (message, context) -> {
                try {
                    assertEquals(Integer.valueOf(2), message);
                    assertNotNull(stateRef.get());
                    assertThrows(SecurityException.class, stateRef.get()::tryRead,
                            "shared actors cannot borrow a sibling's state");
                } catch (Throwable problem) {
                    failure.set(problem);
                } finally {
                    checked.countDown();
                    context.self().stop();
                }
            });
            first.send(1);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            if (failure.get() != null)
                throw new AssertionError("owner setup failed", failure.get());
            assertEquals(first.generation(), stateRef.get().generation());
            second.send(2);
            assertTrue(checked.await(5, TimeUnit.SECONDS));
            assertTrue(first.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(second.awaitTermination(5, TimeUnit.SECONDS));
            if (failure.get() != null)
                throw new AssertionError("cross-actor authorization failed", failure.get());
            assertTrue(stateRef.get().tryClearAfterQuiescence(),
                    "sibling actor STOP must not prevent owner cleanup");
            stateRef.get().close();
        }
    }
}
