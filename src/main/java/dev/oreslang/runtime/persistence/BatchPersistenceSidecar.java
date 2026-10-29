package dev.oreslang.runtime.persistence;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/** Trusted host adapter; NOT exposed to untrusted guests or an OS sandbox. */
public final class BatchPersistenceSidecar implements AutoCloseable {
    public enum Kind { SNAPSHOT, EVENT }
    public enum Status { COMMITTED, DUPLICATE, STALE_EPOCH, VERSION_CONFLICT, OP_ID_CONFLICT }
    public record Identity(String tenant, String actor, String state) {
        public Identity { validId(tenant); validId(actor); validId(state); }
    }
    public record Write(Identity id, String operationId, long epoch, long expectedVersion,
                        int schemaVersion, Kind kind, byte[] payload) {
        public Write {
            Objects.requireNonNull(id); validId(operationId);
            if (epoch <= 0 || expectedVersion < 0 || schemaVersion <= 0 || kind == null) {
                throw new IllegalArgumentException("invalid write metadata");
            }
            payload = Objects.requireNonNull(payload, "payload").clone();
        }
        @Override public byte[] payload() { return payload.clone(); }
        byte[] ownedPayload() { return payload; }
    }
    public record Result(String operationId, Status status, long version, boolean durable) {
        public Result { if (durable && status != Status.COMMITTED && status != Status.DUPLICATE)
            throw new IllegalArgumentException("noncommit cannot be durable"); }
    }
    public interface Store {
        /** Trusted controller only. Must persist monotonically and fence at commit. */
        void grantEpoch(String tenant, String actor, long epoch);
        /** Commit results atomically with state, dedup and epoch validation. */
        List<Result> commit(List<Write> batch) throws Exception;
    }
    /** Must enqueue a continuation under the existing actor-wide execution lease. */
    public interface ActorMailbox { void post(Runnable continuation); }
    public static final class AdmissionException extends RuntimeException {
        public final String code;
        private static final long serialVersionUID = 1L;
        AdmissionException(String code) { super(code); this.code = code; }
    }
    private record Pending(Write write, CompletableFuture<Result> answer, long charge) {}
    private final String tenant;
    private final Store store;
    private final ScheduledExecutorService writer;
    private final ArrayDeque<Pending> queue = new ArrayDeque<>();
    private final int maxEntries, maxBatchEntries, maxPayloadBytes;
    private final long maxQueuedBytes, maxBatchBytes;
    private long queuedBytes;
    private int outstandingEntries;
    private boolean flushRequested;
    private boolean closed;

