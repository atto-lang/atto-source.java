package dev.oreslang.runtime;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Internal actor binding to a physical WAL file. NOT a guest type or DATA
 * payload. Creation requires the current actor of the same OresContext.
 * File open/write/sync/close run on bounded AsyncNativeIo workers.
 *
 * This checks actor affinity and scoped permissions through the context
 * shard registry, but does not yet implement a guest ABI, production
 * tenant-authentication provider, or persistent durable ACK protocol.
 */
final class DbDurableWalAuthority {
    private final OresContext context;
    private final ActorRuntime.ActorId owner;
    private final Path filePath;
    private final DbDurableWalFile file;
    private final DbWalTrustedGrantRegistry.Grant grant;
    private final DbWalAuthorityLifecycle lifecycle = new DbWalAuthorityLifecycle();

    private DbDurableWalAuthority(OresContext context,
            ActorRuntime.ActorId owner, Path filePath, DbDurableWalFile file,
            DbWalTrustedGrantRegistry.Grant grant) {
        this.context = context;
        this.owner = owner;
        this.filePath = filePath;
        this.file = file;
        this.grant = grant;
    }

    static OresFuture<DbDurableWalAuthority> openForActor(
            OresContext context, Path trustedDirectory, String basename,
            DbWalTrustedGrantRegistry.Grant grant) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(trustedDirectory);
        Objects.requireNonNull(grant);
        ActorRuntime.ActorId actor = requireCurrentOwner(context);
        // The issuing host must authenticate tenant and shard. The opaque
        // grant reserves the shard through actor drain/physical close.
        if (!grant.issuedBy(context.dbWalGrants()))
            throw new SecurityException("WAL grant was not issued by owning context");
        int ownerGeneration = grant.generation();
        // Invalid basenames cannot consume an OPENING reservation. Host OS
        // failures after the physical worker starts remain conservatively
        // fenced unless closure of its exact returned file is proven.
        DbDurableWalFile.requireValidOpenArguments(basename, ownerGeneration);
        // Caller is a trusted host service. Never derive this directory from
        // guest DATA. Source guest cannot instantiate DbDurableWalAuthority.
        Path filePath = trustedDirectory.resolve(Objects.requireNonNull(basename));
        context.requirePermission(RuntimePermissions.Permission.READ,
                filePath.toString(), "db.wal.open.read");
        context.requirePermission(RuntimePermissions.Permission.WRITE,
                filePath.toString(), "db.wal.open.write");
        AsyncNativeIo io = context.nativeIo();
        // LAST synchronous step before worker admission. All path and
        // permission checks above can throw without stranding the grant.
        grant.reserveOpen(context.actors());
        return io.submitWithNonStartHook("db.wal.open", () -> {
            DbDurableWalFile opened = null;
            try {
                opened = DbDurableWalFile.openHostOnly(
                        trustedDirectory, basename, ownerGeneration);
                grant.notePhysicalOpen(opened);
                io.register(opened);
                grant.bindPhysicalFile(opened);
                return new DbDurableWalAuthority(
                        context, actor, filePath, opened, grant);
            } catch (Exception rejected) {
                // A resource retained by post-shutdown registration belongs
                // to AsyncNativeIo's physical join/sweep. Never free its FD
                // or make its shard available to another incarnation here.
                if (opened != null && !io.retainsRegisteredResource(opened)) {
                    try {
                        opened.close();
                        grant.failedOpenAfterVerifiedClose(opened);
                    } catch (Exception closeFailure) {
                        rejected.addSuppressed(closeFailure);
                        // Uncertain close leaves OPENING fenced permanently
                        // until context-controlled cleanup/reconciliation.
                    }
                }
                throw rejected;
            }
        }, grant::neverStartedOpen);
    }

    synchronized OresFuture<DbDurableWalFile.SyncReceipt> appendAndSync(
            int generation, int operationId, byte[] boundedFrame) {
        requireOwner();
        grant.requireLive(context.actors());
        if (generation != grant.generation())
            throw new SecurityException("stale WAL generation");
        context.requirePermission(RuntimePermissions.Permission.WRITE,
                filePath.toString(), "db.wal.append_sync");
        Objects.requireNonNull(boundedFrame);
        if (boundedFrame.length < DbDurableWalFile.MIN_FRAME
                || boundedFrame.length > DbDurableWalFile.MAX_FRAME)
            throw new IllegalArgumentException("invalid WAL frame size");
        // Detach BEFORE any wire validation or scheduler admission. A
        // mutable guest buffer must not change tenant/op/checksum between
        // validation and the asynchronous physical write.
        byte[] frame = boundedFrame.clone();
        DbWalFrameV1.requireMatching(
                frame, grant.tenantId(), generation, operationId);
        if (!lifecycle.beginWrite())
            throw new IllegalStateException("WAL sealed or physical operation outstanding");
        AsyncNativeIo io = context.nativeIo();
        // If admission is refused or queued work is discarded, this gate can
        // stay sealed. Fail closed until trusted supervisor reconciliation.
        return io.submitWithNonStartHook("db.wal.append_sync", () -> {
            try (AsyncNativeIo.IoLoan loan = io.borrow(file)) {
                return file.appendAndSync(generation, operationId, frame);
            } finally {
                // Only the actually executing physical worker releases this
                // admission. Never trust a terminal/cancelled Future for it.
                lifecycle.physicalWriteRetired();
            }
        }, lifecycle::neverStartedWrite);
    }

    synchronized OresFuture<Void> revoke() {
        requireOwner();
        // DRAINING permanently seals new writes, even if one is in flight.
        // A second owner call after physical retirement can finish close.
        boolean readyToClose = lifecycle.requestDrain();
        // The grant remains owned by this incarnation while physically
        // draining. No replacement generation may acquire the shard yet.
        grant.beginDrain(context.actors());
        if (!readyToClose)
            throw new IllegalStateException(
                    "WAL drain pending, closing, or already closed");
        if (!lifecycle.beginClose())
            throw new IllegalStateException("WAL close already scheduled");
        AsyncNativeIo io = context.nativeIo();
        // CONTROL rejection/never-started cancellation leaves CLOSING:
        // context-owned registration retains the physical file for a
        // verified shutdown sweep. Never release or retry optimistically.
        java.util.concurrent.atomic.AtomicBoolean closeSucceeded =
                new java.util.concurrent.atomic.AtomicBoolean();
        return io.submitControlWithRetirementHook("db.wal.revoke", () -> {
            try (AsyncNativeIo.IoLoan loan = io.borrow(file)) {
                file.close();
            }
            // The physical resource loan exited, but AsyncNativeIo's
            // callback-drain and final operation retirement have NOT yet.
            io.unregister(file);
            closeSucceeded.set(true);
            return null;
        }, () -> {
            if (!closeSucceeded.get()) return; // unknown; stay CLOSING
            lifecycle.physicalCloseRetired();
            grant.physicalRetire(file, lifecycle);
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
