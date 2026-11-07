package dev.oreslang.runtime;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Host-internal actor-incarnation check for the WAL physical owner.
 * Actor activation authority is minted by the real ActorRuntime ActorCell,
 * never by a caller-owned actor ID or arbitrary thread-local actor proxy.
 *
 * This is NOT a guest builtin, a secure filesystem open, a durable commit API,
 * or a provider of tenant authority. A trusted host registry must supply the
 * independently verified tenant/shard scope and exclusively confined FD.
 * Java package privacy is not a sandbox if unrestricted host interop is enabled.
 */
final class WalStorageActorGate {
    private WalStorageActorGate(Capture capture, WalStorageFileOwner owner) {
        this.capture = Objects.requireNonNull(capture);
        this.owner = Objects.requireNonNull(owner);
    }

    /** One mint per actor incarnation; intentionally never a numeric bearer ID. */
    static final class Capture {
        private final OresContext context;
        private final ActorRuntime.StorageActorProof proof;
        private final WalStorageFileOwner.Scope scope;
        private final Path policyRoot;
        private volatile boolean revoked;

        private Capture(OresContext context, ActorRuntime.StorageActorProof proof,
                        WalStorageFileOwner.Scope scope, Path policyRoot) {
            this.context = context;
            this.proof = proof;
            this.scope = scope;
            this.policyRoot = policyRoot;
        }

        private boolean actorStillAlive() {
            return !revoked && proof.actorStillAlive();
        }

        private void requireActorTurn() {
            if (!actorStillAlive()
                    || !proof.validOnCurrentTurn()
                    || proof.actor().generation() != scope.generation()) {
                throw new SecurityException("foreign, stale or retired WAL actor incarnation");
            }
            // Both actor-local and isolate policy, plus scoped host permission.
            context.requirePermission(RuntimePermissions.Permission.WRITE,
                    policyRoot.toString(), "db.wal.storage");
        }
    }

