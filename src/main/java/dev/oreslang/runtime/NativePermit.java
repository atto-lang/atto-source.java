package dev.oreslang.runtime;

import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One-permit native synchronizer used by SharedMutex.
 *
 * <p>Blocking waiters are queued in FIFO order inside liboresthread. This class
 * deliberately exposes both fair timed acquisition and an explicit barging
 * try-acquire because SharedMutex recovery historically distinguishes them.</p>
 */
final class NativePermit implements AutoCloseable {
    private static final ReferenceQueue<NativePermit> RETIRED = new ReferenceQueue<>();
    private static final ConcurrentHashMap<PhantomReference<NativePermit>, Long> HANDLES =
            new ConcurrentHashMap<>();
    @SuppressWarnings("unused")
    private static final NativeTimerService.Ticket REAPER =
            NativeTimerService.process().scheduleWithFixedDelay(
                    NativePermit::drainRetired,
                    TimeUnit.SECONDS.toNanos(5),
                    TimeUnit.SECONDS.toNanos(5));

    private final AtomicBoolean closed = new AtomicBoolean();
    private final PhantomReference<NativePermit> cleanupRef;
    private volatile long handle;

    NativePermit() {
        NativeCarrierExecutor.ensureNativeLibraryLoaded();
        long created = nativeCreate();
        if (created == 0L) throw new IllegalStateException("native permit returned a null handle");
        this.handle = created;
        this.cleanupRef = new PhantomReference<>(this, RETIRED);
        HANDLES.put(cleanupRef, created);
    }

    void acquire() throws InterruptedException {
        int result = nativeAcquire(requireHandle(), -1L);
        if (result == 1) return;
        if (result == -1) throw new InterruptedException("native permit acquisition interrupted");
        throw new IllegalStateException("native permit indefinite acquire timed out");
    }

    boolean tryAcquire() {
        return nativeTryAcquire(requireHandle(), false);
    }

    boolean tryAcquireFair(long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(unit, "unit");
        if (timeout < 0L) throw new IllegalArgumentException("timeout must be non-negative");
        long nanos = unit.toNanos(timeout);
        int result = nativeAcquire(requireHandle(), nanos);
        if (result == 1) return true;
        if (result == 0) return false;
        if (result == -1) throw new InterruptedException("native permit acquisition interrupted");
        throw new IllegalStateException("invalid native permit acquire result: " + result);
    }

    void release() {
        nativeRelease(requireHandle());
    }

    boolean hasQueuedThreads() {
        return nativeHasQueuedThreads(requireHandle());
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        long retired = handle;
        handle = 0L;
        if (HANDLES.remove(cleanupRef, retired)) {
            cleanupRef.clear();
            nativeDestroy(retired);
        }
    }

    private long requireHandle() {
        long live = handle;
        if (live == 0L || closed.get()) throw new IllegalStateException("native permit is closed");
        return live;
    }

    private static void drainRetired() {
        PhantomReference<? extends NativePermit> ref;
        while ((ref = (PhantomReference<? extends NativePermit>) RETIRED.poll()) != null) {
            Long retired = HANDLES.remove(ref);
            ref.clear();
            if (retired != null && retired != 0L) nativeDestroy(retired);
        }
    }

    private static native long nativeCreate();
    /** 1=acquired, 0=timeout, -1=Java interruption observed. */
    private static native int nativeAcquire(long handle, long timeoutNanos);
    private static native boolean nativeTryAcquire(long handle, boolean fair);
    private static native void nativeRelease(long handle);
    private static native boolean nativeHasQueuedThreads(long handle);
    private static native void nativeDestroy(long handle);
}
