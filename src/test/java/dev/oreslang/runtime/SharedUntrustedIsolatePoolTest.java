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
    void sharedUntrustedPoolNeverAcceptsTrustedOrPrivilegedGroupPolicy() {
        long limit = 256L * 1024 * 1024;
        assertDoesNotThrow(() ->
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

    /**
     * Run on a real GraalVM runner with an Oreslang language-isolate image.
     * It must NEVER pass by silently switching to the host JVM interpreter.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "ORES_TEST_SHARED_UNTRUSTED_ISOLATE", matches = "true")
    void physicallySharesExactlyOneEngineAcrossUntrustedContexts() {
        try (SharedUntrustedIsolatePool pool = new SharedUntrustedIsolatePool(
                256L * 1024 * 1024, 2);
             SharedUntrustedIsolatePool.Group groupA = pool.createGroup(IsolatePolicy.untrustedActor());
             SharedUntrustedIsolatePool.Group groupB = pool.createGroup(IsolatePolicy.untrustedActor())) {
            assertEquals(2, pool.activeGroups());
            assertNotSame(groupA.contextForSupervisor(), groupB.contextForSupervisor());
            assertSame(groupA.contextForSupervisor().getEngine(),
                    groupB.contextForSupervisor().getEngine(),
                    "groups must share one isolate engine and GC/JIT");
            assertThrows(IllegalStateException.class,
                    () -> pool.createGroup(IsolatePolicy.untrustedActor()));
        }
    }
}
