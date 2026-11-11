package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Optional;

/**
 * Protocol-only WebSocket connection state.
 *
 * <p>CONTROL actions are returned synchronously and never consume USER queue
 * capacity. A full USER queue therefore cannot suppress ping/pong or close.
 * Socket writes and actor delivery remain transport responsibilities.</p>
 */
public final class WebSocketSessionState {
    public static final int CLOSE_TRY_AGAIN_LATER = 1013;
    public static final int MAX_USER_QUEUE_CAPACITY = 65_536;
    /** A queue-count cap alone permits catastrophic count * message-size memory use. */
    public static final int MAX_QUEUED_USER_BYTES = 16 * 1024 * 1024;

    public enum State {
        OPEN, CLOSING, CLOSED
    }

    public enum ActionKind {
        NONE,
        USER_ENQUEUED,
        SEND_PONG,
        SEND_CLOSE
    }

    public record Action(ActionKind kind, byte[] payload) {
        public Action {
            if (kind == null) throw new IllegalArgumentException("WebSocket action kind is required");
            payload = payload == null ? new byte[0] : Arrays.copyOf(payload, payload.length);
        }

        @Override
        public byte[] payload() {
            return Arrays.copyOf(payload, payload.length);
        }

        public static Action none() {
            return new Action(ActionKind.NONE, new byte[0]);
        }

        public static Action userEnqueued() {
            return new Action(ActionKind.USER_ENQUEUED, new byte[0]);
        }
    }

    private static final byte[] BACKPRESSURE_CLOSE =
            closePayload(CLOSE_TRY_AGAIN_LATER, "user queue full");

    private final int userCapacity;
    private final int userByteCapacity;
    private int queuedUserBytes;
    private final ArrayDeque<WebSocketFrameCodec.Message> userMessages;
    private final WebSocketFrameCodec.MessageAssembler assembler;
    private State state = State.OPEN;
    private boolean closeSent;
    private boolean closeReceived;

    public WebSocketSessionState(int userCapacity, int maxMessageBytes) {
        this(userCapacity, maxMessageBytes, MAX_QUEUED_USER_BYTES);
    }

    /**
     * The message limit and the queued-data limit are independent, but one
     * whole admitted message must fit in the queue. Retained fragments are
     * separately bounded by MessageAssembler.
     */
    public WebSocketSessionState(
            int userCapacity, int maxMessageBytes, int maxQueuedUserBytes) {
        if (maxQueuedUserBytes < 1 || maxQueuedUserBytes > MAX_QUEUED_USER_BYTES
                || maxMessageBytes > maxQueuedUserBytes) {
            throw new IllegalArgumentException(
                    "WebSocket USER byte quota must fit one message and stay within "
                            + MAX_QUEUED_USER_BYTES);
        }
        if (userCapacity < 1 || userCapacity > MAX_USER_QUEUE_CAPACITY) {
            throw new IllegalArgumentException(
                    "WebSocket USER queue capacity must be in 1.."
                            + MAX_USER_QUEUE_CAPACITY);
        }
        this.userCapacity = userCapacity;
        this.userByteCapacity = maxQueuedUserBytes;
        this.userMessages = new ArrayDeque<>(Math.min(userCapacity, 1024));
        this.assembler = new WebSocketFrameCodec.MessageAssembler(maxMessageBytes);
    }

    public synchronized State state() {
        return state;
    }

    public synchronized int queuedUserMessages() {
        return userMessages.size();
    }

    public synchronized int queuedUserBytes() {
        return queuedUserBytes;
    }

    /**
     * An actor ownership handoff must not inherit USER messages or an
     * incomplete fragmented message from the former owner.
     */
    public synchronized boolean hasPendingUserState() {
        return !userMessages.isEmpty() || assembler.fragmentedMessagePending();
    }

    public synchronized Optional<WebSocketFrameCodec.Message> pollUserMessage() {
        WebSocketFrameCodec.Message message = userMessages.pollFirst();
        if (message != null) queuedUserBytes -= message.payloadLength();
        return Optional.ofNullable(message);
    }

    /**
     * Consume one validated frame and return at most one immediate CONTROL
     * action. USER delivery is represented by a bounded internal queue.
     */
    public synchronized Action accept(WebSocketFrameCodec.Frame frame) {
        if (frame == null) throw new IllegalArgumentException("WebSocket frame is required");
        if (state == State.CLOSED) {
            throw new IllegalStateException("WebSocket session is closed");
        }

        return switch (frame.opcode()) {
            case WebSocketFrameCodec.OPCODE_PING ->
                    new Action(ActionKind.SEND_PONG, frame.payload());
            case WebSocketFrameCodec.OPCODE_PONG -> Action.none();
            case WebSocketFrameCodec.OPCODE_CLOSE -> receiveClose(frame);
            default -> receiveData(frame);
        };
    }

    /** Mark the underlying transport physically closed and revoke queued work. */
    public synchronized void transportClosed() {
        state = State.CLOSED;
        assembler.reset();
        userMessages.clear();
        queuedUserBytes = 0;
    }

    private Action receiveClose(WebSocketFrameCodec.Frame frame) {
        closeReceived = true;
        assembler.reset();
        if (!closeSent) {
            closeSent = true;
            state = State.CLOSING;
            return new Action(ActionKind.SEND_CLOSE, frame.payload());
        }
        state = State.CLOSED;
        return Action.none();
    }

    private Action receiveData(WebSocketFrameCodec.Frame frame) {
        if (state != State.OPEN || closeReceived) {
            throw new IllegalStateException(
                    "WebSocket data is not accepted after close begins");
        }

        Optional<WebSocketFrameCodec.Message> completed = assembler.accept(frame);
        if (completed.isEmpty()) return Action.none();

        WebSocketFrameCodec.Message message = completed.orElseThrow();
        // Subtract before comparing to avoid signed integer overflow.
        if (userMessages.size() >= userCapacity
                || message.payloadLength() > userByteCapacity - queuedUserBytes) {
            if (!closeSent) {
                closeSent = true;
                state = State.CLOSING;
                return new Action(ActionKind.SEND_CLOSE, BACKPRESSURE_CLOSE);
            }
            return Action.none();
        }

        userMessages.addLast(message);
        queuedUserBytes += message.payloadLength();
        return Action.userEnqueued();
    }

    static byte[] closePayload(int code, String reason) {
        if (code < 1000 || code > 4999) {
            throw new IllegalArgumentException("invalid WebSocket close code");
        }
        if (reason == null) throw new IllegalArgumentException("WebSocket close reason is required");
        // Java's permissive String.getBytes would replace unpaired surrogates
        // with '?', silently changing the reason on the wire. Bound the actual
        // UTF-8 octets before allocating; a CLOSE control payload is <=125.
        byte[] reasonBytes;
        try {
            reasonBytes = WebSocketFrameCodec.encodeTextUtf8(reason, 123);
        } catch (java.util.concurrent.RejectedExecutionException oversized) {
            throw new IllegalArgumentException(
                    "WebSocket close reason exceeds 123 UTF-8 bytes", oversized);
        }
        byte[] payload = new byte[2 + reasonBytes.length];
        payload[0] = (byte) (code >>> 8);
        payload[1] = (byte) code;
        System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.length);
        // Reuse the codec's protocol-intrinsic close validation so locally
        // generated control frames cannot emit reserved status codes.
        new WebSocketFrameCodec.Frame(
                true, WebSocketFrameCodec.OPCODE_CLOSE, payload);
        return payload;
    }
}
