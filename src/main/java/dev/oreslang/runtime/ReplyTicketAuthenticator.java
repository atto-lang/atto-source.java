package dev.oreslang.runtime;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Origin-owned per-request reply authority, derived from one random key per
 * origin incarnation. This ticket is NOT a UUID or a globally meaningful ID.
 *
 * <p>The caller sends the 16-byte ticket with its remote request; the callee
 * must echo it when returning a reply. A peer that can guess a (slot,generation)
 * cannot generate the ticket for a request it never received. The ticket is
 * bound to the responding session, endpoints, incarnations, route epochs and
 * response ABI. A compromised peer can, of course, answer a request for which
 * it was legitimately given a ticket; one-shot settlement limits replay.</p>
 *
 * <p>This protects reply *authority*, not reply payload integrity. The network
 * channel MUST independently authenticate and integrity-protect the complete
 * request/reply frame (for example TLS or a session MAC), and provide the
 * authenticated peer identity from the connection rather than from wire fields.
 * Never serialize this origin secret or expose it to guest code.</p>
 */
final class ReplyTicketAuthenticator {
    static final int TICKET_BYTES = 16;
    private static final byte[] DOMAIN =
            "ORES-REPLY-TICKET-V1".getBytes(StandardCharsets.US_ASCII);
    private final SecretKeySpec key;

    ReplyTicketAuthenticator() {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret); // once per origin, not per request
        key = new SecretKeySpec(secret, "HmacSHA256");
        java.util.Arrays.fill(secret, (byte) 0);
    }

    byte[] issue(long handle, ReplySlotRegistry.Endpoint origin,
            ReplySlotRegistry.AuthenticatedPeer responder, String schemaDigest) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            mac.update(DOMAIN);
            putString(mac, origin.id());
            putLong(mac, origin.incarnation());
            putLong(mac, origin.routeEpoch());
            putLong(mac, handle);
            putString(mac, responder.endpoint().id());
            putLong(mac, responder.endpoint().incarnation());
            putLong(mac, responder.endpoint().routeEpoch());
            putLong(mac, responder.sessionId());
            putString(mac, schemaDigest);
            return java.util.Arrays.copyOf(mac.doFinal(), TICKET_BYTES);
        } catch (java.security.GeneralSecurityException error) {
            throw new IllegalStateException("required HMAC-SHA256 provider unavailable", error);
        }
    }

    boolean valid(byte[] supplied, long handle, ReplySlotRegistry.Endpoint origin,
            ReplySlotRegistry.AuthenticatedPeer responder, String schemaDigest) {
        if (supplied == null || supplied.length != TICKET_BYTES) return false;
        return MessageDigest.isEqual(supplied, issue(handle, origin, responder, schemaDigest));
    }

    private static void putLong(Mac mac, long value) {
        mac.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
    }

    private static void putString(Mac mac, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        mac.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        mac.update(bytes);
    }
}
