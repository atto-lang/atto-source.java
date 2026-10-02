package dev.oreslang.runtime;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Oreslang synchronization primitives.
 *
 * <p>{@link Local} is an actor/private-domain mutex. It deliberately does not
 * use a JVM lock: the creating semantic execution domain owns it and recursive
 * acquisition is rejected. Shared actors may migrate JVM worker threads without
 * changing that domain. {@link Shared} is an explicit same-process
 * shared-memory capability backed by a JVM synchronizer with poisoning and
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
     * blocking path. Ownership follows the actor execution domain, not the
     * transient JVM worker Thread. Outside actor execution, Thread identity is
     * used as the local domain.
     */
    public static final class Local<T> implements Lock<T> {
        private final T value;
        private final Object ownerDomain;
        private boolean held;

        private Local(T value) {
            this.value = value;
            this.ownerDomain = ActorRuntime.currentExecutionDomain();
        }

        private void requireOwnerDomain() {
            if (ActorRuntime.currentExecutionDomain() != ownerDomain) {
                throw new WrongMutexDomainException(
                        "Mutex<T> is actor/private-domain state and cannot be accessed from another actor/execution domain; use SharedMutex<T>");
            }
        }

        @Override
        public Guard<T> lock() {
            requireOwnerDomain();
            if (held) throw new RecursiveLockException("Mutex<T>");
            held = true;
            return new LocalGuard();
        }

        @Override
        public Optional<Guard<T>> tryLock() {
            requireOwnerDomain();
            if (held) return Optional.empty();
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
                requireOwnerDomain();
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
        private final Semaphore permit = new Semaphore(1, true);
        private final AtomicBoolean poisoned = new AtomicBoolean();
        private final AtomicReference<Thread> synchronousOwner = new AtomicReference<>();

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
            if (synchronousOwner.get() == Thread.currentThread()) {
                throw new RecursiveLockException("SharedMutex<T>");
            }
        }

        private Guard<T> checkedGuardAfterAcquire(Thread syncOwner) {
            if (poisoned.get()) {
                if (syncOwner != null) synchronousOwner.compareAndSet(syncOwner, null);
                permit.release();
                throw new PoisonedMutexException();
            }
            return new SharedGuard(syncOwner);
        }

        @Override
        public Guard<T> lock() {
            rejectBlockingActorAcquisition();
            rejectRecursiveAcquisition();
            try {
                permit.acquire();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException("SharedMutex lock wait interrupted");
            }
            Thread owner = Thread.currentThread();
            synchronousOwner.set(owner);
            return checkedGuardAfterAcquire(owner);
        }

        @Override
        public Optional<Guard<T>> tryLock() {
            if (synchronousOwner.get() == Thread.currentThread()) return Optional.empty();
            if (!permit.tryAcquire()) return Optional.empty();
            Thread owner = Thread.currentThread();
            synchronousOwner.set(owner);
            return Optional.of(checkedGuardAfterAcquire(owner));
        }

        @Override
        public Optional<Guard<T>> lockFor(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative()) throw new IllegalArgumentException("timeout must not be negative");
            rejectBlockingActorAcquisition();
            if (synchronousOwner.get() == Thread.currentThread()) return Optional.empty();
            try {
                if (!permit.tryAcquire(timeout.toNanos(), TimeUnit.NANOSECONDS)) return Optional.empty();
                Thread owner = Thread.currentThread();
                synchronousOwner.set(owner);
                return Optional.of(checkedGuardAfterAcquire(owner));
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
                    permit.acquire();
                    Guard<T> guard = checkedGuardAfterAcquire(null);
                    if (!future.complete(guard)) guard.release();
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
            try {
                permit.acquire();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException("SharedMutex recovery wait interrupted");
            }
            Thread owner = Thread.currentThread();
            synchronousOwner.set(owner);
            try {
                R result = repair.apply(value);
                poisoned.set(false);
                return result;
            } catch (RuntimeException | Error failure) {
                poisoned.set(true);
                throw failure;
            } finally {
                synchronousOwner.compareAndSet(owner, null);
                permit.release();
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
            private final Thread synchronousAcquirer;
            private final AtomicBoolean released = new AtomicBoolean();

            private SharedGuard(Thread synchronousAcquirer) {
                this.synchronousAcquirer = synchronousAcquirer;
            }

            @Override public T value() { return value; }
            @Override public boolean released() { return released.get(); }

            @Override
            public void release() {
                if (synchronousAcquirer != null && Thread.currentThread() != synchronousAcquirer) {
                    throw new IllegalMonitorStateException("synchronously acquired SharedMutex guard must be released by its acquiring execution thread");
                }
                if (!released.compareAndSet(false, true)) return;
                if (synchronousAcquirer != null) synchronousOwner.compareAndSet(synchronousAcquirer, null);
                permit.release();
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
