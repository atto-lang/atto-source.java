package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Oreslang's runtime future primitive.
 *
 * The language-level Future<T> is deliberately backed by CompletionStage so
 * host async I/O can complete it without occupying an actor carrier thread.
 * Future values are linear/local async state: they are not actor-sendable or
 * SharedSafe.
 */
public final class OresFuture<T> extends CompletableFuture<T> {
    private final Runnable cancelHook;
    private final AtomicBoolean cancelHookRun = new AtomicBoolean();

    public OresFuture() {
        this(() -> { });
    }

    OresFuture(Runnable cancelHook) {
        this.cancelHook = Objects.requireNonNull(cancelHook, "cancelHook");
    }

    public static <T> OresFuture<T> completed(T value) {
        OresFuture<T> future = new OresFuture<>();
        future.complete(value);
        return future;
    }

    public static <T> OresFuture<T> failed(Throwable failure) {
        OresFuture<T> future = new OresFuture<>();
        future.completeExceptionally(Objects.requireNonNull(failure, "failure"));
        return future;
    }

    @SuppressWarnings("unchecked")
    public static <T> OresFuture<T> from(CompletionStage<? extends T> stage) {
        Objects.requireNonNull(stage, "stage");
        if (stage instanceof OresFuture<?> ores) {
            return (OresFuture<T>) ores;
        }

        CompletableFuture<?> source = stage.toCompletableFuture();
        OresFuture<T> result = new OresFuture<>(() -> source.cancel(true));
        stage.whenComplete((value, failure) -> {
            if (failure == null) {
                result.complete(value);
            } else {
                result.completeExceptionally(unwrap(failure));
            }
        });
        return result;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        boolean cancelled = super.cancel(mayInterruptIfRunning);
        if (cancelled && cancelHookRun.compareAndSet(false, true)) {
            cancelHook.run();
        }
        return cancelled;
    }

    static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
