package dev.oreslang.runtime;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Opt-in privileged host I/O boundary for a SQLite durable actor store.
 *
 * <p>Every database operation executes on one isolated worker, NOT on an actor
 * scheduler carrier. Admission is bounded and fails without parking when the
 * worker's outstanding-operation quota is exhausted. The host must suspend an
 * actor turn on the returned stage; joining it on a carrier is prohibited.
 *
 * <p>Guest actors never acquire the Connection, file path, or this provider.
 * This API does not claim guest compiler lowering, outbox delivery or AOT parity.
 */
public final class AsyncSqliteDurableProviderV1 {
    private final ThreadPoolExecutor io;
    private final int maxPending;
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final CompletableFuture<Void> closedFuture = new CompletableFuture<>();
    private SqliteDurableActorStore store; // worker-owned, never returned
    private int pending;
    private boolean closing;

    private AsyncSqliteDurableProviderV1(Path file, int maxPending) {
        Objects.requireNonNull(file, "file");
        if (maxPending < 1 || maxPending > 1024)
            throw new IllegalArgumentException("maxPending must be 1..1024");
        this.maxPending = maxPending;
        io = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(maxPending),
                Thread.ofPlatform().name("atto-durable-sqlite-io-", 0).daemon(true).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        // Opening SQLite is itself disk I/O: never do it on the caller/carrier.
        pending = 1;
        io.execute(() -> {
            try {
                requireHostIoWorker();
                store = SqliteDurableActorStore.open(file);
                ready.complete(null);
            } catch (Throwable failure) {
                ready.completeExceptionally(failure);
            } finally {
                finished();
            }
        });
    }

    public static AsyncSqliteDurableProviderV1 open(Path file, int maxPending) {
        return new AsyncSqliteDurableProviderV1(file, maxPending);
    }

    public CompletionStage<Void> ready() { return ready.minimalCompletionStage(); }

    private boolean closeScheduled;

    /**
     * Admission is a short counter lease only: never hold this monitor over
     * I/O, executor shutdown, Future callbacks, or close. An actor carrier
     * must be able to submit or reject without waiting for SQLite.
     */
    private <T> CompletionStage<T> submit(Function<SqliteDurableActorStore, T> op) {
        Objects.requireNonNull(op, "operation");
        synchronized (this) {
            if (closing)
                return CompletableFuture.failedFuture(
                        new IllegalStateException("durable provider is closing"));
            if (pending >= maxPending)
                return CompletableFuture.failedFuture(
                        new RejectedExecutionException("durable provider backpressure"));
            ++pending;
        }
        CompletableFuture<T> response = new CompletableFuture<>();
        try {
            io.execute(() -> {
                try {
                    requireHostIoWorker();
                    // Opening is the FIRST task on this same serial worker.
                    if (!ready.isDone() || ready.isCompletedExceptionally())
                        throw new IllegalStateException("SQLite provider failed to open");
                    response.complete(op.apply(store));
                } catch (Throwable failure) {
                    response.completeExceptionally(failure);
                } finally {
                    finished();
                }
            });
        } catch (RejectedExecutionException rejected) {
            finished();
            response.completeExceptionally(rejected);
        }
        return response.minimalCompletionStage();
    }

    private void finished() {
        boolean scheduleClose = false;
        synchronized (this) {
            if (--pending < 0) throw new IllegalStateException("durable I/O task underflow");
            if (closing && pending == 0 && !closeScheduled) {
                closeScheduled = true;
                scheduleClose = true;
            }
        }
        if (scheduleClose) scheduleCloseOnWorker();
    }

    private void scheduleCloseOnWorker() {
        try {
            io.execute(this::finishCloseOnWorker);
        } catch (RejectedExecutionException rejected) {
            closedFuture.completeExceptionally(rejected);
            io.shutdown();
        }
    }

    /** Executed only on the dedicated I/O thread, never while holding a lock. */
    private void finishCloseOnWorker() {
        try {
            requireHostIoWorker();
            if (store != null) store.close();
            closedFuture.complete(null);
        } catch (SQLException | RuntimeException closeFailure) {
            closedFuture.completeExceptionally(closeFailure);
        } finally {
            io.shutdown();
        }
    }

    private static void requireHostIoWorker() {
        if (ActorRuntime.isActorCarrierThread())
            throw new IllegalStateException(
                    "blocking SQLite I/O attempted on an actor carrier");
    }

    /** Nonblocking shutdown. Admitted requests settle before the SQLite close. */
    public CompletionStage<Void> closeAsync() {
        boolean scheduleClose = false;
        synchronized (this) {
            if (!closing) {
                closing = true;
                if (pending == 0 && !closeScheduled) {
                    closeScheduled = true;
                    scheduleClose = true;
                }
            }
        }
        if (scheduleClose) scheduleCloseOnWorker();
        return closedFuture.minimalCompletionStage();
    }

    public CompletionStage<Boolean> bootstrap(
            DurableActorStore.Address address, DurableActorStore.Snapshot initial) {
        return submit(s -> s.bootstrap(address, initial));
    }

    public CompletionStage<DurableActorStore.Activation> activate(
            DurableActorStore.Address address) {
        return submit(s -> s.activate(address));
    }

    public CompletionStage<Boolean> enqueue(
            DurableActorStore.Address address, DurableActorStore.Inbound inbound) {
        return submit(s -> s.enqueue(address, inbound));
    }

    public CompletionStage<Boolean> enqueueFenced(
            DurableActorStore.Address address, long epoch, DurableActorStore.Inbound inbound) {
        return submit(s -> s.enqueueFenced(address, epoch, inbound));
    }

    public CompletionStage<Optional<DurableActorStore.Snapshot>> committedDelivery(
            DurableActorStore.Address address, long epoch, DurableActorStore.Inbound original) {
        return submit(s -> s.committedDelivery(address, epoch, original));
    }

    public CompletionStage<Long> commitTurn(DurableActorStore.Turn turn) {
        return submit(s -> s.commitTurn(turn));
    }

    public CompletionStage<Void> requireActive(
            DurableActorStore.Address address, long epoch, long version) {
        return submit(s -> {
            s.requireActive(address, epoch, version);
            return null;
        });
    }

    public CompletionStage<Void> hibernate(
            DurableActorStore.Address address, long epoch) {
        return submit(s -> {
            s.hibernate(address, epoch);
            return null;
        });
    }

    public CompletionStage<Boolean> acknowledge(
            DurableActorStore.Address address, long epoch, String intentId, String receiptId) {
        return submit(s -> s.acknowledge(address, epoch, intentId, receiptId));
    }
}
