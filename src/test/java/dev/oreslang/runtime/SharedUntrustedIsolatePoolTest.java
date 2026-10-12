package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** One untrusted Graal isolate for many actor groups, not one per actor. */
final class SharedUntrustedIsolatePoolTest {
    @Test
    void boundedPoolSettingsRequireSafeResourceLimits() {
        assertThrows(IllegalArgumentException.class,
                () -> SharedUntrustedIsolatePool.validateConfiguration(1024, 1));
        assertThrows(IllegalArgumentException.class,
                () -> SharedUntrustedIsolatePool.validateConfiguration(64L * 1024 * 1024, 0));
        assertThrows(IllegalArgumentException.class,
                () -> SharedUntrustedIsolatePool.validateConfiguration(64L * 1024 * 1024, 1025));
        assertDoesNotThrow(() ->
                SharedUntrustedIsolatePool.validateConfiguration(256L * 1024 * 1024, 16));
    }

    @Test
    void standaloneAdversarialContextCannotAllocateAnExtraIsolate() {
        // This check runs without any Graal language-isolate artifact.
        // It must fail BEFORE trying to load an image or a fallback runtime.
        assertThrows(SecurityException.class, () ->
                IsolatePolicy.untrustedActor()
                        .restrictedContextBuilder(ExecutionProfile.serverJit()));
        assertThrows(SecurityException.class, () ->
                IsolatePolicy.strictFaas()
                        .restrictedContextBuilder(ExecutionProfile.serverJit()));
    }

    @Test
    void sharedUntrustedPoolNeverAcceptsTrustedOrPrivilegedGroupPolicy() {
        long limit = 256L * 1024 * 1024;
        assertDoesNotThrow(() ->
                SharedUntrustedIsolatePool.validateGroupPolicy(
                        IsolatePolicy.untrustedActorGroup(), limit));
        assertThrows(IllegalArgumentException.class, () ->
                SharedUntrustedIsolatePool.validateGroupPolicy(
                        IsolatePolicy.untrustedActor(), limit));
        assertThrows(SecurityException.class, () ->
                SharedUntrustedIsolatePool.validateGroupPolicy(
                        IsolatePolicy.developer(), limit));
        assertThrows(SecurityException.class, () ->
                SharedUntrustedIsolatePool.validateGroupPolicy(
                        new IsolatePolicy(Set.of(IsolatePolicy.Capability.STDOUT),
                                32L * 1024 * 1024, 64, Duration.ofSeconds(1), true), limit));
        assertThrows(IllegalArgumentException.class, () ->
                SharedUntrustedIsolatePool.validateGroupPolicy(
                        new IsolatePolicy(Set.of(), 512L * 1024 * 1024,
                                64, Duration.ofSeconds(1), true), limit));
    }

    @Test
    void executionProfilesRemainDistinctAcrossTrustedHostAndUntrustedGuest() {
        // AOT-only supervisor code is compatible with a separately packaged
        // optimizing guest isolate. Do not run HYBRID in the trusted context.
        ExecutionProfile trusted = new ExecutionProfile(
                ExecutionProfile.Mode.AOT, ExecutionProfile.Platform.SERVER);
        ExecutionProfile untrusted = ExecutionProfile.serverHybrid();
        assertFalse(trusted.guestJitAllowed());
        assertTrue(trusted.hostAheadOfTime());
        assertTrue(untrusted.guestJitAllowed());
        assertTrue(untrusted.hostAheadOfTime());
    }

