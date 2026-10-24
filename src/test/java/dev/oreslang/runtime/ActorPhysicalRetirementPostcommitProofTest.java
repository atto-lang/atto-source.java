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
 * These are deliberately RED proof obligations against the current stacked
 * draft; do not merge until the implementation makes them pass. A Future
 * terminal state is not native-driver exit evidence.
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
        // Currently fails: registry.retired() became true before the failure
        // was latched. Requires prepare/commit separation or a provably
        // nonthrowing terminal publication boundary.
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
                // callback loan until this outer invocation returns.
            } catch (Throwable failure) {
                threadFailure.set(failure);
            }
        });
        try {
            assertTrue(callbackReturned.await(3, TimeUnit.SECONDS));
            // Currently fails: the Java CompletionHandler wrapper has no
            // trusted witness for the enclosing provider invocation's exit.
            assertFalse(actor.done().isDone(),
                    "delegate return cannot certify the provider callback lifetime");
        } finally {
            allowProviderExit.countDown();
            provider.join(3_000);
            assertFalse(provider.isAlive());
            // This scenario intentionally demonstrates a missing driver
            // witness; no external source or runner is involved.
        }
        if (threadFailure.get() != null) {
            throw new AssertionError("provider callback failed", threadFailure.get());
        }
        runtime.close();
    }
}
