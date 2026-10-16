package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host-network admission at the selected address, not merely hostname-policy
 * evaluation. Uses only OS loopback; no external DNS/service dependency.
 */
final class NativeIoPinnedNetworkTest {
    @Test
    void deniesUnscopedAndDeniedSelectedAddressesBeforeConnect() throws Exception {
        RuntimePermissions empty = RuntimePermissions.denyAll();
        assertThrows(SecurityException.class,
                () -> NativeIo.resolveAuthorizedAddress(empty, "127.0.0.1", 443));

        RuntimePermissions denied = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.NET, "*")
                .withDenied(RuntimePermissions.Permission.NET, "127.0.0.1");
        assertThrows(SecurityException.class,
                () -> NativeIo.resolveAuthorizedAddress(denied, "127.0.0.1", 443));
        assertThrows(SecurityException.class,
                () -> NativeIo.resolveAuthorizedAddress(denied, "localhost", 443));

        RuntimePermissions denyPort = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.NET, "*")
                .withDenied(RuntimePermissions.Permission.NET, "127.0.0.1:443");
        assertThrows(SecurityException.class,
                () -> NativeIo.resolveAuthorizedAddress(denyPort, "127.0.0.1", 443));

        RuntimePermissions broad = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.NET, "*");
        for (String alias : new String[] {"127.1", "0x7f.0.0.1", "127.0.0.01"}) {
            assertThrows(SecurityException.class,
                    () -> NativeIo.resolveAuthorizedAddress(broad, alias, 443),
                    "numeric alias was not denied before DNS");
        }
    }

    @Test
    void usesExactlyTheCheckedNumericAddressOnARealSocket() throws Exception {
        InetAddress loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        try (ServerSocket server = new ServerSocket(0, 1, loopback)) {
            int port = server.getLocalPort();
            RuntimePermissions allowed = RuntimePermissions.denyAll()
                    .withAllowed(RuntimePermissions.Permission.NET, "127.0.0.1:" + port);
            InetSocketAddress selected =
                    NativeIo.resolveAuthorizedAddress(allowed, "127.0.0.1", port);
            assertTrue(selected.getAddress() instanceof Inet4Address);
            assertFalse(selected.isUnresolved());
            assertEquals("127.0.0.1", selected.getAddress().getHostAddress());
            assertEquals(port, selected.getPort());

            // Connecting an InetSocketAddress created from InetAddress does
            // not trigger a second hostname resolution.
            try (Socket client = new Socket()) {
                client.connect(selected, 2000);
                try (Socket accepted = server.accept()) {
                    assertTrue(client.isConnected());
                    assertEquals("127.0.0.1", accepted.getInetAddress().getHostAddress());
                }
            }
        }
    }

    @Test
    void builtInHttpRefusesUnpinnedHostnamesEvenWhenAllowed() {
        RuntimePermissions broad = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.NET, "*");
        assertThrows(SecurityException.class,
                () -> NativeIo.requirePinnedHttpLiteral(broad, "localhost:443"));
        assertThrows(SecurityException.class,
                () -> NativeIo.requirePinnedHttpLiteral(broad, "api.example.com:443"));
        assertThrows(SecurityException.class,
                () -> NativeIo.requirePinnedHttpLiteral(broad, "[::1]:443"));
        assertDoesNotThrow(() ->
                NativeIo.requirePinnedHttpLiteral(broad, "127.0.0.1:443"));

        RuntimePermissions denied = broad.withDenied(
                RuntimePermissions.Permission.NET, "127.0.0.1");
        assertThrows(SecurityException.class,
                () -> NativeIo.requirePinnedHttpLiteral(denied, "127.0.0.1:443"));
    }
}
