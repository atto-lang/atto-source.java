package dev.oreslang.runtime.persistence;

import java.util.Objects;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import dev.oreslang.runtime.persistence.BatchPersistenceSidecar.Status;
import dev.oreslang.runtime.persistence.SqliteCommandTransactionStore.Command;
import dev.oreslang.runtime.persistence.SqliteCommandTransactionStore.Receipt;

/**
 * Bounded asynchronous command provider, backed by the SAME WAL/FULL SQLite
 * state and journal tables as the established single-event sidecar.
 * Host-only: no actor carrier calls JDBC and no guest receives a Store.
 *
 * This version serializes commands on one owned worker, preserving ordered
 * admission and per-stream CAS. Cross-actor group COMMIT batching and hard
 * realtime/nonparking guest lowering still require separate proof.
 */
public final class AtomicCommandSidecar implements AutoCloseable {
    private final SqliteCommandTransactionStore store;
    private final ExecutorService writer;
    private final String tenant;
    private final int maxOutstanding;
    private final long maxOutstandingBytes;
    private final Object admission = new Object();
    private final Set<CompletableFuture<Receipt>> unresolved=new HashSet<>();
    private volatile Thread writerThread;
    private int outstanding;
    private long outstandingBytes;
    private boolean closed;

    public AtomicCommandSidecar(String trustedTenant, SqliteCommandTransactionStore store,
                                int maxOutstanding, long maxOutstandingBytes) {
        if (trustedTenant == null || trustedTenant.isBlank() || maxOutstanding <= 0 ||
            maxOutstandingBytes <= 0) throw new IllegalArgumentException("INVALID_SIDECAR_CONFIG");
        this.tenant=trustedTenant;
        this.store=Objects.requireNonNull(store);
        this.maxOutstanding=maxOutstanding;
        this.maxOutstandingBytes=maxOutstandingBytes;
        this.writer=Executors.newSingleThreadExecutor(r -> {
            Thread worker=new Thread(r,"ores-event-command-writer");
            worker.setDaemon(true);
            this.writerThread=worker;
            return worker;
        });
    }
    /** Queue admission is NOT a durable receipt. Future settles after storage proof. */
    public CompletionStage<Receipt> submit(Command proposed) {
        Objects.requireNonNull(proposed);
        // Take new ownership of every mutable source byte before enqueue.
        Command command=new Command(proposed.id(),proposed.opId(),proposed.epoch(),
                proposed.expectedVersion(),proposed.schemaVersion(),
                proposed.commandBytes(),proposed.events(),proposed.resultBytes());
        if (!tenant.equals(command.id().tenant()))
            throw new SecurityException("WRONG_TENANT");
        long charge=command.charge();
        CompletableFuture<Receipt> answer=new CompletableFuture<>();
        synchronized (admission) {
            if (closed) throw new RejectedExecutionException("SIDECAR_CLOSED");
            if (outstanding>=maxOutstanding ||
                charge>maxOutstandingBytes-outstandingBytes)
                throw new RejectedExecutionException("SIDECAR_BACKPRESSURE");
            outstanding++;
            outstandingBytes+=charge;
            unresolved.add(answer);
            try {
                writer.execute(() -> {
                    Receipt committed=null;
                    Throwable error=null;
                    try {
                        committed=store.commit(command);
                        if (committed.durable() &&
                            (committed.status()==Status.COMMITTED ||
                             committed.status()==Status.DUPLICATE)) {
                            // A fabricated success receipt cannot authorize publication.
                            Receipt proof=store.verify(command);
                            if (proof==null || !proof.durable() ||
                                proof.version()!=committed.version() ||
                                proof.firstVersion()!=committed.firstVersion() ||
                                proof.eventCount()!=committed.eventCount() ||
                                !java.util.Arrays.equals(proof.resultBytes(),committed.resultBytes()))
                                throw new IllegalStateException("OUTCOME_UNKNOWN");
                        }
                    } catch (Throwable failure) {
                        error=failure;
                    } finally {
                        // Never run arbitrary CompletableFuture dependents while
                        // holding our admission lock or before freeing capacity.
                        synchronized (admission) {
                            unresolved.remove(answer);
                            outstanding--;
                            outstandingBytes-=charge;
                        }
                    }
                    if (error!=null) answer.completeExceptionally(error);
                    else answer.complete(committed);
                });
            } catch (RuntimeException rejected) {
                unresolved.remove(answer);
                outstanding--;
                outstandingBytes-=charge;
                throw rejected;
            }
        }
        return answer;
    }

    /** Host-side close; never invoke on the actor dispatcher. */
    @Override public void close() {
        synchronized (admission) {
            if (!closed) {
                closed=true;
                writer.shutdown();
            }
        }
        // A second external close must still JOIN an earlier in-writer close
        // before the owning host is permitted to close the SQLite Store.
        // A synchronous CompletableFuture dependent may execute on the writer.
        // It must never await termination of its own thread.
        if (Thread.currentThread()==writerThread) return;
        boolean interrupted=false;
        try {
            try {
                if (writer.awaitTermination(30,TimeUnit.SECONDS)) return;
            } catch (InterruptedException e) {
                interrupted=true;
            }
            // Capture accepted unresolved requests while holding the lock, but
            // publish failures OUTSIDE it (dependent callbacks are arbitrary).
            java.util.List<CompletableFuture<Receipt>> abandoned;
            synchronized (admission) {
                abandoned=java.util.List.copyOf(unresolved);
            }
            for (CompletableFuture<Receipt> future:abandoned)
                future.completeExceptionally(new IllegalStateException("OUTCOME_UNKNOWN"));
            writer.shutdownNow();
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
