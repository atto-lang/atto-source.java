package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class HotReloadCodeImageIdentityTest {
    private static final String SOURCE = "pub routine main(): void { return; }";

    @Test
    void independentManagersCannotCollideOnSameNumericGeneration() {
        try (var left = new HotReloadManager(
                     IsolatePolicy.developer(), ExecutionProfile.serverJit());
             var right = new HotReloadManager(
                     IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var leftGeneration = left.load("service.ores", SOURCE);
            var rightGeneration = right.load("service.ores", SOURCE);

            assertEquals(1, leftGeneration.id());
            assertEquals(1, rightGeneration.id());
            assertNotSame(
                    leftGeneration.codeImageIdentity(),
                    rightGeneration.codeImageIdentity(),
                    "manager-local numeric generation ids must not authenticate code identity");
            assertTrue(leftGeneration.codeImageIdentity().isActive());
            assertTrue(rightGeneration.codeImageIdentity().isActive());
        }
    }

    @Test
    void generationRetirementRevokesRecoveryCodeIdentity() {
        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var generation = manager.load("retire.ores", SOURCE);
            CodeImageIdentity identity = generation.codeImageIdentity();
            assertTrue(identity.isActive());

            manager.retire(generation.id());

            assertFalse(identity.isActive());
            assertTrue(generation.closed());
            assertNull(manager.active("retire.ores"));
        }
    }

    @Test
    void laterGenerationGetsDistinctIdentityWithoutMutatingOldOne() {
        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var first = manager.load("service.ores", SOURCE);
            CodeImageIdentity firstIdentity = first.codeImageIdentity();

            var second = manager.load(
                    "service.ores",
                    "pub routine main(): void { val version = 2; return; }");
            CodeImageIdentity secondIdentity = second.codeImageIdentity();

            assertNotSame(firstIdentity, secondIdentity);
            assertEquals(first.id(), firstIdentity.generation());
            assertEquals(second.id(), secondIdentity.generation());
            assertTrue(firstIdentity.isActive(),
                    "old generation stays valid while it is still live/draining");
            assertTrue(secondIdentity.isActive());

            manager.retire(first.id());
            assertFalse(firstIdentity.isActive());
            assertTrue(secondIdentity.isActive());
        }
    }
}
