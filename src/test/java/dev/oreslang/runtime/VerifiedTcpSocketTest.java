package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.*;

final class VerifiedTcpSocketTest {
    private static InetAddress numeric(int a, int b, int c, int d) throws Exception {
        return InetAddress.getByAddress(new byte[] {
                (byte) a, (byte) b, (byte) c, (byte) d
        });
    }

    @Test
    void acceptedDestinationPinsActualResolvedBytesAndPort() throws Exception {
        RuntimePermissions policy = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.NET, "api.example.com:443");
        InetAddress selected = numeric(203, 0, 113, 7);
        InetSocketAddress pinned = VerifiedTcpSocket.checkedAddress(
                policy, "api.example.com:443", selected, 443);
        assertSame(selected, pinned.getAddress(), "must not replace checked DNS result");
        assertEquals(443, pinned.getPort());
        assertFalse(pinned.isUnresolved());
        assertInstanceOf(Inet4Address.class, pinned.getAddress());
    }

    @Test
    void dnsRebindingToDeniedAddressFailsEvenForAllowedHostname() throws Exception {
        RuntimePermissions policy = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.NET, "api.example.com:443")
                .withDenied(RuntimePermissions.Permission.NET, "127.0.0.1");
        assertThrows(SecurityException.class, () -> VerifiedTcpSocket.checkedAddress(
                policy, "api.example.com:443", numeric(127, 0, 0, 1), 443));
        assertDoesNotThrow(() -> VerifiedTcpSocket.checkedAddress(
                policy, "api.example.com:443", numeric(203, 0, 113, 7), 443));
    }

    @Test
    void deniedHostOrMismatchedPortCannotBeRescuedBySafeIp() throws Exception {
        RuntimePermissions policy = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.NET, "api.example.com:443");
        InetAddress ip = numeric(203, 0, 113, 7);
        assertThrows(SecurityException.class, () -> VerifiedTcpSocket.checkedAddress(
                policy, "other.example.com:443", ip, 443));
        assertThrows(SecurityException.class, () -> VerifiedTcpSocket.checkedAddress(
                policy, "api.example.com:443", ip, 80));
        assertThrows(SecurityException.class, () -> VerifiedTcpSocket.checkedAddress(
                policy, "api.example.com:443", ip, 0));
    }

    @Test
    void numericGrantCannotConnectToDifferentResolvedIpEvenWithWildcard() throws Exception {
        RuntimePermissions policy = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.NET, "*");
        assertThrows(SecurityException.class, () -> VerifiedTcpSocket.checkedAddress(
                policy, "203.0.113.7:443", numeric(198, 51, 100, 8), 443));
        assertDoesNotThrow(() -> VerifiedTcpSocket.checkedAddress(
                policy, "203.0.113.7:443", numeric(203, 0, 113, 7), 443));
    }

    @Test
    void ipv6AndMappedAddressFailClosed() throws Exception {
        RuntimePermissions policy = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.NET, "*");
        InetAddress v6 = InetAddress.getByAddress(new byte[] {
                0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1
        });
        assertInstanceOf(Inet6Address.class, v6);
        assertThrows(SecurityException.class, () -> VerifiedTcpSocket.checkedAddress(
                policy, "[::1]:443", v6, 443));
        InetAddress mapped = InetAddress.getByAddress(new byte[] {
                0,0,0,0,0,0,0,0,0,0,(byte)255,(byte)255,127,0,0,1
        });
        // The JDK sometimes canonicalizes mapped addresses to IPv4. Even
        // then, policy must reject an IPv6 requested endpoint.
        assertThrows(SecurityException.class, () -> VerifiedTcpSocket.checkedAddress(
                policy, "[::ffff:127.0.0.1]:443", mapped, 443));
    }

    @Test
    void unspecifiedIpv4RemoteCannotAliasToDeniedLoopback() throws Exception {
        RuntimePermissions policy = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.NET, "*")
                .withDenied(RuntimePermissions.Permission.NET, "127.0.0.1");
        // Linux may route an attempted TCP connection to 0.0.0.0 through
        // loopback, so that value is not an admissible selected peer identity.
        assertFalse(policy.allowsNetworkConnection(
                "api.example.com:443", "0.0.0.0:443"));
        assertFalse(policy.allowsNetworkConnection(
                "0.0.0.0:443", "0.0.0.0:443"));
        assertThrows(SecurityException.class, () -> VerifiedTcpSocket.checkedAddress(
                policy, "api.example.com:443", numeric(0, 0, 0, 0), 443));
        assertThrows(SecurityException.class, () -> VerifiedTcpSocket.checkedAddress(
                policy, "0.0.0.0:443", numeric(0, 0, 0, 0), 443));
        assertDoesNotThrow(() -> VerifiedTcpSocket.checkedAddress(
                policy, "api.example.com:443", numeric(203, 0, 113, 7), 443));
    }

    @Test
    void defaultDenyBlocksNumericallyPinnedDestination() throws Exception {
        assertThrows(SecurityException.class, () -> VerifiedTcpSocket.checkedAddress(
                RuntimePermissions.denyAll(), "127.0.0.1:8080",
                numeric(127, 0, 0, 1), 8080));
    }
}
