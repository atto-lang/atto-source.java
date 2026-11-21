package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Regression gates for issue #1944 / draft #1953.
 *
 * Regression proof obligations for postcommit retirement and native-provider
 * exit. A Future terminal state or CompletionHandler return is not
 * native-driver exit evidence.
 */
@Timeout(20)
final class ActorPhysicalRetirementPostcommitProofTest {
    @Test
    void finalizationHandoffFailureAfterRetireCannotBeQuarantinedRetroactively() {
        var registryRef = new AtomicReference<ActorPhysicalRetirementRegistry>();
        var postcommitFailure = new IllegalStateException("failure after actor retirement");
        var registry = new ActorPhysicalRetirementRegistry(
                ActorRuntime.ActorId.create(), 4L,
                () -> { }, () -> {
                    // Reproduce a fallible owner-finalizer that performs the
                    // irreversible action and only then reports an error.
                    registryRef.get().retireVerified();
                    throw postcommitFailure;
                });
        registryRef.set(registry);
        var receipt = registry.register(
                ActorPhysicalRetirementRegistry.Kind.FFI_LOAN, false);
        registry.seal();

        assertSame(postcommitFailure, assertThrows(IllegalStateException.class,
                receipt::completeAfterPhysicalRelease));
        assertSame(postcommitFailure, registry.snapshot().failure());
        // retireVerified() is staged while the finalization handoff runs.
        // A later failure must discard that staged commit and quarantine.
        assertFalse(registry.snapshot().retired(),
                "a failed finalizer must not succeed in reclaiming the actor");
    }

    @Test
    void hostCallbackReturnIsNotEvidenceOfItsProviderCallersPhysicalExit()
            throws Exception {
        var runtime = new ActorRuntime();
        var actor = runtime.<String>spawnShared(() -> (message, context) -> { });
        var callback = runtime.<Integer, Void>registerActorIoCompletionFromDriver(
                actor, ActorPhysicalRetirementRegistry.Kind.NATIVE_IO_OPERATION,
                new java.nio.channels.CompletionHandler<>() {
                    @Override public void completed(Integer value, Void attachment) { }
                    @Override public void failed(Throwable error, Void attachment) { }
                });
        var callbackReturned = new CountDownLatch(1);
        var allowProviderExit = new CountDownLatch(1);
        var threadFailure = new AtomicReference<Throwable>();
        assertTrue(actor.cancel());
        Thread provider = Thread.ofVirtual().start(() -> {
            try {
                callback.completed(1, null);
                callbackReturned.countDown();
                if (!allowProviderExit.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("provider driver did not finish");
                }
                // The native provider is still holding its stack / native
                // callback loan until this outer invocation exits. The
                // trusted driver emits its separate physical-exit witness only
                // after the delegated CompletionHandler invocation is over.
                callback.providerExited();
            } catch (Throwable failure) {
                threadFailure.set(failure);
            }
        });
        try {
            assertTrue(callbackReturned.await(3, TimeUnit.SECONDS));
            // Delegate return alone is not the provider-exit witness.
            assertFalse(actor.done().isDone(),
                    "delegate return cannot certify the provider callback lifetime");
        } finally {
            allowProviderExit.countDown();
            provider.join(3_000);
            assertFalse(provider.isAlive());
        }
        if (threadFailure.get() != null) {
            throw new AssertionError("provider callback failed", threadFailure.get());
        }
        assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS),
                "driver exit witness should release the final physical receipt");
        runtime.close();
    }
}
