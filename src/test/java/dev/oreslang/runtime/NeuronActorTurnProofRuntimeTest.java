package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Actual ActorRuntime integration smoke test for physical turn authenticity.
 * This does NOT run compiled .atto neuron code or grant a state-region loan.
 */
@Timeout(20)
final class NeuronActorTurnProofRuntimeTest {
    @Test void proofExpiresBetweenTwoMessagesInSamePhysicalThroughputBatch()
            throws Exception {
        AtomicReference<ActorRuntime.NeuronActorTurnProof> first =
                new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);

        // Throughput=8 permits consecutive messages on the same carrier.
        // The neuron proof MUST still be scoped to the individual envelope.
        try (var runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 2, 8, 2),
                ActorRuntime.TurnExecutor.direct())) {
            var actor = runtime.<Integer>spawnShared(() -> (message, context) -> {
                try {
                    if (message == 1) {
                        var proof = ActorRuntime.mintNeuronActorTurnProof();
                        assertTrue(proof.validOnCurrentTurn());
                        first.set(proof);
                        context.self().send(2);
                    } else if (message == 2) {
                        var prior = first.get();
                        assertNotNull(prior);
                        assertFalse(prior.validOnCurrentTurn(),
                                "same batch must NOT imply same message authority");
                        assertThrows(SecurityException.class, prior::checkedHandlerIdentity);
                        var fresh = ActorRuntime.mintNeuronActorTurnProof();
                        assertTrue(fresh.validOnCurrentTurn());
                        assertNotSame(prior, fresh);
                        completed.countDown();
                        context.self().stop();
                    } else {
                        throw new AssertionError("unexpected message " + message);
                    }
                } catch (Throwable problem) {
                    failure.set(problem);
                    completed.countDown();
                    context.self().stop();
                }
            });
            actor.send(1);
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            if (failure.get() != null)
                throw new AssertionError("same-batch envelope fencing failed", failure.get());
            assertNotNull(first.get());
            assertFalse(first.get().validOnCurrentTurn());
        }
    }

    @Test void proofCannotBeMintedFromHostAndCannotEscapeItsPhysicalDispatch()
            throws Exception {
        assertThrows(SecurityException.class, ActorRuntime::mintNeuronActorTurnProof);
        AtomicReference<ActorRuntime.NeuronActorTurnProof> first =
                new AtomicReference<>();
        AtomicReference<ActorRuntime.NeuronActorTurnProof> second =
                new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch firstHandled = new CountDownLatch(1);
        CountDownLatch secondHandled = new CountDownLatch(1);
        // Throughput=1 forces a distinct physical actor dispatch per message.
        var config = new ActorRuntime.DispatcherConfig(2, 2, 1, 2);

        try (var runtime = new ActorRuntime(
                IsolatePolicy.developer(), config, ActorRuntime.TurnExecutor.direct())) {
            var actor = runtime.<Integer>spawnShared(() -> (message, context) -> {
                try {
                    var proof = ActorRuntime.mintNeuronActorTurnProof();
                    assertTrue(proof.validOnCurrentTurn());
                    assertSame(runtime, proof.runtime());
                    assertEquals(context.self().id(), proof.actorId());
                    assertNotNull(proof.checkedHandlerIdentity());
                    assertSame(proof.checkedHandlerIdentity(),
                            ActorRuntime.mintNeuronActorTurnProof().checkedHandlerIdentity());
                    if (message == 1) {
                        first.set(proof);
                        // A foreign carrier cannot acquire proof authority
                        // even while the originating actor turn is active.
                        var attempted = new AtomicReference<Throwable>();
                        Thread foreign = Thread.ofPlatform().start(() -> {
                            try {
                                assertFalse(proof.validOnCurrentTurn());
                                assertThrows(SecurityException.class,
                                        proof::checkedHandlerIdentity);
                                assertThrows(SecurityException.class,
                                        ActorRuntime::mintNeuronActorTurnProof);
                            } catch (Throwable rejected) {
                                attempted.set(rejected);
                            }
                        });
                        foreign.join(3_000);
                        assertFalse(foreign.isAlive());
                        if (attempted.get() != null)
                            throw new AssertionError("off-carrier rejection failed",
                                    attempted.get());
                        firstHandled.countDown();
                    } else if (message == 2) {
                        assertFalse(first.get().validOnCurrentTurn(),
                                "captured previous turn witness must be stale");
                        assertThrows(SecurityException.class,
                                first.get()::checkedHandlerIdentity);
                        assertNotSame(first.get(), proof);
                        second.set(proof);
                        secondHandled.countDown();
                        context.self().stop();
                    } else {
                        throw new AssertionError("unexpected message " + message);
                    }
                } catch (Throwable problem) {
                    failure.set(problem);
                    firstHandled.countDown();
                    secondHandled.countDown();
                    context.self().stop();
                }
            });

            actor.send(1);
            assertTrue(firstHandled.await(5, TimeUnit.SECONDS));
            if (failure.get() != null)
                throw new AssertionError("neuron proof first dispatch failed", failure.get());
            assertNotNull(first.get());
            assertFalse(first.get().validOnCurrentTurn());
            assertThrows(SecurityException.class, first.get()::checkedHandlerIdentity);

            actor.send(2);
            assertTrue(secondHandled.await(5, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            if (failure.get() != null)
                throw new AssertionError("neuron proof second dispatch failed", failure.get());
            assertNotNull(second.get());
            assertFalse(second.get().validOnCurrentTurn());
            assertThrows(SecurityException.class, second.get()::checkedHandlerIdentity);
        }
    }
}
