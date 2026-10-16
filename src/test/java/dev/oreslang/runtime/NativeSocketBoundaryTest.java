package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.*;

/** Executes the real socket-address selection path used by NativeIo/NativeHttp. */
final class NativeSocketBoundaryTest {
    @Test
    void directLoopbackUsesAlreadyAuthorizedNumericAddress() throws Exception {
        InetAddress ipv4 = InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 });
        try (ServerSocket server = new ServerSocket(0, 1, ipv4)) {
            int port = server.getLocalPort();
            String endpoint = "127.0.0.1:" + port;
            RuntimePermissions granted = RuntimePermissions.denyAll()
                    .withAllowed(RuntimePermissions.Permission.NET, endpoint);
            InetSocketAddress selected = NativeIo.resolveAuthorizedAddress(
                    granted, "127.0.0.1", port);
            assertFalse(selected.isUnresolved());
            assertEquals(ipv4, selected.getAddress());
            assertEquals(port, selected.getPort());
            try (Socket client = new Socket(Proxy.NO_PROXY)) {
                client.connect(selected, 2000);
                assertEquals(ipv4, client.getInetAddress());
                assertEquals(port, client.getPort());
            }
        }
    }

    @Test
    void preResolvedLoopbackDenyBlocksHostnameEvenUnderBroadGrant() throws Exception {
        RuntimePermissions policy = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.NET, "*")
                .withDenied(RuntimePermissions.Permission.NET, "127.0.0.1")
                .withDenied(RuntimePermissions.Permission.NET, "[::1]");
        assertThrows(SecurityException.class, () -> NativeIo.resolveAuthorizedAddress(
                policy, "localhost", 12345));
        assertThrows(SecurityException.class, () -> NativeIo.resolveAuthorizedAddress(
                policy, "127.0.0.1", 12345));
    }

    @Test
    void invalidAndPortlessEndpointsCannotReachResolution() {
        RuntimePermissions broad = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.NET, "*");
        assertThrows(IllegalArgumentException.class, () -> NativeIo.resolveAuthorizedAddress(
                broad, "127.0.0.1", 0));
        assertThrows(SecurityException.class, () -> NativeIo.resolveAuthorizedAddress(
                broad, "127.1", 443));
        assertThrows(SecurityException.class, () -> NativeIo.resolveAuthorizedAddress(
                broad, "[::1]", 443));
    }

    @Test
    void httpMustNotPerformUnpinnedHostnameResolution() {
        RuntimePermissions policy = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.NET, "*")
                .withDenied(RuntimePermissions.Permission.NET, "127.0.0.1");
        assertThrows(SecurityException.class, () -> NativeIo.requirePinnedHttpLiteral(
                policy, "api.example.com:443"));
        assertThrows(SecurityException.class, () -> NativeIo.requirePinnedHttpLiteral(
                policy, "127.0.0.1:443"));
        assertDoesNotThrow(() -> NativeIo.requirePinnedHttpLiteral(
                policy, "203.0.113.7:443"));
        assertThrows(SecurityException.class, () -> NativeIo.requirePinnedHttpLiteral(
                policy, "[::1]:443"));
    }
}
