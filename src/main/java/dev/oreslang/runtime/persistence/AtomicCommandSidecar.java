package dev.oreslang.runtime.persistence;

import java.util.Objects;
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
            try {
                writer.execute(() -> {
                    try {
                        Receipt committed=store.commit(command);
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
                        answer.complete(committed);
                    } catch (Throwable error) {
                        answer.completeExceptionally(error);
                    } finally {
                        synchronized (admission) {
                            outstanding--;
                            outstandingBytes-=charge;
                        }
                    }
                });
            } catch (RuntimeException rejected) {
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
            if (closed) return;
            closed=true;
            writer.shutdown();
        }
        boolean interrupted=false;
        try {
            while (true) {
                try {
                    if (writer.awaitTermination(30,TimeUnit.SECONDS)) break;
                    // Cannot promise an exact outcome if a durable commit is stalled.
                    writer.shutdownNow();
                    break;
                } catch (InterruptedException e) {
                    interrupted=true;
                    writer.shutdownNow();
                    break;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