    public BatchPersistenceSidecar(String tenant, Store store, int maxEntries, long maxQueuedBytes,
                                   int maxPayloadBytes, int maxBatchEntries, long maxBatchBytes,
                                   long maxWaitMillis) {
        validId(tenant); this.tenant = tenant; this.store = Objects.requireNonNull(store);
        if (maxEntries <= 0 || maxQueuedBytes <= 0 || maxPayloadBytes <= 0 ||
            maxBatchEntries <= 0 || maxBatchBytes <= 0 || maxWaitMillis <= 0 ||
            maxBatchBytes < maxPayloadBytes) throw new IllegalArgumentException("invalid limits");
        this.maxEntries = maxEntries; this.maxQueuedBytes = maxQueuedBytes;
        this.maxPayloadBytes = maxPayloadBytes; this.maxBatchEntries = maxBatchEntries;
        this.maxBatchBytes = maxBatchBytes;
        this.writer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ores-tenant-persistence-sidecar");
            t.setDaemon(true); return t;
        });
        this.writer.scheduleWithFixedDelay(this::safeFlush, maxWaitMillis, maxWaitMillis, TimeUnit.MILLISECONDS);
    }
    /** Admission is not durability. Only successful completion of the returned future is. */
    public CompletionStage<Result> submit(Write original) {
        Objects.requireNonNull(original);
        if (!tenant.equals(original.id().tenant())) throw new AdmissionException("WRONG_TENANT");
        Write write = new Write(original.id(), original.operationId(), original.epoch(),
                original.expectedVersion(), original.schemaVersion(), original.kind(), original.payload());
        if (write.ownedPayload().length > maxPayloadBytes) throw new AdmissionException("PAYLOAD_TOO_LARGE");
        long charge = 128L + write.ownedPayload().length; // even an empty write consumes admission credit
        CompletableFuture<Result> future = new CompletableFuture<>();
        synchronized (queue) {
            if (closed) throw new AdmissionException("CLOSED");
            if (outstandingEntries >= maxEntries || charge > maxQueuedBytes - queuedBytes)
                throw new AdmissionException("BACKPRESSURE");
            queue.addLast(new Pending(write, future, charge));
            queuedBytes += charge; outstandingEntries++;
            if (!flushRequested && (queue.size() >= maxBatchEntries || queuedBytes >= maxBatchBytes)) {
                flushRequested = true; writer.execute(this::flushRequestedBatches);
            }
        }
        return future;
    }
    /** Guest callback is never invoked on the storage worker; it must be mailbox-admitted. */
    public void submitToActor(Write write, ActorMailbox mailbox, BiConsumer<Result, Throwable> continuation) {
        Objects.requireNonNull(mailbox); Objects.requireNonNull(continuation);
        submit(write).whenComplete((result, error) -> mailbox.post(() -> continuation.accept(result, error)));
    }
    /** Nonblocking flush trigger; completion returned only once selected writes settle. */
    public CompletionStage<Void> drain() {
        CompletableFuture<Void> result = new CompletableFuture<>();
        synchronized (queue) {
            if (closed) throw new AdmissionException("CLOSED");
            writer.execute(() -> { while (true) { if (!safeFlush()) break; } result.complete(null); });
        }
        return result;
    }
    private void flushRequestedBatches() {
        while (true) {
            synchronized (queue) {
                if (queue.size() < maxBatchEntries && queuedBytes < maxBatchBytes) {
                    flushRequested = false; return;
                }
            }
            if (!safeFlush()) {
                synchronized (queue) { flushRequested = false; }
                return;
            }
        }
    }
    private boolean safeFlush() {
        List<Pending> batch = new ArrayList<>();
        synchronized (queue) {
            long bytes = 0;
            for (Pending pending : queue) {
                long length = pending.write().ownedPayload().length;
                if (batch.size() == maxBatchEntries || bytes + length > maxBatchBytes) break;
                batch.add(pending); bytes += length;
            }
            for (int i = 0; i < batch.size(); i++) {
                queue.removeFirst();
            }
        }
        if (batch.isEmpty()) return false;
        List<Write> writes = batch.stream().map(Pending::write).toList();
        try {
            List<Result> results = store.commit(writes);
            if (results == null || results.size() != batch.size()) throw new IllegalStateException("bad store receipts");
            for (int i = 0; i < batch.size(); i++) {
                Result receipt = results.get(i);
                if (receipt == null || !receipt.operationId().equals(batch.get(i).write().operationId()))
                    throw new IllegalStateException("unmatched store receipt");
            }
            for (int i = 0; i < batch.size(); i++) batch.get(i).answer().complete(results.get(i));
        } catch (Exception failure) {
            // Never report a definite failure of effects after an ambiguous commit exception.
            for (Pending pending : batch) pending.answer().completeExceptionally(
                    new AdmissionException("OUTCOME_UNKNOWN"));
        }
        synchronized (queue) {
            for (Pending pending : batch) { queuedBytes -= pending.charge(); outstandingEntries--; }
        }
        return true;
    }
    @Override public void close() {
        synchronized (queue) {
            if (closed) return;
            closed = true;
            writer.execute(() -> { while (safeFlush()) { /* drain bounded batches */ } });
            writer.shutdown();
        }
        try {
            if (!writer.awaitTermination(15, TimeUnit.SECONDS)) writer.shutdownNow();
        } catch (InterruptedException interrupted) {
            writer.shutdownNow(); Thread.currentThread().interrupt();
        }
        synchronized (queue) {
            for (Pending p : queue) p.answer().completeExceptionally(new AdmissionException("OUTCOME_UNKNOWN"));
            queue.clear(); queuedBytes = 0; outstandingEntries = 0;
        }
    }
    private static void validId(String value) {
        if (value == null || value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > 128)
            throw new IllegalArgumentException("invalid identity");
    }

    /** Non-durable, single-process reference store for contract tests only. */
    public static final class MemoryStore implements Store {
        private record Slot(Identity id) {}
        private record Operation(Identity id, String op) {}
        private record Dedup(byte[] digest, Result result) {}
        private final Map<String, Long> epochs = new HashMap<>();
        private final Map<Slot, Long> versions = new HashMap<>();
        private final Map<Slot, byte[]> snapshots = new HashMap<>();
        private final Map<Operation, Dedup> operations = new HashMap<>();
        private int commitCalls;
        private static String epochKey(String tenant, String actor) { return tenant + "\u0000" + actor; }
        @Override public synchronized void grantEpoch(String tenant, String actor, long epoch) {
            validId(tenant); validId(actor);
            if (epoch <= 0 || epoch < epochs.getOrDefault(epochKey(tenant, actor), 0L))
                throw new AdmissionException("STALE_EPOCH");
            epochs.put(epochKey(tenant, actor), epoch);
        }
        @Override public synchronized List<Result> commit(List<Write> batch) {
            commitCalls++;
            List<Result> out = new ArrayList<>();
            for (Write w : batch) {
                Operation op = new Operation(w.id(), w.operationId());
                byte[] hash = digest(w);
                Dedup seen = operations.get(op);
                if (seen != null) {
                    out.add(Arrays.equals(seen.digest(), hash)
                        ? new Result(w.operationId(), Status.DUPLICATE, seen.result().version(), false)
                        : new Result(w.operationId(), Status.OP_ID_CONFLICT, -1, false));
                    continue;
                }
                if (epochs.getOrDefault(epochKey(w.id().tenant(), w.id().actor()), 0L) != w.epoch()) {
                    out.add(new Result(w.operationId(), Status.STALE_EPOCH, -1, false)); continue;
                }
                Slot slot = new Slot(w.id());
                long version = versions.getOrDefault(slot, 0L);
                if (version != w.expectedVersion() || version == Long.MAX_VALUE) {
                    out.add(new Result(w.operationId(), Status.VERSION_CONFLICT, version, false)); continue;
                }
                versions.put(slot, version + 1);
                if (w.kind() == Kind.SNAPSHOT) snapshots.put(slot, w.payload());
                Result result = new Result(w.operationId(), Status.COMMITTED, version + 1, false);
                operations.put(op, new Dedup(hash, result));
                out.add(result);
            }
            return out;
        }
        public synchronized int commitCalls() { return commitCalls; }
        public synchronized long version(Identity id) { return versions.getOrDefault(new Slot(id), 0L); }
        public synchronized byte[] snapshot(Identity id) {
            byte[] data = snapshots.get(new Slot(id)); return data == null ? null : data.clone();
        }
        private static byte[] digest(Write w) {
            try {
                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                for (String s : List.of(w.id().tenant(),w.id().actor(),w.id().state(),w.operationId(),
                     Long.toString(w.epoch()),Long.toString(w.expectedVersion()),
                     Integer.toString(w.schemaVersion()),w.kind().name())) {
                    byte[] data = s.getBytes(StandardCharsets.UTF_8);
                    sha.update(ByteBuffer.allocate(4).putInt(data.length).array()); sha.update(data);
                }
                sha.update(ByteBuffer.allocate(4).putInt(w.ownedPayload().length).array());
                return sha.digest(w.ownedPayload());
            } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        }
    }
}
