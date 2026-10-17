package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

final class RuntimePermissionCeilingTest {
    private static IsolatePolicy coarse() {
        return IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.ENVIRONMENT,
                IsolatePolicy.Capability.NETWORK,
                IsolatePolicy.Capability.FILESYSTEM_READ,
                IsolatePolicy.Capability.FILESYSTEM_WRITE);
    }

    @Test
    void runtimeGrantCannotExpandBuildCeiling() {
        RuntimePermissions ceiling = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.ENV, "SAFE")
                .withAllowed(RuntimePermissions.Permission.NET, "api.example.com:443");
        RuntimePermissions requested = RuntimePermissions.denyAll()
                .allowAll(RuntimePermissions.Permission.ENV, RuntimePermissions.Permission.NET)
                .boundedBy(ceiling);

        assertTrue(requested.allows(RuntimePermissions.Permission.ENV, "SAFE"));
        assertFalse(requested.allows(RuntimePermissions.Permission.ENV, "SECRET"));
        assertTrue(requested.allows(RuntimePermissions.Permission.NET, "api.example.com:443"));
        assertFalse(requested.allows(RuntimePermissions.Permission.NET, "other.example.com:443"));

        RuntimePermissions attemptedWiden = requested
                .withAllowed(RuntimePermissions.Permission.ENV, "SECRET")
                .withAllowed(RuntimePermissions.Permission.NET, "other.example.com:443");
        assertFalse(attemptedWiden.allows(RuntimePermissions.Permission.ENV, "SECRET"),
                "withAllowed must preserve an inherited ceiling");
        assertFalse(attemptedWiden.allows(
                RuntimePermissions.Permission.NET, "other.example.com:443"));
    }

    @Test
    void selectedIpAuthorizationMustPassBuildCeilingAndRuntimeGrant() {
        RuntimePermissions ceiling = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.NET, "api.example.com:443")
                .withDenied(RuntimePermissions.Permission.NET, "127.0.0.1:443");
        RuntimePermissions broadRuntime = RuntimePermissions.denyAll()
                .allowAll(RuntimePermissions.Permission.NET)
                .boundedBy(ceiling);

        assertTrue(broadRuntime.allowsNetworkConnection(
                "api.example.com:443", "203.0.113.7:443"));
        assertFalse(broadRuntime.allowsNetworkConnection(
                "api.example.com:443", "127.0.0.1:443"),
                "runtime wildcard cannot bypass a build-time selected-IP deny");
        assertFalse(broadRuntime.allowsNetworkConnection(
                "other.example.com:443", "203.0.113.7:443"));
    }

    @Test
    void buildCeilingAndRuntimeGrantSerializeAsIndependentChannels() {
        IsolatePolicy policy = coarse();
        RuntimePermissions ceiling = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.ENV, "SAFE")
                .withAllowed(RuntimePermissions.Permission.NET, "api.example.com:443");
        RuntimePermissions runtime = RuntimePermissions.denyAll()
                .allowAll(RuntimePermissions.Permission.ENV, RuntimePermissions.Permission.NET);

        String[] args = policy.applicationArguments(
                ExecutionProfile.parse("jit", "server"),
                ceiling,
                runtime,
                PermissionCheckMode.RUNTIME);

        assertTrue(Arrays.stream(args).anyMatch(
                value -> value.equals("--ores-build-permission-explicit=true")));
        assertTrue(Arrays.stream(args).anyMatch(
                value -> value.equals("--ores-permission-explicit=true")));

        RuntimePermissions parsedCeiling =
                RuntimePermissions.buildCeilingFromApplicationArguments(args, policy);
        RuntimePermissions parsedRuntime =
                RuntimePermissions.fromApplicationArguments(args, policy)
                        .boundedBy(parsedCeiling);
        assertTrue(parsedRuntime.allows(RuntimePermissions.Permission.ENV, "SAFE"));
        assertFalse(parsedRuntime.allows(RuntimePermissions.Permission.ENV, "SECRET"));
        assertTrue(parsedRuntime.allows(
                RuntimePermissions.Permission.NET, "api.example.com:443"));
        assertFalse(parsedRuntime.allows(
                RuntimePermissions.Permission.NET, "other.example.com:443"));
    }

    @Test
    void missingOrDuplicateBuildCeilingMarkerFailsClosed() {
        IsolatePolicy policy = coarse();
        assertThrows(IllegalArgumentException.class, () ->
                RuntimePermissions.buildCeilingFromApplicationArguments(
                        new String[]{"--ores-build-permission-env-allow=SAFE"}, policy));
        assertThrows(IllegalArgumentException.class, () ->
                RuntimePermissions.buildCeilingFromApplicationArguments(
                        new String[]{
                                "--ores-build-permission-explicit=true",
                                "--ores-build-permission-explicit=true"
                        }, policy));
    }

    @Test
    void explicitlyEmptyBuildCeilingDeniesEvenBroadRuntimeGrant() {
        IsolatePolicy policy = coarse();
        String[] args = {
                "--ores-build-permission-explicit=true",
                "--ores-permission-explicit=true",
                "--ores-permission-env-allow=*"
        };
        RuntimePermissions effective = RuntimePermissions
                .fromApplicationArguments(args, policy)
                .boundedBy(RuntimePermissions.buildCeilingFromApplicationArguments(args, policy));
        assertFalse(effective.allows(RuntimePermissions.Permission.ENV, "PATH"));
        assertFalse(effective.grantsAnything(RuntimePermissions.Permission.ENV));
    }
}
