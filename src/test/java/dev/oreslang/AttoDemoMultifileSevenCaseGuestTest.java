package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import dev.oreslang.runtime.PermissionCheckMode;
import dev.oreslang.runtime.RuntimePermissions;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compiler-owned exact source-copy of oreslang-demos PR #48 at
 * 30eb32297c1a9f496d15adab6198052e884b19e0.
 *
 * No source stitching or Java host implementation substitutes for the guest.
 * Three cases must run actual linked .atto guests; four must reject before any
 * guest statement runs: three during linked-source validation and the alias
 * leak during main's load-time type check (a guest runtime failure is NOT enough).
 *
 * These are proposed compiler conformance tests until Maven/JUnit executes
 * nonzero steps at this exact compiler PR head. JIT is not Native Image AOT.
 */
final class AttoDemoMultifileSevenCaseGuestTest {
    private static final Path FIXTURES =
            Path.of("src/test/resources/atto/demo-multifile-v1");
    private static final List<String> SOURCE_PATHS = List.of(
            ".atto.pkg.toml",
            "src/math.atto",
            "src/main.atto",
            "module/contract.atto",
            "module/main.atto",
            "module/alias-leak.atto",
            "negative/missing-helper.atto",
            "negative/conflicts.atto",
            "negative/ambiguous-import.atto",
            "negative/ownership-conflict.atto",
            "negative/ownership-valid.atto");

    @TempDir Path temp;
    private Path projectRoot;

    @BeforeEach
    void installByteIdenticalSourceTree() throws IOException {
        projectRoot = temp.resolve("demo-multifile-v1");
        for (String relative : SOURCE_PATHS) {
            Path reviewedSource = FIXTURES.resolve(relative);
            assertTrue(Files.isRegularFile(reviewedSource), relative);
            assertFalse(Files.isSymbolicLink(reviewedSource), relative);
            Path destination = projectRoot.resolve(relative);
            Files.createDirectories(destination.getParent());
            Files.copy(reviewedSource, destination);
            assertEquals(-1L, Files.mismatch(reviewedSource, destination),
                    "fixture copy must preserve every source byte: " + relative);
        }
    }

    private Path entry(String relative) {
        Path path = projectRoot.resolve(relative);
        assertTrue(Files.isRegularFile(path), relative);
        return path;
    }

