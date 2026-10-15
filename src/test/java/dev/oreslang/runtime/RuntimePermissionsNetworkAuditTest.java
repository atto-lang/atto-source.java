package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial endpoint-policy tests for both compiler arguments and runtime I/O.
 * These do not imply DNS or filesystem sandboxing; they cover scoped policy
 * normalization and allow/deny precedence before a native host operation.
 */
final class RuntimePermissionsNetworkAuditTest {
    @Test
    void wildcardPortDenyOverridesAnUnrestrictedNetworkGrant() {
        RuntimePermissions policy = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.NET, "*")
                .withDenied(RuntimePermissions.Permission.NET, "*.example.com:443");

        assertFalse(policy.allows(RuntimePermissions.Permission.NET, "api.example.com:443"));
        assertFalse(policy.allows(RuntimePermissions.Permission.NET, "nested.api.example.com:0443"));
        assertFalse(policy.allows(RuntimePermissions.Permission.NET, "API.EXAMPLE.COM.:443"));
        assertTrue(policy.allows(RuntimePermissions.Permission.NET, "api.example.com:444"));
        assertTrue(policy.allows(RuntimePermissions.Permission.NET, "example.com:443"));
        assertTrue(policy.allows(RuntimePermissions.Permission.NET, "api.example.net:443"));
    }

    @Test
    void omittedPortCannotBypassPortSpecificDenials() {
        RuntimePermissions policy = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.NET, "*")
                .withDenied(RuntimePermissions.Permission.NET, "*.example.com:443")
                .withDenied(RuntimePermissions.Permission.NET, "[::1]:8443");

        // Without a port, the caller cannot prove it is not requesting 443.
        assertFalse(policy.allows(RuntimePermissions.Permission.NET, "api.example.com"));
        assertFalse(policy.allows(RuntimePermissions.Permission.NET, "API.EXAMPLE.COM."));
        assertFalse(policy.allows(RuntimePermissions.Permission.NET, "[::1]"));
        assertTrue(policy.allows(RuntimePermissions.Permission.NET, "api.example.com:444"));
        assertTrue(policy.allows(RuntimePermissions.Permission.NET, "[::1]:8080"));
        assertTrue(policy.allows(RuntimePermissions.Permission.NET, "other.example.net"));

        // Neither a sibling hostname nor the apex matches the wildcard.
        assertTrue(policy.allows(RuntimePermissions.Permission.NET, "example.com"));
        assertTrue(policy.allows(RuntimePermissions.Permission.NET, "badexample.com"));
    }

    @Test
    void wildcardPortAllowOnlyGrantsMatchingSubdomainAndPort() {
        RuntimePermissions policy = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.NET, "*.example.com:443");

        assertTrue(policy.allows(RuntimePermissions.Permission.NET, "api.example.com:443"));
        assertFalse(policy.allows(RuntimePermissions.Permission.NET, "api.example.com:80"));
        assertFalse(policy.allows(RuntimePermissions.Permission.NET, "example.com:443"));
        assertFalse(policy.allows(RuntimePermissions.Permission.NET, "badexample.com:443"));
    }

    @Test
    void fullyQualifiedDnsSpellingCannotEvadeExactHostDeny() {
        RuntimePermissions policy = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.NET, "*")
                .withDenied(RuntimePermissions.Permission.NET, "api.example.com");

        assertFalse(policy.allows(RuntimePermissions.Permission.NET, "api.example.com:443"));
        assertFalse(policy.allows(RuntimePermissions.Permission.NET, "api.example.com.:443"));
        assertFalse(policy.allows(RuntimePermissions.Permission.NET, "API.Example.Com.:80"));
    }

    @Test
    void bracketedIpv6CanBeScopedByPort() {
        RuntimePermissions policy = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.NET, "[::1]")
                .withDenied(RuntimePermissions.Permission.NET, "[::1]:443");

        assertTrue(policy.allows(RuntimePermissions.Permission.NET, "[::1]:80"));
        assertFalse(policy.allows(RuntimePermissions.Permission.NET, "[::1]:443"));
        assertFalse(policy.allows(RuntimePermissions.Permission.NET, "[::2]:80"));
        assertFalse(policy.allows(RuntimePermissions.Permission.NET, "::1:80"));
    }

    @Test
    void equivalentIpv6AddressSpellingsCannotEvadeExactDeny() {
        RuntimePermissions policy = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.NET, "[::1]")
                .withDenied(RuntimePermissions.Permission.NET,
                        "[0:0:0:0:0:0:0:1]:443");

        assertFalse(policy.allows(RuntimePermissions.Permission.NET, "[::1]:443"));
        assertFalse(policy.allows(RuntimePermissions.Permission.NET,
                "[0000:0000:0000:0000:0000:0000:0000:0001]:00443"));
        assertTrue(policy.allows(RuntimePermissions.Permission.NET, "[::1]:444"));
        assertFalse(policy.allows(RuntimePermissions.Permission.NET, "[::2]:444"));
        assertFalse(policy.allows(RuntimePermissions.Permission.NET, "127.0.0.1:444"));
    }

    @Test
    void malformedIpv6CannotMatchBroadAllowOrBecomeDenyRule() {
        RuntimePermissions broad = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.NET, "*");
        for (String malformed : new String[]{
                "[:::1]:443", "[1:2:3:4:5:6:7:8:9]:443", "[12345::1]:443",
                "[::1%lo0]:443", "[::ffff:127.0.0.1]:443",
                "[::1]:443:444", "[::1]:0", "[::1]:65536"
        }) {
            assertFalse(broad.allows(RuntimePermissions.Permission.NET, malformed),
                    "malformed IPv6 endpoint matched unrestricted grant: " + malformed);
            assertThrows(IllegalArgumentException.class,
                    () -> RuntimePermissions.denyAll()
                            .withDenied(RuntimePermissions.Permission.NET, malformed),
                    "malformed IPv6 deny scope accepted: " + malformed);
        }
    }

    @Test
    void canonicalIpv6DenialsSurviveApplicationArgumentRoundTrip() {
        RuntimePermissions original = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.NET, "[::1]")
                .withDenied(RuntimePermissions.Permission.NET, "[0:0:0:0:0:0:0:1]:443");
        RuntimePermissions restored = RuntimePermissions.fromApplicationArguments(
                original.applicationArguments(),
                IsolatePolicy.developer().withCapabilities(IsolatePolicy.Capability.NETWORK));
        assertFalse(restored.allows(RuntimePermissions.Permission.NET, "[::1]:443"));
        assertTrue(restored.allows(RuntimePermissions.Permission.NET,
                "[0000:0000:0000:0000:0000:0000:0000:0001]:8443"));
    }

    @Test
    void numericIpv4AliasesNeverBypassScopedDenyUsingBroadGrant() {
        RuntimePermissions policy = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.NET, "*")
                .withDenied(RuntimePermissions.Permission.NET, "127.0.0.1");

        assertFalse(policy.allows(RuntimePermissions.Permission.NET, "127.0.0.1:443"));
        for (String alias : new String[]{
                "127.1:443", "127.0.1:443", "2130706433:443",
                "0127.0.0.1:443", "127.00.0.1:443", "127.0.0.256:443",
                "127.0.0:443", "1.2.3.4.5:443"
        }) {
            assertFalse(policy.allows(RuntimePermissions.Permission.NET, alias),
                    "ambiguous IPv4 alias unexpectedly allowed: " + alias);
            assertThrows(IllegalArgumentException.class,
                    () -> RuntimePermissions.denyAll()
                            .withAllowed(RuntimePermissions.Permission.NET, alias));
        }
        assertTrue(policy.allows(RuntimePermissions.Permission.NET, "127.0.0.2:443"));
        assertTrue(policy.allows(RuntimePermissions.Permission.NET, "8.8.8.8:53"));
    }

    @Test
    void malformedEndpointsDoNotUseBroadAllow() {
        RuntimePermissions policy = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.NET, "*");

        for (String target : new String[]{
                "", "api.example.com:0", "api.example.com:65536",
                "api.example.com:443:999", "api.example.com:", "*.example.com:443",
                "http://api.example.com:443", "user@api.example.com:443",
                "api.example.com..:443", "[::1]garbage"
        }) {
            assertFalse(policy.allows(RuntimePermissions.Permission.NET, target),
                    "malformed endpoint unexpectedly allowed: " + target);
        }
    }

    @Test
    void malformedDenyRulesFailClosedDuringPolicyConstruction() {
        for (String malformed : new String[]{
                "", " ", "*.example.com:", "*.example.com:abc", "*.example.com:65536",
                "api.example.com:22:443", "*example.com:443", "http://api.example.com"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> RuntimePermissions.denyAll()
                            .withDenied(RuntimePermissions.Permission.NET, malformed),
                    "malformed deny scope accepted: " + malformed);
        }
    }

    @Test
    void malformedCsvEntriesAreNeverSilentlyDiscarded() {
        for (String malformed : new String[]{
                "", " ", ",", "api.example.com,", ",api.example.com",
                "api.example.com,,other.example.com"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> new RuntimePermissions.Builder()
                            .denyCsv(RuntimePermissions.Permission.NET, malformed),
                    "malformed deny CSV accepted: " + malformed);
            assertThrows(IllegalArgumentException.class,
                    () -> new RuntimePermissions.Builder()
                            .allowCsv(RuntimePermissions.Permission.NET, malformed),
                    "malformed allow CSV accepted: " + malformed);
        }
    }

    @Test
    void serializationPreservesPortDenialsAtRuntimeBoundary() {
        RuntimePermissions original = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.NET, "*")
                .withDenied(RuntimePermissions.Permission.NET, "*.example.com:443");
        IsolatePolicy isolate = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.NETWORK);

        RuntimePermissions restored = RuntimePermissions.fromApplicationArguments(
                original.applicationArguments(), isolate);
        assertFalse(restored.allows(RuntimePermissions.Permission.NET, "api.example.com:443"));
        assertTrue(restored.allows(RuntimePermissions.Permission.NET, "api.example.com:80"));
        assertFalse(restored.allows(RuntimePermissions.Permission.WRITE, "/tmp/out"));
    }
}
