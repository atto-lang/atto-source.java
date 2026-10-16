package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class IsolatePolicySerializationAuditTest {
    @Test
    void emptyGuestArgumentArrayPreservesLegacyEmbedderBehavior() {
        assertEquals(IsolatePolicy.developer(),
                IsolatePolicy.fromApplicationArguments(new String[0]));
    }

    @Test
    void partiallySerializedPolicyCannotInheritDeveloperGrants() {
        for (String arg : new String[]{
                "--ores-max-heap-bytes=16777216",
                "--ores-adversarial=true",
                "--ores-permission-explicit=true",
                "--ores-permission-net-allow=*",
                "--ores-execution-mode=JIT"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> IsolatePolicy.fromApplicationArguments(new String[]{arg}),
                    "missing capabilities marker was accepted: " + arg);
        }
    }

    @Test
    void duplicateCapabilityHeaderAndHostLimitsCannotOverrideEarlierPolicy() {
        for (String[] arguments : new String[][]{
                {"--ores-capabilities=", "--ores-capabilities=NETWORK"},
                {"--ores-capabilities=STDOUT", "--ores-capabilities=STDOUT"},
                {"--ores-capabilities=STDOUT", "--ores-max-heap-bytes=16777216",
                        "--ores-max-heap-bytes=536870912"},
                {"--ores-capabilities=STDOUT", "--ores-max-mailbox-messages=1",
                        "--ores-max-mailbox-messages=9999"},
                {"--ores-capabilities=STDOUT", "--ores-max-wall-ms=1",
                        "--ores-max-wall-ms=30000"},
                {"--ores-capabilities=STDOUT", "--ores-adversarial=true",
                        "--ores-adversarial=false"}
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> IsolatePolicy.fromApplicationArguments(arguments));
        }
    }

    @Test
    void invalidBooleanPolicyMarkersMustNotBeSilentlyParsedAsFalse() {
        for (String invalid : new String[]{"yes", "false ", "0", "", "TRUE\n"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> IsolatePolicy.fromApplicationArguments(new String[]{
                            "--ores-capabilities=STDOUT",
                            "--ores-adversarial=" + invalid
                    }));
        }
    }

    @Test
    void malformedOrDuplicateCapabilitiesFailClosed() {
        for (String raw : new String[]{
                ",STDOUT", "STDOUT,", "STDOUT,,NETWORK",
                "STDOUT,stdout", "NETWORK,NETWORK", " "
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> IsolatePolicy.fromApplicationArguments(new String[]{
                            "--ores-capabilities=" + raw
                    }), "invalid serialized capability list was accepted: " + raw);
        }
    }

    @Test
    void explicitEmptyCapabilitiesRemainEmpty() {
        IsolatePolicy restored = IsolatePolicy.fromApplicationArguments(
                new String[]{"--ores-capabilities="});
        assertEquals(Set.of(), restored.capabilities());
        assertFalse(restored.allows(IsolatePolicy.Capability.NETWORK));
        assertFalse(restored.allows(IsolatePolicy.Capability.FILESYSTEM_READ));
    }

    @Test
    void hostGeneratedPolicyRoundTripsWithoutExpandingAuthority() {
        IsolatePolicy host = IsolatePolicy.untrustedActor();
        String[] args = host.applicationArguments(
                ExecutionProfile.serverJit(),
                RuntimePermissions.denyAll(),
                PermissionCheckMode.RUNTIME);
        assertEquals(host, IsolatePolicy.fromApplicationArguments(args));

        IsolatePolicy local = IsolatePolicy.developer();
        assertEquals(local, IsolatePolicy.fromApplicationArguments(
                local.applicationArguments(
                        ExecutionProfile.serverJit(),
                        RuntimePermissions.denyAll(),
                        PermissionCheckMode.RUNTIME)));
    }
    @Test
    void duplicateExecutionMetadataIsRejected() {
        for (String[] args : new String[][]{
                {"--ores-execution-mode=JIT", "--ores-execution-mode=AOT"},
                {"--ores-platform=SERVER", "--ores-platform=SERVER"}
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> IsolatePolicy.executionProfileFromApplicationArguments(args));
        }
    }

    @Test
    void duplicateOrEmptyPermissionCheckModeIsRejected() {
        for (String[] args : new String[][]{
                {"--ores-permission-check=compile", "--ores-permission-check=runtime"},
                {"--ores-permission-check=runtime", "--ores-permission-check=runtime"},
                {"--ores-permission-check="},
                {"--ores-permission-check=unknown"}
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> PermissionCheckMode.fromApplicationArguments(args));
        }
    }

    @Test
    void validSinglePermissionCheckModeAndLegacyDefaultArePreserved() {
        assertEquals(PermissionCheckMode.COMPILE,
                PermissionCheckMode.fromApplicationArguments(new String[0]));
        assertEquals(PermissionCheckMode.COMPILE,
                PermissionCheckMode.fromApplicationArguments(
                        new String[]{"--ores-permission-check=compile"}));
        assertEquals(PermissionCheckMode.RUNTIME,
                PermissionCheckMode.fromApplicationArguments(
                        new String[]{"--ores-permission-check=runtime"}));
    }

}
