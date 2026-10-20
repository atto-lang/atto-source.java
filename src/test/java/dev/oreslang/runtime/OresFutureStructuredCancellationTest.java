package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class OresFutureStructuredCancellationTest {

    @Test
    void cancellationClaimIsNotRuntimeReadyUntilRevoked() {
        AtomicBoolean revoked = new AtomicBoolean();
        AtomicBoolean terminalReady = new AtomicBoolean();

        OresFuture<Integer> future = new OresFuture<>(
                () -> true,
                () -> revoked.set(true),
                () -> terminalReady.set(true),
                () -> { });

        OresFuture.RuntimeCancellation<Integer> claim =
                future.claimCancellationFromRuntime();

        assertNotNull(claim);
        assertTrue(future.isCancelled());
        assertFalse(revoked.get());
        assertFalse(terminalReady.get());
        assertNull(future.runtimeTerminalStateOrNull(),
                "cancelled state must remain hidden from runtime readiness until revocation");

        claim.revoke();

        assertTrue(revoked.get());
        assertTrue(terminalReady.get());
        assertNotNull(future.runtimeTerminalStateOrNull());
    }

    @Test
    void terminalReadyRunsBeforeBlockingCancellationSubscriber() throws Exception {
        AtomicBoolean revoked = new AtomicBoolean();
        AtomicBoolean terminalReadySawRevocation = new AtomicBoolean();
        CountDownLatch subscriberEntered = new CountDownLatch(1);
        CountDownLatch releaseSubscriber = new CountDownLatch(1);

        OresFuture<Integer> future = new OresFuture<>(
                () -> true,
                () -> revoked.set(true),
                () -> terminalReadySawRevocation.set(revoked.get()),
                () -> { });

        future.whenCompleteRuntime((value, failure) -> {
            subscriberEntered.countDown();
            try {
                releaseSubscriber.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });

        OresFuture.RuntimeCancellation<Integer> claim =
                future.claimCancellationFromRuntime();
        assertNotNull(claim);
        claim.revoke();

        assertTrue(terminalReadySawRevocation.get(),
                "terminal-ready must run only after cancellation authority is revoked");

        Thread publisher = Thread.ofPlatform().start(claim::publish);
        assertTrue(subscriberEntered.await(5, TimeUnit.SECONDS));
        assertTrue(publisher.isAlive(),
                "ordinary subscriber should be able to block publication without undoing revocation");
        assertNotNull(future.runtimeTerminalStateOrNull());

        releaseSubscriber.countDown();
        publisher.join(5000);
        assertFalse(publisher.isAlive());
    }

    @Test
    void terminalReadyPrecedesSuccessAndFailureSubscribers() {
        AtomicInteger successOrder = new AtomicInteger();
        OresFuture<Integer> success = new OresFuture<>(
                () -> true,
                () -> { },
                () -> successOrder.compareAndSet(0, 1),
                () -> { });
        success.whenCompleteRuntime((value, failure) ->
                successOrder.compareAndSet(1, 2));

        assertTrue(success.completeFromRuntime(7));
        assertEquals(2, successOrder.get());

        AtomicInteger failureOrder = new AtomicInteger();
        OresFuture<Integer> failed = new OresFuture<>(
                () -> true,
                () -> { },
                () -> failureOrder.compareAndSet(0, 1),
                () -> { });
        failed.whenCompleteRuntime((value, failure) ->
                failureOrder.compareAndSet(1, 2));

        assertTrue(failed.failFromRuntime(new IllegalStateException("boom")));
        assertEquals(2, failureOrder.get());
    }

    @Test
    void splitCancellationRejectsPublicationBeforeRevocation() {
        OresFuture<Integer> future = new OresFuture<>();
        OresFuture.RuntimeCancellation<Integer> claim =
                future.claimCancellationFromRuntime();

        assertNotNull(claim);
        assertThrows(IllegalStateException.class, claim::publish);
        assertNull(future.runtimeTerminalStateOrNull());

        claim.revoke();
        claim.publish();
        assertTrue(future.isCancelled());
        assertNotNull(future.runtimeTerminalStateOrNull());
    }

    @Test
    void onlyOneCancellationClaimCanWin() {
        OresFuture<Integer> future = new OresFuture<>();
        OresFuture.RuntimeCancellation<Integer> first =
                future.claimCancellationFromRuntime();

        assertNotNull(first);
        assertNull(future.claimCancellationFromRuntime());

        first.revoke();
        first.publish();
        assertTrue(future.isCancelled());
    }
}
