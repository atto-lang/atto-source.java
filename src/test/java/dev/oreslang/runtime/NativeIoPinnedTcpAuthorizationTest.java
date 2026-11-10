package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;

/** Tests the trusted, DNS-free destination selection seam; no sockets opened. */
final class NativeIoPinnedTcpAuthorizationTest {
    private static final RuntimePermissions.Permission NET = RuntimePermissions.Permission.NET;

    @Test void hostnameGrantDoesNotAuthorizeSelectedNumericAddress() throws Exception {
        var selected = InetAddress.getByAddress(new byte[] {(byte) 203, 0, 113, 7});
        var hostnameOnly = RuntimePermissions.denyAll()
                .withAllowed(NET, "service.example:443");
        assertThrows(SecurityException.class, () ->
                NativeIo.checkedSelectedTcpAddress(
                        hostnameOnly, "service.example:443", 443, selected));

        var selectedOnly = RuntimePermissions.denyAll()
                .withAllowed(NET, "203.0.113.7:443");
        assertThrows(SecurityException.class, () ->
                NativeIo.checkedSelectedTcpAddress(
                        selectedOnly, "service.example:443", 443, selected));

        var both = hostnameOnly.withAllowed(NET, "203.0.113.7:443");
        InetSocketAddress pinned = NativeIo.checkedSelectedTcpAddress(
                both, "service.example:443", 443, selected);
        assertSame(selected, pinned.getAddress(), "physical connect must use selected InetAddress");
        assertEquals(443, pinned.getPort());

        var explicitDeny = both.withDenied(NET, "203.0.113.7:443");
        assertThrows(SecurityException.class, () ->
                NativeIo.checkedSelectedTcpAddress(
                        explicitDeny, "service.example:443", 443, selected));
    }

    @Test void unspecifiedAndMulticastDestinationsCannotBecomeRemoteTargets() throws Exception {
        var wide = RuntimePermissions.denyAll().withAllowed(NET, "*");
        var unspecified = InetAddress.getByAddress(new byte[] {0, 0, 0, 0});
        var multicast = InetAddress.getByAddress(new byte[] {(byte) 224, 0, 0, 1});
        for (var address : new InetAddress[] { unspecified, multicast }) {
            assertThrows(SecurityException.class, () ->
                    NativeIo.checkedSelectedTcpAddress(wide, "example.com:443", 443, address));
        }
        var valid = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        assertThrows(SecurityException.class, () ->
                NativeIo.checkedSelectedTcpAddress(wide, "example.com:443", 0, valid));
    }

    @Test void ipv6SelectedEndpointUsesUnambiguousBrackets() throws Exception {
        var ipv6 = InetAddress.getByAddress(new byte[] {
                0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1
        });
        var numeric = "[" + ipv6.getHostAddress() + "]:443";
        var permissions = RuntimePermissions.denyAll()
                .withAllowed(NET, "example.com:443")
                .withAllowed(NET, numeric);
        var pinned = NativeIo.checkedSelectedTcpAddress(
                permissions, "example.com:443", 443, ipv6);
        assertSame(ipv6, pinned.getAddress());
        assertEquals(443, pinned.getPort());
    }
}
