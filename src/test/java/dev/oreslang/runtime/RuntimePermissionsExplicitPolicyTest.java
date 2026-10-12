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

    @Test void malformedExplicitMarkerFailsClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> RuntimePermissions.fromApplicationArguments(
                        new String[]{"--ores-permission-explicit=false"}, powerfulPolicy()));
    }
}
