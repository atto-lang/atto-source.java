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
    void hostLegacyUntrustedSpawnFailsClosedWhenPhysicalBoundaryRequired() {
        String key = "ores.untrusted.require-physical-isolate";
        String previous = System.getProperty(key);
        try {
            System.setProperty(key, "true");
            try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
                SecurityException rejected = assertThrows(SecurityException.class, () ->
                        runtime.<String>spawnUntrusted(context -> (message, actor) -> {}));
                assertTrue(rejected.getMessage().contains("physical shared Graal isolate"));
            }
            // An adversarial Java runtime *also* cannot fake isolate residency.
            try (ActorRuntime runtime = new ActorRuntime(
                    IsolatePolicy.sharedUntrustedDomain(256L * 1024 * 1024))) {
                assertThrows(SecurityException.class, () ->
                        runtime.<String>spawnUntrusted(context -> (message, actor) -> {}));
            }
        } finally {
            if (previous == null) System.clearProperty(key);
            else System.setProperty(key, previous);
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
            if (Boolean.getBoolean("ores.untrusted.require-native-isolate")) {
                throw new AssertionError(
                        "native Oreslang isolate build smoke must actually enter a guest isolate",
                        unavailable);
            }
            System.out.println("Expected fail-closed bootstrap without a language-isolate image: "
                    + unavailable.getCause());
            assertTrue(unavailable.getMessage().contains("could not start"),
                    "a missing Graal isolate must fail closed, not fall back to the host JVM");
            assertNotNull(unavailable.getCause());
            String causeText = unavailable.getCause().toString();
            assertTrue(causeText.contains("No native isolate library is available for the requested language(s) [ores]"),
                    "only the expected absent Oreslang native-isolate artifact may pass this "
                    + "fail-closed test; configuration or lifecycle errors must fail: " + causeText);
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
            // A successful native-isolate smoke must use syntactically
            // valid Oreslang. Empty source is intentionally a parse error.
            domain.submitSource("pub routine main(): void { return; }")
                    .get(15, TimeUnit.SECONDS);
            if (Boolean.getBoolean("ores.untrusted.require-native-isolate")) {
                // Disambiguate repeated-eval lifecycle defects from actor
                // scheduling defects in a persistent UNTRUSTED Context.
                // Both no-op units receive different host-owned code IDs.
                domain.submitSource("pub routine main(): void { return; }")
                        .get(15, TimeUnit.SECONDS);
                // This positive Native Image gate is deliberately stronger than
                // the JVM fail-closed smoke: both actors must start, exchange
                // mailbox traffic and terminate inside the SAME guest Context.
                // First isolate guest actor admission from inter-actor
                // mailbox/lifecycle interactions. A distinct error label
                // identifies the failing phase even when Graal masks a guest
                // exception during sandbox listener onLeaveThread().
                assertGuestPhase(domain, "one actor lifecycle", """
                        define untrusted actor Worker as
                          receive(ActorMail<String> mail): void {
                            self.end();
                            return;
                          }
                        end
                        pub async routine main(): void {
                          val worker = spawn Worker();
                          await worker.ready;
                          worker.send("single");
                          await worker.done;
                          return;
                        }
                        """);
                assertGuestPhase(domain, "two actor mailbox", """
                        define untrusted actor Worker as
                          receive(ActorMail<String> mail): void {
                            self.end();
                            return;
                          }
                        end

                        pub async routine main(): void {
                          val left = spawn Worker();
                          val right = spawn Worker();
                          await left.ready;
                          await right.ready;
                          left.send("left");
                          right.send("right");
                          await left.done;
                          await right.done;
                          return;
                        }
                        """);
            }
        } finally {
            domain.close();
        }
        assertThrows(IllegalStateException.class, () -> domain.submitSource("const a = 1;"));
    }

    private static void assertGuestPhase(
            SharedUntrustedGraalIsolate domain, String phase, String source) throws Exception {
        try {
            domain.submitSource(source).get(30, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException failure) {
            throw new AssertionError(
                    "shared native isolate failed during " + phase
                            + "; guest context may have been invalidated by sandbox leave", failure);
        }
    }
}
