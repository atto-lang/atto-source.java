package dev.oreslang.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;

/**
 * Bounded reply-frame admission at an already-authenticated IPC session.
 *
 * <p>Transport/TLS owns peer authentication, channel integrity, stream framing,
 * flow control and reconnection. Instantiate one gateway from the VERIFIED
 * connection/session identity, never from sender-supplied frame metadata.
 * The schema-specific decoder must be registered by the runtime from its
 * trusted protocol ABI: never use Java deserialization or an arbitrary guest
 * callback here. This adapter performs no guest execution or inline resume.</p>
 *
 * <p>Wire v1: magic:u32, version:u8, slot+generation:u64,
 * request-ticket:16 bytes, body-size:u32, body:bytes (network order).
 * There are no pointers, UUIDs, guest function handles or wire-selected
 * Java classes. An independent authenticated and integrity-protected channel
 * is still REQUIRED, since the ticket proves reply authority, not the
 * integrity or privacy of the body.</p>
 */
final class ReplyTransportGateway<T> implements AutoCloseable {
    private static final int MAGIC = 0x4f525250; // ORRP
    private static final int VERSION = 1;
    private static final int HEADER = Integer.BYTES + 1 + Long.BYTES
            + ReplyTicketAuthenticator.TICKET_BYTES + Integer.BYTES;
    private static final int HARD_MAX_BYTES = 2 * 1024 * 1024;

    private final ReplySlotRegistry slots;
    private final ReplySlotRegistry.AuthenticatedPeer verifiedPeer;
    private final String schemaDigest;
    private final Function<byte[], T> decoder;
    private final int maxFrameBytes;
    private boolean closed;

    ReplyTransportGateway(
            ReplySlotRegistry slots,
            ReplySlotRegistry.AuthenticatedPeer verifiedPeer,
            String schemaDigest,
            Function<byte[], T> trustedDecoder,
            int maxFrameBytes) {
        this.slots = Objects.requireNonNull(slots, "slots");
        this.verifiedPeer = Objects.requireNonNull(verifiedPeer, "verifiedPeer");
        if (schemaDigest == null || schemaDigest.isBlank() || schemaDigest.length() > 128) {
            throw new IllegalArgumentException("bounded response protocol ABI digest required");
        }
        this.schemaDigest = schemaDigest;
        this.decoder = Objects.requireNonNull(trustedDecoder, "trustedDecoder");
        if (maxFrameBytes < HEADER || maxFrameBytes > HARD_MAX_BYTES) {
            throw new IllegalArgumentException("reply frame size limit is invalid");
        }
        this.maxFrameBytes = maxFrameBytes;
    }

    /**
     * Called ONLY after the authenticated connection has delivered a complete,
     * integrity-checked frame. Malformed/unauthorized frames are dropped; they
     * must not consume or fail the genuine caller's pending reply capability.
     */
    synchronized boolean acceptAuthenticatedFrame(byte[] frame) {
        if (closed) return false;
        if (frame == null || frame.length < HEADER || frame.length > maxFrameBytes) {
            return false;
        }
        ByteBuffer buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        if (buffer.getInt() != MAGIC || Byte.toUnsignedInt(buffer.get()) != VERSION) {
            return false;
        }
        long handle = buffer.getLong();
        byte[] ticket = new byte[ReplyTicketAuthenticator.TICKET_BYTES];
        buffer.get(ticket);
        int length = buffer.getInt();
        if (length < 0 || length != buffer.remaining()) return false;

        // Verify request authority BEFORE handing attacker-controlled payload
        // to a decoder. The registry's final completeRemote check is the
        // authoritative once-only settlement and closes any intervening race.
        if (!slots.isAuthorizedRemoteTicket(
                slots.origin(), handle, verifiedPeer, schemaDigest, ticket)) {
            return false;
        }
        byte[] bytes = Arrays.copyOfRange(frame, HEADER, frame.length);
        final T decoded;
        try {
            decoded = decoder.apply(bytes);
        } catch (RuntimeException rejectedPayload) {
            return false;
        }
        return slots.completeRemote(
                slots.origin(), handle, verifiedPeer, schemaDigest, ticket, decoded);
    }

    /**
     * Runtime-owned authenticated session teardown. Exact peer/session binding
     * ensures a stale connection cannot settle or revoke a new session.
     * Closing does not cancel remote work already accepted for execution.
     */
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        slots.terminateResponder(verifiedPeer,
                new IllegalStateException("authenticated actor reply session disconnected"));
    }

    /** Trusted transport serializer helper. The caller MUST authenticate the frame. */
    static byte[] encodeFrame(long handle, byte[] ticket, byte[] payload, int limit) {
        Objects.requireNonNull(ticket, "ticket");
        Objects.requireNonNull(payload, "payload");
        if (ticket.length != ReplyTicketAuthenticator.TICKET_BYTES || limit < HEADER
                || limit > HARD_MAX_BYTES || payload.length > limit - HEADER) {
            throw new IllegalArgumentException("invalid or oversized reply frame");
        }
        return ByteBuffer.allocate(HEADER + payload.length)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(MAGIC).put((byte) VERSION).putLong(handle).put(ticket)
                .putInt(payload.length).put(payload).array();
    }
}
