package dev.oreslang.runtime;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Bounded resolver execution with cancellation and physical retirement receipts.
 *
 * <p>Java's system resolver is not specified to be interruptible. A timeout or
 * cancellation therefore revokes the authorization result immediately, but it
 * does not pretend that the resolver worker has physically stopped. Every
 * admitted lookup borrows an {@link AsyncNativeIo.IoLoan}; that loan is
 * released only from the resolver worker's physical finally block. Actor and
 * context retirement therefore remain pinned until a late resolver actually
 * exits.</p>
 */
final class BoundedDnsResolver
        implements OutboundEgressPolicy.CancellableResolver, AutoCloseable {
    private static final int SYSTEM_WORKERS = 8;
    private static final int SYSTEM_QUEUE_CAPACITY = 32;
    private static final long POLL_NANOS =
            TimeUnit.MILLISECONDS.toNanos(10);

    private final AsyncNativeIo nativeIo;
    private final OutboundEgressPolicy.Resolver delegate;
    private final Duration timeout;
    private final ThreadPoolExecutor workers;
    private final Set<Resolution> active =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong retired = new AtomicLong();

    BoundedDnsResolver(
            AsyncNativeIo nativeIo,
            OutboundEgressPolicy.Resolver delegate,
            Duration timeout,
            int workerCount,
            int queueCapacity)
            throws Exception {
        this.nativeIo = Objects.requireNonNull(nativeIo, "nativeIo");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException(
                    "DNS timeout must be positive");
        }
        if (workerCount < 1 || workerCount > 64
                || queueCapacity < 1 || queueCapacity > 1024) {
            throw new IllegalArgumentException(
                    "invalid DNS worker/queue capacity");
        }
        this.workers = new ThreadPoolExecutor(
                workerCount,
                workerCount,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                Thread.ofVirtual()
                        .name("ores-dns-", 0)
                        .factory(),
                new ThreadPoolExecutor.AbortPolicy());
        try {
            nativeIo.register(this);
        } catch (Throwable registrationFailure) {
            try {
                close();
            } catch (Throwable cleanupFailure) {
                registrationFailure.addSuppressed(cleanupFailure);
            }
            if (registrationFailure instanceof Exception checked) {
                throw checked;
            }
            if (registrationFailure instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(
                    "DNS resolver registration failed",
                    registrationFailure);
        }
    }

    static BoundedDnsResolver system(
            AsyncNativeIo nativeIo,
            Duration timeout) {
        try {
            return new BoundedDnsResolver(
                    nativeIo,
                    OutboundEgressPolicy.Resolver.system(),
                    timeout,
                    SYSTEM_WORKERS,
                    SYSTEM_QUEUE_CAPACITY);
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "failed to register bounded DNS resolver",
                    failure);
        }
    }

    @Override
    public List<InetAddress> resolve(String asciiHost)
            throws UnknownHostException {
        return resolve(
                asciiHost,
                Long.MAX_VALUE,
                () -> false);
    }

    @Override
    public List<InetAddress> resolve(
            String asciiHost,
            long deadlineNanos,
            BooleanSupplier cancelled)
            throws UnknownHostException {
        Objects.requireNonNull(asciiHost, "asciiHost");
        Objects.requireNonNull(cancelled, "cancelled");
        if (closed.get()) {
            throw failure(
                    "DNS resolver is closed",
                    new RejectedExecutionException(
                            "bounded DNS resolver closed"));
        }
        if (cancelled.getAsBoolean()) {
            throw failure("DNS resolution cancelled before admission", null);
        }

        final AsyncNativeIo.IoLoan loan;
        try {
            loan = nativeIo.borrow(this);
        } catch (RuntimeException admissionFailure) {
            throw failure(
                    "DNS resolver physical-loan admission failed",
                    admissionFailure);
        }

        Resolution resolution = new Resolution(asciiHost, loan);
        active.add(resolution);
        try {
            workers.execute(resolution);
        } catch (RuntimeException rejected) {
            resolution.cancel();
            resolution.retire();
            throw failure(
                    "DNS resolver capacity exhausted",
                    rejected);
        }

        long phaseDeadline = earlierDeadline(
                deadlineNanos,
                deadlineAfter(timeout));
        try {
            while (true) {
                if (closed.get() || cancelled.getAsBoolean()) {
                    resolution.cancel();
                    resolution.retireIfQueued();
                    throw failure("DNS resolution cancelled", null);
                }
                long remaining = phaseDeadline - System.nanoTime();
                if (remaining <= 0) {
                    resolution.cancel();
                    resolution.retireIfQueued();
                    throw failure("DNS resolution deadline expired", null);
                }
                if (resolution.done.await(
                        Math.min(remaining, POLL_NANOS),
                        TimeUnit.NANOSECONDS)) {
                    break;
                }
            }
        } catch (InterruptedException interrupted) {
            resolution.cancel();
            resolution.retireIfQueued();
            Thread.currentThread().interrupt();
            throw failure(
                    "DNS resolution interrupted",
                    interrupted);
        }

        if (resolution.stale.get()) {
            throw failure(
                    "DNS resolution completed after revocation",
                    resolution.failure);
        }
        if (resolution.failure != null) {
            if (resolution.failure instanceof UnknownHostException unknown) {
                throw unknown;
            }
            throw failure(
                    "DNS resolution failed",
                    resolution.failure);
        }
        if (resolution.result == null) {
            throw failure(
                    "DNS resolver retired without a result",
                    null);
        }
        return resolution.result;
    }

    int activeResolutions() {
        return active.size();
    }

    long retiredResolutions() {
        return retired.get();
    }

    boolean closed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;

        for (Resolution resolution : List.copyOf(active)) {
            resolution.cancel();
        }
        List<Runnable> discarded = workers.shutdownNow();
        for (Runnable runnable : discarded) {
            if (runnable instanceof Resolution resolution) {
                resolution.cancel();
                resolution.retire();
            }
        }
        nativeIo.unregister(this);
    }

    private final class Resolution implements Runnable {
        private final String host;
        private final AsyncNativeIo.IoLoan loan;
        private final CountDownLatch done = new CountDownLatch(1);
        private final AtomicBoolean stale = new AtomicBoolean();
        private final AtomicBoolean retiredOnce = new AtomicBoolean();
        private volatile Thread runner;
        private volatile List<InetAddress> result;
        private volatile Throwable failure;

        private Resolution(
                String host,
                AsyncNativeIo.IoLoan loan) {
            this.host = host;
            this.loan = loan;
        }

        @Override
        public void run() {
            runner = Thread.currentThread();
            try {
                if (stale.get() || closed.get()) return;
                List<InetAddress> resolved = delegate.resolve(host);
                if (stale.get() || closed.get()) return;
                result = List.copyOf(resolved);
            } catch (Throwable caught) {
                failure = caught;
            } finally {
                runner = null;
                retire();
            }
        }

        void cancel() {
            stale.set(true);
            Thread running = runner;
            if (running != null) running.interrupt();
        }

        void retireIfQueued() {
            if (workers.remove(this)) {
                retire();
            }
        }

        void retire() {
            if (!retiredOnce.compareAndSet(false, true)) return;
            try {
                loan.close();
            } catch (Throwable receiptFailure) {
                if (failure == null) failure = receiptFailure;
                else if (failure != receiptFailure) {
                    failure.addSuppressed(receiptFailure);
                }
            } finally {
                active.remove(this);
                retired.incrementAndGet();
                done.countDown();
            }
        }
    }

    private static long earlierDeadline(
            long first,
            long second) {
        if (first == Long.MAX_VALUE) return second;
        if (second == Long.MAX_VALUE) return first;
        return Math.min(first, second);
    }

    private static long deadlineAfter(Duration duration) {
        long now = System.nanoTime();
        long delta;
        try {
            delta = duration.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
        if (Long.MAX_VALUE - now < delta) {
            return Long.MAX_VALUE;
        }
        return now + delta;
    }

    private static UnknownHostException failure(
            String message,
            Throwable cause) {
        UnknownHostException failure =
                new UnknownHostException(message);
        if (cause != null) failure.initCause(cause);
        return failure;
    }
}
