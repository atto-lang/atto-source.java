package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cross-execution-surface cooperation gate. Source Tasks hand off via an
 * OresScheduler.Cooperate continuation; only legacy generator activations
 * may use AsyncRuntime's owned virtual-thread compatibility bridge.
 */
@Timeout(15)
final class AsyncRuntimeCooperationTest {

    @Test
    void ordinaryPlatformThreadCannotClaimItCooperatedAsAGenerator() {
        IllegalStateException denied = assertThrows(
                IllegalStateException.class,
                AsyncRuntime::cooperateCurrentCarrier);
        assertTrue(denied.getMessage().contains("owned virtual generator carrier"));
    }

    @Test
    void runtimeOwnedVirtualCarrierCanCooperateAndResume() throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime()) {
            OresFuture<Boolean> future = runtime.submit(() -> {
                assertTrue(Thread.currentThread().isVirtual());
                assertTrue(AsyncRuntime.isAsyncCarrierThread());
                AsyncRuntime.cooperateCurrentCarrier();
                return AsyncRuntime.isAsyncCarrierThread();
            });
            assertTrue(future.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void cancellationIsObservedBeforeGeneratorCarrierCanYield() throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime()) {
            OresFuture<Boolean> future = runtime.submit(() -> {
                Thread.currentThread().interrupt();
                try {
                    assertThrows(
                            CancellationException.class,
                            AsyncRuntime::cooperateCurrentCarrier);
                } finally {
                    Thread.interrupted();
                }
                return true;
            });
            assertTrue(future.get(5, TimeUnit.SECONDS));
        }
    }
}