    private static String canonicalId(Path file) {
        return file.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private void assertExactSeparateSourceUnits(
            java.util.Map<String, ?> actualUnits, String entry, String linkedFile) {
        Set<String> expected = linkedFile == null
                ? Set.of(canonicalId(entry(entry)))
                : Set.of(canonicalId(entry(entry)), canonicalId(entry(linkedFile)));
        assertEquals(expected, actualUnits.keySet(),
                "compiler must preserve each exact .atto file as its own unit, "
                        + "never stitch, omit, duplicate or substitute linked sources");
    }

    private void acceptsAndRunsTo42(String relative, String linkedFile) throws Exception {
        Path program = entry(relative);
        // Source-graph resolution must preserve every distinct file and unit.
        var validation = LinkedProgramRunner.validate(program, Map.of());
        assertExactSeparateSourceUnits(validation.units(), relative, linkedFile);
        IsolatePolicy policy = IsolatePolicy.developer();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        LinkedProgramRunner.RunResult result = LinkedProgramRunner.runWithExitStatus(
                program,
                policy,
                RuntimePermissions.fromCapabilities(policy.capabilities()),
                PermissionCheckMode.COMPILE,
                ExecutionProfile.serverJit(),
                Set.of(),
                Map.of(),
                stdout,
                stderr);
        assertExactSeparateSourceUnits(result.build().units(), relative, linkedFile);
        assertEquals(0, result.exitStatus(), "guest exit status for " + relative);
        assertEquals("42", stdout.toString(StandardCharsets.UTF_8),
                "guest stdout must be exactly 42 for " + relative);
        assertEquals("", withoutHostEngineNotice(stderr.toString(StandardCharsets.UTF_8)),
                "guest stderr must be empty for " + relative);
    }

    /**
     * The polyglot engine may print a host-JVM notice (fallback runtime /
     * version check) on stderr before the guest starts. That is launcher
     * environment output, not guest output; everything else must be empty.
     */
    private static String withoutHostEngineNotice(String stderr) {
        return stderr
                .replaceAll("(?s)\\[To redirect Truffle log output.*?\\]\\R", "")
                .replaceAll("(?s)\\[engine\\] WARNING: The polyglot engine uses a fallback runtime.*?WarnInterpreterOnly[^\\r\\n]*(\\R|\\z)", "");
    }

    /**
     * Main type-checks the linked program when it is loaded, before any guest
     * statement runs (see AttoImportedModuleClassGuestTest leaked-alias case);
     * graph validation alone only resolves files. Require that load-time
     * denial and prove no guest output was produced.
     */
    private void rejectsAtLinkedLoadBeforeGuestOutput(String relative, String diagnostic) {
        Path program = entry(relative);
        IsolatePolicy policy = IsolatePolicy.developer();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        RuntimeException rejected = assertThrows(RuntimeException.class,
                () -> LinkedProgramRunner.runWithExitStatus(
                        program, policy,
                        RuntimePermissions.fromCapabilities(policy.capabilities()),
                        PermissionCheckMode.COMPILE, ExecutionProfile.serverJit(),
                        Set.of(), Map.of(), stdout, stderr),
                "aliased module must not expose its unaliased name: " + relative);
        assertTrue(String.valueOf(rejected.getMessage()).contains(diagnostic),
                () -> "wrong compiler denial for " + relative + ": " + rejected.getMessage());
        assertEquals("", stdout.toString(StandardCharsets.UTF_8),
                "no guest statement may run before the denial: " + relative);
    }

    private void rejectsBeforeExecution(String relative, String diagnostic) {
        Path program = entry(relative);
        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(program, Map.of()),
                "a runtime failure does NOT count as compile-time rejection: " + relative);
        String message = rejected.getMessage();
        assertTrue(message != null && message.contains(canonicalId(program)),
                () -> "negative rejection must name its exact source unit: " + message);
        assertTrue(message.contains(diagnostic),
                () -> "wrong compiler denial for " + relative + ": " + message);
        assertFalse(message.contains("Failed to execute goal")
                        || message.contains("Exception in thread")
                        || message.contains("NoClassDefFoundError"),
                "Maven/JVM infrastructure errors cannot count as guest compiler rejections");
    }

    @Test
    void linkedExtensionlessFunctionImportEmits42() throws Exception {
        acceptsAndRunsTo42("src/main.atto", "src/math.atto");
    }

    @Test
    void linkedAliasedModulePublicClassEmits42() throws Exception {
        acceptsAndRunsTo42("module/main.atto", "module/contract.atto");
    }

    @Test
    void distinctMutableOwnerLoansEmit42() throws Exception {
        acceptsAndRunsTo42("negative/ownership-valid.atto", null);
    }

    @Test
    void missingRelativeImportRejectedAtCompileTime() {
        rejectsBeforeExecution("negative/missing-helper.atto", "does not resolve to a file");
    }

    @Test
    void ambiguousExportedFunctionRejectedAtCompileTime() {
        rejectsBeforeExecution("negative/ambiguous-import.atto", "is ambiguous in");
    }

    @Test
    void doubleMutableBorrowOfOneOwnerRejectedAtCompileTime() {
        rejectsBeforeExecution("negative/ownership-conflict.atto",
                "overlapping mutable borrow arguments");
    }

    @Test
    void importedModuleAliasCannotLeakUnaliasedName() {
        rejectsAtLinkedLoadBeforeGuestOutput("module/alias-leak.atto",
                "unknown class transport_contract.Request");
    }
}
