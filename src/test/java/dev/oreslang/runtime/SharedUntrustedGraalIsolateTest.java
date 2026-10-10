package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SharedUntrustedGraalIsolateTest {
    @Test
    void oneDomainRootCanCreateActorsWithoutGrantingHostCapabilities() {
        IsolatePolicy root = IsolatePolicy.sharedUntrustedDomain(256L * 1024 * 1024);
        assertTrue(root.adversarial());
        assertEquals(Set.of(IsolatePolicy.Capability.ACTOR_SPAWN), root.capabilities());
        assertFalse(root.allows(IsolatePolicy.Capability.JAVA_INTEROP));
        assertFalse(root.allows(IsolatePolicy.Capability.FFI));
        assertFalse(root.allows(IsolatePolicy.Capability.NETWORK));
        assertFalse(root.allows(IsolatePolicy.Capability.THREAD_CREATE));
        assertTrue(IsolatePolicy.untrustedActor().capabilities().isEmpty(),
                "individual actors must not inherit domain-root spawn authority");
        assertEquals(192L * 1024 * 1024, root.maxHeapBytes());
        assertThrows(IllegalArgumentException.class, () ->
                IsolatePolicy.sharedUntrustedDomain(16L * 1024 * 1024));
    }

    @Test
    void rootDomainRejectsPrivateAndSharedPrivilegeEscalation() {
        try (ActorRuntime guest = new ActorRuntime(
                IsolatePolicy.sharedUntrustedDomain(256L * 1024 * 1024))) {
            assertThrows(SecurityException.class, () ->
                    guest.<String>spawnPrivate(context -> (message, turn) -> { }));
            assertThrows(SecurityException.class, () ->
                    guest.<String>spawnShared(context -> (message, turn) -> { }));
        }
    }

    @Test
    void tooSmallIsolateCannotBeCreated() {
        assertThrows(IllegalArgumentException.class, () ->
                SharedUntrustedGraalIsolate.open(1024));
    }

    @Test
    void sharedDomainIsOneProcessSingletonOrFailsClosedWithoutIsolateImage() throws Exception {
        SharedUntrustedGraalIsolate domain;
        try {
            domain = SharedUntrustedGraalIsolate.open();
        } catch (IllegalStateException unavailable) {
            System.out.println("Expected fail-closed bootstrap without a language-isolate image: "
                    + unavailable.getCause());
            assertTrue(unavailable.getMessage().contains("could not start"),
                    "a missing Graal isolate must fail closed, not fall back to the host JVM");
            assertNotNull(unavailable.getCause());
            return;
        }

        try {
            assertTrue(domain.usesOneEngineForWholeDomain());
            assertThrows(IllegalArgumentException.class, () ->
                    domain.submitSource("x".repeat(1_048_577)),
                    "large source submissions must be rejected before entering host queue");
            assertSame(domain, SharedUntrustedGraalIsolate.open(),
                    "multiple untrusted host entrypoints must reuse the same engine");
            assertThrows(IllegalStateException.class, () ->
                    SharedUntrustedGraalIsolate.open(128L * 1024 * 1024));
            // No host Value or Context escapes the source-submission API.
            assertNotNull(domain.submitSource(""));
        } finally {
            domain.close();
        }
        assertThrows(IllegalStateException.class, () -> domain.submitSource("const a = 1;"));
    }
}
