package dev.oreslang.runtime;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Byte-bounded HTTP response subscriber, including streaming/chunked bodies. */
final class BoundedHttpTextBodySubscriber implements HttpResponse.BodySubscriber<String> {
    private final int maxBytes;
    private final long declaredBytes;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final CompletableFuture<String> result = new CompletableFuture<>();
    private Flow.Subscription subscription;
    private boolean terminated;

    BoundedHttpTextBodySubscriber(int maxBytes, long declaredBytes) {
        if (maxBytes < 1) throw new IllegalArgumentException("HTTP byte limit must be positive");
        this.maxBytes = maxBytes;
        this.declaredBytes = declaredBytes;
    }

    @Override public CompletionStage<String> getBody() { return result; }

    @Override public void onSubscribe(Flow.Subscription incoming) {
        Objects.requireNonNull(incoming, "subscription");
        if (subscription != null || terminated) {
            incoming.cancel();
            return;
        }
        subscription = incoming;
        if (declaredBytes > maxBytes) {
            reject(new IllegalStateException("HTTP response exceeds " + maxBytes + " bytes"));
            return;
        }
        incoming.request(1);
    }

    @Override public void onNext(List<ByteBuffer> buffers) {
        if (terminated) return;
        try {
            for (ByteBuffer buffer : buffers) {
                int size = buffer.remaining();
                if (size > maxBytes - bytes.size()) {
                    reject(new IllegalStateException("HTTP response exceeds " + maxBytes + " bytes"));
                    return;
                }
                byte[] chunk = new byte[size];
                buffer.get(chunk);
                bytes.write(chunk, 0, chunk.length);
            }
            subscription.request(1);
        } catch (Throwable failure) {
            reject(failure);
        }
    }

    @Override public void onError(Throwable failure) {
        if (!terminated) {
            terminated = true;
            result.completeExceptionally(failure);
        }
    }

    @Override public void onComplete() {
        if (!terminated) {
            terminated = true;
            result.complete(bytes.toString(StandardCharsets.UTF_8));
        }
    }

    private void reject(Throwable failure) {
        if (terminated) return;
        terminated = true;
        result.completeExceptionally(failure);
        if (subscription != null) subscription.cancel();
    }
}
