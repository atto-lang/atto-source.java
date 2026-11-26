package dev.oreslang.runtime;

import java.lang.management.ManagementFactory;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class SupervisorEmergencyControlV1Standalone {
    private static void check(boolean value) {
        if (!value) throw new AssertionError("CONTROL carrier invariant");
    }

    private static void await(CountDownLatch latch) {
        try { check(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { throw new AssertionError(e); }
    }

    private static void until(AtomicInteger done, int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (done.get() != expected) {
            if (System.nanoTime() - deadline >= 0) throw new AssertionError("CONTROL wake lost");
            Thread.onSpinWait();
        }
    }

    private static RejectedExecutionException rejected(SupervisorEmergencyControlV1 control, Runnable wake) {
        try { control.execute(wake); }
        catch (RejectedExecutionException e) { return e; }
        throw new AssertionError("unexpected CONTROL admission");
    }

    public static void main(String[] args) {
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        check(bean.isThreadAllocatedMemorySupported());
        bean.setThreadAllocatedMemoryEnabled(true);
        long id = Thread.currentThread().threadId();
        var done = new AtomicInteger();
        var observed = new AtomicReference<Thread>();
        Thread caller = Thread.currentThread();
        Runnable wake = () -> { observed.set(Thread.currentThread()); done.incrementAndGet(); };
        try (var control = new SupervisorEmergencyControlV1()) {
            for (int i = 1; i <= 100_000; i++) { control.execute(wake); until(done, i); }
            check(observed.get() != caller);
            long before = bean.getThreadAllocatedBytes(id);
            for (int i = 100_001; i <= 200_000; i++) { control.execute(wake); until(done, i); }
            long delta = bean.getThreadAllocatedBytes(id) - before;
            check(before >= 0 && delta == 0);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            Runnable held = () -> { entered.countDown(); await(release); };
            control.execute(held);
            await(entered);
            control.execute(wake);
            try { check(rejected(control, wake) == rejected(control, wake)); }
            finally { release.countDown(); }
            until(done, 200_001);
            check(control.failure() == null);
            System.out.println("PASS CONTROL_WAKE_CYCLES=100000 SUBMIT_ALLOCATED_BYTES=0 BOUNDED_SLOT=1 OFF_CALLER=1");
        }
        var failed = new SupervisorEmergencyControlV1();
        var original = new IllegalStateException("injected trusted CONTROL failure");
        failed.execute(() -> { throw original; });
        failed.close();
        check(failed.failure() == original);
        check(rejected(failed, wake) == rejected(failed, wake));
        System.out.println("PASS CONTROL_FAILURE_IDENTITY=1 CLOSED_ADMISSION_DENIED=1");
    }
}
