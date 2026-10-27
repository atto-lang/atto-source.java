package dev.oreslang.runtime;

import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * QUIC/HTTP3 native-host admission boundary, intentionally NOT a QUIC engine.
 * Consume a previously validated complete DNS set from OutboundEgressPolicy;
 * pass only the selected pinned address to a future UDP/QUIC provider while
 * keeping the original hostname for TLS 1.3 SNI and certificate verification.
 *
 * <p>Guest QUIC/HTTP3 is not enabled until provider interop, bounded async
 * lifecycle, native-image and actor-generation tests are verified. Do not
 * silently substitute HTTP/2 over TCP.</p>
 */
final class NativeQuicAdmission {
    static final String HTTP3_ALPN = "h3";

    record PinnedPeer(
            InetSocketAddress pinnedAddress, String canonicalSni,
            String alpn, long resolutionGeneration) {
        PinnedPeer {
            Objects.requireNonNull(pinnedAddress, "pinnedAddress");
            Objects.requireNonNull(canonicalSni, "canonicalSni");
            Objects.requireNonNull(alpn, "alpn");
            if (pinnedAddress.isUnresolved()
                    || pinnedAddress.getAddress().isAnyLocalAddress()
                    || pinnedAddress.getPort() < 1
                    || canonicalSni.isBlank()
                    || resolutionGeneration <= 0) {
                throw new IllegalArgumentException("invalid pinned QUIC peer");
            }
            if (!HTTP3_ALPN.equals(alpn)) {
                throw new IllegalArgumentException("HTTP/3 requires h3 ALPN");
            }
        }
    }

    private NativeQuicAdmission() { }

    /**
     * A DNS authorization result is connection-scoped. Call the policy afresh
     * for each new physical QUIC connection, including retries after closure.
     */
    static PinnedPeer http3Peer(
            OutboundEgressPolicy.ResolvedTarget authorized, int selectedAddress) {
        Objects.requireNonNull(authorized, "authorized");
        if (!"https".equals(authorized.scheme())) {
            throw new SecurityException("HTTP/3 requires https authority");
        }
        if (authorized.host().isBlank()) {
            throw new SecurityException("HTTP/3 requires canonical TLS identity");
        }
        // No hostname-based connect: the address has already passed policy.
        InetSocketAddress pinned = authorized.connectAddress(selectedAddress);
        if (pinned.isUnresolved()) {
            throw new SecurityException("QUIC may only connect to pinned IP");
        }
        return new PinnedPeer(
                pinned, authorized.host(), HTTP3_ALPN,
                authorized.resolutionGeneration());
    }

    /** Not a support claim. A provider must first be wired with native AOT evidence. */
    static void requireGuestQuicProvider() {
        throw new UnsupportedOperationException(
                "Oreslang QUIC guest provider unavailable; HTTP/2 fallback forbidden");
    }
}
