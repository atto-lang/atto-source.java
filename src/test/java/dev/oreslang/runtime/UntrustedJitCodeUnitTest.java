package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

final class UntrustedJitCodeUnitTest {
    @Test void rejectsObjectsOrUnboundedPayloadsBeforeCrossingIsolate() {
        assertNull(UntrustedJitCodeUnit.copyScalar(null));
        assertEquals(12, UntrustedJitCodeUnit.copyScalar(12));
        assertEquals("allowed", UntrustedJitCodeUnit.copyScalar("allowed"));
        assertEquals(true, UntrustedJitCodeUnit.copyScalar(true));
        assertThrows(SecurityException.class, () -> UntrustedJitCodeUnit.copyScalar(new Object()));
        assertThrows(SecurityException.class, () -> UntrustedJitCodeUnit.copyScalar(
                new ActorGraphArena.NodeId(UUID.randomUUID(), 0, 1)));
        assertThrows(SecurityException.class, () -> UntrustedJitCodeUnit.copyScalar(new byte[12]));
        assertThrows(SecurityException.class, () -> UntrustedJitCodeUnit.copyScalar(
                "x".repeat(16 * 1024 + 1)));
        assertThrows(SecurityException.class, () -> UntrustedJitCodeUnit.copyScalar(Double.NaN));
        assertThrows(SecurityException.class, () -> UntrustedJitCodeUnit.copyScalar(
                Double.POSITIVE_INFINITY));
    }

    @Test
    void utf8WireQuotaAndMalformedSurrogatesFailClosed() {
        assertEquals("é".repeat(8192),
                UntrustedJitCodeUnit.copyScalar("é".repeat(8192)));
        assertThrows(SecurityException.class, () ->
                UntrustedJitCodeUnit.copyScalar("é".repeat(8193)));
        assertEquals("😀".repeat(4096),
                UntrustedJitCodeUnit.copyScalar("😀".repeat(4096)));
        assertThrows(SecurityException.class, () ->
                UntrustedJitCodeUnit.copyScalar("😀".repeat(4097)));
        assertThrows(SecurityException.class, () ->
                UntrustedJitCodeUnit.copyScalar(Character.toString((char) 0xD800)));
        assertThrows(SecurityException.class, () ->
                UntrustedJitCodeUnit.copyScalar(Character.toString((char) 0xDC00)));
    }

    @Test
    void isolatedActorControlCommandsFailClosedInOrdinaryHostContext() {
        String code = """
                define untrusted actor LocalSandbox as
                  receive(ActorMail<String> mail): void {
                    self.end();
                    return;
                  }
                end
                """;
        try (org.graalvm.polyglot.Context host =
                     org.graalvm.polyglot.Context.create("ores")) {
            var parsed = host.parse(org.graalvm.polyglot.Source
                    .newBuilder("ores", code, "host-control-rejection")
                    .buildLiteral());
            parsed.execute(dev.oreslang.nodes.OresEvalRootNode.LINK_ONLY_COMMAND);
            var denied = assertThrows(org.graalvm.polyglot.PolyglotException.class,
                    () -> parsed.execute(
                            dev.oreslang.nodes.OresEvalRootNode.ISOLATED_ACTOR_SPAWN_COMMAND,
                            "LocalSandbox"));
            assertTrue(denied.getMessage().contains("adversarial"),
                    "ordinary trusted Context cannot masquerade as untrusted isolate");
        }
    }

    /**
     * Actual actor source must execute inside the guest Context, not in the
     * host ActorRuntime. This remains gated until a language-isolate artifact
     * is available to the funded runner.
     */
    @Test
    @EnabledIfEnvironmentVariable(named="ORES_TEST_SHARED_UNTRUSTED_ISOLATE", matches="true")
    void untrustedSourceActorMailboxIsRoutedThroughGuestOpaqueId() throws Exception {
        String source = """
                define untrusted actor IsolatedWorker as
                  receive(ActorMail<String> mail): void {
                    if mail.value == "stop"; do
                      self.end();
                    fi
                    return;
                  }
                end
                """;
        try (SharedUntrustedIsolatePool pool =
                 new SharedUntrustedIsolatePool(256L * 1024 * 1024, 2);
             SharedUntrustedIsolatePool.Group group = pool.createGroup();
             UntrustedJitCodeUnit code = new UntrustedJitCodeUnit(
                     group, source, "actor-bridge")) {
            try (var actor = code.spawnUntrustedActor(code.generation(), "IsolatedWorker")) {
                assertTrue(actor.id() > 0);
                assertThrows(SecurityException.class, () -> actor.send(new Object()));
                assertThrows(IllegalStateException.class,
                        () -> code.spawnUntrustedActor(
                                UUID.randomUUID(), "IsolatedWorker"));
                actor.send("stop");
                long deadline = System.nanoTime() +
                        java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (!actor.done() && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
                assertTrue(actor.done(),
                        "guest-side untrusted actor did not finish after stop message");
            }
        }
    }

    /**
     * Requires the real Graal native language isolate artifact and cannot
     * run under an ordinary host JVM which lacks that language isolate.
     * This is source-execution evidence, NOT source actor mailbox routing.
     */
    @Test
    @EnabledIfEnvironmentVariable(named="ORES_TEST_SHARED_UNTRUSTED_ISOLATE", matches="true")
    void actualJitIsolateCompilesAndRunsGuestSourceWithoutExposingValue() {
        try (SharedUntrustedIsolatePool pool =
                 new SharedUntrustedIsolatePool(256L * 1024 * 1024, 2);
             SharedUntrustedIsolatePool.Group group = pool.createGroup();
             UntrustedJitCodeUnit code = new UntrustedJitCodeUnit(
                     group,
                     "pub routine double_value(int n): int { return n * 2; }",
                     "arithmetic")) {
            assertEquals(42, code.invoke(code.generation(), "double_value", 21));
            assertThrows(IllegalStateException.class,
                    () -> code.invoke(UUID.randomUUID(), "double_value", 3));
            assertThrows(SecurityException.class,
                    () -> code.invoke(code.generation(), "double_value", new Object()));
        }
    }
}
