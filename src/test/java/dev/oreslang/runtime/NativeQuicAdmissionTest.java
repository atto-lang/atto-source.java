package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NativeQuicAdmissionTest {
    private static InetAddress loopback() throws UnknownHostException {
        return InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
    }

    private static OutboundEgressPolicy.ResolvedTarget target(String url) {
        return OutboundEgressPolicy.resolveForConnection(
                url, OutboundEgressPolicy.Purpose.GENERAL_HTTP,
                OutboundEgressPolicy.Policy.loopbackOnly(),
                host -> {
                    assertEquals("origin.example", host);
                    return List.of(loopback());
                });
    }

    @Test
    void http3UsesAuthorizedIpAndOriginalTlsIdentity() throws Exception {
        var authorized = target("https://origin.example:443/test");
        var peer = NativeQuicAdmission.http3Peer(authorized, 0);
        assertEquals(loopback(), peer.pinnedAddress().getAddress());
        assertEquals(443, peer.pinnedAddress().getPort());
        assertEquals("origin.example", peer.canonicalSni());
        assertEquals("h3", peer.alpn());
        assertEquals(authorized.resolutionGeneration(), peer.resolutionGeneration());
    }

    @Test
    void completeDnsSetDenialBlocksQuicEvenWithOneAllowedAddress() {
        assertThrows(SecurityException.class, () ->
                OutboundEgressPolicy.resolveForConnection(
                        "https://origin.example/",
                        OutboundEgressPolicy.Purpose.GENERAL_HTTP,
                        OutboundEgressPolicy.Policy.loopbackOnly(),
                        host -> List.of(loopback(),
                                InetAddress.getByAddress(new byte[] {8, 8, 8, 8}))));
    }

    @Test
    void noHttpDowngradeAndNoFallback() {
        assertThrows(SecurityException.class,
                () -> NativeQuicAdmission.http3Peer(
                        target("http://origin.example/"), 0));
        var unsupported = assertThrows(UnsupportedOperationException.class,
                NativeQuicAdmission::requireGuestQuicProvider);
        assertTrue(unsupported.getMessage().contains("HTTP/2 fallback forbidden"));
    }

    @Test
    void selectionDoesNotWrapOrAcceptNegativeIndices() {
        var authorized = target("https://origin.example/");
        assertThrows(IndexOutOfBoundsException.class,
                () -> NativeQuicAdmission.http3Peer(authorized, -1));
        assertThrows(IndexOutOfBoundsException.class,
                () -> NativeQuicAdmission.http3Peer(authorized, 1));
    }

    @Test
    void pinnedPeerCannotBeConstructedByRuntimeCallers() {
        var constructors = NativeQuicAdmission.PinnedPeer.class.getDeclaredConstructors();
        assertEquals(1, constructors.length);
        assertTrue(Modifier.isPrivate(constructors[0].getModifiers()),
                "future code must not forge a pinned peer using public constructors");
    }

    @Test
    void forgedCanonicalHostnamesFailClosed() throws Exception {
        for (String invalid : List.of("ORIGIN.EXAMPLE", "bad..example",
                "origin.example.", "-bad.example", "bad_.example")) {
            var base = target("https://origin.example/");
            var altered = new OutboundEgressPolicy.ResolvedTarget(
                    base.uri(), base.scheme(), invalid, base.port(),
                    base.authority(), base.addresses(),
                    base.purpose(), base.resolutionGeneration());
            assertThrows(SecurityException.class,
                    () -> NativeQuicAdmission.http3Peer(altered, 0), invalid);
        }
    }
}
