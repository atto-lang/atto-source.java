package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class CarrierCpuBindingTest {
    @Test void bindsOncePerCarrierNotOncePerActorTurn() {
        try {
            AtomicInteger calls = new AtomicInteger();
            for (int i = 0; i < 10_000; i++) {
                CarrierCpuBinding.ensureBound(3, cpu -> {
                    assertEquals(3, cpu);
                    calls.incrementAndGet();
                }, () -> 42L);
            }
            assertEquals(1, calls.get());
        } finally {
            CarrierCpuBinding.clearCurrentCarrier();
        }
    }

    @Test void cpuChangeExpiryAndClockRegressionRequireRebind() {
        try {
            AtomicInteger calls = new AtomicInteger();
            CarrierCpuBinding.ensureBound(1, cpu -> calls.incrementAndGet(), () -> 50L);
            CarrierCpuBinding.ensureBound(1, cpu -> calls.incrementAndGet(), () -> 51L);
            assertEquals(1, calls.get());

            CarrierCpuBinding.ensureBound(2, cpu -> calls.incrementAndGet(), () -> 52L);
            assertEquals(2, calls.get());

            CarrierCpuBinding.ensureBound(2, cpu -> calls.incrementAndGet(),
                    () -> 52L + CarrierCpuBinding.REBIND_INTERVAL_NANOS);
            assertEquals(3, calls.get());

            // A discontinuity/wrap in the monotonic source must not suppress binding.
            CarrierCpuBinding.ensureBound(2, cpu -> calls.incrementAndGet(), () -> 0L);
            assertEquals(4, calls.get());
        } finally {
            CarrierCpuBinding.clearCurrentCarrier();
        }
    }

    @Test void failedRebindNeverMarksCpuAsPinned() {
        try {
            AtomicInteger attempts = new AtomicInteger();
            assertThrows(IllegalStateException.class, () ->
                    CarrierCpuBinding.ensureBound(4, cpu -> {
                        attempts.incrementAndGet();
                        throw new IllegalStateException("OS refused mask");
                    }, () -> 42L));
            CarrierCpuBinding.ensureBound(4, cpu -> attempts.incrementAndGet(), () -> 42L);
            assertEquals(2, attempts.get());
        } finally {
            CarrierCpuBinding.clearCurrentCarrier();
        }
    }

    @Test void carrierBindingDoesNotLeakAcrossPhysicalThreads() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(2);
        Runnable run = () -> {
            try {
                CarrierCpuBinding.ensureBound(7, cpu -> calls.incrementAndGet(), () -> 12L);
                CarrierCpuBinding.ensureBound(7, cpu -> calls.incrementAndGet(), () -> 13L);
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
            } finally {
                CarrierCpuBinding.clearCurrentCarrier();
                finished.countDown();
            }
        };
        Thread a = Thread.ofPlatform().start(run);
        Thread b = Thread.ofPlatform().start(run);
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        a.join();
        b.join();
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertEquals(2, calls.get());
    }

    @Test void rejectsInvalidCpuWithoutNativeCall() {
        assertThrows(IllegalArgumentException.class,
                () -> CarrierCpuBinding.ensureBound(-1, cpu -> fail("should not bind"), () -> 0L));
    }
}
