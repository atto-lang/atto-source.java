package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class SupervisorEmergencyControlShutdownStandalone {
    private static void check(boolean ok) {
        if (!ok) throw new AssertionError("shutdown invariant");
    }

    private static void await(CountDownLatch latch) {
        try { check(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException error) { throw new AssertionError(error); }
    }

    public static void main(String[] args) {
        for (int i = 0; i < 128; i++) {
            var control = new SupervisorEmergencyControlV1();
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var completed = new AtomicInteger();
            control.execute(() -> { entered.countDown(); await(release); });
            await(entered);
            control.execute(completed::incrementAndGet);
            try {
                check(control.availabilityFault() == null);
                check(!control.closeWithin(1, TimeUnit.MILLISECONDS));
                check(completed.get() == 0);
                check(control.availabilityFault() instanceof RejectedExecutionException);
                try { control.execute(completed::incrementAndGet); throw new AssertionError("closed admission"); }
                catch (RejectedExecutionException expected) { check(expected == control.availabilityFault()); }
                Thread.currentThread().interrupt();
                check(!control.closeWithin(1, TimeUnit.MILLISECONDS));
                check(Thread.interrupted());
            } finally { release.countDown(); }
            check(control.closeWithin(5, TimeUnit.SECONDS));
            check(completed.get() == 1);
            check(control.failure() == null);
            check(control.closeWithin(0, TimeUnit.NANOSECONDS));
            check(control.availabilityFault() != null);
        }
        var failed = new SupervisorEmergencyControlV1();
        var original = new IllegalStateException("original");
        failed.execute(() -> { throw original; });
        failed.close();
        check(failed.failure() == original && failed.availabilityFault() == original);
        System.out.println("PASS CONTROL_SHUTDOWN_TIMEOUT_CYCLES=128 PENDING_WAKE_RETAINED=1 INTERRUPT_PRESERVED=1 CLOSED_RESTART_DENIED=1 FIRST_FAILURE_PRESERVED=1");
    }
}
