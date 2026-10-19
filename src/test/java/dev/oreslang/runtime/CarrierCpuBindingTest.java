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
                }, () -> 3, () -> 42L);
            }
            assertEquals(1, calls.get());
        } finally {
            CarrierCpuBinding.clearCurrentCarrier();
        }
    }

    @Test void cpuChangeExpiryAndClockRegressionRequireRebind() {
        try {
            AtomicInteger calls = new AtomicInteger();
            CarrierCpuBinding.ensureBound(1, cpu -> calls.incrementAndGet(), () -> 1, () -> 50L);
            CarrierCpuBinding.ensureBound(1, cpu -> calls.incrementAndGet(), () -> 1, () -> 51L);
            assertEquals(1, calls.get());

            CarrierCpuBinding.ensureBound(2, cpu -> calls.incrementAndGet(), () -> 2, () -> 52L);
            assertEquals(2, calls.get());

            CarrierCpuBinding.ensureBound(2, cpu -> calls.incrementAndGet(),
                    () -> 2, () -> 52L + CarrierCpuBinding.REBIND_INTERVAL_NANOS);
            assertEquals(3, calls.get());

            // A discontinuity/wrap in the monotonic source must not suppress binding.
            CarrierCpuBinding.ensureBound(2, cpu -> calls.incrementAndGet(), () -> 2, () -> 0L);
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
                    }, () -> 4, () -> 42L));
            CarrierCpuBinding.ensureBound(4, cpu -> attempts.incrementAndGet(), () -> 4, () -> 42L);
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
                CarrierCpuBinding.ensureBound(7, cpu -> calls.incrementAndGet(), () -> 7, () -> 7, () -> 12L);
                CarrierCpuBinding.ensureBound(7, cpu -> calls.incrementAndGet(), () -> 7, () -> 7, () -> 13L);
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
                () -> CarrierCpuBinding.ensureBound(-1, cpu -> fail("should not bind"), () -> -1, () -> 0L));
    }
    @Test void observedCarrierDriftRequiresImmediateRebind() {
        var actual = new AtomicInteger(-1);
        var binds = new AtomicInteger();
        java.util.function.IntConsumer binder = cpu -> { binds.incrementAndGet(); actual.set(cpu); };
        try {
            CarrierCpuBinding.ensureBound(3, binder, actual::get, () -> 100L);
            CarrierCpuBinding.ensureBound(3, binder, actual::get, () -> 101L);
            assertEquals(1, binds.get());
            actual.set(7); // External native code or a cpuset change.
            CarrierCpuBinding.ensureBound(3, binder, actual::get, () -> 102L);
            assertEquals(2, binds.get());
            assertEquals(3, actual.get());
        } finally {
            CarrierCpuBinding.clearCurrentCarrier();
        }
    }

    @Test void failedCpuChangeRevokesPreviouslyValidCache() {
        var actual = new AtomicInteger(-1);
        var binds = new AtomicInteger();
        try {
            CarrierCpuBinding.ensureBound(1, cpu -> { binds.incrementAndGet(); actual.set(cpu); },
                    actual::get, () -> 100L);
            assertThrows(IllegalStateException.class, () ->
                    CarrierCpuBinding.ensureBound(2, cpu -> {
                        binds.incrementAndGet();
                        actual.set(6); // Simulate partial native mutation before failure.
                        throw new IllegalStateException("failed native bind");
                    }, actual::get, () -> 101L));
            CarrierCpuBinding.ensureBound(1, cpu -> { binds.incrementAndGet(); actual.set(cpu); },
                    actual::get, () -> 102L);
            assertEquals(3, binds.get());
            assertEquals(1, actual.get());
        } finally {
            CarrierCpuBinding.clearCurrentCarrier();
        }
    }

    @Test void unsuccessfulCpuObservationCannotCertifyAffinity() {
        try {
            assertThrows(IllegalStateException.class, () ->
                    CarrierCpuBinding.ensureBound(4, cpu -> { }, () -> 9, () -> 42L));
            var binds = new AtomicInteger();
            CarrierCpuBinding.ensureBound(4, cpu -> binds.incrementAndGet(), () -> 4, () -> 42L);
            assertEquals(1, binds.get());
        } finally {
            CarrierCpuBinding.clearCurrentCarrier();
        }
    }

}
