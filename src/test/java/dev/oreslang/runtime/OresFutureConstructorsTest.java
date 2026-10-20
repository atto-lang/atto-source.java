package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class OresFutureConstructorsTest {
    @Test
    void asyncRunStartsOnRuntimeCarrierAndReturnsTypedValue() throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime()) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            OresFuture<Integer> future = OresFutureConstructors.fromAsyncRun(
                    runtime, () -> {
                        assertTrue(AsyncRuntime.isAsyncCarrierThread());
                        entered.countDown();
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                        return 5 * 8;
                    });

            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertFalse(future.isDone());
            release.countDown();
            assertEquals(40, AsyncRuntime.await(future));
        }
    }

    @Test
    void asyncRunFailurePreservesOriginalFailure() {
        try (AsyncRuntime runtime = new AsyncRuntime()) {
            IllegalStateException original = new IllegalStateException("run failed");
            OresFuture<Integer> future = OresFutureConstructors.fromAsyncRun(
                    runtime, () -> { throw original; });

            assertSame(original, assertThrows(
                    IllegalStateException.class, () -> AsyncRuntime.await(future)));
        }
    }

    @Test
    void callbackRunReturningWithoutSettlingKeepsFuturePending() throws Exception {
        AtomicReference<OresFuture.Callback<Integer>> resolver = new AtomicReference<>();
        OresFuture<Integer> future = OresFutureConstructors.fromRunCallback(
                resolver::set);

        assertNotNull(resolver.get());
        assertFalse(future.isDone(), "returning from run must not resolve");
        resolver.get().resolve(5 * 8);
        assertEquals(40, future.get(5, TimeUnit.SECONDS));
        assertThrows(OresFuture.AlreadySettledException.class,
                () -> resolver.get().resolve(41));
    }

    @Test
    void callbackRunFailureBeforeSettlementFailsFuture() {
        IllegalStateException original = new IllegalStateException("registration failed");
        OresFuture<Integer> future = OresFutureConstructors.fromRunCallback(
                callback -> { throw original; });

        var failed = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> future.get(5, TimeUnit.SECONDS));
        assertSame(original, failed.getCause());
    }

    @Test
    void callbackResolutionBeatsLaterRegistrarFailure() throws Exception {
        OresFuture<Integer> future = OresFutureConstructors.fromRunCallback(
                callback -> {
                    callback.resolve(40);
                    throw new IllegalStateException("too late");
                });

        assertEquals(40, future.get(5, TimeUnit.SECONDS));
    }

    @Test
    void cancellationCannotBeUndoneByLateCallback() {
        AtomicReference<OresFuture.Callback<Integer>> resolver = new AtomicReference<>();
        OresFuture<Integer> future = OresFutureConstructors.fromRunCallback(
                resolver::set);

        assertTrue(future.cancel(false));
        assertDoesNotThrow(() -> resolver.get().resolve(40));
        assertTrue(future.isCancelled());
        assertThrows(OresFuture.AlreadySettledException.class,
                () -> resolver.get().resolve(41));
    }

    @Test
    void nullAdaptersAreRejected() {
        assertThrows(NullPointerException.class,
                () -> OresFutureConstructors.fromRunCallback(null));
        try (AsyncRuntime runtime = new AsyncRuntime()) {
            assertThrows(NullPointerException.class,
                    () -> OresFutureConstructors.fromAsyncRun(runtime, null));
        }
    }
}
