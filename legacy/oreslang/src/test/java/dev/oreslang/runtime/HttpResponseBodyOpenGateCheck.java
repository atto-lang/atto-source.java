package dev.oreslang.runtime;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Source-only bounded producer acquisition proof; no compiler dependencies. */
public final class HttpResponseBodyOpenGateCheck {
    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
    }
    private static void eventually(java.util.function.BooleanSupplier yes, String label) throws Exception {
        for (int i = 0; i < 200 && !yes.getAsBoolean(); i++) Thread.sleep(5);
        check(yes.getAsBoolean(), label);
    }

    public static void main(String[] ignored) throws Exception {
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            HttpResponseBodyOpenGate gate = new HttpResponseBodyOpenGate(1, workers);
            for (int attempt = 0; attempt < 30; attempt++) {
                CountDownLatch started = new CountDownLatch(1);
                CountDownLatch release = new CountDownLatch(1);
                AtomicInteger closed = new AtomicInteger();
                HttpResponseDraft stuck = HttpResponseDraft.streaming(() -> {
                    started.countDown();
                    while (true) {
                        try {
                            if (release.await(2, TimeUnit.SECONDS)) break;
                        } catch (InterruptedException ignoredInterrupt) {
                            // Simulate a hostile/noncooperative supplier.
                        }
                    }
                    return new ByteArrayInputStream(new byte[] { 1 }) {
                        @Override public void close() throws IOException {
                            closed.incrementAndGet();
                            super.close();
                        }
                    };
                }, 200, List.of());
                stuck.claim();
                try {
                    gate.open(stuck, () -> false, TimeUnit.MILLISECONDS.toNanos(75));
                    throw new AssertionError("stuck supplier admitted beyond deadline");
                } catch (IOException expected) {
                    check(expected.getMessage().contains("deadline"), "deadline reason");
                }
                check(started.await(1, TimeUnit.SECONDS), "stuck supplier began");
                check(gate.availableSlots() == 0, "hung supplier retains its permit");
                AtomicInteger secondOpened = new AtomicInteger();
                HttpResponseDraft rejected = HttpResponseDraft.streaming(() -> {
                    secondOpened.incrementAndGet();
                    return InputStream.nullInputStream();
                }, 200, List.of());
                rejected.claim();
                try {
                    gate.open(rejected, () -> false, TimeUnit.SECONDS.toNanos(1));
                    throw new AssertionError("second supplier escaped capacity cap");
                } catch (IOException expected) {
                    check(expected.getMessage().contains("admission"), "admission reason");
                }
                check(secondOpened.get() == 0, "no supplier spawned while slots exhausted");
                release.countDown();
                eventually(() -> closed.get() == 1 && gate.availableSlots() == 1,
                        "late-opened response must close and release slot");
            }

            AtomicBoolean cancelled = new AtomicBoolean();
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger lateCloses = new AtomicInteger();
            HttpResponseDraft cancellation = HttpResponseDraft.streaming(() -> {
                started.countDown();
                while (true) {
                    try { if (release.await(2, TimeUnit.SECONDS)) break; }
                    catch (InterruptedException ignoredInterrupt) { }
                }
                return new ByteArrayInputStream(new byte[] { 1 }) {
                    @Override public void close() throws IOException {
                        lateCloses.incrementAndGet();
                        super.close();
                    }
                };
            }, 200, List.of());
            cancellation.claim();
            var waiting = workers.submit(() -> {
                try {
                    gate.open(cancellation, cancelled::get, TimeUnit.SECONDS.toNanos(3));
                    throw new AssertionError("cancelled opener returned a body");
                } catch (IOException expected) {
                    check(expected.getMessage().contains("cancelled"), "cancellation reason");
                }
            });
            check(started.await(1, TimeUnit.SECONDS), "cancel test supplier began");
            cancelled.set(true);
            waiting.get(1, TimeUnit.SECONDS);
            check(gate.availableSlots() == 0, "cancelled-but-hung opener retains permit");
            release.countDown();
            eventually(() -> lateCloses.get() == 1 && gate.availableSlots() == 1,
                    "cancelled late stream must close and release slot");

            HttpResponseDraft good = HttpResponseDraft.streaming(
                    () -> new ByteArrayInputStream(new byte[] { 7 }), 200, List.of());
            good.claim();
            try (InputStream body = gate.open(good, () -> false, TimeUnit.SECONDS.toNanos(2))) {
                check(body.read() == 7 && body.read() < 0, "normal producer body");
            }
            eventually(() -> gate.availableSlots() == 1, "normal supplier releases permit");
            check(gate.availableSlots() == 1, "no slot leakage");
        }
        System.out.println("PASS: 30 deadlines, permit retention, bounded overload, cancellation and late cleanup");
    }
}
