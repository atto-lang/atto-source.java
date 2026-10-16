package dev.oreslang.runtime;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * Converts a DNS result into the exact numeric socket address authorized by a
 * host-owned network policy. This method never performs another DNS lookup.
 *
 * IPv6 is intentionally denied pending parity across the native backends.
 */
final class VerifiedTcpSocket {
    private VerifiedTcpSocket() { }

    static InetSocketAddress checkedAddress(
            RuntimePermissions permissions, String requestedEndpoint,
            InetAddress resolvedAddress, int port) {
        Objects.requireNonNull(permissions, "permissions");
        if (port < 1 || port > 65535 || !(resolvedAddress instanceof Inet4Address)) {
            throw new SecurityException("TCP destination is not an admissible IPv4 address");
        }
        // This conversion produces a canonical numeric dotted quad, not the
        // possibly untrusted original DNS spelling or a second resolution.
        String selectedEndpoint = resolvedAddress.getHostAddress() + ":" + port;
        if (!permissions.allowsNetworkConnection(requestedEndpoint, selectedEndpoint)) {
            throw new SecurityException("TCP destination denied by selected-IP permission policy");
        }
        // The InetAddress constructor pins the socket to these four bytes.
        return new InetSocketAddress(resolvedAddress, port);
    }
}