    @Test
    void adversarialHotLoadValidatesBeforeAllocatingNativeIsolate() {
        // Deliberately runs on ordinary OpenJDK: validation must happen before
        // the singleton Engine/guest language-isolate is acquired.
        try (HotReloadManager loader = HotReloadManager.forUntrustedActors(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            assertThrows(IllegalArgumentException.class, () ->
                    loader.load("oversized.ores", "x".repeat(1_048_577)));
            assertEquals(0, loader.liveGenerations());
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ORES_TEST_SHARED_UNTRUSTED_ISOLATE", matches = "true")
    void hotReloadMultipleGenerationsReuseExactlyOneUntrustedEngine() {
        try (HotReloadManager loader = HotReloadManager.forUntrustedActors(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            HotReloadManager.Generation first = loader.load(
                    "a.ores", "pub routine main(): void { return; }");
            HotReloadManager.Generation second = loader.load(
                    "b.ores", "pub routine main(): void { val x = 2; return; }");
            assertEquals(2, loader.liveGenerations());
            assertNotSame(first.context(), second.context());
            assertSame(first.context().getEngine(), second.context().getEngine(),
                    "hot-loaded generations may allocate Contexts, never Engines");
            first.start();
            second.start();
            loader.retire(first.id());
            assertTrue(first.closed());
            assertEquals(1, loader.liveGenerations());
            HotReloadManager.Generation replacement = loader.load(
                    "c.ores", "pub routine main(): void { return; }");
            assertSame(second.context().getEngine(), replacement.context().getEngine(),
                    "retirement must reuse the existing physical untrusted isolate");
            // Untrusted actor hot-load starts the code generation but must
            // NEVER run the top-level main in the 12-hour group Context.
            HotReloadManager.Generation dangerousMain = loader.load(
                    "dangerous.ores", """
                    pub routine main(): void {
                      val values = arr[1];
                      val invalid = values[99];
                      return;
                    }
                    """);
            assertNull(dangerousMain.start(),
                    "untrusted hot load must only link/init, not invoke main");
        }
    }

    @Test
    void closedUntrustedLoaderRejectsNewGenerationsBeforeIsolateAllocation() {
        HotReloadManager loader = HotReloadManager.forUntrustedActors(
                IsolatePolicy.developer(), ExecutionProfile.serverJit());
        loader.close();
        assertThrows(IllegalStateException.class, () ->
                loader.load("a.ores", "pub routine main(): void { return; }"));
        loader.close(); // exactly-once close is idempotent
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ORES_TEST_SHARED_UNTRUSTED_ISOLATE", matches = "true")
    void rejectedGuestSourceDoesNotExhaustGenerationQuotaAfterConfirmedClose() {
        try (HotReloadManager loader = HotReloadManager.forUntrustedActors(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            for (int i = 0; i < 12; i++) {
                assertThrows(IllegalStateException.class, () -> loader.loadAndStart(
                        "bad.ores", "pub routine main(): void {"),
                        "invalid source must fail inside isolated guest");
                assertEquals(0, loader.liveGenerations(),
                        "only verified, fully closed generations release capacity");
            }
        }
    }

    /**
     * Run on a real GraalVM runner with an Oreslang language-isolate image.
     * It must NEVER pass by silently switching to the host JVM interpreter.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "ORES_TEST_SHARED_UNTRUSTED_ISOLATE", matches = "true")
    void physicallySharesExactlyOneEngineAcrossUntrustedContexts() {
        try (SharedUntrustedIsolatePool pool = new SharedUntrustedIsolatePool(
                256L * 1024 * 1024, 2);
             SharedUntrustedIsolatePool.Group groupA = pool.createGroup();
             SharedUntrustedIsolatePool.Group groupB = pool.createGroup()) {
            assertEquals(2, pool.activeGroups());
            assertThrows(IllegalStateException.class, () ->
                    new SharedUntrustedIsolatePool(256L * 1024 * 1024, 2),
                    "a second pool would allocate a third execution domain");
            assertNotSame(groupA.contextForSupervisor(), groupB.contextForSupervisor());
            assertSame(groupA.contextForSupervisor().getEngine(),
                    groupB.contextForSupervisor().getEngine(),
                    "groups must share one isolate engine and GC/JIT");
            assertThrows(IllegalStateException.class,
                    () -> pool.createGroup());
            groupA.close();
            assertEquals(1, pool.activeGroups());
            assertThrows(IllegalStateException.class, groupA::contextForSupervisor,
                    "retired groups must never re-enter the guest Context");
            try (SharedUntrustedIsolatePool.Group replacement = pool.createGroup()) {
                assertEquals(2, pool.activeGroups());
                assertSame(groupB.contextForSupervisor().getEngine(),
                        replacement.contextForSupervisor().getEngine());
            }
        }
    }
}
