package dev.oreslang.runtime;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/** Trusted host-only response-to-worker handoff; never invoke guest callbacks. */
final class ProxyBodyCopy {
    interface Source extends AutoCloseable {
        int status();
        List<Map.Entry<String, String>> headers();
        long streamBody(OutputStream sink, BooleanSupplier cancelled) throws IOException;
        @Override void close() throws IOException;
    }

    record Receipt(int status, List<Map.Entry<String, String>> headers, long bytesTransferred) {
        Receipt {
            headers = List.copyOf(headers);
            if (bytesTransferred < 0) throw new IllegalArgumentException("negative body byte count");
        }
    }

    private ProxyBodyCopy() { }

    /**
     * The provided worker must be a context-owned host I/O executor. The sink
     * must be a trusted host OutputStream, never a guest callback. A cancelled
     * completion does not free a stream lease until the worker exits/close runs.
     */
    static CompletableFuture<Receipt> start(
            CompletableFuture<? extends Source> opened, OutputStream sink, Executor worker) {
        Objects.requireNonNull(opened, "opened");
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(worker, "worker");
        CompletableFuture<Receipt> result = new CompletableFuture<>();
        result.whenComplete((value, failure) -> {
            if (result.isCancelled()) opened.cancel(true);
        });
        opened.whenComplete((source, failure) -> {
            if (failure != null) {
                result.completeExceptionally(failure);
                return;
            }
            if (source == null) {
                result.completeExceptionally(new IllegalStateException("null outbound response"));
                return;
            }
            try {
                // Cancellation never runs user-supplied OutputStream methods on
                // the future-completion/actor carrier thread.
                worker.execute(() -> {
                    try {
                        Receipt receipt;
                        try (source) {
                            if (result.isDone()) return;
                            long count = source.streamBody(sink, result::isCancelled);
                            receipt = new Receipt(source.status(), source.headers(), count);
                        }
                        // Do not settle success until close has retired the
                        // body lease, including any blocked physical writer.
                        result.complete(receipt);
                    } catch (Throwable error) {
                        result.completeExceptionally(error);
                        if (error instanceof VirtualMachineError fatal) throw fatal;
                        if (error instanceof ThreadDeath fatal) throw fatal;
                        if (error instanceof LinkageError fatal) throw fatal;
                    }
                });
            } catch (RuntimeException rejected) {
                // Worker rejection is a host-side admission failure. Do not
                // expose an unread response or retain its transport lease.
                Thread.ofVirtual().name("ores-proxy-rejected-close").start(() -> {
                    try { source.close(); }
                    catch (IOException cleanup) { rejected.addSuppressed(cleanup); }
                    result.completeExceptionally(rejected);
                });
            }
        });
        return result;
    }
}
