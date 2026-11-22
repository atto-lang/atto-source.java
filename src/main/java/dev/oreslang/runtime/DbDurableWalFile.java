package dev.oreslang.runtime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;

/**
 * HOST-ONLY physical WAL file gate; never exposed as a guest host value or
 * mailbox DATA. The trusted caller must own an authenticated, private
 * directory and enforce permissions, actor generation, and I/O-worker loans.
 * No constructor argument is an authorization capability. This performs
 * synchronous system calls: invoke ONLY on a bounded pinned I/O worker.
 *
 * A returned receipt means FileChannel.force(true) completed without error;
 * it is NOT a guest commit, ACK, or proof of parent-directory sync.
 * Locks are advisory: the runtime must own a private non-hostile directory.
 */
final class DbDurableWalFile implements AutoCloseable {
    static final int MAX_FRAME = 1118;
    static final int MIN_FRAME = 31;

    /** Metadata only; neither serializable nor an authorization receipt. */
    static final class SyncReceipt {
        private final int generation;
        private final int operationId;
        private final long begin;
        private final long end;
        private SyncReceipt(int generation, int operationId, long begin, long end) {
            this.generation = generation;
            this.operationId = operationId;
            this.begin = begin;
            this.end = end;
        }
        int generation() { return generation; }
        int operationId() { return operationId; }
        long begin() { return begin; }
        long end() { return end; }
    }

    private final FileChannel channel;
    private final FileLock lock;
    private final int generation;
    private boolean closed;
    private boolean uncertain;

    private DbDurableWalFile(FileChannel channel, FileLock lock, int generation) {
        this.channel = channel;
        this.lock = lock;
        this.generation = generation;
    }

    /** Host-internal only: caller must establish trusted directory authority. */
    static void requireValidOpenArguments(String basename, int generation) {
        if (generation < 1 || basename == null || basename.isEmpty()
                || basename.length() > 100 || basename.equals(".") || basename.equals("..")
                || basename.indexOf('/') >= 0 || basename.indexOf('\\') >= 0
                || basename.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid WAL owner generation or basename");
        }
    }

    static DbDurableWalFile openHostOnly(Path privateDirectory, String basename,
                                        int generation) throws IOException {
        requireValidOpenArguments(basename, generation);
        Path root = privateDirectory.toAbsolutePath().normalize();
        Path physical = root.toRealPath();
        if (!physical.equals(root.toRealPath(LinkOption.NOFOLLOW_LINKS))
                || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new SecurityException("WAL directory is not a trusted real directory");
        }
        Path target = root.resolve(basename);
        Set<OpenOption> options = Set.of(StandardOpenOption.CREATE,
                StandardOpenOption.READ, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        FileChannel opened = FileChannel.open(target, options);
        try {
            BasicFileAttributes attrs = Files.readAttributes(
                    target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isRegularFile()) throw new SecurityException("WAL must be regular file");
            try {
                Object links = Files.getAttribute(target, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
                if (links instanceof Number n && n.longValue() != 1)
                    throw new SecurityException("WAL has multiple hard links");
            } catch (UnsupportedOperationException ignored) {
                // If unsupported, the owner MUST guarantee private directory.
            }
            FileLock ownerLock;
            try {
                ownerLock = opened.tryLock();
            } catch (OverlappingFileLockException alreadyOwned) {
                throw new IOException("WAL already has an owner", alreadyOwned);
            }
            if (ownerLock == null) throw new IOException("WAL owner lock unavailable");
            return new DbDurableWalFile(opened, ownerLock, generation);
        } catch (IOException | RuntimeException error) {
            opened.close();
            throw error;
        }
    }

    /**
     * A failed/partially completed write can have reached disk. Poison the
     * handle and return no clean negative outcome; replay under a newer fence.
     */
    synchronized SyncReceipt appendAndSync(int expectedGeneration,
                                           int operationId, byte[] frame) throws IOException {
        if (closed || uncertain) throw new IllegalStateException("WAL sealed or unknown");
        if (expectedGeneration != generation || operationId < 1)
            throw new SecurityException("stale WAL generation or operation ID");
        if (frame == null || frame.length < MIN_FRAME || frame.length > MAX_FRAME)
            throw new IllegalArgumentException("invalid bounded WAL frame");
        long before = channel.size();
        if (before > Long.MAX_VALUE - frame.length) throw new IOException("WAL length overflow");
        try {
            channel.position(before);
            ByteBuffer bytes = ByteBuffer.wrap(frame.clone());
            while (bytes.hasRemaining()) {
                int n = channel.write(bytes);
                if (n <= 0) throw new IOException("WAL write made no progress");
            }
            channel.force(true);
            return new SyncReceipt(generation, operationId, before, before + frame.length);
        } catch (IOException | RuntimeException error) {
            uncertain = true;
            throw error;
        }
    }

    synchronized long size() throws IOException {
        if (closed) throw new IllegalStateException("WAL closed");
        return channel.size();
    }

    /**
     * Trusted single-generation recovery preflight through the physically
     * locked owner file. The generation here identifies existing on-disk
     * records, which may predate this newly opened owner's generation.
     * Caller must hold the exclusive shard-recovery fence and stage state.
     * Never auto-truncate, publish, or ACK based on a scan result alone.
     */
    synchronized DbWalReplayV1.Report scanRecovery(
            int tenant, int segmentGeneration) throws IOException {
        if (closed || uncertain) throw new IllegalStateException(
                "WAL closed or prior physical result unresolved");
        return DbWalReplayV1.scan(channel, tenant, segmentGeneration);
    }

    synchronized boolean uncertain() { return uncertain; }
    synchronized boolean closed() { return closed; }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try { lock.release(); } finally { channel.close(); }
    }
}