    /**
     * Call only from a trusted compiler/runtime actor turn. Scope comes from a
     * supervisor registry, NOT a WAL header or guest message. This proves the
     * live actor incarnation and scheduler-minted numeric storage slot,
     * not independent tenant registry custody.
     */
    static Capture captureOnActorTurn(OresContext context,
                                      ActorRuntime.ActorRef<?> expectedActor,
                                      WalStorageFileOwner.Scope trustedScope,
                                      Path hostPolicyRoot) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(expectedActor);
        Objects.requireNonNull(trustedScope);
        Path root = Objects.requireNonNull(hostPolicyRoot).toAbsolutePath().normalize();
        // Capture the real scheduler-owned ActorCell, NOT an identity inferred
        // from a caller-selected ActorRef, message, or Java thread-local proxy.
        ActorRuntime.StorageActorProof proof = ActorRuntime.mintStorageActorProof();
        if (proof.runtime() != context.actors()
                || proof.actor() != expectedActor
                || proof.actor().generation() != trustedScope.generation()
                || proof.storageActorOrdinal() != trustedScope.actor()) {
            throw new SecurityException("WAL storage capture has foreign runtime/actor/generation");
        }
        context.requirePermission(RuntimePermissions.Permission.WRITE, root.toString(),
                "db.wal.storage.capture");
        return new Capture(context, proof, trustedScope, root);
    }

    /**
     * Adopt an independently confined, EXCLUSIVE, preopened FD off actor
     * carriers. The caller must already have proved inode/directory custody,
     * parent-directory durability and tenant/shard registration. Nothing here
     * opens files or resolves attacker-controlled paths.
     */
    static WalStorageActorGate attachOffCarrier(Capture capture,
                                                FileChannel preopenedExclusiveFile)
            throws IOException {
        Objects.requireNonNull(capture);
        Objects.requireNonNull(preopenedExclusiveFile);
        if (ActorRuntime.isActorCarrierThread() || ActorRuntime.inActorExecution()) {
            throw new SecurityException("file descriptor adoption cannot block actor carrier");
        }
        if (!capture.actorStillAlive()) {
            throw new SecurityException("actor retired before WAL descriptor adoption");
        }
        WalStorageFileOwner owner = WalStorageFileOwner.openVerified(
                preopenedExclusiveFile, capture, capture.scope);
        if (!capture.actorStillAlive()) {
            owner.revoke();
            owner.revokeAndCloseAsync(); // retains FD until quiescent; never release on caller thread
            throw new SecurityException("actor retired during WAL descriptor adoption");
        }
        return new WalStorageActorGate(capture, owner);
    }

    /** A pending host-only effect; cannot be used to claim durable DB commit. */
    static final class Pending {
        private final WalStorageActorGate issuer;
        private final CompletionStage<WalStorageFileOwner.SyncReceipt> stage;
        private final byte[] submittedDigest;
        private final int operation;
        private final int sequence;
        // A physical sync witness can authorize at most one logical state
        // transition. This is not a persisted deduplication ledger.
        private final AtomicBoolean checkedOnce = new AtomicBoolean();

        private Pending(WalStorageActorGate issuer,
                        CompletionStage<WalStorageFileOwner.SyncReceipt> stage,
                        byte[] submittedDigest, int operation, int sequence) {
            this.issuer = issuer;
            this.stage = stage;
            this.submittedDigest = submittedDigest.clone();
            this.operation = operation;
            this.sequence = sequence;
        }

        CompletionStage<WalStorageFileOwner.SyncReceipt> completion() { return stage; }
    }

    private final Capture capture;
    private final WalStorageFileOwner owner;

    Pending appendAndSyncOnActorTurn(byte[] untrustedFrame) {
        capture.requireActorTurn();
        byte[] copied = Objects.requireNonNull(untrustedFrame).clone();
        // The owner's bounded frame validator runs before any dispatch. These
        // offsets are read only after it successfully accepted the frame.
        CompletionStage<WalStorageFileOwner.SyncReceipt> stage = owner.appendAndSync(
                capture, capture.scope, copied);
        return new Pending(this, stage, sha256(copied),
                signedI31(copied, 18), signedI31(copied, 14));
    }

    /**
     * Must run on the RE-ENTERED owner actor turn after awaiting Pending.stage.
     * Compares actual completed object identity (not forged receipt fields).
     * Does not publish state or issue guest `Committed`.
     */
    void verifyPhysicalSyncOnActorTurn(Pending pending,
                                       WalStorageFileOwner.SyncReceipt receipt) {
        capture.requireActorTurn();
        if (pending == null || pending.issuer != this || receipt == null) {
            throw new SecurityException("foreign WAL completion");
        }
        final WalStorageFileOwner.SyncReceipt actual;
        try {
            actual = pending.stage.toCompletableFuture().getNow(null);
        } catch (RuntimeException failedOrCancelled) {
            throw new SecurityException("failed or indeterminate WAL completion", failedOrCancelled);
        }
        if (actual != receipt || !capture.scope.equals(receipt.scope())
                || receipt.operation() != pending.operation
                || receipt.sequence() != pending.sequence
                || !MessageDigest.isEqual(pending.submittedDigest, receipt.sha256())) {
            throw new SecurityException("WAL completion is not this authenticated sync");
        }
        // Only spend the receipt after all identity and source-content checks
        // pass. If a guest continuation retries a successful verification,
        // deny the duplicate instead of publishing two logical commits.
        if (!pending.checkedOnce.compareAndSet(false, true)) {
            throw new SecurityException("WAL sync receipt already verified");
        }
    }

    /** Revocation may race an already dispatched write: never infer rollback. */
    CompletionStage<Void> revokeAndCloseAsync() {
        capture.revoked = true;
        return owner.revokeAndCloseAsync();
    }

    private static int signedI31(byte[] b, int i) {
        return (b[i] & 255) | ((b[i+1] & 255) << 8)
                | ((b[i+2] & 255) << 16) | ((b[i+3] & 127) << 24);
    }

    private static byte[] sha256(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
