package dev.oreslang.runtime;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Release-gate negatives. Passing the successful 136-case HTTP guest fixture
 * is also required: rejecting all imports is never considered a green result.
 */
final class NativeAttoHttpBoundaryNegativeTest {
    private static String expectNoOutputAndNoLinkedGuest(String fixture) throws Exception {
        Path path = Path.of("src/test/resources/stdlib-parity/" + fixture);
        assertTrue(Files.isRegularFile(path), "test fixture must exist: " + path);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Exception failure = assertThrows(Exception.class, () -> LinkedProgramRunner.run(
                path, IsolatePolicy.developer(), ExecutionProfile.serverJit(), out, err));
        assertEquals("", out.toString(StandardCharsets.UTF_8),
                "no guest side effect may happen before rejecting an import");

        // Distinguish a genuine visibility/import failure from an unrelated
        // parser, compiler or runtime crash. Generic assertThrows would make
        // a broken build produce a misleading green negative gate.
        StringBuilder diagnostics = new StringBuilder();
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 12; depth++) {
            diagnostics.append(current.getMessage()).append(' ');
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return diagnostics.toString();
    }

    @Test
    void wildcardHttpNamespaceMustNotExportPrivateHelpers() throws Exception {
        String diagnostic = expectNoOutputAndNoLinkedGuest("http-private-negative.atto");
        assertTrue(diagnostic.contains("is_token_char"), diagnostic);
        assertTrue(diagnostic.contains("export") || diagnostic.contains("private")
                        || diagnostic.contains("public") || diagnostic.contains("unresolved")
                        || diagnostic.contains("unknown member"),
                "expected a private-export denial, not an unrelated exception: " + diagnostic);
    }

    @Test
    void wildcardNamespaceMustNotExposePrivateModuleFacade() throws Exception {
        String diagnostic = expectNoOutputAndNoLinkedGuest(
                "http-private-module-namespace-negative.atto");
        assertTrue(diagnostic.contains("internal"), diagnostic);
        assertTrue(diagnostic.contains("export") || diagnostic.contains("private"),
                "expected private module denial, not an unrelated failure: " + diagnostic);
    }

    @Test
    void namedFunctionImportCannotReachPublicMemberOfPrivateModule() throws Exception {
        String diagnostic = expectNoOutputAndNoLinkedGuest(
                "http-private-module-member-negative.atto");
        assertTrue(diagnostic.contains("leak"), diagnostic);
        assertTrue(diagnostic.contains("export") || diagnostic.contains("private"),
                "expected private member denial, not an unrelated failure: " + diagnostic);
    }

    @Test
    void missingReservedBuiltinMustNotResolveViaFilesystem() throws Exception {
        String diagnostic = expectNoOutputAndNoLinkedGuest("http-unregistered-negative.atto");
        assertTrue(diagnostic.contains("std.unregistered"), diagnostic);
        assertTrue(diagnostic.contains("reserved") || diagnostic.contains("unregistered"),
                "expected reserved-library rejection, not an unrelated exception: " + diagnostic);
    }
}
