package dev.oreslang.runtime;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Independent concurrent-abort and normal-worker-close proof. */
public final class HttpResponseCloseRaceCheck {
    public static void main(String[] args) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            AtomicInteger opens = new AtomicInteger();
            AtomicInteger closes = new AtomicInteger();
            HttpResponseDraft response = HttpResponseDraft.streaming(() -> {
                opens.incrementAndGet();
                return new ByteArrayInputStream(new byte[] {1, 2, 3}) {
                    @Override public void close() throws IOException {
                        closes.incrementAndGet();
                        super.close();
                    }
                };
            }, 200, List.of());
            response.claim();
            InputStream body = response.openBody();
            CountDownLatch ready = new CountDownLatch(8);
            CountDownLatch start = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(8)) {
                @SuppressWarnings("unchecked")
                Future<?>[] jobs = new Future<?>[8];
                for (int i = 0; i < jobs.length; i++) {
                    jobs[i] = pool.submit(() -> {
                        ready.countDown();
                        try {
                            if (!start.await(3, TimeUnit.SECONDS)) throw new AssertionError("start timed out");
                            body.close();
                        } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                        catch (IOException error) { throw new AssertionError(error); }
                    });
                }
                if (!ready.await(3, TimeUnit.SECONDS)) throw new AssertionError("workers did not start");
                start.countDown();
                for (Future<?> job : jobs) job.get(3, TimeUnit.SECONDS);
            }
            if (opens.get() != 1 || closes.get() != 1)
                throw new AssertionError("stream opened " + opens.get() + "/closed " + closes.get());
            try { response.openBody(); throw new AssertionError("body opened twice"); }
            catch (IllegalStateException expected) { }
        }
        System.out.println("PASS: 100 concurrent-close attempts, 8 closers, one underlying close each");
    }
}
