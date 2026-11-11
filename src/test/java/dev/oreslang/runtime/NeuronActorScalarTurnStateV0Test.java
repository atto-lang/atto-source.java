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
                        11, 4, 2, List.of("weight", "bias"));
                stateRef.set(owned);
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

            assertThrows(SecurityException.class, stateRef.get()::tryRead,
                    "host code outside the admitted actor cannot borrow its state");
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            if (failed.get() != null)
                throw new AssertionError("neuron envelope-state fencing failed", failed.get());
            assertEquals(7.0, observed.get());

            var state = stateRef.get();
            assertThrows(SecurityException.class, state::tryRead);
            state.close();
            assertTrue(state.tryClearAfterQuiescence(),
                    "all completed turns/loans have drained before host-only zeroization");
            assertEquals(0, state.activeLoanCount());
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
                        21, 4, 2, List.of("weight"));
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
                    assertEquals(2, message);
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
            second.send(2);
            assertTrue(checked.await(5, TimeUnit.SECONDS));
            assertTrue(first.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(second.awaitTermination(5, TimeUnit.SECONDS));
            if (failure.get() != null)
                throw new AssertionError("cross-actor authorization failed", failure.get());
            stateRef.get().close();
            assertTrue(stateRef.get().tryClearAfterQuiescence());
        }
    }
}
