package dev.oreslang.runtime;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Oreslang synchronization primitives.
 *
 * <p>{@link Local} is an actor/private-domain mutex. It deliberately does not
 * use a JVM lock: the creating execution thread owns it and recursive
 * acquisition is rejected. {@link Shared} is an explicit same-process
 * shared-memory capability backed by a JVM lock with poisoning and
 * acquire/release ordering.</p>
 */
public final class OresMutex {
    private OresMutex() { }

    public static <T> Local<T> local(T value) {
        return new Local<>(value);
    }

    public static <T> Shared<T> shared(T value) {
        return new Shared<>(value);
    }

    public sealed interface Lock<T> permits Local, Shared {
        Guard<T> lock();
        Optional<Guard<T>> tryLock();
        Optional<Guard<T>> lockFor(Duration timeout);
        CompletableFuture<Guard<T>> lockAsync();
        <R> R withLock(Function<? super T, ? extends R> body);
        boolean isPoisoned();
    }

    /**
     * Linear lock capability. Oreslang lowering owns guard release; guest code
     * may release early, but the mutex itself has no public unlock operation.
     */
    public interface Guard<T> extends AutoCloseable {
        T value();
        boolean released();
        void release();

        /**
         * Releases after an abnormal critical-section exit. Shared mutexes are
         * poisoned; actor-local mutexes simply release because their state is
         * confined to the failing actor/private domain.
         */
        void fail();

        @Override
        default void close() {
            release();
        }
    }

    public static final class RecursiveLockException extends IllegalStateException {
        public RecursiveLockException(String kind) {
            super(kind + " is non-reentrant; recursive acquisition is forbidden");
        }
    }

    public static final class WrongMutexDomainException extends IllegalStateException {
        public WrongMutexDomainException(String message) {
            super(message);
        }
    }

    public static final class PoisonedMutexException extends IllegalStateException {
        public PoisonedMutexException() {
            super("SharedMutex is poisoned because a previous critical section exited abnormally; call recover(...)");
        }
    }

    /**
     * Actor/private-domain mutex. There is no host lock and therefore no
     * blocking path. Cross-thread use is a domain error rather than silently
     * turning actor-local state into shared memory.
     */
    public static final class Local<T> implements Lock<T> {
        private final T value;
        private final Thread ownerThread;
        private boolean held;

        private Local(T value) {
            this.value = value;
            this.ownerThread = Thread.currentThread();
        }

        private void requireOwnerThread() {
            if (Thread.currentThread() != ownerThread) {
                throw new WrongMutexDomainException(
                        "Mutex<T> is actor/private-domain state and cannot be accessed from another JVM thread; use SharedMutex<T>");
            }
        }

        @Override
        public Guard<T> lock() {
            requireOwnerThread();
            if (held) throw new RecursiveLockException("Mutex<T>");
            held = true;
            return new LocalGuard();
        }

        @Override
        public Optional<Guard<T>> tryLock() {
            requireOwnerThread();
            if (held) throw new RecursiveLockException("Mutex<T>");
            held = true;
            return Optional.of(new LocalGuard());
        }

        @Override
        public Optional<Guard<T>> lockFor(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative()) throw new IllegalArgumentException("timeout must not be negative");
            return tryLock();
        }

        @Override
        public CompletableFuture<Guard<T>> lockAsync() {
            try {
                return CompletableFuture.completedFuture(lock());
            } catch (Throwable failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }

        @Override
        public <R> R withLock(Function<? super T, ? extends R> body) {
            Objects.requireNonNull(body, "body");
            Guard<T> guard = lock();
            try {
                R result = body.apply(value);
                guard.release();
                return result;
            } catch (RuntimeException | Error failure) {
                guard.fail();
                throw failure;
            }
        }

        @Override
        public boolean isPoisoned() {
            return false;
        }

        private final class LocalGuard implements Guard<T> {
            private boolean released;

            @Override public T value() { return value; }
            @Override public boolean released() { return released; }

            @Override
            public void release() {
                requireOwnerThread();
                if (released) return;
                released = true;
                held = false;
            }

            @Override
            public void fail() {
                release();
            }
        }
    }

