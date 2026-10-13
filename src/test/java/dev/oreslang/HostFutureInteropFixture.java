package dev.oreslang;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;

/** Public host fixture deliberately returned through the explicit Java allowlist. */
public final class HostFutureInteropFixture extends FutureTask<Integer> {
    public HostFutureInteropFixture() {
        super(() -> 77);
    }

    public static FutureTask<Integer> pendingFuture() {
        return new FutureTask<>(() -> 42);
    }

    public static FutureTask<Integer> completedFuture() {
        FutureTask<Integer> result = pendingFuture();
        result.run();
        return result;
    }

    public static CompletableFuture<Integer> completedStage() {
        return CompletableFuture.completedFuture(43);
    }
}
