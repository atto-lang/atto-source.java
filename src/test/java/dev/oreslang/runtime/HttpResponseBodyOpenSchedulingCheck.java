package dev.oreslang.runtime;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Verify deadline includes executor dispatch, plus queued cancellation and failure cleanup. */
public final class HttpResponseBodyOpenSchedulingCheck {
    private static void check(boolean ok, String reason) {
        if (!ok) throw new AssertionError(reason);
    }
    private static void eventual(java.util.function.BooleanSupplier condition, String reason) throws Exception {
        for (int i = 0; i < 200 && !condition.getAsBoolean(); i++) Thread.sleep(5);
        check(condition.getAsBoolean(), reason);
    }
    public static void main(String[] unused) throws Exception {
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            AtomicInteger opens = new AtomicInteger();
            AtomicInteger closes = new AtomicInteger();
            Executor delayedDispatch = job -> {
                try { Thread.sleep(100); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                workers.execute(job);
            };
            HttpResponseBodyOpenGate gate = new HttpResponseBodyOpenGate(1, delayedDispatch);
            HttpResponseDraft draft = HttpResponseDraft.streaming(() -> {
                opens.incrementAndGet();
                return new ByteArrayInputStream(new byte[] { 42 }) {
                    @Override public void close() throws IOException {
                        closes.incrementAndGet(); super.close();
                    }
                };
            }, 200, List.of());
            draft.claim();
            try {
                gate.open(draft, () -> false, TimeUnit.MILLISECONDS.toNanos(25));
                throw new AssertionError("dispatch delay incorrectly reset body-opening deadline");
            } catch (IOException expected) {
                check(expected.getMessage().contains("deadline"), "deadline reason");
            }
            eventual(() -> gate.availableSlots() == 1, "delayed dispatch permit released");
            check(opens.get() <= 1 && opens.get() == closes.get(), "late stream closed");

            AtomicReference<Runnable> enqueued = new AtomicReference<>();
            HttpResponseBodyOpenGate queued = new HttpResponseBodyOpenGate(1, enqueued::set);
            AtomicInteger unwanted = new AtomicInteger();
            HttpResponseDraft pending = HttpResponseDraft.streaming(() -> {
                unwanted.incrementAndGet(); return InputStream.nullInputStream();
            }, 200, List.of());
            pending.claim();
            try { queued.open(pending, () -> false, TimeUnit.MILLISECONDS.toNanos(20));
                  throw new AssertionError("queued task avoided deadline"); }
            catch (IOException expected) { check(expected.getMessage().contains("deadline"), "queue timeout"); }
            check(queued.availableSlots() == 0, "queued admission retained");
            check(enqueued.get() != null, "queued task exists");
            workers.submit(enqueued.get()).get(2, TimeUnit.SECONDS);
            check(unwanted.get() == 0, "abandoned queued producer should not start");
            eventual(() -> queued.availableSlots() == 1, "queued permit released");

            HttpResponseBodyOpenGate rejected = new HttpResponseBodyOpenGate(1,
                command -> { throw new RejectedExecutionException("shutdown"); });
            HttpResponseDraft never = HttpResponseDraft.streaming(InputStream::nullInputStream, 200, List.of());
            never.claim();
            try { rejected.open(never, () -> false, TimeUnit.SECONDS.toNanos(1));
                  throw new AssertionError("rejected executor accepted task"); }
            catch (IOException expected) { check(expected.getMessage().contains("could not start"), "rejection error"); }
            check(rejected.availableSlots() == 1, "rejected executor must return permit");

            HttpResponseBodyOpenGate thrown = new HttpResponseBodyOpenGate(1, workers);
            HttpResponseDraft bad = HttpResponseDraft.streaming(() -> { throw new IllegalStateException("bad supplier"); }, 200, List.of());
            bad.claim();
            try { thrown.open(bad, () -> false, TimeUnit.SECONDS.toNanos(1));
                  throw new AssertionError("failed supplier returned"); }
            catch (IllegalStateException expected) { check(expected.getMessage().contains("bad supplier"), "supplier error"); }
            eventual(() -> thrown.availableSlots() == 1, "failed supplier returned permit");
        }
        System.out.println("PASS: scheduling deadline, queued abandonment, rejected executor, failed supplier");
    }
}
