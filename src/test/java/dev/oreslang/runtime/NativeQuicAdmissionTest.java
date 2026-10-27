package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NativeQuicAdmissionTest {
    private static InetAddress loopback() throws UnknownHostException {
        return InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 });
    }

    @Test
    void http3UsesAuthorizedIpWithoutHostnameReresolution() throws Exception {
        var target = OutboundEgressPolicy.resolveForConnection(
                "https://origin.example:443/test",
                OutboundEgressPolicy.Purpose.GENERAL_HTTP,
                OutboundEgressPolicy.Policy.loopbackOnly(),
                host -> {
                    assertEquals("origin.example", host);
                    return List.of(loopback());
                });
        var peer = NativeQuicAdmission.http3Peer(target, 0);
        assertEquals(loopback(), peer.pinnedAddress().getAddress());
        assertEquals(443, peer.pinnedAddress().getPort());
        assertEquals("origin.example", peer.canonicalSni());
        assertEquals("h3", peer.alpn());
        assertEquals(target.resolutionGeneration(), peer.resolutionGeneration());
    }

    @Test
    void completeDnsSetDenialBlocksQuicEvenWithOneAllowedAddress() throws Exception {
        assertThrows(SecurityException.class, () ->
                OutboundEgressPolicy.resolveForConnection(
                        "https://origin.example/",
                        OutboundEgressPolicy.Purpose.GENERAL_HTTP,
                        OutboundEgressPolicy.Policy.loopbackOnly(),
                        host -> List.of(
                                loopback(),
                                InetAddress.getByAddress(new byte[] { 8, 8, 8, 8 }))));
    }

    @Test
    void httpNotPromotedToHttp3AndUnimplementedProviderNeverFallsBack() throws Exception {
        var target = OutboundEgressPolicy.resolveForConnection(
                "http://origin.example/",
                OutboundEgressPolicy.Purpose.GENERAL_HTTP,
                OutboundEgressPolicy.Policy.loopbackOnly(),
                host -> List.of(loopback()));
        assertThrows(SecurityException.class,
                () -> NativeQuicAdmission.http3Peer(target, 0));
        var unsupported = assertThrows(UnsupportedOperationException.class,
                NativeQuicAdmission::requireGuestQuicProvider);
        assertTrue(unsupported.getMessage().contains("HTTP/2 fallback forbidden"));
    }

    @Test
    void corruptOrUnpinnedDescriptorsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                new NativeQuicAdmission.PinnedPeer(
                        new java.net.InetSocketAddress("0.0.0.0", 443),
                        "origin.example", "h3", 1L));
        assertThrows(IllegalArgumentException.class, () ->
                new NativeQuicAdmission.PinnedPeer(
                        new java.net.InetSocketAddress("127.0.0.1", 443),
                        "origin.example", "h2", 1L));
    }
}