    /**
     * Explicit same-process shared-memory mutex. This is intentionally not a
     * distributed lock and must not be serialized across OS-process/Graal
     * isolate boundaries.
     */
    public static final class Shared<T> implements Lock<T>, ActorRuntime.Sendable {
        private final T value;
        private final ReentrantLock lock = new ReentrantLock();
        private final AtomicBoolean poisoned = new AtomicBoolean();

        private Shared(T value) {
            this.value = value;
        }

        private void rejectBlockingActorAcquisition() {
            if (ActorRuntime.inActorExecution()) {
                throw new WrongMutexDomainException(
                        "blocking SharedMutex.lock()/lock_for() is forbidden on an actor execution thread; use await mutex.lock_async()");
            }
        }

        private void rejectRecursiveAcquisition() {
            if (lock.isHeldByCurrentThread()) throw new RecursiveLockException("SharedMutex<T>");
        }

        private Guard<T> checkedGuardAfterAcquire() {
            if (poisoned.get()) {
                lock.unlock();
                throw new PoisonedMutexException();
            }
            return new SharedGuard();
        }

        @Override
        public Guard<T> lock() {
            rejectBlockingActorAcquisition();
            rejectRecursiveAcquisition();
            lock.lock();
            return checkedGuardAfterAcquire();
        }

        @Override
        public Optional<Guard<T>> tryLock() {
            rejectRecursiveAcquisition();
            if (!lock.tryLock()) return Optional.empty();
            return Optional.of(checkedGuardAfterAcquire());
        }

        @Override
        public Optional<Guard<T>> lockFor(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative()) throw new IllegalArgumentException("timeout must not be negative");
            rejectBlockingActorAcquisition();
            rejectRecursiveAcquisition();
            try {
                if (!lock.tryLock(timeout.toNanos(), TimeUnit.NANOSECONDS)) return Optional.empty();
                return Optional.of(checkedGuardAfterAcquire());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException("SharedMutex lock wait interrupted");
            }
        }

        @Override
        public CompletableFuture<Guard<T>> lockAsync() {
            CompletableFuture<Guard<T>> future = new CompletableFuture<>();
            Thread waiter = Thread.ofVirtual().name("ores-shared-mutex-waiter").unstarted(() -> {
                try {
                    lock.lockInterruptibly();
                    future.complete(checkedGuardAfterAcquire());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    future.completeExceptionally(new java.util.concurrent.CancellationException("SharedMutex async wait cancelled"));
                } catch (Throwable failure) {
                    future.completeExceptionally(failure);
                }
            });
            future.whenComplete((ignored, failure) -> {
                if (future.isCancelled()) waiter.interrupt();
            });
            waiter.start();
            return future;
        }

        @Override
        public <R> R withLock(Function<? super T, ? extends R> body) {
            Objects.requireNonNull(body, "body");
            Guard<T> guard = lock();
            try {
                R result = body.apply(value);
                guard.release();
                return result;
            } catch (RuntimeException | Error failure) {
                guard.fail();
                throw failure;
            }
        }

        public <R> R recover(Function<? super T, ? extends R> repair) {
            Objects.requireNonNull(repair, "repair");
            rejectBlockingActorAcquisition();
            rejectRecursiveAcquisition();
            lock.lock();
            try {
                R result = repair.apply(value);
                poisoned.set(false);
                return result;
            } catch (RuntimeException | Error failure) {
                poisoned.set(true);
                throw failure;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public boolean isPoisoned() {
            return poisoned.get();
        }

        /** Shared mutex handles cross actor mailboxes by reference only when SHARED_MEMORY is permitted. */
        @Override
        public Object freezeForSend() {
            return this;
        }

        private final class SharedGuard implements Guard<T> {
            private final AtomicBoolean released = new AtomicBoolean();

            @Override public T value() { return value; }
            @Override public boolean released() { return released.get(); }

            @Override
            public void release() {
                if (!released.compareAndSet(false, true)) return;
                if (!lock.isHeldByCurrentThread()) {
                    throw new IllegalMonitorStateException("SharedMutex guard must be released by the execution thread that acquired it");
                }
                lock.unlock();
            }

            @Override
            public void fail() {
                if (released()) return;
                poisoned.set(true);
                release();
            }
        }
    }
}
