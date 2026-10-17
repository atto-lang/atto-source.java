package dev.oreslang.runtime;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Static literal-resource diagnostics; runtime mediation remains mandatory. */
final class ScopedCompileTimePermissionTest {
    private static final IsolatePolicy HOST = IsolatePolicy.developer().withCapabilities(
            IsolatePolicy.Capability.ENVIRONMENT,
            IsolatePolicy.Capability.NETWORK,
            IsolatePolicy.Capability.FILESYSTEM_READ,
            IsolatePolicy.Capability.FILESYSTEM_WRITE);

    private static void check(String source, RuntimePermissions p, PermissionCheckMode mode) {
        CapabilityChecker.check(Parser.parse(source), HOST, mode, p);
    }

    @Test
    void literalNetworkHostPortMustBeAuthorizedAtCompileTime() {
        String source = "pub fnc main(): void { network.connect(\"api.example.com\", 443); return; }";
        RuntimePermissions onlyOtherPort = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.NET, "api.example.com:80");
        assertThrows(SecurityException.class,
                () -> check(source, onlyOtherPort, PermissionCheckMode.COMPILE));
        assertDoesNotThrow(() -> check(source, onlyOtherPort, PermissionCheckMode.RUNTIME));

        RuntimePermissions correctPort = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.NET, "api.example.com:443");
        assertDoesNotThrow(() -> check(source, correctPort, PermissionCheckMode.COMPILE));
        RuntimePermissions deniedPort = correctPort.withDenied(
                RuntimePermissions.Permission.NET, "api.example.com:443");
        assertThrows(SecurityException.class,
                () -> check(source, deniedPort, PermissionCheckMode.COMPILE));
    }

    @Test
    void literalHttpOriginMatchesTheJavaRuntimeIpv4OnlyTransport() {
        String numeric = "pub fnc main(): void { http.get_text(\"https://203.0.113.7/v1\"); return; }";
        String named = "pub fnc main(): void { http.get_text(\"https://api.example.com/v1\"); return; }";
        RuntimePermissions approved = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.NET, "203.0.113.7:443");
        assertDoesNotThrow(() -> check(numeric, approved, PermissionCheckMode.COMPILE));
        assertThrows(SecurityException.class, () -> check(numeric,
                RuntimePermissions.denyAll().withAllowed(
                        RuntimePermissions.Permission.NET, "203.0.113.7:80"),
                PermissionCheckMode.COMPILE));
        assertThrows(SecurityException.class, () -> check(numeric,
                approved.withDenied(RuntimePermissions.Permission.NET, "203.0.113.7:443"),
                PermissionCheckMode.COMPILE));
        // Java HttpClient currently rejects DNS hostnames even when the
        // coarse host permission permits one: static admission must agree.
        RuntimePermissions namedGrant = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.NET, "api.example.com:443");
        assertThrows(SecurityException.class,
                () -> check(named, namedGrant, PermissionCheckMode.COMPILE));
        assertDoesNotThrow(() -> check(named, namedGrant, PermissionCheckMode.RUNTIME));
    }

    @Test
    void literalFilesystemReadsAndWritesRespectIndependentScopes() {
        String read = "pub fnc main(): void { fs.read_text(\"/safe/data.txt\"); return; }";
        String write = "pub fnc main(): void { fs.write_text(\"/safe/data.txt\", \"x\"); return; }";
        RuntimePermissions readOnly = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.READ, "/safe");
        assertDoesNotThrow(() -> check(read, readOnly, PermissionCheckMode.COMPILE));
        assertThrows(SecurityException.class,
                () -> check(write, readOnly, PermissionCheckMode.COMPILE));
        assertThrows(SecurityException.class,
                () -> check(read, readOnly.withDenied(
                        RuntimePermissions.Permission.READ, "/safe/data.txt"),
                        PermissionCheckMode.COMPILE));
    }

    @Test
    void literalFilesystemMutationCommandsCannotBypassCompileScope() {
        String mkdir = "pub fnc main(): void { fs.mkdir_all(\"/safe/new\"); return; }";
        String remove = "pub fnc main(): void { fs.remove(\"/safe/old\"); return; }";
        RuntimePermissions readOnly = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.READ, "/safe");
        assertThrows(SecurityException.class, () ->
                check(mkdir, readOnly, PermissionCheckMode.COMPILE));
        assertThrows(SecurityException.class, () ->
                check(remove, readOnly, PermissionCheckMode.COMPILE));

        RuntimePermissions write = readOnly.withAllowed(
                RuntimePermissions.Permission.WRITE, "/safe");
        assertDoesNotThrow(() -> check(mkdir, write, PermissionCheckMode.COMPILE));
        assertDoesNotThrow(() -> check(remove, write, PermissionCheckMode.COMPILE));
        assertThrows(SecurityException.class, () -> check(remove,
                write.withDenied(RuntimePermissions.Permission.WRITE, "/safe/old"),
                PermissionCheckMode.COMPILE));
    }

    @Test
    void dynamicResourcesAreNeverConsideredVerifiedByCompileTime() {
        // The compiler cannot prove the runtime value. In both modes the
        // actual native I/O operation must perform the host permission check.
        String source = "pub fnc main(): void { val String key = \"PATH\"; env.has(key); return; }";
        assertDoesNotThrow(() ->
                check(source, RuntimePermissions.denyAll(), PermissionCheckMode.COMPILE));
    }
}
