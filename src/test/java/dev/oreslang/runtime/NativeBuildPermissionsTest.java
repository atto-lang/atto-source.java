package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class NativeBuildPermissionsTest {
    @TempDir Path temp;

    @Test
    void sealedArtifactDefaultsToNoPrivilegedHostIo() {
        RuntimePermissions ceiling = NativeBuildPermissions.fromProperties(Map.of(), true);
        for (RuntimePermissions.Permission permission : RuntimePermissions.Permission.values()) {
            assertFalse(ceiling.grantsAnything(permission), permission.cliName());
        }
    }

    @Test
    void sealedArtifactAcceptsOnlyExplicitCategoryScopes() {
        RuntimePermissions ceiling = NativeBuildPermissions.fromProperties(Map.of(
                "ores.native.permission.env", "PATH",
                "ores.native.permission.net", "api.example.com:443",
                "ores.native.permission.read", temp.toAbsolutePath().toString()
        ), true);

        assertTrue(ceiling.allows(RuntimePermissions.Permission.ENV, "PATH"));
        assertFalse(ceiling.allows(RuntimePermissions.Permission.ENV, "SECRET"));
        assertTrue(ceiling.allows(RuntimePermissions.Permission.NET, "api.example.com:443"));
        assertFalse(ceiling.allows(RuntimePermissions.Permission.NET, "api.example.com:80"));
        assertTrue(ceiling.allows(RuntimePermissions.Permission.READ,
                temp.resolve("input.txt").toString()));
        assertFalse(ceiling.grantsAnything(RuntimePermissions.Permission.WRITE));
    }

    @Test
    void sealedArtifactRejectsRelativeFilesystemLikeScopes() {
        for (String key : new String[]{"read", "write", "ffi", "import"}) {
            assertThrows(IllegalArgumentException.class, () ->
                    NativeBuildPermissions.fromProperties(
                            Map.of("ores.native.permission." + key, "relative/path"), true),
                    key);
        }
    }

    @Test
    void jvmDevelopmentCeilingDoesNotReplaceRuntimeDefaultDeny() {
        RuntimePermissions ceiling =
                NativeBuildPermissions.fromProperties(Map.of(), false);
        RuntimePermissions runtime = RuntimePermissions.denyAll().boundedBy(ceiling);
        assertTrue(ceiling.allows(RuntimePermissions.Permission.ENV, "ANY"));
        assertFalse(runtime.allows(RuntimePermissions.Permission.ENV, "ANY"));
    }

    @Test
    void buildCeilingAppArgsRemainValidCoarsePolicyArguments() {
        IsolatePolicy policy = IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.ENVIRONMENT,
                IsolatePolicy.Capability.NETWORK);
        RuntimePermissions ceiling = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.ENV, "PATH");
        RuntimePermissions grant = RuntimePermissions.denyAll()
                .allowAll(RuntimePermissions.Permission.ENV);

        String[] args = policy.applicationArguments(
                ExecutionProfile.serverJit(), ceiling, grant, PermissionCheckMode.RUNTIME);
        assertEquals(policy, IsolatePolicy.fromApplicationArguments(args),
                "new build-ceiling arguments must not corrupt coarse host policy parsing");

        RuntimePermissions effective = RuntimePermissions.fromApplicationArguments(args, policy)
                .boundedBy(RuntimePermissions.buildCeilingFromApplicationArguments(args, policy));
        assertTrue(effective.allows(RuntimePermissions.Permission.ENV, "PATH"));
        assertFalse(effective.allows(RuntimePermissions.Permission.ENV, "SECRET"));
    }
}
