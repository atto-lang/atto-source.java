package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Uses the ACTUAL ActorRuntime actor mailbox, not the isolated WAL test shim. */
@Timeout(20)
final class WalStorageActorProofRuntimeTest {
    @Test
    void storageProofIsMintedOnlyByAnAdmittedLiveActorTurnAndFencedAfterStop()
            throws Exception {
        assertThrows(SecurityException.class, ActorRuntime::mintStorageActorProof);
        AtomicReference<ActorRuntime.StorageActorProof> first = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch initialized = new CountDownLatch(1);
        CountDownLatch resumed = new CountDownLatch(1);

        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                try {
                    if (message == 1) {
                        ActorRuntime.StorageActorProof proof = ActorRuntime.mintStorageActorProof();
                        assertSame(runtime, proof.runtime());
                        assertSame(context.self(), proof.actor());
                        assertTrue(proof.storageActorOrdinal() > 0);
                        assertTrue(proof.actorStillAlive());
                        assertTrue(proof.validOnCurrentTurn());
                        first.set(proof);
                        initialized.countDown();
                    } else if (message == 2) {
                        ActorRuntime.StorageActorProof proof = first.get();
                        assertNotNull(proof);
                        assertTrue(proof.validOnCurrentTurn());
                        var reminted = ActorRuntime.mintStorageActorProof();
                        assertSame(proof.actor(), reminted.actor());
                        assertEquals(proof.storageActorOrdinal(), reminted.storageActorOrdinal());
                        resumed.countDown();
                        context.self().stop();
                    } else {
                        throw new AssertionError("unexpected actor message");
                    }
                } catch (Throwable problem) {
                    failure.set(problem);
                    initialized.countDown();
                    resumed.countDown();
                    context.self().stop();
                }
            });
            ref.send(1);
            assertTrue(initialized.await(5, TimeUnit.SECONDS));
            if (failure.get() != null) throw new AssertionError("actor proof mint failed", failure.get());
            ActorRuntime.StorageActorProof captured = first.get();
            assertNotNull(captured);
            // The host and any non-owner carrier cannot use the actor proof.
            assertFalse(captured.validOnCurrentTurn());
            assertThrows(SecurityException.class, ActorRuntime::mintStorageActorProof);
            ref.send(2);
            assertTrue(resumed.await(5, TimeUnit.SECONDS));
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
            if (failure.get() != null) throw new AssertionError("actor proof reuse failed", failure.get());
            assertFalse(captured.actorStillAlive());
            assertFalse(captured.validOnCurrentTurn());
        }
    }
}
