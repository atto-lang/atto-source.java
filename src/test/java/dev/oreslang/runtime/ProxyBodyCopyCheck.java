package dev.oreslang.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** JDK-only adversarial worker/cancellation handoff test. */
public final class ProxyBodyCopyCheck {
    private static class Response implements ProxyBodyCopy.Source {
        final AtomicInteger closes = new AtomicInteger();
        final CountDownLatch writing;
        final CountDownLatch resume;
        Response(CountDownLatch writing, CountDownLatch resume) {
            this.writing = writing;
            this.resume = resume;
        }
        public int status() { return 206; }
        public List<Map.Entry<String, String>> headers() { return List.of(Map.entry("Set-Cookie", "a=1")); }
        public long streamBody(OutputStream sink, BooleanSupplier cancelled) throws IOException {
            writing.countDown();
            try { if (!resume.await(5, TimeUnit.SECONDS)) throw new IOException("fixture timeout"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
            if (cancelled.getAsBoolean()) throw new IOException("cancelled");
            sink.write(new byte[]{1,2,3});
            return 3;
        }
        public void close() { closes.incrementAndGet(); }
    }
    public static void main(String[] args) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i=0;i<150;i++) {
                var writing = new CountDownLatch(1);
                var resume = new CountDownLatch(1);
                var response = new Response(writing, resume);
                var sink = new ByteArrayOutputStream();
                var copied = ProxyBodyCopy.start(CompletableFuture.completedFuture(response), sink, executor);
                if (!writing.await(5, TimeUnit.SECONDS)) throw new AssertionError("worker not started");
                if (!copied.cancel(true)) throw new AssertionError("cancel failed");
                if (response.closes.get()!=0) throw new AssertionError("early close during physical copy");
                resume.countDown();
                long until = System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(response.closes.get()==0 && System.nanoTime()<until) Thread.sleep(1);
                if(response.closes.get()!=1 || !sink.toString().isEmpty())
                    throw new AssertionError("cancel delivered bytes or leaked response");
            }
            var response = new Response(new CountDownLatch(1), new CountDownLatch(0));
            var bytes = new ByteArrayOutputStream();
            var copied = ProxyBodyCopy.start(CompletableFuture.completedFuture(response), bytes, executor);
            var receipt = copied.get(5, TimeUnit.SECONDS);
            if(receipt.status()!=206 || receipt.bytesTransferred()!=3 || bytes.size()!=3 || response.closes.get()!=1)
                throw new AssertionError("successful copy lost body/status/lease");
            // Success must not become observable until the physical body
            // close has completed and its transport lease is retired.
            var closing = new CountDownLatch(1);
            var finishClose = new CountDownLatch(1);
            var closeGate = new Response(new CountDownLatch(1), new CountDownLatch(0)) {
                @Override public void close() {
                    closing.countDown();
                    try { if (!finishClose.await(5, TimeUnit.SECONDS)) throw new AssertionError("close gate timeout"); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                    super.close();
                }
            };
            var beforeClose = ProxyBodyCopy.start(CompletableFuture.completedFuture(closeGate),
                    new ByteArrayOutputStream(), executor);
            if (!closing.await(5, TimeUnit.SECONDS)) throw new AssertionError("response never closed");
            if (beforeClose.isDone()) throw new AssertionError("future completed before close retired lease");
            finishClose.countDown();
            if (beforeClose.get(5, TimeUnit.SECONDS).bytesTransferred()!=3 || closeGate.closes.get()!=1)
                throw new AssertionError("close-before-success violated");
            var pending = new CompletableFuture<ProxyBodyCopy.Source>();
            var cancelled = ProxyBodyCopy.start(pending, new ByteArrayOutputStream(), executor);
            if (!cancelled.cancel(true) || !pending.isCancelled())
                throw new AssertionError("pending acquisition not canceled");
            var rejectedResponse = new Response(new CountDownLatch(1), new CountDownLatch(0));
            var rejected = ProxyBodyCopy.start(CompletableFuture.completedFuture(rejectedResponse),
                    new ByteArrayOutputStream(), command -> { throw new RejectedExecutionException("closed"); });
            try { rejected.get(5, TimeUnit.SECONDS); }
            catch (java.util.concurrent.ExecutionException expected) { /* rejected */ }
            if (!rejected.isCompletedExceptionally() || rejectedResponse.closes.get()!=1)
                throw new AssertionError("worker rejection leaked response");
            System.out.println("PASS 150 copy-cancellation races, success, preacquire cancellation and worker rejection");
        }
    }
}
