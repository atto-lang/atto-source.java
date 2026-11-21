package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class RuntimePermissionsIdentityHardeningTest {
    @Test
    void trailingWhitespaceIsPartOfEnvironmentIdentity() {
        var policy = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.ENV, "API_KEY");
        assertTrue(policy.allows(RuntimePermissions.Permission.ENV, "API_KEY"));
        assertFalse(policy.allows(RuntimePermissions.Permission.ENV, "API_KEY "));
        assertFalse(policy.allows(RuntimePermissions.Permission.ENV, " API_KEY"));
        assertFalse(policy.allows(RuntimePermissions.Permission.ENV, "api_key"));
    }

    @Test
    void surroundingWhitespaceCannotTurnLiteralAsteriskIntoBroadGrant() {
        var policy = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.ENV, "* ")
                .withAllowed(RuntimePermissions.Permission.RUN, " *");
        assertFalse(policy.allows(RuntimePermissions.Permission.ENV, "SECRET"));
        assertFalse(policy.allows(RuntimePermissions.Permission.RUN, "sh"));
        assertTrue(policy.allows(RuntimePermissions.Permission.ENV, "* "));
        assertTrue(policy.allows(RuntimePermissions.Permission.RUN, " *"));
    }

    @Test
    void exactPathIdentityRetainsRealFilenameSpaces() throws Exception {
        Path directory = Files.createTempDirectory("ores-permission-identities-");
        Path normal = directory.resolve("data");
        Path withSpace = directory.resolve("data ");
        Files.writeString(normal, "ordinary");
        Files.writeString(withSpace, "different");
        var policy = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.READ, normal.toString());
        assertTrue(policy.allows(RuntimePermissions.Permission.READ, normal.toString()));
        assertFalse(policy.allows(RuntimePermissions.Permission.READ, withSpace.toString()));
        var converse = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.READ, withSpace.toString());
        assertFalse(converse.allows(RuntimePermissions.Permission.READ, normal.toString()));
        assertTrue(converse.allows(RuntimePermissions.Permission.READ, withSpace.toString()));
    }

    @Test
    void malformedNetworkWhitespaceCannotUseWildcardAuthority() {
        var policy = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.NET, "*");
        for (String malformed : new String[]{
                " api.example.com:443", "api.example.com:443 ", "\tapi.example.com:443",
                "api.example.com:443\t"
        }) {
            assertFalse(policy.allows(RuntimePermissions.Permission.NET, malformed),
                    "malformed network target matched wildcard");
            assertThrows(IllegalArgumentException.class,
                    () -> RuntimePermissions.denyAll()
                            .withDenied(RuntimePermissions.Permission.NET, malformed),
                    "malformed denial scope was accepted");
            assertThrows(IllegalArgumentException.class,
                    () -> RuntimePermissions.denyAll()
                            .withAllowed(RuntimePermissions.Permission.NET, malformed),
                    "malformed grant scope was accepted");
        }
    }

    @Test
    void hexIpv4AliasesCannotEvadeLocalhostDenials() {
        var policy = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.NET, "*")
                .withDenied(RuntimePermissions.Permission.NET, "127.0.0.1");
        for (String alias : new String[] {
                "0x7f.0.0.1:443", "0x7f000001:443",
                "127.0x0.0.1:443", "0x7f.0x0.0x0.0x1:443"
        }) {
            assertFalse(policy.allows(RuntimePermissions.Permission.NET, alias),
                    "hexadecimal IPv4 alias matched wildcard network scope: " + alias);
            assertThrows(IllegalArgumentException.class,
                    () -> RuntimePermissions.denyAll()
                            .withAllowed(RuntimePermissions.Permission.NET, alias));
        }
    }

    @Test
    void serializedLiteralEnvironmentNamesRemainExact() {
        var original = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.ENV, "API_KEY ")
                .withDenied(RuntimePermissions.Permission.ENV, "API_KEY");
        var policy = RuntimePermissions.fromApplicationArguments(
                original.applicationArguments(),
                IsolatePolicy.developer().withCapabilities(IsolatePolicy.Capability.ENVIRONMENT));
        assertTrue(policy.allows(RuntimePermissions.Permission.ENV, "API_KEY "));
        assertFalse(policy.allows(RuntimePermissions.Permission.ENV, "API_KEY"));
    }
}
