package dev.oreslang.runtime;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Internal actor binding to a physical WAL file. NOT a guest type or DATA
 * payload. Creation requires the current actor of the same OresContext.
 * File open/write/sync/close run on bounded AsyncNativeIo workers.
 *
 * This checks actor affinity and scoped permissions, but does not yet
 * implement a guest ABI, tenant/shard registry, or persistent ACK protocol.
 */
final class DbDurableWalAuthority {
    private final OresContext context;
    private final ActorRuntime.ActorId owner;
    private final Path filePath;
    private final DbDurableWalFile file;
    private final AtomicBoolean outstanding = new AtomicBoolean();
    private final AtomicBoolean revoked = new AtomicBoolean();

    private DbDurableWalAuthority(OresContext context,
            ActorRuntime.ActorId owner, Path filePath, DbDurableWalFile file) {
        this.context = context;
        this.owner = owner;
        this.filePath = filePath;
        this.file = file;
    }

    static OresFuture<DbDurableWalAuthority> openForActor(
            OresContext context, Path trustedDirectory, String basename,
            int ownerGeneration) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(trustedDirectory);
        ActorRuntime.ActorId actor = requireCurrentOwner(context);
        // Caller is a trusted host service. Never derive this directory from
        // guest DATA. Source guest cannot instantiate DbDurableWalAuthority.
        Path filePath = trustedDirectory.resolve(Objects.requireNonNull(basename));
        context.requirePermission(RuntimePermissions.Permission.READ,
                filePath.toString(), "db.wal.open.read");
        context.requirePermission(RuntimePermissions.Permission.WRITE,
                filePath.toString(), "db.wal.open.write");
        AsyncNativeIo io = context.nativeIo();
        return io.submit("db.wal.open", () -> {
            DbDurableWalFile opened = DbDurableWalFile.openHostOnly(
                    trustedDirectory, basename, ownerGeneration);
            try {
                io.register(opened);
                return new DbDurableWalAuthority(context, actor, filePath, opened);
            } catch (Exception rejected) {
                opened.close();
                throw rejected;
            }
        });
    }

    synchronized OresFuture<DbDurableWalFile.SyncReceipt> appendAndSync(
            int generation, int operationId, byte[] boundedFrame) {
        requireOwner();
        context.requirePermission(RuntimePermissions.Permission.WRITE,
                filePath.toString(), "db.wal.append_sync");
        Objects.requireNonNull(boundedFrame);
        if (boundedFrame.length < DbDurableWalFile.MIN_FRAME
                || boundedFrame.length > DbDurableWalFile.MAX_FRAME)
            throw new IllegalArgumentException("invalid WAL frame size");
        if (revoked.get() || !outstanding.compareAndSet(false, true))
            throw new IllegalStateException("WAL revoked or operation already outstanding");
        // Detached copy prevents guest-owned buffer mutation while suspended.
        byte[] frame = boundedFrame.clone();
        AsyncNativeIo io = context.nativeIo();
        // If admission is refused or queued work is discarded, this gate can
        // stay sealed. Fail closed until trusted supervisor reconciliation.
        return io.submit("db.wal.append_sync", () -> {
            try (AsyncNativeIo.IoLoan loan = io.borrow(file)) {
                return file.appendAndSync(generation, operationId, frame);
            } finally {
                outstanding.set(false);
            }
        });
    }

    synchronized OresFuture<Void> revoke() {
        requireOwner();
        if (!revoked.compareAndSet(false, true))
            throw new IllegalStateException("WAL authority already revoked");
        if (outstanding.get())
            throw new IllegalStateException("WAL physical operation unresolved");
        AsyncNativeIo io = context.nativeIo();
        // CONTROL may be rejected during shutdown; then the registered file
        // remains under context ownership until physical I/O retirement.
        return io.submitControl("db.wal.revoke", () -> {
            try (AsyncNativeIo.IoLoan loan = io.borrow(file)) {
                file.close();
            } finally {
                io.unregister(file);
            }
            return null;
        });
    }

    private static ActorRuntime.ActorId requireCurrentOwner(OresContext context) {
        if (ActorRuntime.currentActorRuntime() != context.actors())
            throw new SecurityException("WAL requires owning ActorRuntime");
        return ActorRuntime.currentActorId().orElseThrow(() ->
                new SecurityException("WAL requires an actor execution turn"));
    }

    private void requireOwner() {
        ActorRuntime.ActorId current = requireCurrentOwner(context);
        if (!current.equals(owner)) throw new SecurityException("foreign WAL actor");
    }
}
