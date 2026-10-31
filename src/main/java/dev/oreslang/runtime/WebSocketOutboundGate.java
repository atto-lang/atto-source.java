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
        private final OresFuture<Void> receipt =
                new OresFuture<>(() -> cancel(this));

        private Write(WebSocketFrameCodec.Frame frame) {
            this.frame = frame;
        }

        WebSocketFrameCodec.Frame frame() { return frame; }
        OresFuture<Void> receipt() { return receipt; }
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
        if (retired || closing) throw new RejectedExecutionException("WebSocket outbound closed");
        if (opcode != WebSocketFrameCodec.OPCODE_TEXT
                && opcode != WebSocketFrameCodec.OPCODE_BINARY) {
            throw new IllegalArgumentException("USER write must be text or binary");
        }
        WebSocketFrameCodec.Frame frame =
                new WebSocketFrameCodec.Frame(true, opcode, payload);
        int size = frame.payloadUnsafe().length;
        if (size > maxFrameBytes || heldUserCount >= userCapacity
                || size > maxUserBytes - heldUserBytes) {
            throw new RejectedExecutionException("WebSocket outbound USER quota exhausted");
        }
        Write write = new Write(frame);
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
            if (heldControlCount >= controlCapacity) {
                throw new RejectedExecutionException("WebSocket CONTROL quota exhausted");
            }
            write = new Write(frame);
            control.addLast(write);
            heldControlCount++;
        }
        for (Write pending : dropped) {
            pending.receipt.failFromRuntime(
                    new RejectedExecutionException("WebSocket closing superseded pending writes"));
        }
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
        if (failure != null || wasRetired) {
            completed.receipt.failFromRuntime(failure != null ? failure
                    : new RejectedExecutionException("WebSocket retired during write"));
        } else {
            completed.receipt.completeFromRuntime(null);
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
        for (Write pending : dropped) {
            pending.receipt.failFromRuntime(
                    new RejectedExecutionException("WebSocket connection retired"));
        }
    }

    private void cancel(Write write) {
        boolean cancelActive = false;
        synchronized (this) {
            if (user.remove(write)) {
                heldUserCount--;
                heldUserBytes -= write.frame.payloadUnsafe().length;
            } else if (control.remove(write)) {
                heldControlCount--;
            } else {
                cancelActive = active == write;
            }
        }
        // A partially written RFC6455 frame cannot safely be abandoned.
        // Closing the physical transport is the only permitted active cancel.
        if (cancelActive) abortActive.run();
    }

    synchronized int heldUserBytes() { return heldUserBytes; }
    synchronized int heldUserCount() { return heldUserCount; }
    synchronized int heldControlCount() { return heldControlCount; }
}
