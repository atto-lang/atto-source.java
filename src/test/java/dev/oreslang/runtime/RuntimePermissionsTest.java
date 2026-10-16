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
    void filesystemResourceAndScopeSpacesRemainDistinct() throws Exception {
        Path directory = Files.createTempDirectory("ores-perm-space-");
        Path plain = Files.writeString(directory.resolve("private.txt"), "plain");
        Path spaced = Files.writeString(directory.resolve("private.txt "), "spaced");

        for (RuntimePermissions.Permission permission : new RuntimePermissions.Permission[] {
                RuntimePermissions.Permission.READ,
                RuntimePermissions.Permission.WRITE,
                RuntimePermissions.Permission.FFI,
                RuntimePermissions.Permission.IMPORT
        }) {
            RuntimePermissions plainOnly = RuntimePermissions.denyAll()
                    .withAllowed(permission, plain.toString());
            assertTrue(plainOnly.allows(permission, plain.toString()), permission.name());
            assertFalse(plainOnly.allows(permission, spaced.toString()),
                    "trailing filename space cannot collapse into an allowed file: " + permission);
            assertThrows(SecurityException.class,
                    () -> plainOnly.require(permission, spaced.toString(), "test.file"),
                    "native resource with trailing space must be denied: " + permission);

            RuntimePermissions spacedOnly = RuntimePermissions.denyAll()
                    .withAllowed(permission, spaced.toString());
            assertTrue(spacedOnly.allows(permission, spaced.toString()),
                    "literal trailing-space file scope must be usable: " + permission);
            assertFalse(spacedOnly.allows(permission, plain.toString()),
                    "space-padded grant cannot authorize unpadded file: " + permission);

            RuntimePermissions denySpaced = RuntimePermissions.denyAll()
                    .allowAll(permission).withDenied(permission, spaced.toString());
            assertFalse(denySpaced.allows(permission, spaced.toString()),
                    "space-padded denial must actually block its file: " + permission);
            assertTrue(denySpaced.allows(permission, plain.toString()),
                    "space-padded denial must not accidentally block other files: " + permission);
        }
    }

    @Test
    void environmentAndExecutionResourcesAreNotTrimmedIntoOtherKeys() {
        for (RuntimePermissions.Permission permission : new RuntimePermissions.Permission[] {
                RuntimePermissions.Permission.ENV,
                RuntimePermissions.Permission.RUN,
                RuntimePermissions.Permission.SYS
        }) {
            RuntimePermissions exact = RuntimePermissions.denyAll()
                    .withAllowed(permission, "PUBLIC_NAME");
            assertTrue(exact.allows(permission, "PUBLIC_NAME"));
            assertFalse(exact.allows(permission, "PUBLIC_NAME "));
            assertFalse(exact.allows(permission, " PUBLIC_NAME"));
            assertThrows(SecurityException.class,
                    () -> exact.require(permission, "PUBLIC_NAME ", "test.token"));
        }
    }

    @Test
    void networkEndpointsWithSurroundingSpacesAreNotAuthorized() {
        RuntimePermissions broad = RuntimePermissions.denyAll()
                .allowAll(RuntimePermissions.Permission.NET);
        assertTrue(broad.allows(RuntimePermissions.Permission.NET, "api.example.com:443"));
        assertFalse(broad.allows(RuntimePermissions.Permission.NET, "api.example.com:443 "));
        assertFalse(broad.allows(RuntimePermissions.Permission.NET, " api.example.com:443"));
    }

    @Test
    void csvFilesystemScopesPreserveLiteralFilenameSpaces() throws Exception {
        Path directory = Files.createTempDirectory("ores-csv-literal-");
        Path plain = Files.writeString(directory.resolve("private"), "normal");
        Path spaced = Files.writeString(directory.resolve("private "), "sensitive");
        for (RuntimePermissions.Permission permission : new RuntimePermissions.Permission[] {
                RuntimePermissions.Permission.READ,
                RuntimePermissions.Permission.WRITE,
                RuntimePermissions.Permission.FFI,
                RuntimePermissions.Permission.IMPORT
        }) {
            RuntimePermissions.Builder allowed = new RuntimePermissions.Builder();
            allowed.allowCsv(permission, spaced.toString());
            assertTrue(allowed.build().allows(permission, spaced.toString()));
            assertFalse(allowed.build().allows(permission, plain.toString()),
                    "CSV scope dropped trailing space in filename: " + permission);

            RuntimePermissions.Builder denied = new RuntimePermissions.Builder();
            denied.allowAll(permission).denyCsv(permission, spaced.toString());
            assertFalse(denied.build().allows(permission, spaced.toString()),
                    "CSV deny must use the literal path: " + permission);
            assertTrue(denied.build().allows(permission, plain.toString()));
        }
    }

    @Test
    void paddedWildcardIsRejectedBeforeChangingPermissionRules() {
        for (RuntimePermissions.Permission permission : RuntimePermissions.Permission.values()) {
            for (String padded : new String[] {"* ", " *", " * "}) {
                RuntimePermissions.Builder builder = new RuntimePermissions.Builder();
                assertThrows(IllegalArgumentException.class,
                        () -> builder.allow(permission, padded),
                        "padded allow wildcard " + permission);
                assertThrows(IllegalArgumentException.class,
                        () -> builder.deny(permission, padded),
                        "padded deny wildcard " + permission);
                assertThrows(IllegalArgumentException.class,
                        () -> builder.allowCsv(permission, "PATH," + padded),
                        "padded CSV allow wildcard " + permission);
                assertThrows(IllegalArgumentException.class,
                        () -> builder.denyCsv(permission, "PATH," + padded),
                        "padded CSV deny wildcard " + permission);
                assertFalse(builder.build().grantsAnything(permission),
                        "failed wildcard parse must not grant permissions: " + permission);
            }
        }

        RuntimePermissions.Builder staged = new RuntimePermissions.Builder();
        staged.allowCsv(RuntimePermissions.Permission.ENV, "ALREADY_ALLOWED");
        assertThrows(IllegalArgumentException.class,
                () -> staged.allowCsv(RuntimePermissions.Permission.ENV, "NEW_KEY, * "));
        assertTrue(staged.build().allows(RuntimePermissions.Permission.ENV, "ALREADY_ALLOWED"));
        assertFalse(staged.build().allows(RuntimePermissions.Permission.ENV, "NEW_KEY"));

        // Preserve intentional CSV token-list padding, without granting '*'.
        staged.allowCsv(RuntimePermissions.Permission.ENV, " SECOND_KEY , THIRD_KEY ");
        assertTrue(staged.build().allows(RuntimePermissions.Permission.ENV, "SECOND_KEY"));
        assertTrue(staged.build().allows(RuntimePermissions.Permission.ENV, "THIRD_KEY"));
        assertFalse(staged.build().allows(RuntimePermissions.Permission.ENV, "SECOND_KEY "));
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
    void actualGuestWriteCannotAliasAWhitespaceSensitiveAllowedFile() throws Exception {
        Path root = Files.createTempDirectory("ores-native-whitespace-").toRealPath();
        Path source = root.resolve("program.ores");
        Path allowed = root.resolve("allowed.txt");
        Path distinct = root.resolve("allowed.txt ");
        Files.writeString(allowed, "safe");
        Files.writeString(distinct, "intact");

        Files.writeString(source, """
                pub fnc main(): void {
                  fs.write_text("%s", "unauthorized");
                  return;
                }
                """.formatted(escape(distinct)));

        IsolatePolicy hostPolicy = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.FILESYSTEM_WRITE);
        RuntimePermissions scoped = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.WRITE, allowed.toString());

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> LinkedProgramRunner.run(
                        source,
                        hostPolicy,
                        scoped,
                        PermissionCheckMode.RUNTIME,
                        ExecutionProfile.serverJit(),
                        Set.of(),
                        System.getenv(),
                        new ByteArrayOutputStream(),
                        new ByteArrayOutputStream()));
        assertTrue(deepMessage(failure).contains("permission denied"),
                "native guest write must enforce the exact authorized filename");
        assertEquals("safe", Files.readString(allowed));
        assertEquals("intact", Files.readString(distinct));
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
