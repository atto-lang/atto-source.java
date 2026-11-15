package dev.oreslang.runtime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Runtime-internal WAL physical I/O owner. NEVER publish this class, a FileChannel,
 * its token, or its receipt as guest DATA or a Java interop target.
 *
 * Admission and file custody must be established by the embedding runtime on
 * an OFF-CARRIER host thread BEFORE calling openVerified(). This primitive
 * does not implement secure path traversal, actor identity lookup, or
 * namespace registration; until those integrations exist guest access is DENIED.
 */
final class WalStorageFileOwner {
    static final int MAX_OUTSTANDING = 32;
    private static final int MAX_FRAME = 1_118;
    private static final int MAX_HISTORY = 4_096;
    private static final long MAX_BYTES = 1_048_576;

    record Scope(int tenant, long isolate, int shard, long actor, int generation) {
        Scope {
            if (tenant <= 0 || isolate <= 0 || shard <= 0 || actor <= 0 || generation <= 0) {
                throw new IllegalArgumentException("invalid WAL storage scope");
            }
        }
    }

    static final class SyncReceipt {
        private final Scope scope;
        private final int operation;
        private final int sequence;
        private final long endOffset;
        private final byte[] sha256;

        private SyncReceipt(Scope scope, int operation, int sequence, long endOffset, byte[] sha256) {
            this.scope = scope;
            this.operation = operation;
            this.sequence = sequence;
            this.endOffset = endOffset;
            this.sha256 = sha256.clone();
        }
        Scope scope() { return scope; }
        int operation() { return operation; }
        int sequence() { return sequence; }
        long endOffset() { return endOffset; }
        byte[] sha256() { return sha256.clone(); }
    }

    /** A dispatched write may have affected disk even when its outcome is lost. */
    static final class UnknownOutcome extends IOException {
        private static final long serialVersionUID = 1L;
        UnknownOutcome(String message, Throwable cause) { super(message, cause); }
    }

    private final Object runtimeActivation;
    private final Scope scope;
    private final FileChannel file;
    private final ThreadPoolExecutor diskWorker;
    private final ThreadPoolExecutor completionWorker;
    private final Object gate = new Object();
    private final Set<Integer> operationHistory = new HashSet<>();
    private int nextSequence = 1;
    private int outstanding;
    private long admittedBytes;
    private long syncedEnd;
    private boolean revoked;
    private boolean uncertain;
    private CompletableFuture<Void> shutdown;

    private WalStorageFileOwner(FileChannel file, Object runtimeActivation, Scope scope) {
        this.file = Objects.requireNonNull(file);
        this.runtimeActivation = Objects.requireNonNull(runtimeActivation);
        this.scope = Objects.requireNonNull(scope);
        // Workload isolation is about physical off-carrier dispatch, NOT
        // creating a blocking operation on the current actor scheduler thread.
        diskWorker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(MAX_OUTSTANDING),
                task -> new Thread(task, "ores-wal-disk"),
                new ThreadPoolExecutor.AbortPolicy());
        completionWorker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(MAX_OUTSTANDING),
                task -> new Thread(task, "ores-wal-handoff"),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Host-only adoption. The caller MUST already have verified a regular,
     * exclusive, confined file handle and synced creation metadata. This method
     * refuses all existing data until durable replay has reconstructed history.
     * Caller owns the FD if adoption fails. Never call on an actor carrier.
     */
    static WalStorageFileOwner openVerified(FileChannel preopenedFile,
                                            Object authenticatedActivation, Scope owner)
            throws IOException {
        Objects.requireNonNull(preopenedFile);
        if (!preopenedFile.isOpen() || preopenedFile.size() != 0L) {
            throw new IllegalStateException("nonempty WAL requires authenticated recovery");
        }
        return new WalStorageFileOwner(preopenedFile, authenticatedActivation, owner);
    }

    /**
     * Never blocks on physical I/O. A successful completion is HOST-ONLY sync
     * evidence, not a database commit ACK. Caller must verify its own current
     * actor activation after async completion before mutating guest state.
     */
    CompletionStage<SyncReceipt> appendAndSync(Object activation, Scope claimed,
                                                byte[] untrustedFrame) {
        Objects.requireNonNull(untrustedFrame);
        byte[] bytes = untrustedFrame.clone();
        FrameHeader header = verifyFrame(bytes);
        CompletableFuture<SyncReceipt> result = new CompletableFuture<>();
        synchronized (gate) {
            if (revoked || uncertain) throw new RejectedExecutionException("storage owner unavailable");
            if (activation != runtimeActivation || !scope.equals(claimed)) {
                throw new SecurityException("foreign or stale runtime activation");
            }
            if (header.tenant != scope.tenant() || header.generation != scope.generation()) {
                throw new SecurityException("WAL frame has foreign tenant or generation");
            }
            if (header.sequence != nextSequence) throw new RejectedExecutionException("sequence gap");
            if (operationHistory.contains(header.operation)) throw new RejectedExecutionException("duplicate operation");
            if (outstanding >= MAX_OUTSTANDING || operationHistory.size() >= MAX_HISTORY
                    || bytes.length > MAX_BYTES - admittedBytes) {
                throw new RejectedExecutionException("bounded admission limit");
            }
            ++outstanding;
            ++nextSequence;
            admittedBytes += bytes.length;
            operationHistory.add(header.operation);
            try {
                diskWorker.execute(() -> physicallyAppendAndSync(bytes, header, result));
            } catch (RejectedExecutionException rejected) {
                --outstanding;
                --nextSequence;
                admittedBytes -= bytes.length;
                operationHistory.remove(header.operation);
                throw rejected;
            }
        }
        // A minimal CompletionStage cannot be directly cancelled to claim that
        // a dispatched physical operation was undone.
        return result.minimalCompletionStage();
    }

