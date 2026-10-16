package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class ChildProcLaunchAdmissionTest {
    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java")
                .toAbsolutePath().toString();
    }

    private static String cwd() {
        return Path.of("").toAbsolutePath().normalize().toString();
    }

    private static ChildProcLaunchAdmission.PermissionGuard allow() {
        return (permission, resource) -> { };
    }

    @Test
    void canonicalizesExecutableWithoutImplicitEnvironmentOrPathLookup() throws Exception {
        var spec = new ChildProcLaunchAdmission.Spec(
                javaExecutable(), List.of("-version"), cwd(), Map.of());
        var plan = ChildProcLaunchAdmission.admit(spec, allow());
        assertEquals(Path.of(javaExecutable()).toRealPath(), plan.executable());
        assertEquals(plan.executable().toString(), plan.argv().getFirst());
        assertEquals("-version", plan.argv().get(1));
        assertEquals(Map.of(), plan.environment());
        assertThrows(UnsupportedOperationException.class, () -> plan.argv().add("other"));
    }

    @Test
    void deniesUnscopedLaunchBeforeFilesystemProbe() {
        AtomicInteger checked = new AtomicInteger();
        var spec = new ChildProcLaunchAdmission.Spec(
                "/not-a-real-allowed-binary", List.of(), cwd(), Map.of());
        assertThrows(SecurityException.class, () ->
                ChildProcLaunchAdmission.admit(spec, (permission, resource) -> {
                    checked.incrementAndGet();
                    throw new SecurityException("RUN is denied");
                }));
        assertEquals(1, checked.get());
    }

    @Test
    void refusesRelativeExecutableAndRelativeWorkingDirectory() {
        var relative = new ChildProcLaunchAdmission.Spec(
                "java", List.of(), cwd(), Map.of());
        assertThrows(IllegalArgumentException.class, () ->
                ChildProcLaunchAdmission.admit(relative, allow()));

        var cwdRelative = new ChildProcLaunchAdmission.Spec(
                javaExecutable(), List.of(), ".", Map.of());
        assertThrows(IllegalArgumentException.class, () ->
                ChildProcLaunchAdmission.admit(cwdRelative, allow()));
    }

    @Test
    void enforcesPermissionsForEnvironmentKeysAndWorkingDirectory() {
        var spec = new ChildProcLaunchAdmission.Spec(
                javaExecutable(), List.of(), cwd(), Map.of("SECRET_KEY", "value"));
        assertThrows(SecurityException.class, () ->
                ChildProcLaunchAdmission.admit(spec, (permission, resource) -> {
                    if (permission == ChildProcLaunchAdmission.Permission.ENV) {
                        throw new SecurityException("environment not allowed");
                    }
                }));
        assertThrows(SecurityException.class, () ->
                ChildProcLaunchAdmission.admit(spec, (permission, resource) -> {
                    if (permission == ChildProcLaunchAdmission.Permission.READ) {
                        throw new SecurityException("cwd not allowed");
                    }
                }));
    }

    @Test
    void validatesBothSymbolicLinkAndResolvedExecutable() throws Exception {
        Path dir = Files.createTempDirectory("ores-childproc-symlink-");
        try {
            Path link = dir.resolve("alias");
            try {
                Files.createSymbolicLink(link, Path.of(javaExecutable()));
            } catch (UnsupportedOperationException | java.io.IOException | SecurityException unsupported) {
                org.junit.jupiter.api.Assumptions.assumeTrue(false, "symbolic links not supported: " + unsupported);
            }
            Set<String> forbidden = Set.of(Path.of(javaExecutable()).toRealPath().toString());
            assertThrows(SecurityException.class, () ->
                    ChildProcLaunchAdmission.admit(
                            new ChildProcLaunchAdmission.Spec(link.toString(), List.of(), cwd(), Map.of()),
                            (permission, resource) -> {
                                if (permission == ChildProcLaunchAdmission.Permission.RUN
                                        && forbidden.contains(resource)) {
                                    throw new SecurityException("canonical executable denied");
                                }
                            }));
        } finally {
            Files.deleteIfExists(dir.resolve("alias"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void rejectsNulAndOversizedArguments() {
        var nul = new ChildProcLaunchAdmission.Spec(
                javaExecutable(), List.of("before" + (char) 0 + "after"), cwd(), Map.of());
        assertThrows(IllegalArgumentException.class, () ->
                ChildProcLaunchAdmission.admit(nul, allow()));
        var oversized = new ChildProcLaunchAdmission.Spec(
                javaExecutable(), List.of("x".repeat(128 * 1024)), cwd(), Map.of());
        assertThrows(IllegalArgumentException.class, () ->
                ChildProcLaunchAdmission.admit(oversized, allow()));
    }

    @Test
    void neverUsesMutableCallerCollectionsAsLaunchInputs() throws Exception {
        List<String> args = new ArrayList<>(List.of("-version"));
        var spec = new ChildProcLaunchAdmission.Spec(
                javaExecutable(), args, cwd(), Map.of());
        args.add("unexpected");
        var plan = ChildProcLaunchAdmission.admit(spec, allow());
        assertEquals(List.of(plan.executable().toString(), "-version"), plan.argv());
    }

    @Test
    void invalidEnvironmentKeyIsRejected() {
        var spec = new ChildProcLaunchAdmission.Spec(
                javaExecutable(), List.of(), cwd(), Map.of("FOO=BAR", "unsafe"));
        assertThrows(IllegalArgumentException.class, () ->
                ChildProcLaunchAdmission.admit(spec, allow()));
    }
}
