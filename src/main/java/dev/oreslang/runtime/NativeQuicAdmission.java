package dev.oreslang.runtime;

import java.net.IDN;
import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.Objects;

/**
 * Host-only HTTP/3 peer admission. This does NOT implement QUIC or export a
 * guest-visible capability. The underlying egress policy must authorize the
 * complete DNS answer set for this physical connection.
 */
final class NativeQuicAdmission {
    static final String HTTP3_ALPN = "h3";

    /**
     * Opaque, immutable pin. Do not replace with a record or public factory:
     * callers must not be able to create an apparent policy authorization by
     * supplying an IP address and a positive generation number.
     */
    static final class PinnedPeer {
        private final InetSocketAddress pinnedAddress;
        private final String canonicalSni;
        private final String alpn;
        private final long resolutionGeneration;

        private PinnedPeer(InetSocketAddress pinnedAddress, String canonicalSni,
                           String alpn, long resolutionGeneration) {
            this.pinnedAddress = Objects.requireNonNull(pinnedAddress);
            this.canonicalSni = Objects.requireNonNull(canonicalSni);
            this.alpn = Objects.requireNonNull(alpn);
            this.resolutionGeneration = resolutionGeneration;
            if (pinnedAddress.isUnresolved()
                    || pinnedAddress.getAddress().isAnyLocalAddress()
                    || pinnedAddress.getAddress().isMulticastAddress()
                    || pinnedAddress.getPort() < 1
                    || resolutionGeneration <= 0
                    || !HTTP3_ALPN.equals(alpn)
                    || !validCanonicalDnsName(canonicalSni)) {
                throw new IllegalArgumentException("invalid or unauthenticated HTTP/3 peer");
            }
        }

        InetSocketAddress pinnedAddress() { return pinnedAddress; }
        String canonicalSni() { return canonicalSni; }
        String alpn() { return alpn; }
        long resolutionGeneration() { return resolutionGeneration; }
    }

    private NativeQuicAdmission() { }

    static PinnedPeer http3Peer(
            OutboundEgressPolicy.ResolvedTarget authorized, int selectedAddress) {
        Objects.requireNonNull(authorized, "authorized");
        if (!"https".equals(authorized.scheme())) {
            throw new SecurityException("HTTP/3 requires https authority");
        }
        if (!validCanonicalDnsName(authorized.host())) {
            throw new SecurityException("HTTP/3 requires canonical DNS TLS identity");
        }
        // Retry attempts are not array-selection authority. Reject negative/OOB
        // choices rather than wrapping via ResolvedTarget.connectAddress().
        Objects.checkIndex(selectedAddress, authorized.addresses().size());
        // Construct exclusively from the complete, previously policy-authorized
        // DNS set. A future QUIC provider must connect to the returned literal IP.
        InetSocketAddress pinned = new InetSocketAddress(
                authorized.addresses().get(selectedAddress), authorized.port());
        return new PinnedPeer(
                pinned, authorized.host(), HTTP3_ALPN,
                authorized.resolutionGeneration());
    }

    private static boolean validCanonicalDnsName(String name) {
        if (name == null || name.isBlank() || name.length() > 253
                || name.startsWith(".") || name.endsWith(".")
                || !name.equals(name.toLowerCase(Locale.ROOT))) {
            return false;
        }
        try {
            return name.equals(IDN.toASCII(name, IDN.USE_STD3_ASCII_RULES))
                    && !name.contains("..");
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    /** Strictly unsupported until QUIC/TLS, AOT, actor loans and wire tests exist. */
    static void requireGuestQuicProvider() {
        throw new UnsupportedOperationException(
                "Oreslang QUIC guest provider unavailable; HTTP/2 fallback forbidden");
    }
}
