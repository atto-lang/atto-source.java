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
    void hexadecimalIpv4AliasesCannotEvadeExactDenyOrBecomeRules() {
        RuntimePermissions policy = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.NET, "*")
                .withDenied(RuntimePermissions.Permission.NET, "127.0.0.1");
        for (String alias : new String[]{
                "0x7f.0.0.1", "0X7F.0.0.1", "0x7f000001",
                "127.0.0.0x1", "0x7f.1", "0x7f.0.0.1."
        }) {
            for (String endpoint : new String[]{alias, alias + ":443"}) {
                assertFalse(policy.allows(RuntimePermissions.Permission.NET, endpoint),
                        "hex IPv4 alias unexpectedly authorized: " + endpoint);
                assertThrows(IllegalArgumentException.class,
                        () -> RuntimePermissions.denyAll()
                                .withAllowed(RuntimePermissions.Permission.NET, endpoint));
                assertThrows(IllegalArgumentException.class,
                        () -> RuntimePermissions.denyAll()
                                .withDenied(RuntimePermissions.Permission.NET, endpoint));
            }
        }
        assertTrue(policy.allows(RuntimePermissions.Permission.NET, "127.0.0.2:443"));
        assertTrue(policy.allows(RuntimePermissions.Permission.NET, "3com.example:443"));
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
    void portlessNetworkAuthorizationCannotBypassPortScopedDeny() {
        RuntimePermissions policy = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.NET, "*")
                .withDenied(RuntimePermissions.Permission.NET, "*.example.com:443")
                .withDenied(RuntimePermissions.Permission.NET, "[::1]:443");
        assertFalse(policy.allows(RuntimePermissions.Permission.NET, "api.example.com"));
        assertFalse(policy.allows(RuntimePermissions.Permission.NET, "API.EXAMPLE.COM."));
        assertFalse(policy.allows(RuntimePermissions.Permission.NET, "[::1]"));
        assertTrue(policy.allows(RuntimePermissions.Permission.NET, "other.example.net"));
        assertTrue(policy.allows(RuntimePermissions.Permission.NET, "api.example.com:444"));
        assertTrue(policy.allows(RuntimePermissions.Permission.NET, "[::1]:444"));
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

    @Test
    void malformedResourceStringsNeverPassUnrestrictedPermissions() {
        RuntimePermissions policy = RuntimePermissions.denyAll()
                .allowAll(RuntimePermissions.Permission.values());

        String loneHighSurrogate = String.valueOf((char) 0xd800);
        String loneLowSurrogate = String.valueOf((char) 0xdc00);
        for (RuntimePermissions.Permission permission : RuntimePermissions.Permission.values()) {
            for (String malformed : new String[] {
                    null, "", " ", "\t", "broken\u0000scope",
                    "line\nbreak", loneHighSurrogate, loneLowSurrogate
            }) {
                assertFalse(policy.allows(permission, malformed),
                        "malformed resource admitted by " + permission);
                assertThrows(IllegalArgumentException.class,
                        () -> RuntimePermissions.denyAll().withAllowed(permission, malformed),
                        "malformed allow scope accepted by " + permission);
                assertThrows(IllegalArgumentException.class,
                        () -> RuntimePermissions.denyAll().withDenied(permission, malformed),
                        "malformed deny scope accepted by " + permission);
            }
        }
    }

    @Test
    void malformedLaterNetworkCsvScopeCannotLeaveEarlierGrantInBuilder() {
        RuntimePermissions.Builder builder = new RuntimePermissions.Builder();
        assertThrows(IllegalArgumentException.class,
                () -> builder.allowCsv(RuntimePermissions.Permission.NET,
                        "api.example.com:443,api.example.com:0"));
        assertFalse(builder.build().allows(RuntimePermissions.Permission.NET,
                "api.example.com:443"), "failed CSV parse must be transactional");

        assertThrows(IllegalArgumentException.class,
                () -> builder.allowCsv(RuntimePermissions.Permission.NET,
                        "api.example.com:443,not.valid.example:65536"));
        assertFalse(builder.build().allows(RuntimePermissions.Permission.NET,
                "api.example.com:443"));
    }

    @Test
    void malformedLaterFileCsvScopeCannotLeaveEarlierGrantInBuilder() throws Exception {
        String root = java.nio.file.Files.createTempDirectory("ores-permission-csv-")
                .toAbsolutePath().toString();
        RuntimePermissions.Builder builder = new RuntimePermissions.Builder();
        assertThrows(IllegalArgumentException.class,
                () -> builder.allowCsv(RuntimePermissions.Permission.READ,
                        root + ",path\u0000injection"));
        assertFalse(builder.build().allows(RuntimePermissions.Permission.READ,
                root), "invalid CSV must not leave an earlier filesystem grant");
    }

    @Test
    void serializedScopedRulesRequireExplicitPolicyMarker() {
        IsolatePolicy coarse = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.NETWORK);
        // The legacy embedding bridge accepts *no* policy arguments, but a
        // partially transmitted scoped policy must never be accepted.
        assertThrows(IllegalArgumentException.class,
                () -> RuntimePermissions.fromApplicationArguments(new String[]{
                        "--ores-permission-net-allow=*"
                }, coarse));
        assertThrows(IllegalArgumentException.class,
                () -> RuntimePermissions.fromApplicationArguments(new String[]{
                        "--ores-permission-net-deny=api.example.com",
                        "--ores-permission-net-allow=*"
                }, coarse));
        RuntimePermissions marked = RuntimePermissions.fromApplicationArguments(new String[]{
                "--ores-permission-net-allow=api.example.com:443",
                "--ores-permission-explicit=true"
        }, coarse);
        assertTrue(marked.allows(RuntimePermissions.Permission.NET,
                "api.example.com:443"));
        assertFalse(marked.allows(RuntimePermissions.Permission.NET,
                "another.example.com:443"));
    }

    @Test
    void duplicateSerializedAuthorityEntriesFailClosed() {
        IsolatePolicy policy = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.NETWORK);
        assertThrows(IllegalArgumentException.class,
                () -> RuntimePermissions.fromApplicationArguments(
                        new String[] {
                                "--ores-permission-explicit=true",
                                "--ores-permission-explicit=true"
                        }, policy));
        assertThrows(IllegalArgumentException.class,
                () -> RuntimePermissions.fromApplicationArguments(
                        new String[] {
                                "--ores-permission-explicit=true",
                                "--ores-permission-net-allow=api.example.com:443",
                                "--ores-permission-net-allow=api.example.com:443"
                        }, policy));

        RuntimePermissions valid = RuntimePermissions.fromApplicationArguments(
                new String[] {
                        "--ores-permission-explicit=true",
                        "--ores-permission-net-allow=api.example.com:443",
                        "--ores-permission-net-allow=api.example.net:443",
                        "--ores-permission-net-deny=api.example.net:443"
                }, policy);
        assertTrue(valid.allows(RuntimePermissions.Permission.NET,
                "api.example.com:443"));
        assertFalse(valid.allows(RuntimePermissions.Permission.NET,
                "api.example.net:443"));
    }
}
