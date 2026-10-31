package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;

/**
 * Bounded host-only serialized WebSocket outbound admission. CONTROL is selected
 * ahead of waiting USER frames, but an already-started frame is never interleaved.
 * This is NOT an actor/guest socket authority or a physical nonblocking writer.
 */
final class WebSocketOutboundGate {
    final class Write {
        private final WebSocketFrameCodec.Frame frame;
        private final WebSocketActorConnectionLease.Loan actorLoan;
        private final OresFuture<Void> receipt =
                new OresFuture<>(() -> cancel(this));

        private Write(WebSocketFrameCodec.Frame frame,
                WebSocketActorConnectionLease.Loan actorLoan) {
            this.frame = frame;
            this.actorLoan = actorLoan;
        }

        WebSocketFrameCodec.Frame frame() { return frame; }
        OresFuture<Void> receipt() { return receipt; }
        // Queued cancellation releases after removal; active write releases
        // only on the physical writer's finish() after SocketChannel I/O.
        void releaseOwnership() {
            if (actorLoan != null) actorLoan.close();
        }
    }

    private final ArrayDeque<Write> control = new ArrayDeque<>();
    private final ArrayDeque<Write> user = new ArrayDeque<>();
    private final int maxFrameBytes;
    private final int userCapacity;
    private final int maxUserBytes;
    private final int controlCapacity;
    private final Runnable abortActive;
    private Write active;
    private int heldUserBytes;
    private int heldUserCount;
    private int heldControlCount;
    private boolean closing;
    private boolean retired;

    WebSocketOutboundGate(int maxFrameBytes, int userCapacity,
            int maxUserBytes, int controlCapacity, Runnable abortActive) {
        if (maxFrameBytes < 125 || maxFrameBytes > 64 * 1024
                || userCapacity < 1 || userCapacity > 16
                || maxUserBytes < maxFrameBytes || maxUserBytes > 1024 * 1024
                || controlCapacity < 1 || controlCapacity > 4) {
            throw new IllegalArgumentException("invalid bounded WebSocket outbound quotas");
        }
        this.maxFrameBytes = maxFrameBytes;
        this.userCapacity = userCapacity;
        this.maxUserBytes = maxUserBytes;
        this.controlCapacity = controlCapacity;
        this.abortActive = Objects.requireNonNull(abortActive);
    }

    synchronized Write enqueueUser(int opcode, byte[] payload) {
        return enqueueUser(opcode, payload, null);
    }

    synchronized Write enqueueUser(int opcode, byte[] payload,
            WebSocketActorConnectionLease.Loan actorLoan) {
        if (retired || closing) throw new RejectedExecutionException("WebSocket outbound closed");
        if (opcode != WebSocketFrameCodec.OPCODE_TEXT
                && opcode != WebSocketFrameCodec.OPCODE_BINARY) {
            throw new IllegalArgumentException("USER write must be text or binary");
        }
        Objects.requireNonNull(payload, "WebSocket outbound payload");
        int size = payload.length;
        // Validate quotas on the caller-owned length BEFORE cloning into the
        // immutable Frame. Oversized payloads must not amplify allocation.
        if (size > maxFrameBytes || heldUserCount >= userCapacity
                || size > maxUserBytes - heldUserBytes) {
            throw new RejectedExecutionException("WebSocket outbound USER quota exhausted");
        }
        WebSocketFrameCodec.Frame frame =
                new WebSocketFrameCodec.Frame(true, opcode, payload);
        Write write = new Write(frame, actorLoan);
        user.addLast(write);
        heldUserCount++;
        heldUserBytes += size;
        return write;
    }

