package dev.oreslang.runtime;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compiler-owned HTTP library imports do not widen the owner's borrow
 * capabilities. Run genuine linked Atto guest programs, not a host shim.
 */
final class NativeAttoHttpCoreBorrowBoundaryTest {
    private static final Path FIXTURES = Path.of("src/test/resources/stdlib-parity");

    private static String expectRejectedWithoutGuestOutput(String name) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Exception rejected = assertThrows(Exception.class, () -> LinkedProgramRunner.run(
                FIXTURES.resolve(name), IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), out, err));
        assertEquals("", out.toString(StandardCharsets.UTF_8),
                "borrow/type denial must occur before any imported HTTP guest output");
        StringBuilder detail = new StringBuilder();
        Throwable cursor = rejected;
        for (int depth = 0; cursor != null && depth < 12; depth++) {
            detail.append(cursor.getMessage()).append(' ');
            if (cursor == cursor.getCause()) break;
            cursor = cursor.getCause();
        }
        return detail.toString().toLowerCase(java.util.Locale.ROOT);
    }

    @Test
    void linkedHttpAcceptsCopyBoolAndTheNewAffineOwner() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        var linked = LinkedProgramRunner.run(
                FIXTURES.resolve("http-core-borrow-positive.atto"),
                IsolatePolicy.developer(), ExecutionProfile.serverJit(), out, err);
        assertTrue(linked.units().containsKey("@std/http.atto"),
                "pure policy must be loaded from the registered native .atto source");
        assertEquals("true\n42",
                out.toString(StandardCharsets.UTF_8).trim().replace("\r", ""),
                () -> err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void linkedHttpImportCannotMakeMovedClassOwnerReadableAgain() throws Exception {
        String diagnostic = expectRejectedWithoutGuestOutput("http-core-borrow-negative.atto");
        assertTrue(diagnostic.contains("original"), diagnostic);
        assertTrue(diagnostic.contains("mov") || diagnostic.contains("take"),
                "expected affine ownership denial, not an unrelated failure: " + diagnostic);
    }

    @Test
    void linkedHttpScalarCallRejectsImplicitNonCopyClassArgument() throws Exception {
        String diagnostic = expectRejectedWithoutGuestOutput(
                "http-core-noncopy-argument-negative.atto");
        assertTrue(diagnostic.contains("status_valid") || diagnostic.contains("owner"),
                diagnostic);
        assertTrue(diagnostic.contains("type") || diagnostic.contains("int")
                        || diagnostic.contains("argument") || diagnostic.contains("class"),
                "expected imported scalar ABI denial: " + diagnostic);
    }
}
