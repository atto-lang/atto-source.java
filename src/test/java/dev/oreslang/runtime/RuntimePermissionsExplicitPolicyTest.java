package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class RuntimePermissionsExplicitPolicyTest {
    @TempDir Path root;

    private IsolatePolicy powerfulPolicy() {
        return IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.FILESYSTEM_READ,
                IsolatePolicy.Capability.FILESYSTEM_WRITE,
                IsolatePolicy.Capability.NETWORK);
    }

    @Test void explicitDenyAllNeverExpandsIntoCapabilityGrants() {
        String[] arguments = RuntimePermissions.denyAll().applicationArguments();
        assertArrayEquals(new String[]{"--ores-permission-explicit=true"}, arguments);
        RuntimePermissions restored = RuntimePermissions.fromApplicationArguments(
                arguments, powerfulPolicy());
        assertFalse(restored.allows(RuntimePermissions.Permission.READ, root.toString()));
        assertFalse(restored.allows(RuntimePermissions.Permission.WRITE, root.toString()));
        assertFalse(restored.allows(RuntimePermissions.Permission.NET, "127.0.0.1:443"));
    }

    @Test void absentPolicyKeepsLegacyCompatibilityWithoutConfusingDenyAll() {
        RuntimePermissions legacy = RuntimePermissions.fromApplicationArguments(
                new String[0], powerfulPolicy());
        assertTrue(legacy.allows(RuntimePermissions.Permission.READ, root.toString()));
        assertTrue(legacy.allows(RuntimePermissions.Permission.WRITE, root.toString()));
        assertTrue(legacy.allows(RuntimePermissions.Permission.NET, "127.0.0.1:443"));
    }

    @Test void scopedPermissionsSurviveExplicitRoundTrip() {
        RuntimePermissions policy = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.READ, root.toString())
                .withAllowed(RuntimePermissions.Permission.NET, "127.0.0.1:7777");
        String[] args = policy.applicationArguments();
        assertTrue(Arrays.asList(args).contains("--ores-permission-explicit=true"));
        RuntimePermissions reconstructed = RuntimePermissions.fromApplicationArguments(
                args, powerfulPolicy());
        assertTrue(reconstructed.allows(RuntimePermissions.Permission.READ, root.resolve("file.txt").toString()));
        assertFalse(reconstructed.allows(RuntimePermissions.Permission.WRITE, root.resolve("file.txt").toString()));
        assertTrue(reconstructed.allows(RuntimePermissions.Permission.NET, "127.0.0.1:7777"));
        assertFalse(reconstructed.allows(RuntimePermissions.Permission.NET, "127.0.0.1:7778"));
    }

    @Test void deterministicScopedPermissionRoundTripsNeverWidenGrants() {
        java.util.Random random = new java.util.Random(20261011L);
        for (int index = 0; index < 100; index++) {
            RuntimePermissions policy = RuntimePermissions.denyAll();
            String net = "127.0.0.1:" + (10000 + index);
            String folder = root.resolve("scope" + index).toString();
            if (random.nextBoolean())
                policy = policy.withAllowed(RuntimePermissions.Permission.NET, net);
            if (random.nextBoolean())
                policy = policy.withAllowed(RuntimePermissions.Permission.READ, folder);
            RuntimePermissions restored = RuntimePermissions.fromApplicationArguments(
                    policy.applicationArguments(), powerfulPolicy());
            for (RuntimePermissions.Permission permission : RuntimePermissions.Permission.values()) {
                for (String resource : new String[] {
                        root.toString(), folder, net, "127.0.0.1:80"
                }) {
                    assertEquals(policy.allows(permission, resource),
                            restored.allows(permission, resource),
                            "round trip cannot widen permission at " + index
                                    + " / " + permission + " / " + resource);
                }
            }
        }
    }

    @Test void blankFilesystemAllowScopeCannotBecomeRootWideGrant() {
        for (String scope : new String[] {"", " ", "\t", "\n"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> RuntimePermissions.denyAll()
                            .withAllowed(RuntimePermissions.Permission.READ, scope));
            assertThrows(IllegalArgumentException.class,
                    () -> RuntimePermissions.denyAll()
                            .withAllowed(RuntimePermissions.Permission.WRITE, scope));
            assertThrows(IllegalArgumentException.class,
                    () -> RuntimePermissions.denyAll()
                            .withDenied(RuntimePermissions.Permission.READ, scope));
        }
        assertThrows(IllegalArgumentException.class,
                () -> RuntimePermissions.denyAll()
                        .withAllowed(RuntimePermissions.Permission.READ, (String) null));
        assertThrows(IllegalArgumentException.class,
                () -> RuntimePermissions.denyAll()
                        .withDenied(RuntimePermissions.Permission.READ, (String) null));
    }

    @Test void emptyScopesInSerializedArgumentsFailClosed() {
        for (String permission : new String[] {"read", "write", "net", "env", "run", "sys", "ffi", "import"}) {
            for (String action : new String[] {"allow", "deny"}) {
                String malformed = "--ores-permission-" + permission + "-" + action + "=";
                assertThrows(IllegalArgumentException.class,
                        () -> RuntimePermissions.fromApplicationArguments(
                                new String[]{"--ores-permission-explicit=true", malformed},
                                powerfulPolicy()), malformed);
            }
        }
    }

    @Test void emptyScopeCannotWidenAnOtherwiseScopedPolicy() {
        RuntimePermissions scoped = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.READ, root.toString());
        assertThrows(IllegalArgumentException.class,
                () -> RuntimePermissions.fromApplicationArguments(
                        new String[]{
                                "--ores-permission-explicit=true",
                                "--ores-permission-read-allow=" + root,
                                "--ores-permission-read-allow="
                        }, powerfulPolicy()));
        assertFalse(scoped.allows(RuntimePermissions.Permission.READ,
                root.resolveSibling("sibling").resolve("sensitive.txt").toString()));
    }

    @Test void explicitScopedPolicyCannotCreateMissingIsolateCapabilities() {
        IsolatePolicy narrow = IsolatePolicy.developer();
        for (String grant : new String[] {
                "--ores-permission-read-allow=" + root,
                "--ores-permission-write-allow=" + root,
                "--ores-permission-net-allow=127.0.0.1:443",
                "--ores-permission-ffi-allow=" + root
        }) {
            assertThrows(SecurityException.class,
                    () -> RuntimePermissions.fromApplicationArguments(
                            new String[]{"--ores-permission-explicit=true", grant}, narrow),
                    grant);
        }
    }

    @Test void denyOnlyPolicyRequiresNoCoarseGrant() {
        RuntimePermissions denied = RuntimePermissions.fromApplicationArguments(
                new String[]{
                        "--ores-permission-explicit=true",
                        "--ores-permission-read-deny=*",
                        "--ores-permission-net-deny=127.0.0.1:443"
                }, IsolatePolicy.developer());
        assertFalse(denied.grantsAnything(RuntimePermissions.Permission.READ));
        assertFalse(denied.grantsAnything(RuntimePermissions.Permission.NET));
        assertFalse(denied.allows(RuntimePermissions.Permission.READ, root.toString()));
    }

    @Test void malformedExplicitMarkerFailsClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> RuntimePermissions.fromApplicationArguments(
                        new String[]{"--ores-permission-explicit=false"}, powerfulPolicy()));
    }
}
