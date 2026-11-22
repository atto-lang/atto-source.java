package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class AdversarialPermissionCeilingTest {
    private static final Set<IsolatePolicy.Capability> UNSAFE = Set.of(
            IsolatePolicy.Capability.THREAD_CREATE,
            IsolatePolicy.Capability.JAVA_INTEROP,
            IsolatePolicy.Capability.JAVA_SOURCE_INTEROP,
            IsolatePolicy.Capability.FFI,
            IsolatePolicy.Capability.NATIVE,
            IsolatePolicy.Capability.REFLECTION,
            IsolatePolicy.Capability.POLYGLOT,
            IsolatePolicy.Capability.CHILD_PROCESS,
            IsolatePolicy.Capability.PROCESS_EXIT,
            IsolatePolicy.Capability.GC_CONTROL,
            IsolatePolicy.Capability.SHARED_MEMORY,
            IsolatePolicy.Capability.ACTOR_SHARE_READONLY,
            IsolatePolicy.Capability.GPU,
            IsolatePolicy.Capability.HOT_CODE_LOAD);

    @Test
    void adversarialIsolateConstructorRejectsEveryUnsafeCapability() {
        for (IsolatePolicy.Capability forbidden : UNSAFE) {
            var error = assertThrows(IllegalArgumentException.class, () ->
                    new IsolatePolicy(Set.of(forbidden),
                            32L * 1024 * 1024, 128, Duration.ofMillis(250), true),
                    forbidden.name());
            assertTrue(error.getMessage().contains(forbidden.name()), forbidden.name());
        }
    }

    @Test
    void untrustedActorCannotGainUnsafeCapabilitiesViaWithCapabilities() {
        for (IsolatePolicy.Capability forbidden : UNSAFE) {
            assertThrows(IllegalArgumentException.class,
                    () -> IsolatePolicy.untrustedActor().withCapabilities(forbidden),
                    forbidden.name());
        }
    }

    @Test
    void serializedApplicationArgumentsCannotRecreateForbiddenAdversarialAuthority() {
        for (IsolatePolicy.Capability forbidden : UNSAFE) {
            assertThrows(IllegalArgumentException.class,
                    () -> IsolatePolicy.fromApplicationArguments(new String[] {
                            "--ores-adversarial=true",
                            "--ores-capabilities=" + forbidden.name()
                    }), forbidden.name());
        }
    }

    @Test
    void conversionToAdversarialDoesNotSilentlyRetainRootProcessAuthority() {
        assertThrows(IllegalArgumentException.class,
                () -> IsolatePolicy.developer().asAdversarial());
    }

    @Test
    void safelyScopedIoStillRequiresExplicitResourcePermission() {
        var untrusted = IsolatePolicy.untrustedActor()
                .withCapabilities(IsolatePolicy.Capability.FILESYSTEM_READ,
                        IsolatePolicy.Capability.NETWORK);
        var empty = RuntimePermissions.fromApplicationArguments(
                new String[] {"--ores-permission-explicit=true"}, untrusted);
        assertFalse(empty.allows(RuntimePermissions.Permission.READ, "/tmp/guest.txt"));
        assertFalse(empty.allows(RuntimePermissions.Permission.NET, "example.com:443"));
        assertTrue(IsolatePolicy.untrustedActor().capabilities().isEmpty());
        assertEquals(Set.of(), RuntimePermissions.denyAll().grantedCapabilities());
    }

    @Test
    void trustedHostMayStillExpressUnsafeCapabilityWithoutBeingAnAdversarialGuest() {
        var trusted = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.FFI,
                        IsolatePolicy.Capability.CHILD_PROCESS);
        assertFalse(trusted.adversarial());
        assertTrue(trusted.allows(IsolatePolicy.Capability.FFI));
        assertTrue(trusted.allows(IsolatePolicy.Capability.CHILD_PROCESS));
    }
}
