package dev.oreslang.runtime;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Trusted response handoff; worker admission always precedes network acquisition. */
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
     * The supplier is trusted host code that starts an already-authorized
     * request. Crucially it is NEVER invoked unless the bounded I/O executor
     * accepts a worker. Rejection therefore creates no response to clean up.
     *
     * The worker is a virtual I/O thread, not an actor scheduler carrier.
     * Future cancellation cancels pending HTTP acquisition; a response
     * already acquired is always closed on the worker before retirement.
     */
    static CompletableFuture<Receipt> start(
            Supplier<? extends CompletableFuture<? extends Source>> acquire,
            OutputStream sink, Executor worker) {
        Objects.requireNonNull(acquire, "acquire");
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(worker, "worker");
        CompletableFuture<Receipt> result = new CompletableFuture<>();
        try {
            worker.execute(() -> {
                CompletableFuture<? extends Source> opening = null;
                try {
                    if (result.isDone()) return;
                    opening = Objects.requireNonNull(acquire.get(), "null request future");
                    CompletableFuture<? extends Source> current = opening;
                    result.whenComplete((ignored, failure) -> {
                        if (result.isCancelled()) current.cancel(true);
                    });
                    // Waiting for upstream headers is allowed only on this
                    // virtual I/O worker. Never block an actor carrier here.
                    Source source = current.join();
                    if (source == null) throw new IllegalStateException("null outbound response");
                    Receipt receipt;
                    try (source) {
                        if (result.isDone()) return;
                        long count = source.streamBody(sink, result::isCancelled);
                        receipt = new Receipt(source.status(), source.headers(), count);
                    }
                    // Completing after source.close() preserves lease ordering.
                    result.complete(receipt);
                } catch (Throwable failure) {
                    Throwable cause = failure instanceof CompletionException wrapped
                            && wrapped.getCause() != null ? wrapped.getCause() : failure;
                    result.completeExceptionally(cause);
                    if (failure instanceof VirtualMachineError fatal) throw fatal;
                    if (failure instanceof ThreadDeath fatal) throw fatal;
                    if (failure instanceof LinkageError fatal) throw fatal;
                }
            });
        } catch (RuntimeException rejected) {
            // No request has started: rejecting the worker cannot strand an
            // acquired body or require an unbounded emergency cleanup thread.
            result.completeExceptionally(rejected);
        }
        return result;
    }
}