    private void physicallyAppendAndSync(byte[] bytes, FrameHeader header,
                                         CompletableFuture<SyncReceipt> result) {
        SyncReceipt receipt = null;
        Throwable failure = null;
        try {
            synchronized (gate) {
                if (revoked || uncertain) throw new IOException("revoked before write");
            }
            long before = file.size();
            if (before != syncedEnd) throw new IOException("external WAL mutation");
            ByteBuffer src = ByteBuffer.wrap(bytes);
            long written = 0;
            while (src.hasRemaining()) {
                int n = file.write(src, before + written);
                if (n <= 0) throw new IOException("short or stalled WAL append");
                written += n;
            }
            file.force(true);
            syncedEnd = before + written;
            synchronized (gate) {
                if (revoked) throw new IOException("revoked after sync");
            }
            receipt = new SyncReceipt(scope, header.operation, header.sequence,
                    syncedEnd, sha256(bytes));
        } catch (IOException | RuntimeException error) {
            failure = new UnknownOutcome("WAL physical outcome unknown; quarantine owner", error);
            synchronized (gate) { uncertain = true; }
        }
        final SyncReceipt published = receipt;
        final Throwable problem = failure;
        try {
            completionWorker.execute(() -> {
                Throwable deliverError;
                synchronized (gate) {
                    deliverError = problem != null ? problem :
                            (revoked || uncertain ?
                             new UnknownOutcome("revoked or quarantined before receipt delivery", null)
                             : null);
                    // Do NOT invoke user continuations while holding the gate.
                    // A failed/ambiguous operation retains its admission history.
                    if (deliverError == null) --outstanding;
                }
                if (deliverError != null) result.completeExceptionally(deliverError);
                else result.complete(published);
            });
        } catch (RejectedExecutionException rejected) {
            synchronized (gate) { uncertain = true; }
            // Never run a user-defined completion continuation on diskWorker.
            Thread.ofVirtual().name("ores-wal-emergency-handoff").start(() ->
                    result.completeExceptionally(new UnknownOutcome("receipt handoff lost", rejected)));
        }
    }

    /** Retain unprovable outcomes and admitted history; do not silently reset. */
    void revoke() { synchronized (gate) { revoked = true; } }

    /**
     * Asynchronous shutdown. Never block an actor carrier on disk or handoff
     * retirement. An unretired writer deliberately keeps its FD owned so the
     * runtime must quarantine this object instead of releasing its arena.
     */
    CompletionStage<Void> revokeAndCloseAsync() {
        synchronized (gate) {
            if (shutdown != null) return shutdown.minimalCompletionStage();
            revoked = true;
            shutdown = new CompletableFuture<>();
            CompletableFuture<Void> ready = shutdown;
            Thread.ofVirtual().name("ores-wal-close").start(() -> {
                diskWorker.shutdown();
                try {
                    if (!diskWorker.awaitTermination(30, TimeUnit.SECONDS)) {
                        throw new IOException("disk worker not retired; preserve WAL FD");
                    }
                    completionWorker.shutdown();
                    if (!completionWorker.awaitTermination(30, TimeUnit.SECONDS)) {
                        throw new IOException("handoff worker not retired; preserve WAL FD");
                    }
                    file.close();
                    ready.complete(null);
                } catch (Exception error) {
                    ready.completeExceptionally(error);
                }
            });
            return shutdown.minimalCompletionStage();
        }
    }

    private record FrameHeader(int tenant, int generation, int sequence, int operation) { }
    private static int u8(byte[] a, int i) { return a[i] & 255; }
    private static int u16(byte[] a, int i) { return u8(a,i) | u8(a,i+1) << 8; }
    private static int i31(byte[] a, int i) {
        if (u8(a,i+3) > 127) throw new IllegalArgumentException("high-bit WAL ID");
        return u8(a,i) | u8(a,i+1) << 8 | u8(a,i+2) << 16 | u8(a,i+3) << 24;
    }
    private static FrameHeader verifyFrame(byte[] a) {
        if (a.length < 31 || a.length > MAX_FRAME || u8(a,0)!=79 || u8(a,1)!=68
                || u8(a,2)!=66 || u8(a,3)!=49 || u8(a,4)!=1) {
            throw new IllegalArgumentException("invalid WAL frame magic or length");
        }
        int kind = u8(a,5);
        if (kind != 1 && kind != 2) throw new IllegalArgumentException("invalid WAL kind");
        int tenant = i31(a,6), generation = i31(a,10), sequence = i31(a,14), operation = i31(a,18);
        int key = u16(a,22), value = u16(a,24);
        if (tenant<=0 || generation<=0 || sequence<=0 || operation<=0 || key<1 || key>64
                || value>1024 || (kind==2 && value!=0) || a.length!=30+key+value) {
            throw new IllegalArgumentException("invalid WAL header");
        }
        int s1=1,s2=0;
        for(int i=0;i<a.length-4;i++) {s1=(s1+u8(a,i))%65521;s2=(s2+s1)%65521;}
        if (u16(a,a.length-4)!=s1 || u16(a,a.length-2)!=s2)
            throw new IllegalArgumentException("invalid WAL checksum");
        return new FrameHeader(tenant,generation,sequence,operation);
    }
    private static byte[] sha256(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
