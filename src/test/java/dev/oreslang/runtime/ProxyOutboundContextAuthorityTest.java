package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Source-level runtime security contracts. This does not simulate a guest
 * actor turn or prove transport/permission integration over a live socket.
 */
final class ProxyOutboundContextAuthorityTest {
    @Test
    void outboundHostGateIsNotPublicGuestApi() throws Exception {
        var method = AsyncNativeIo.class.getDeclaredMethod(
                "requestProxyLoopback", OresContext.class, URI.class, String.class);
        assertFalse(Modifier.isPublic(method.getModifiers()));
        assertFalse(Modifier.isProtected(method.getModifiers()));
        assertEquals(CompletableFuture.class, method.getReturnType());
    }

    @Test
    void canonicalLoopbackValidatorRejectsAlternativeAuthoritiesAndUnsafeTargets() {
        assertDoesNotThrow(() -> ProxyOutboundLoopback.validateTarget(
                URI.create("http://127.0.0.1:8080/ok"), "GET"));
        assertDoesNotThrow(() -> ProxyOutboundLoopback.validateTarget(
                URI.create("http://127.0.0.1:8080/ok"), "HEAD"));
        for (String forbidden : new String[] {
                "http://localhost:8080/ok",
                "http://127.0.0.2:8080/ok",
                "https://127.0.0.1:8080/ok",
                "http://user@127.0.0.1:8080/ok",
                "http://127.0.0.1:8080/ok?redirect=1",
                "http://127.0.0.1:8080/ok#fragment",
                "http://127.0.0.1:8080/a/../secret",
                "http://127.0.0.1:8080/a%2Fsecret",
                "http://127.0.0.1:8080/a//secret" }) {
            assertThrows(IllegalArgumentException.class, () ->
                    ProxyOutboundLoopback.validateTarget(URI.create(forbidden), "GET"),
                    forbidden);
        }
        assertThrows(IllegalArgumentException.class, () ->
                ProxyOutboundLoopback.validateTarget(
                        URI.create("http://127.0.0.1:8080/ok"), "CONNECT"));
    }

    @Test
    void networkScopesDenyUnlessExplicitlyAllowedAndDenyRulesWin() {
        var denied = RuntimePermissions.denyAll();
        assertFalse(denied.allows(RuntimePermissions.Permission.NET, "127.0.0.1:8080"));
        var allowed = denied.withAllowed(RuntimePermissions.Permission.NET, "127.0.0.1:8080");
        assertTrue(allowed.allows(RuntimePermissions.Permission.NET, "127.0.0.1:8080"));
        assertFalse(allowed.allows(RuntimePermissions.Permission.NET, "127.0.0.1:8081"));
        assertFalse(allowed.allows(RuntimePermissions.Permission.NET, "127.0.0.2:8080"));
        var overridden = allowed.withDenied(RuntimePermissions.Permission.NET, "127.0.0.1:8080");
        assertFalse(overridden.allows(RuntimePermissions.Permission.NET, "127.0.0.1:8080"));
    }
}
