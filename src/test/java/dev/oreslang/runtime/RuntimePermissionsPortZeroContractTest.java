package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Ensure the pinned 44-case v1 endpoint oracle is not confused with ephemeral bind semantics. */
final class RuntimePermissionsPortZeroContractTest {
    @Test
    void v1ExplicitNetWildcardNeverGrantsOutboundPortZero() {
        var policy = RuntimePermissions.denyAll()
                .allowAll(RuntimePermissions.Permission.NET);
        assertFalse(policy.allows(RuntimePermissions.Permission.NET, "api.example.com:0"));
        assertFalse(policy.allows(RuntimePermissions.Permission.NET, "[::1]:0"));
        assertTrue(policy.allows(RuntimePermissions.Permission.NET, "api.example.com:443"));
        assertThrows(SecurityException.class, () ->
                policy.require(RuntimePermissions.Permission.NET,
                        "api.example.com:0", "network.connect"));
    }

    @Test
    void explicitListenerAllowsPortZeroOnlyAtBindAuthorization() {
        var policy = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.NET, "127.0.0.1:0");
        assertTrue(policy.allowsNetworkListen("127.0.0.1:0"));
        assertFalse(policy.allowsNetworkListen("127.0.0.1:443"));
        assertFalse(policy.allows(RuntimePermissions.Permission.NET, "127.0.0.1:0"));
        assertDoesNotThrow(() -> policy.requireNetworkListen("127.0.0.1:0", "http.listen"));
    }

    @Test
    void noPortCannotBeUsedForListenerAuthorization() {
        var policy = RuntimePermissions.denyAll()
                .allowAll(RuntimePermissions.Permission.NET);
        assertFalse(policy.allowsNetworkListen("localhost"));
        assertFalse(policy.allowsNetworkListen("[::1]"));
    }

    @Test
    void listenerDenyOverridesWildcardAndPortZeroAllow() {
        var policy = RuntimePermissions.denyAll()
                .allowAll(RuntimePermissions.Permission.NET)
                .withDenied(RuntimePermissions.Permission.NET, "127.0.0.1:0");
        assertFalse(policy.allowsNetworkListen("127.0.0.1:0"));
        assertTrue(policy.allowsNetworkListen("127.0.0.1:8080"));
        assertThrows(SecurityException.class, () ->
                policy.requireNetworkListen("127.0.0.1:0", "http.listen"));
    }

    @Test
    void childCannotRecoverListenerAuthorityDeniedByAncestor() {
        var root = ActorPermissionScope.root(RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.NET, "127.0.0.1:0"));
        var child = root.attenuate(RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.NET, "127.0.0.1:443"));
        assertThrows(SecurityException.class,
                () -> child.requireNetworkListen("127.0.0.1:0", "http.listen"));
        assertThrows(SecurityException.class,
                () -> child.requireNetworkListen("127.0.0.1:443", "http.listen"));
        assertDoesNotThrow(() -> root.requireNetworkListen("127.0.0.1:0", "http.listen"));
    }

    @Test
    void childWildcardCannotOverrideParentEphemeralBindDeny() {
        var root = ActorPermissionScope.root(RuntimePermissions.denyAll()
                .allowAll(RuntimePermissions.Permission.NET)
                .withDenied(RuntimePermissions.Permission.NET, "localhost:0"));
        var child = root.attenuate(RuntimePermissions.denyAll()
                .allowAll(RuntimePermissions.Permission.NET));
        assertThrows(SecurityException.class, () ->
                child.requireNetworkListen("localhost:0", "http.listen"));
        assertDoesNotThrow(() ->
                child.requireNetworkListen("localhost:8080", "http.listen"));
    }
}
