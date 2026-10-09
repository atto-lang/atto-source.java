package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class HotReloadBoundarySecurityTest {

    @Test
    void adversarialGenericStartupChecksRawIdentityBeforeNormalization() {
        try (HotReloadManager loader = HotReloadManager.forUntrustedActors(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            // Path normalization would collapse the long untrusted input into
            // a short path. The 512-character admission cap applies to raw input.
            String oversizedId = "a/../".repeat(110) + "worker.ores";
            IllegalArgumentException rejected = assertThrows(
                    IllegalArgumentException.class, () -> loader.loadAndStart(
                            oversizedId, "pub routine main(): void { return; }"));
            assertTrue(rejected.getMessage().contains("maximum length"));
            assertEquals(0, loader.liveGenerations());
            assertNull(loader.active());
        }
    }

    @Test
    void adversarialGenericStartupChecksSourceSizeBeforeParsing() {
        try (HotReloadManager loader = HotReloadManager.forUntrustedActors(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            IllegalArgumentException rejected = assertThrows(
                    IllegalArgumentException.class, () -> loader.loadAndStart(
                            "worker.ores", "x".repeat(1_048_577)));
            assertTrue(rejected.getMessage().contains("maximum character count"));
            assertEquals(0, loader.liveGenerations());
            assertNull(loader.active("worker.ores"));
        }
    }

    @Test
    void closingActiveGenerationDisablesEveryActiveLookup() {
        try (HotReloadManager loader = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var generation = loader.loadAndStart(
                    "closing.ores", "pub routine main(): void { return; }");
            assertSame(generation, loader.active());
            assertSame(generation, loader.active("closing.ores"));
            assertEquals(1, loader.activeGenerations().size());

            generation.close();
            assertNull(loader.active());
            assertNull(loader.active("closing.ores"));
            assertTrue(loader.activeGenerations().isEmpty());
            assertEquals(0, loader.liveGenerations());
        }
    }

    @Test
    void registryBoundsAdversarialApprovalsPerAuthority() {
        SharedCodeRegistry registry = new SharedCodeRegistry();
        Object tenant = new Object();
        IsolatePolicy supervisor = IsolatePolicy.developer();
        String source = "pub routine main(): void { return; }";
        for (int i = 0; i < 64; i++) {
            registry.approveForAdversarialSharing(
                    supervisor, tenant, "image-" + i + ".ores", source);
        }
        // Repeated exact approval is idempotent and cannot consume more quota.
        assertDoesNotThrow(() -> registry.approveForAdversarialSharing(
                supervisor, tenant, "image-0.ores", source));
        assertThrows(IllegalStateException.class,
                () -> registry.approveForAdversarialSharing(
                        supervisor, tenant, "image-64.ores", source));
        registry.revokeAllForAuthority(tenant);
        assertDoesNotThrow(() -> registry.approveForAdversarialSharing(
                supervisor, tenant, "image-new.ores", source));
    }
}
