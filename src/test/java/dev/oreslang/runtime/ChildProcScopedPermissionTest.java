package dev.oreslang.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ChildProcScopedPermissionTest {
    private static final String JAVA =
            Path.of(System.getProperty("java.home"), "bin", "java").toAbsolutePath().toString();
    private static final String CWD = Path.of("").toAbsolutePath().toString();

    private static ChildProcLaunchAdmission.PermissionGuard guard(RuntimePermissions policy) {
        return (kind, name) -> policy.require(switch (kind) {
            case RUN -> RuntimePermissions.Permission.RUN;
            case READ -> RuntimePermissions.Permission.READ;
            case ENV -> RuntimePermissions.Permission.ENV;
        }, name, "ChildProcManager");
    }

    @Test
    void wildcardRunCannotBypassReadScope() {
        RuntimePermissions permissions = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.RUN, "*");
        assertThrows(SecurityException.class, () ->
                ChildProcLaunchAdmission.admit(
                        new ChildProcLaunchAdmission.Spec(JAVA, List.of(), CWD, Map.of()),
                        guard(permissions)));
    }

    @Test
    void denyRunOverridesWildcardAllowBeforeFilesystemLookup() {
        RuntimePermissions permissions = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.RUN, "*")
                .withDenied(RuntimePermissions.Permission.RUN, "/missing-process");
        assertThrows(SecurityException.class, () ->
                ChildProcLaunchAdmission.admit(
                        new ChildProcLaunchAdmission.Spec("/missing-process", List.of(), CWD, Map.of()),
                        guard(permissions)));
    }
}