    Write enqueueControl(int opcode, byte[] payload) {
        List<Write> dropped = List.of();
        Write write;
        synchronized (this) {
            if (retired || closing) {
                throw new RejectedExecutionException("WebSocket outbound closed");
            }
            if (opcode != WebSocketFrameCodec.OPCODE_PONG
                    && opcode != WebSocketFrameCodec.OPCODE_CLOSE) {
                throw new IllegalArgumentException("only required CONTROL responses allowed");
            }
            WebSocketFrameCodec.Frame frame =
                    new WebSocketFrameCodec.Frame(true, opcode, payload);
            if (opcode == WebSocketFrameCodec.OPCODE_CLOSE) {
                closing = true;
                dropped = new ArrayList<>(user);
                dropped.addAll(control);
                for (Write pending : user) {
                    heldUserBytes -= pending.frame.payloadUnsafe().length;
                    heldUserCount--;
                }
                heldControlCount -= control.size();
                user.clear();
                control.clear();
            } else if (heldControlCount >= controlCapacity) {
                throw new RejectedExecutionException("WebSocket CONTROL quota exhausted");
            }
            // RFC6455 close is the terminal CONTROL action. A reserved close
            // slot must remain available even when the sole physical writer
            // is sending a PONG and already holds every ordinary CONTROL
            // credit. Otherwise accepting CLOSE would set 'closing', drop
            // pending USER messages, then reject the only CLOSE response.
            //
            // At most one CLOSE can be queued: 'closing' is set before
            // publication and subsequent admission fails. Waiting PONGs
            // were discarded above, so this transient extra slot is bounded.
            write = new Write(frame, null);
            control.addLast(write);
            heldControlCount++;
        }
        RuntimeException callbackFailure = null;
        for (Write pending : dropped) {
            try {
                pending.receipt.failFromRuntime(
                        new RejectedExecutionException("WebSocket closing superseded pending writes"));
            } catch (RuntimeException error) {
                if (callbackFailure == null) callbackFailure = error;
                else callbackFailure.addSuppressed(error);
            } finally {
                pending.releaseOwnership();
            }
        }
        if (callbackFailure != null) throw callbackFailure;
        return write;
    }

    synchronized Write poll() {
        if (active != null) throw new IllegalStateException("concurrent WebSocket writer");
        if (retired) return null;
        Write result = control.pollFirst();
        if (result == null) result = user.pollFirst();
        active = result;
        return result;
    }

    synchronized boolean hasPending() {
        return !retired && (!control.isEmpty() || !user.isEmpty());
    }

    void finish(Write completed, Throwable failure) {
        boolean wasRetired;
        synchronized (this) {
            if (active != completed || completed == null) {
                throw new IllegalStateException("invalid WebSocket outbound completion");
            }
            active = null;
            if (completed.frame.control()) heldControlCount--;
            else {
                heldUserCount--;
                heldUserBytes -= completed.frame.payloadUnsafe().length;
            }
            wasRetired = retired;
        }
        try {
            if (failure != null || wasRetired) {
                completed.receipt.failFromRuntime(failure != null ? failure
                        : new RejectedExecutionException("WebSocket retired during write"));
            } else {
                completed.receipt.completeFromRuntime(null);
            }
        } finally {
            // The owning worker already exited its native socket borrow.
            // A Future cancelled while the frame was active does not own
            // this settlement: its physical lease remains until now.
            completed.releaseOwnership();
        }
    }

    void retire() {
        List<Write> dropped;
        synchronized (this) {
            if (retired) return;
            retired = true;
            closing = true;
            dropped = new ArrayList<>(control);
            dropped.addAll(user);
            for (Write pending : user) {
                heldUserBytes -= pending.frame.payloadUnsafe().length;
                heldUserCount--;
            }
            heldControlCount -= control.size();
            control.clear();
            user.clear();
            // The active write retains its credit and physical loan until
            // its worker returns through finish(). No forged completion.
        }
        RuntimeException callbackFailure = null;
        for (Write pending : dropped) {
            try {
                pending.receipt.failFromRuntime(
                        new RejectedExecutionException("WebSocket connection retired"));
            } catch (RuntimeException error) {
                if (callbackFailure == null) callbackFailure = error;
                else callbackFailure.addSuppressed(error);
            } finally {
                pending.releaseOwnership();
            }
        }
        if (callbackFailure != null) throw callbackFailure;
    }

    private void cancel(Write write) {
        boolean cancelActive = false;
        boolean removedQueued = false;
        synchronized (this) {
            if (user.remove(write)) {
                heldUserCount--;
                heldUserBytes -= write.frame.payloadUnsafe().length;
                removedQueued = true;
            } else if (control.remove(write)) {
                heldControlCount--;
                removedQueued = true;
            } else {
                cancelActive = active == write;
            }
        }
        if (removedQueued) write.releaseOwnership();
        // Active write cancellation can only request physical abort; no
        // release until the writer leaves its SocketChannel loan and finish.
        if (cancelActive) abortActive.run();
    }

    synchronized int heldUserBytes() { return heldUserBytes; }
    synchronized int heldUserCount() { return heldUserCount; }
    synchronized int heldControlCount() { return heldControlCount; }
}
