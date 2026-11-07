package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class OutboundEgressPolicyTest {
    private static InetAddress v4(int a, int b, int c, int d) {
        try {
            return InetAddress.getByAddress(new byte[] {
                    (byte) a, (byte) b, (byte) c, (byte) d
            });
        } catch (UnknownHostException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static InetAddress literal(String address) {
        try {
            return InetAddress.getByName(address);
        } catch (UnknownHostException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static OutboundEgressPolicy.Resolver noDns() {
        return host -> {
            throw new AssertionError(
                    "literal target unexpectedly reached DNS resolver: " + host);
        };
    }

    @Test
    void canonicalizationPreservesRawTargetAndNormalizesAuthority() {
        var uri = OutboundEgressPolicy.canonicalUri(
                "HTTPS://Example.COM./a%2Fb?q=x%2Fy");
        assertEquals(
                "https://example.com:443/a%2Fb?q=x%2Fy",
                uri.toASCIIString());

        var http = OutboundEgressPolicy.canonicalUri(
                "http://Example.COM/path");
        assertEquals(
                "http://example.com:80/path",
                http.toASCIIString());
    }

    @Test
    void ambiguousOrCapabilityBearingAuthoritiesFailClosed() {
        for (String target : List.of(
                "ftp://example.com/file",
                "http://user@example.com/",
                "http://example.com/#fragment",
                "http://127.0.0.01/",
                "http://2130706433/",
                "http://example.com:0/",
                "http://example.com:65536/",
                "http://[fe80::1%25eth0]/")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> OutboundEgressPolicy.canonicalUri(target),
                    target);
        }
    }

    @Test
    void forwardProxyDefaultRejectsEveryNonPublicAddressClass() {
        var policy = OutboundEgressPolicy.Policy.forwardProxyDefault();
        for (String target : List.of(
                "http://127.0.0.1/",
                "http://10.0.0.1/",
                "http://100.64.0.1/",
                "http://169.254.169.254/",
                "http://192.0.2.1/",
                "http://198.18.0.1/",
                "http://224.0.0.1/",
                "http://240.0.0.1/",
                "http://[::1]/",
                "http://[fc00::1]/",
                "http://[fe80::1]/",
                "http://[2001:db8::1]/",
                "http://[ff02::1]/")) {
            SecurityException denied = assertThrows(
                    SecurityException.class,
                    () -> OutboundEgressPolicy.resolveForConnection(
                            target,
                            OutboundEgressPolicy.Purpose.FORWARD_PROXY,
                            policy,
                            noDns()),
                    target);
            assertTrue(denied.getMessage().contains("denied"), denied.getMessage());
        }

        var publicTarget = OutboundEgressPolicy.resolveForConnection(
                "http://8.8.8.8/",
                OutboundEgressPolicy.Purpose.FORWARD_PROXY,
                policy,
                noDns());
        assertEquals(
                OutboundEgressPolicy.AddressClass.PUBLIC,
                OutboundEgressPolicy.classify(
                        publicTarget.addresses().getFirst()));
        assertEquals(
                v4(8, 8, 8, 8),
                publicTarget.connectAddress(0).getAddress());
    }

    @Test
    void reverseProxyRequiresExplicitPrivateAndLoopbackOptIn() {
        var privateOnly =
                OutboundEgressPolicy.Policy.reverseProxy(true, false);
        assertDoesNotThrow(() ->
                OutboundEgressPolicy.resolveForConnection(
                        "http://10.20.30.40:8080/",
                        OutboundEgressPolicy.Purpose.REVERSE_PROXY,
                        privateOnly,
                        noDns()));
        assertThrows(
                SecurityException.class,
                () -> OutboundEgressPolicy.resolveForConnection(
                        "http://127.0.0.1:8080/",
                        OutboundEgressPolicy.Purpose.REVERSE_PROXY,
                        privateOnly,
                        noDns()));

        var privateAndLoopback =
                OutboundEgressPolicy.Policy.reverseProxy(true, true);
        assertDoesNotThrow(() ->
                OutboundEgressPolicy.resolveForConnection(
                        "http://127.0.0.1:8080/",
                        OutboundEgressPolicy.Purpose.REVERSE_PROXY,
                        privateAndLoopback,
                        noDns()));

        assertThrows(
                SecurityException.class,
                () -> OutboundEgressPolicy.resolveForConnection(
                        "http://169.254.169.254/",
                        OutboundEgressPolicy.Purpose.REVERSE_PROXY,
                        privateAndLoopback,
                        noDns()),
                "link-local metadata space is never implied by private opt-in");
    }

    @Test
    void mixedDnsAnswersAreRejectedAsAnSsrfRebindingSurface() {
        OutboundEgressPolicy.Resolver mixed = host -> List.of(
                v4(8, 8, 8, 8),
                v4(10, 0, 0, 7));

        SecurityException denied = assertThrows(
                SecurityException.class,
                () -> OutboundEgressPolicy.resolveForConnection(
                        "https://origin.example/resource",
                        OutboundEgressPolicy.Purpose.GENERAL_HTTP,
                        OutboundEgressPolicy.Policy.generalHttpDefault(),
                        mixed));
        assertTrue(denied.getMessage().contains("PRIVATE"), denied.getMessage());
    }

    @Test
    void everyNewConnectionGetsAFreshResolutionGeneration() {
        AtomicInteger calls = new AtomicInteger();
        OutboundEgressPolicy.Resolver changing = host -> switch (
                calls.getAndIncrement()) {
            case 0 -> List.of(v4(8, 8, 8, 8));
            default -> List.of(v4(1, 1, 1, 1));
        };
        var policy = OutboundEgressPolicy.Policy.generalHttpDefault();

        var first = OutboundEgressPolicy.resolveForConnection(
                "https://origin.example/data",
                OutboundEgressPolicy.Purpose.GENERAL_HTTP,
                policy,
                changing);
        var second = OutboundEgressPolicy.resolveForConnection(
                "https://origin.example/data",
                OutboundEgressPolicy.Purpose.GENERAL_HTTP,
                policy,
                changing);

        assertEquals(2, calls.get());
        assertNotEquals(
                first.resolutionGeneration(),
                second.resolutionGeneration());
        assertEquals(v4(8, 8, 8, 8), first.addresses().getFirst());
        assertEquals(v4(1, 1, 1, 1), second.addresses().getFirst());
    }

    @Test
    void duplicateDnsAnswersDoNotInflateTheAdmissionBudget() {
        var limits = new OutboundEgressPolicy.Limits(
                2,
                5,
                Duration.ofSeconds(3),
                Duration.ofSeconds(15),
                Duration.ofSeconds(30),
                Duration.ofSeconds(60),
                32,
                256);
        var policy = new OutboundEgressPolicy.Policy(
                Set.of(OutboundEgressPolicy.AddressClass.PUBLIC),
                false,
                limits);
        OutboundEgressPolicy.Resolver duplicate = host -> List.of(
                v4(8, 8, 8, 8),
                v4(8, 8, 8, 8),
                v4(1, 1, 1, 1));

        var target = OutboundEgressPolicy.resolveForConnection(
                "https://origin.example/",
                OutboundEgressPolicy.Purpose.GENERAL_HTTP,
                policy,
                duplicate);
        assertEquals(2, target.addresses().size());

        OutboundEgressPolicy.Resolver tooMany = host -> List.of(
                v4(8, 8, 8, 8),
                v4(1, 1, 1, 1),
                v4(9, 9, 9, 9));
        assertThrows(
                IllegalArgumentException.class,
                () -> OutboundEgressPolicy.resolveForConnection(
                        "https://origin.example/",
                        OutboundEgressPolicy.Purpose.GENERAL_HTTP,
                        policy,
                        tooMany));
    }

    @Test
    void redirectsAreResolvedAndAuthorizedAgain() {
        OutboundEgressPolicy.Resolver publicDns =
                host -> List.of(v4(8, 8, 8, 8));
        var policy = OutboundEgressPolicy.Policy.forwardProxyDefault();
        var initial = OutboundEgressPolicy.resolveForConnection(
                "https://origin.example/a",
                OutboundEgressPolicy.Purpose.FORWARD_PROXY,
                policy,
                publicDns);

        assertThrows(
                SecurityException.class,
                () -> OutboundEgressPolicy.authorizeRedirect(
                        initial,
                        "http://other.example/b",
                        0,
                        policy,
                        publicDns),
                "HTTPS downgrade must not be implicit");

        OutboundEgressPolicy.Resolver privateRedirect = host ->
                host.equals("private.example")
                        ? List.of(v4(10, 0, 0, 1))
                        : List.of(v4(8, 8, 8, 8));
        assertThrows(
                SecurityException.class,
                () -> OutboundEgressPolicy.authorizeRedirect(
                        initial,
                        "https://private.example/b",
                        0,
                        policy,
                        privateRedirect),
                "redirect target must pass a fresh SSRF authorization");

        assertThrows(
                SecurityException.class,
                () -> OutboundEgressPolicy.authorizeRedirect(
                        initial,
                        "https://other.example/b",
                        policy.limits().maxRedirects(),
                        policy,
                        publicDns));
    }

    @Test
    void literalTargetsNeverInvokeDnsAndStillCarryAConnectionPin() {
        AtomicInteger calls = new AtomicInteger();
        OutboundEgressPolicy.Resolver resolver = host -> {
            calls.incrementAndGet();
            return List.of(v4(1, 1, 1, 1));
        };

        var target = OutboundEgressPolicy.resolveForConnection(
                "http://127.0.0.1:9090/path",
                OutboundEgressPolicy.Purpose.REVERSE_PROXY,
                OutboundEgressPolicy.Policy.loopbackOnly(),
                resolver);

        assertEquals(0, calls.get());
        assertEquals("127.0.0.1:9090", target.authority());
        assertEquals(9090, target.connectAddress(0).getPort());
        assertEquals(
                OutboundEgressPolicy.AddressClass.LOOPBACK,
                OutboundEgressPolicy.classify(
                        target.connectAddress(0).getAddress()));
    }

    @Test
    void addressClassifierSeparatesSpecialUseFamilies() {
        assertEquals(
                OutboundEgressPolicy.AddressClass.PUBLIC,
                OutboundEgressPolicy.classify(v4(8, 8, 8, 8)));
        assertEquals(
                OutboundEgressPolicy.AddressClass.PRIVATE,
                OutboundEgressPolicy.classify(v4(192, 168, 1, 1)));
        assertEquals(
                OutboundEgressPolicy.AddressClass.CARRIER_GRADE_NAT,
                OutboundEgressPolicy.classify(v4(100, 64, 0, 1)));
        assertEquals(
                OutboundEgressPolicy.AddressClass.DOCUMENTATION,
                OutboundEgressPolicy.classify(v4(203, 0, 113, 1)));
        assertEquals(
                OutboundEgressPolicy.AddressClass.PRIVATE,
                OutboundEgressPolicy.classify(literal("fc00::1")));
        assertEquals(
                OutboundEgressPolicy.AddressClass.TRANSITION,
                OutboundEgressPolicy.classify(literal("2001:20::1")));
        assertEquals(
                OutboundEgressPolicy.AddressClass.TRANSITION,
                OutboundEgressPolicy.classify(literal("2002::1")));
        assertEquals(
                OutboundEgressPolicy.AddressClass.DOCUMENTATION,
                OutboundEgressPolicy.classify(literal("3fff::1")));
        assertEquals(
                OutboundEgressPolicy.AddressClass.PUBLIC,
                OutboundEgressPolicy.classify(literal("2606:4700:4700::1111")));
    }

    @Test
    void limitsFailClosedOnImpossibleDeadlineOrPoolShapes() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new OutboundEgressPolicy.Limits(
                        0,
                        5,
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(15),
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(60),
                        32,
                        256));
        assertThrows(
                IllegalArgumentException.class,
                () -> new OutboundEgressPolicy.Limits(
                        16,
                        5,
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(15),
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(5),
                        32,
                        256));
        assertThrows(
                IllegalArgumentException.class,
                () -> new OutboundEgressPolicy.Limits(
                        16,
                        5,
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(15),
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(60),
                        64,
                        32));
    }
}
