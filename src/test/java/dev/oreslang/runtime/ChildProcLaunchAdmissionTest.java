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
    @Test
    void rejectsMalformedUnicodeBeforeLaunching() {
        String loneHigh = String.valueOf((char) 0xd800);
        String loneLow = String.valueOf((char) 0xdc00);
        for (String malformed : List.of(loneHigh, loneLow, "prefix" + loneHigh)) {
            var spec = new ChildProcLaunchAdmission.Spec(
                    javaExecutable(), List.of(malformed), cwd(), Map.of());
            assertThrows(IllegalArgumentException.class, () ->
                    ChildProcLaunchAdmission.admit(spec, allow()));
        }
        var env = new ChildProcLaunchAdmission.Spec(
                javaExecutable(), List.of(), cwd(), Map.of("KEY", loneHigh));
        assertThrows(IllegalArgumentException.class, () ->
                ChildProcLaunchAdmission.admit(env, allow()));
    }

    @Test
    void countsUtf8SupplementaryCharactersRatherThanUtf16CodeUnits() throws Exception {
        String emoji = new String(Character.toChars(0x1f600));
        var small = new ChildProcLaunchAdmission.Spec(
                javaExecutable(), List.of(emoji.repeat(1024)), cwd(), Map.of());
        assertEquals(2, ChildProcLaunchAdmission.admit(small, allow()).argv().size());
        var excessive = new ChildProcLaunchAdmission.Spec(
                javaExecutable(), List.of(emoji.repeat(32768)), cwd(), Map.of());
        assertThrows(IllegalArgumentException.class, () ->
                ChildProcLaunchAdmission.admit(excessive, allow()));
    }

    @Test
    void boundsCollectionCountsBeforeCloning() {
        var tooManyArgs = new ChildProcLaunchAdmission.Spec[1];
        assertThrows(IllegalArgumentException.class, () ->
                tooManyArgs[0] = new ChildProcLaunchAdmission.Spec(
                        javaExecutable(), java.util.Collections.nCopies(257, "x"), cwd(), Map.of()));
        java.util.Map<String, String> env = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 129; i++) env.put("KEY_" + i, "value");
        assertThrows(IllegalArgumentException.class, () ->
                new ChildProcLaunchAdmission.Spec(javaExecutable(), List.of(), cwd(), env));
    }

    @Test
    void failsClosedForDirectoryAndNonExecutableFile() throws Exception {
        Path dir = Files.createTempDirectory("ores-childproc-invalid-");
        try {
            Path file = Files.createFile(dir.resolve("not-executable"));
            var dirSpec = new ChildProcLaunchAdmission.Spec(dir.toString(), List.of(), cwd(), Map.of());
            var fileSpec = new ChildProcLaunchAdmission.Spec(file.toString(), List.of(), cwd(), Map.of());
            assertThrows(IllegalArgumentException.class, () ->
                    ChildProcLaunchAdmission.admit(dirSpec, allow()));
            assertThrows(IllegalArgumentException.class, () ->
                    ChildProcLaunchAdmission.admit(fileSpec, allow()));
        } finally {
            Files.deleteIfExists(dir.resolve("not-executable"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void preparedProcessBuilderCannotInheritHostEnvironment() throws Exception {
        var spec = new ChildProcLaunchAdmission.Spec(
                javaExecutable(), List.of("-version"), cwd(), Map.of());
        var plan = ChildProcLaunchAdmission.admit(spec, allow());
        ProcessBuilder builder = ChildProcLaunchAdmission.newProcessBuilder(plan);
        assertEquals(Map.of(), builder.environment());
        assertEquals(plan.argv(), builder.command());
        assertEquals(plan.cwd().toFile(), builder.directory());
        assertFalse(builder.redirectErrorStream());
    }

    @Test
    void preparedProcessBuilderUsesOnlyExplicitAuthorizedEnvironmentAndPipedStdio() throws Exception {
        var spec = new ChildProcLaunchAdmission.Spec(
                javaExecutable(), List.of("-version"), cwd(), Map.of("APP_SAFE", "value"));
        var plan = ChildProcLaunchAdmission.admit(spec, allow());
        ProcessBuilder builder = ChildProcLaunchAdmission.newProcessBuilder(plan);
        assertEquals(Map.of("APP_SAFE", "value"), builder.environment());
        assertEquals(ProcessBuilder.Redirect.PIPE, builder.redirectInput());
        assertEquals(ProcessBuilder.Redirect.PIPE, builder.redirectOutput());
        assertEquals(ProcessBuilder.Redirect.PIPE, builder.redirectError());
    }

}
