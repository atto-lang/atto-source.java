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
    void dynamicResourcesAreNeverConsideredVerifiedByCompileTime() {
        // The compiler cannot prove the runtime value. In both modes the
        // actual native I/O operation must perform the host permission check.
        String source = "pub fnc main(): void { val String key = \"PATH\"; env.has(key); return; }";
        assertDoesNotThrow(() ->
                check(source, RuntimePermissions.denyAll(), PermissionCheckMode.COMPILE));
    }
}
