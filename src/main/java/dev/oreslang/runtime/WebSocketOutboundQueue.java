package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.Optional;

/**
 * Bounded, host-only outbound WebSocket admission state. Socket I/O, actor
 * ownership and deadlines are deliberately NOT implemented here.
 *
 * <p>Only one write lease can be outstanding. A lease keeps USER credits
 * reserved until the transport acknowledges completion, not merely until a
 * writer takes the frame from the queue. CONTROL frames overtake queued USER
 * frames; a close request discards queued work but cannot interrupt an active
 * socket write. The transport must close that socket on its own deadline.</p>
 */
final class WebSocketOutboundQueue {
    static final int MAX_USER_FRAMES = 1024;
    static final int MAX_USER_BYTES = 16 * 1024 * 1024;
    static final int MAX_CONTROL_FRAMES = 4;

    enum Admission {
        ACCEPTED, CAPACITY, TERMINAL
    }

    static final class Lease {
        private final WebSocketFrameCodec.Frame frame;
        private Lease(WebSocketFrameCodec.Frame frame) {
            this.frame = frame;
        }
        WebSocketFrameCodec.Frame frame() {
            return frame;
        }
    }

    private final int userFrameLimit;
    private final int userByteLimit;
    private final int controlFrameLimit;
    private final ArrayDeque<WebSocketFrameCodec.Frame> user = new ArrayDeque<>();
    private final ArrayDeque<WebSocketFrameCodec.Frame> control = new ArrayDeque<>();
    private Lease active;
    private int reservedUserFrames;
    private int reservedUserBytes;
    private boolean closing;
    private boolean closed;
    private boolean closeFlushed;

    WebSocketOutboundQueue(int userFrameLimit, int userByteLimit, int controlFrameLimit) {
        if (userFrameLimit < 1 || userFrameLimit > MAX_USER_FRAMES
                || userByteLimit < 1 || userByteLimit > MAX_USER_BYTES
                || controlFrameLimit < 1 || controlFrameLimit > MAX_CONTROL_FRAMES) {
            throw new IllegalArgumentException("invalid bounded WebSocket outbound limits");
        }
        this.userFrameLimit = userFrameLimit;
        this.userByteLimit = userByteLimit;
        this.controlFrameLimit = controlFrameLimit;
    }

    synchronized Admission offerUser(WebSocketFrameCodec.Frame frame) {
        requireUser(frame);
        if (closed || closing) return Admission.TERMINAL;
        int bytes = frame.payloadUnsafe().length;
        // Both count and byte quotas include the in-flight USER lease.
        if (reservedUserFrames >= userFrameLimit
                || bytes > userByteLimit - reservedUserBytes) {
            return Admission.CAPACITY;
        }
        user.addLast(frame);
        reservedUserFrames++;
        reservedUserBytes += bytes;
        return Admission.ACCEPTED;
    }

    synchronized Admission offerControl(WebSocketFrameCodec.Frame frame) {
        requireControl(frame);
        if (closed || closing) return Admission.TERMINAL;
        int activeControls = active != null && active.frame.control() ? 1 : 0;
        if (control.size() + activeControls >= controlFrameLimit) {
            return Admission.CAPACITY;
        }
        control.addLast(frame);
        return Admission.ACCEPTED;
    }

    /**
     * Always reserve the close path ahead of queued frames, even if ordinary
     * CONTROL capacity is full. An active write may still require physical
     * socket cancellation by a deadline owner.
     */
    synchronized Admission offerClose(WebSocketFrameCodec.Frame closeFrame) {
        if (closeFrame == null || closeFrame.opcode() != WebSocketFrameCodec.OPCODE_CLOSE) {
            throw new IllegalArgumentException("close must be a validated CLOSE frame");
        }
        if (closed || closing) return Admission.TERMINAL;
        closing = true;
        control.clear();
        // Retire queued USER credits. Preserve the active lease credits.
        user.clear();
        if (active != null && !active.frame.control()) {
            reservedUserFrames = 1;
            reservedUserBytes = active.frame.payloadUnsafe().length;
        } else {
            reservedUserFrames = 0;
            reservedUserBytes = 0;
        }
        control.addFirst(closeFrame);
        return Admission.ACCEPTED;
    }

    synchronized Optional<Lease> acquireWrite() {
        if (closed || active != null) return Optional.empty();
        WebSocketFrameCodec.Frame next = control.pollFirst();
        if (next == null) next = user.pollFirst();
        if (next == null) return Optional.empty();
        active = new Lease(next);
        return Optional.of(active);
    }

    /**
     * Release a write lease once. A failed write retires all work and requires
     * the transport to physically close its socket. A stale/duplicate lease
     * cannot mutate credits after completion or retirement.
     */
    synchronized boolean completeWrite(Lease lease, boolean succeeded) {
        if (closed || lease == null || lease != active) return false;
        WebSocketFrameCodec.Frame frame = active.frame;
        active = null;
        if (!succeeded) {
            transportClosed();
            return true;
        }
        if (!frame.control()) {
            reservedUserFrames--;
            reservedUserBytes -= frame.payloadUnsafe().length;
        } else if (frame.opcode() == WebSocketFrameCodec.OPCODE_CLOSE) {
            closeFlushed = true;
        }
        return true;
    }

    synchronized void transportClosed() {
        closed = true;
        closing = true;
        user.clear();
        control.clear();
        active = null;
        reservedUserFrames = 0;
        reservedUserBytes = 0;
    }

    synchronized int reservedUserFrames() { return reservedUserFrames; }
    synchronized int reservedUserBytes() { return reservedUserBytes; }
    synchronized int queuedControlFrames() { return control.size(); }
    synchronized boolean closing() { return closing; }
    synchronized boolean closed() { return closed; }
    synchronized boolean closeFlushed() { return closeFlushed; }

    private static void requireUser(WebSocketFrameCodec.Frame frame) {
        if (frame == null || frame.control()
                || (frame.opcode() != WebSocketFrameCodec.OPCODE_TEXT
                    && frame.opcode() != WebSocketFrameCodec.OPCODE_BINARY
                    && frame.opcode() != WebSocketFrameCodec.OPCODE_CONTINUATION)) {
            throw new IllegalArgumentException("expected validated USER WebSocket frame");
        }
    }

    private static void requireControl(WebSocketFrameCodec.Frame frame) {
        if (frame == null || (frame.opcode() != WebSocketFrameCodec.OPCODE_PING
                && frame.opcode() != WebSocketFrameCodec.OPCODE_PONG)) {
            throw new IllegalArgumentException("only PING/PONG accepted; CLOSE uses offerClose");
        }
    }
}
