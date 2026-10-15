package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class RuntimePermissionsTest {
    @Test
    void denyRuleOverridesBroadAllow() throws Exception {
        Path root = Files.createTempDirectory("ores-perm-root-");
        Path secret = Files.createDirectories(root.resolve("secret"));

        RuntimePermissions permissions = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.READ, root.toString())
                .withDenied(RuntimePermissions.Permission.READ, secret.toString());

        assertTrue(permissions.allows(
                RuntimePermissions.Permission.READ,
                root.resolve("public.txt").toString()));
        assertFalse(permissions.allows(
                RuntimePermissions.Permission.READ,
                secret.resolve("token.txt").toString()));
    }

    @Test
    void networkHostGrantCanBeNarrowedByPortDeny() {
        RuntimePermissions permissions = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.NET, "example.com")
                .withDenied(RuntimePermissions.Permission.NET, "example.com:22");

        assertTrue(permissions.allows(RuntimePermissions.Permission.NET, "example.com:443"));
        assertFalse(permissions.allows(RuntimePermissions.Permission.NET, "example.com:22"));
        assertFalse(permissions.allows(RuntimePermissions.Permission.NET, "other.example:443"));
    }

    @Test
    void wildcardSubdomainDoesNotGrantBareParent() {
        RuntimePermissions permissions = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.NET, "*.example.com");

        assertTrue(permissions.allows(RuntimePermissions.Permission.NET, "api.example.com:443"));
        assertFalse(permissions.allows(RuntimePermissions.Permission.NET, "example.com:443"));
    }

    @Test
    void applicationArgumentsRoundTripScopedRules() throws Exception {
        Path root = Files.createTempDirectory("ores-perm-roundtrip-");
        RuntimePermissions original = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.READ, root.toString())
                .withAllowed(RuntimePermissions.Permission.ENV, "PUBLIC_*")
                .withDenied(RuntimePermissions.Permission.ENV, "PUBLIC_SECRET");

        IsolatePolicy policy = IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.FILESYSTEM_READ,
                IsolatePolicy.Capability.ENVIRONMENT);

        RuntimePermissions restored = RuntimePermissions.fromApplicationArguments(
                original.applicationArguments(),
                policy);

        assertTrue(restored.allows(
                RuntimePermissions.Permission.READ,
                root.resolve("file.txt").toString()));
        assertTrue(restored.allows(RuntimePermissions.Permission.ENV, "PUBLIC_NAME"));
        assertFalse(restored.allows(RuntimePermissions.Permission.ENV, "PUBLIC_SECRET"));
    }

    @Test
    void compileModeRejectsForbiddenIoButRuntimeModeDefersIt() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                pub fnc main(): void {
                  val text = fs.read_text("/tmp/blocked.txt");
                  return;
                }
                """));

        SecurityException compileFailure = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(
                        program,
                        IsolatePolicy.developer(),
                        PermissionCheckMode.COMPILE));
        assertTrue(compileFailure.getMessage().contains("FILESYSTEM_READ"));

        assertDoesNotThrow(() -> CapabilityChecker.check(
                program,
                IsolatePolicy.developer(),
                PermissionCheckMode.RUNTIME));
    }

    @Test
    void runtimeWriteSucceedsOnlyInsideGrantedRoot() throws Exception {
        Path root = Files.createTempDirectory("ores-native-write-").toRealPath();
        Path source = root.resolve("program.ores");
        Path allowed = root.resolve("allowed.txt");
        Path denied = Files.createTempFile("ores-native-denied-", ".txt").toRealPath();

        Files.writeString(source, """
                pub fnc main(): void {
                  fs.write_text("%s", "allowed");
                  return;
                }
                """.formatted(escape(allowed)));

        IsolatePolicy policy = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.FILESYSTEM_WRITE);
        RuntimePermissions permissions = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.WRITE, root.toString());

        LinkedProgramRunner.run(
                source,
                policy,
                permissions,
                PermissionCheckMode.RUNTIME,
                ExecutionProfile.serverJit(),
                Set.of(),
                System.getenv(),
                new ByteArrayOutputStream(),
                new ByteArrayOutputStream());

        assertEquals("allowed", Files.readString(allowed));

        Files.writeString(source, """
                pub fnc main(): void {
                  fs.write_text("%s", "blocked");
                  return;
                }
                """.formatted(escape(denied)));

        RuntimeException failure = assertThrows(
                RuntimeException.class,
                () -> LinkedProgramRunner.run(
                        source,
                        policy,
                        permissions,
                        PermissionCheckMode.RUNTIME,
                        ExecutionProfile.serverJit(),
                        Set.of(),
                        System.getenv(),
                        new ByteArrayOutputStream(),
                        new ByteArrayOutputStream()));
        assertTrue(deepMessage(failure).contains("permission denied"));
        assertNotEquals("blocked", Files.readString(denied));
    }

    @Test
    void checkModeArgumentDoesNotBecomeAPermissionGrant() {
        String[] args = {
                "--ores-permission-check=runtime", "--ores-permission-env-allow=PUBLIC_*"
        };
        // A scoped permission can narrow ENVIRONMENT when the host already
        // granted that coarse capability. Permission-check mode grants nothing.
        IsolatePolicy authorized = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.ENVIRONMENT);
        RuntimePermissions permissions = RuntimePermissions.fromApplicationArguments(
                args, authorized);
        assertTrue(permissions.allows(RuntimePermissions.Permission.ENV, "PUBLIC_NAME"));
        assertFalse(permissions.allows(RuntimePermissions.Permission.ENV, "SECRET"));
        assertFalse(permissions.allows(RuntimePermissions.Permission.WRITE, "/tmp/out"));

        // A CLI/application argument must never promote a restricted isolate.
        SecurityException denied = assertThrows(SecurityException.class,
                () -> RuntimePermissions.fromApplicationArguments(
                        args, IsolatePolicy.developer()));
        assertTrue(denied.getMessage().contains("ENVIRONMENT"), denied.getMessage());
    }

    @Test
    void nonexistentChildOfSymlinkResolvesBeforePermissionComparison() throws Exception {
        Path root = Files.createTempDirectory("ores-permission-links-");
        Path granted = Files.createDirectory(root.resolve("granted"));
        Path outside = Files.createDirectory(root.resolve("outside"));
        Path link = Files.createSymbolicLink(root.resolve("alias"), granted);
        Path escape = Files.createSymbolicLink(granted.resolve("escape"), outside);
        RuntimePermissions permissions = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.WRITE, granted.toString());
        assertTrue(permissions.allows(RuntimePermissions.Permission.WRITE, link.resolve("new.txt").toString()));
        assertFalse(permissions.allows(RuntimePermissions.Permission.WRITE, escape.resolve("new.txt").toString()));
    }

    @Test
    void deniedNetworkHostCannotBeReachedUsingEquivalentDnsOrIpv4Spelling() {
        RuntimePermissions permissions = RuntimePermissions.denyAll()
                .allowAll(RuntimePermissions.Permission.NET)
                .withDenied(RuntimePermissions.Permission.NET, "EXAMPLE.COM")
                .withDenied(RuntimePermissions.Permission.NET, "127.0.0.1");

        assertFalse(permissions.allows(RuntimePermissions.Permission.NET, "example.com.:443"));
        assertFalse(permissions.allows(RuntimePermissions.Permission.NET, "EXAMPLE.COM:80"));
        assertFalse(permissions.allows(RuntimePermissions.Permission.NET, "127.1:80"));
        assertFalse(permissions.allows(RuntimePermissions.Permission.NET, "127.000.000.001:80"));
        assertTrue(permissions.allows(RuntimePermissions.Permission.NET, "other.example.com:443"));
    }

    @Test
    void scopedIpv6PortAndWildcardNetworkPermissionsAreConsistent() {
        RuntimePermissions permissions = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.NET, "[::1]")
                .withAllowed(RuntimePermissions.Permission.NET, "*.example.com:443")
                .withDenied(RuntimePermissions.Permission.NET, "[0:0:0:0:0:0:0:1]:22");

        assertTrue(permissions.allows(RuntimePermissions.Permission.NET, "[::1]:443"));
        assertFalse(permissions.allows(RuntimePermissions.Permission.NET, "[::1]:22"));
        assertFalse(permissions.allows(RuntimePermissions.Permission.NET, "[::2]:443"));
        assertTrue(permissions.allows(RuntimePermissions.Permission.NET, "api.example.com:443"));
        assertFalse(permissions.allows(RuntimePermissions.Permission.NET, "api.example.com:80"));
        assertFalse(permissions.allows(RuntimePermissions.Permission.NET, "example.com:443"));
        assertEquals(RuntimePermissions.networkEndpoint("::1", 443),
                RuntimePermissions.networkEndpoint("[0:0:0:0:0:0:0:1]", 443));
        assertEquals("example.com:0", RuntimePermissions.networkEndpoint("EXAMPLE.COM.", 0));
    }

    @Test
    void malformedNetworkScopesAndResourcesFailClosed() {
        for (String invalid : new String[]{
                "http://example.com", "example.com:xyz", "example.com:65536",
                "example.com:", "[::gg]:443", "::1:443", "example.com:443/path"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> RuntimePermissions.denyAll()
                            .withAllowed(RuntimePermissions.Permission.NET, invalid), invalid);
            assertFalse(RuntimePermissions.denyAll()
                    .allowAll(RuntimePermissions.Permission.NET)
                    .allows(RuntimePermissions.Permission.NET, invalid), invalid);
        }
        assertFalse(RuntimePermissions.denyAll()
                .allowAll(RuntimePermissions.Permission.NET)
                .allows(RuntimePermissions.Permission.NET, null));
    }

    @Test
    void commaSeparatedPermissionScopesNeverDiscardEmptyDenials() {
        for (RuntimePermissions.Permission permission : RuntimePermissions.Permission.values()) {
            for (String malformed : new String[]{",", "alpha,", ",alpha", "alpha, ,beta", " "}) {
                RuntimePermissions.Builder builder = new RuntimePermissions.Builder();
                assertThrows(IllegalArgumentException.class,
                        () -> builder.allowCsv(permission, malformed), permission + " / " + malformed);
                assertThrows(IllegalArgumentException.class,
                        () -> builder.denyCsv(permission, malformed), permission + " / " + malformed);
                assertFalse(builder.build().grantsAnything(permission),
                        "failed parsing cannot partially grant " + permission);
            }
        }
    }

    @Test
    void serializedPermissionArgumentsRequireUniqueExplicitMarkerAndRules() {
        String marker = "--ores-permission-explicit=true";
        String grant = "--ores-permission-env-allow=PATH";
        String deny = "--ores-permission-env-deny=SECRET";
        IsolatePolicy host = IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.ENVIRONMENT);

        assertThrows(IllegalArgumentException.class,
                () -> RuntimePermissions.fromApplicationArguments(new String[]{grant}, host),
                "unmarked serialized grant must not be accepted");
        assertThrows(IllegalArgumentException.class,
                () -> RuntimePermissions.fromApplicationArguments(
                        new String[]{marker, marker, grant}, host),
                "duplicate explicit marker is ambiguous");
        assertThrows(IllegalArgumentException.class,
                () -> RuntimePermissions.fromApplicationArguments(
                        new String[]{marker, grant, grant}, host),
                "duplicate serialized allow rule is ambiguous");
        assertThrows(IllegalArgumentException.class,
                () -> RuntimePermissions.fromApplicationArguments(
                        new String[]{marker, deny, deny}, host),
                "duplicate serialized deny rule is ambiguous");

        RuntimePermissions scoped = RuntimePermissions.fromApplicationArguments(
                new String[]{marker, grant, deny}, host);
        assertTrue(scoped.allows(RuntimePermissions.Permission.ENV, "PATH"));
        assertFalse(scoped.allows(RuntimePermissions.Permission.ENV, "SECRET"));
    }

    private static String escape(Path path) {
        return path.toString().replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String deepMessage(Throwable error) {
        StringBuilder message = new StringBuilder();
        Throwable current = error;
        while (current != null) {
            if (current.getMessage() != null) message.append(current.getMessage()).append(' ');
            current = current.getCause();
        }
        return message.toString();
    }
}
