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
    void dnsAuthorizationCannotBeConstructedByUntrustedRuntimeCallers() {
        var constructors =
                OutboundEgressPolicy.ResolvedTarget.class.getDeclaredConstructors();
        assertEquals(1, constructors.length);
        assertTrue(Modifier.isPrivate(constructors[0].getModifiers()),
                "package-level code must not mint DNS-set egress authorization");
        assertFalse(OutboundEgressPolicy.ResolvedTarget.class.isRecord(),
                "records have package-visible canonical constructors");
    }

    @Test
    void issuedDnsAuthorizationIsImmutableAndFreshPerConnection() throws Exception {
        var first = target("https://origin.example/");
        var second = target("https://origin.example/");
        assertNotSame(first, second);
        assertNotEquals(first.resolutionGeneration(), second.resolutionGeneration());
        assertThrows(UnsupportedOperationException.class,
                () -> first.addresses().add(loopback()));
        assertEquals(first.addresses(), second.addresses());
        assertEquals(first.host(), NativeQuicAdmission.http3Peer(first, 0).canonicalSni());
    }

}
