package dev.oreslang.runtime;

import java.util.concurrent.Callable;

/** Retired worker ABI: blocking operations cannot be submitted, even by embedders. */
public final class AsyncNativeIo implements AutoCloseable {
    public AsyncNativeIo() { }
    AsyncNativeIo(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("I/O capacity must be positive");
    }
    private static <T> OresFuture<T> reject(String operation) {
        return OresFuture.failed(CoreIoPolicy.NATIVE_ASYNC_ONLY.blocking(operation));
    }
    public <T> OresFuture<T> submit(OresContext context, RuntimePermissions.Permission permission,
                                   String operation, Callable<T> work) {
        context.requireCapability(permission.capability(), operation);
        return reject(operation);
    }
    public <T> OresFuture<T> submitClose(OresContext context, Callable<T> work) {
        return reject("legacy socket close");
    }
    <T> OresFuture<T> submit(String operation, Callable<T> work) { return reject(operation); }
    <T> OresFuture<T> submitControl(String operation, Callable<T> work) { return reject(operation); }
    void register(AutoCloseable resource) { throw CoreIoPolicy.NATIVE_ASYNC_ONLY.blocking("legacy resource registration"); }
    void unregister(AutoCloseable resource) { }
    @Override public void close() { }
}
