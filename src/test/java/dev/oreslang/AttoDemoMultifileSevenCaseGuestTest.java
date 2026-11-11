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
 * Three cases must run actual linked .atto guests; four must reject during
 * compile-time linked-source validation (a runtime exception is NOT enough).
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

    private void acceptsAndRunsTo42(String relative) throws Exception {
        Path program = entry(relative);
        // Source-graph resolution is a separate gate before real guest execution.
        LinkedProgramRunner.validate(program, Map.of());
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
        assertEquals(0, result.exitStatus(), "guest exit status for " + relative);
        assertEquals("42", stdout.toString(StandardCharsets.UTF_8),
                "guest stdout must be exactly 42 for " + relative);
        assertEquals("", stderr.toString(StandardCharsets.UTF_8),
                "guest stderr must be empty for " + relative);
    }

    private void rejectsBeforeExecution(String relative, String diagnostic) {
        Path program = entry(relative);
        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(program, Map.of()),
                "a runtime failure does NOT count as compile-time rejection: " + relative);
        assertTrue(rejected.getMessage() != null
                        && rejected.getMessage().contains(diagnostic),
                () -> "wrong compiler denial for " + relative + ": " + rejected.getMessage());
    }

    @Test
    void linkedExtensionlessFunctionImportEmits42() throws Exception {
        acceptsAndRunsTo42("src/main.atto");
    }

    @Test
    void linkedAliasedModulePublicClassEmits42() throws Exception {
        acceptsAndRunsTo42("module/main.atto");
    }

    @Test
    void distinctMutableOwnerLoansEmit42() throws Exception {
        acceptsAndRunsTo42("negative/ownership-valid.atto");
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
        rejectsBeforeExecution("module/alias-leak.atto",
                "unknown class transport_contract.Request");
    }
}
