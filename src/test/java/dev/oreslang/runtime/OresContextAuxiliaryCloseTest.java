package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic failure injection into the same auxiliary/drain controller
 * called by the real OresContext.close() path. These tests are not a
 * substitute for a full Truffle context or native actor-retirement run.
 */
final class OresContextAuxiliaryCloseTest {
    @Test
    void auxiliaryFailuresStillAttemptStdioDrainAndRetryOnlyIncompleteStages() {
        var stages = new OresContext.AuxiliaryCloseStages();
        var http = new AtomicInteger();
        var async = new AtomicInteger();
        var vm = new AtomicInteger();
        var stdio = new AtomicInteger();

        Runnable closeHttp = () -> {
            if (http.incrementAndGet() == 1) throw new IllegalStateException("http-first");
        };
        Runnable closeAsync = async::incrementAndGet;
        Runnable closeVm = () -> {
            if (vm.incrementAndGet() == 1) throw new IllegalStateException("vm-first");
        };
        Runnable closeStdio = () -> {
            if (stdio.incrementAndGet() == 1) {
                throw new IllegalStateException("drain-first");
            }
        };

        IllegalStateException failed = assertThrows(IllegalStateException.class,
                () -> stages.closeRemaining(closeHttp, closeAsync, closeVm, closeStdio));
        assertTrue(failed.getMessage().contains("auxiliary shutdown incomplete"));
        Throwable first = failed.getCause();
        assertNotNull(first);
        assertEquals("http-first", first.getMessage());
        assertEquals(2, first.getSuppressed().length,
                "secondary VM and async-drain errors must remain inspectable");
        assertEquals("vm-first", first.getSuppressed()[0].getMessage());
        assertEquals("drain-first", first.getSuppressed()[1].getMessage());
        assertEquals(1, http.get());
        assertEquals(1, async.get());
        assertEquals(1, vm.get());
        assertEquals(1, stdio.get(),
                "stdout drain must still be attempted after auxiliary failure");

        assertDoesNotThrow(() -> stages.closeRemaining(closeHttp, closeAsync, closeVm, closeStdio));
        assertEquals(2, http.get(), "only failed HTTP close is retried");
        assertEquals(1, async.get(), "successful async close is never repeated");
        assertEquals(2, vm.get(), "only failed VM close is retried");
        assertEquals(2, stdio.get(), "only failed stdio drain is retried");

        assertDoesNotThrow(() -> stages.closeRemaining(closeHttp, closeAsync, closeVm, closeStdio));
        assertEquals(2, http.get());
        assertEquals(1, async.get());
        assertEquals(2, vm.get());
        assertEquals(2, stdio.get());
    }

    @Test
    void successfulDrainIsNotRepeatedWhenVmCleanupMustRetry() {
        var stages = new OresContext.AuxiliaryCloseStages();
        var http = new AtomicInteger();
        var async = new AtomicInteger();
        var vm = new AtomicInteger();
        var stdio = new AtomicInteger();

        Runnable closeVm = () -> {
            if (vm.incrementAndGet() == 1) throw new IllegalStateException("vm-physical");
        };
        IllegalStateException failed = assertThrows(IllegalStateException.class,
                () -> stages.closeRemaining(http::incrementAndGet, async::incrementAndGet,
                        closeVm, stdio::incrementAndGet));
        assertTrue(failed.getMessage().contains("auxiliary shutdown incomplete"));
        assertEquals(1, stdio.get(), "writer drain must not be skipped by failed VM close");

        assertDoesNotThrow(() -> stages.closeRemaining(http::incrementAndGet,
                async::incrementAndGet, closeVm, stdio::incrementAndGet));
        assertEquals(1, http.get());
        assertEquals(1, async.get());
        assertEquals(2, vm.get());
        assertEquals(1, stdio.get(), "successful drain receipt survives partial failure");
    }

    @Test
    void drainOnlyFailureHasDistinctErrorAndRetriesWithoutReclosingHealthyServices() {
        var stages = new OresContext.AuxiliaryCloseStages();
        var http = new AtomicInteger();
        var async = new AtomicInteger();
        var vm = new AtomicInteger();
        var stdio = new AtomicInteger();

        Runnable closeStdio = () -> {
            if (stdio.incrementAndGet() == 1) {
                throw new IllegalStateException("writer-blocked");
            }
        };
        IllegalStateException failed = assertThrows(IllegalStateException.class,
                () -> stages.closeRemaining(http::incrementAndGet, async::incrementAndGet,
                        vm::incrementAndGet, closeStdio));
        assertTrue(failed.getMessage().contains("logging drain incomplete"));
        assertEquals("writer-blocked", failed.getCause().getMessage());

        assertDoesNotThrow(() -> stages.closeRemaining(http::incrementAndGet,
                async::incrementAndGet, vm::incrementAndGet, closeStdio));
        assertEquals(1, http.get());
        assertEquals(1, async.get());
        assertEquals(1, vm.get());
        assertEquals(2, stdio.get());
    }

    @Test
    void sharedFailureObjectCannotBecomeItsOwnSuppressedException() {
        var stages = new OresContext.AuxiliaryCloseStages();
        var common = new IllegalStateException("shared-stage-failure");
        var stdio = new AtomicInteger();
        IllegalStateException failed = assertThrows(IllegalStateException.class,
                () -> stages.closeRemaining(
                        () -> { throw common; },
                        () -> { throw common; },
                        () -> { throw common; },
                        stdio::incrementAndGet));
        assertSame(common, failed.getCause());
        assertEquals(0, common.getSuppressed().length);
        assertEquals(1, stdio.get(), "a shared auxiliary error must not skip drain");
    }
}
