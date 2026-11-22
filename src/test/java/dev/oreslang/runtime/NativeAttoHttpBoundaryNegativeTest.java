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
    private static void expectNoOutputAndNoLinkedGuest(String fixture) throws Exception {
        Path path = Path.of("src/test/resources/stdlib-parity/" + fixture);
        assertTrue(Files.isRegularFile(path), "test fixture must exist: " + path);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertThrows(Exception.class, () -> LinkedProgramRunner.run(
                path, IsolatePolicy.developer(), ExecutionProfile.serverJit(), out, err));
        assertEquals("", out.toString(StandardCharsets.UTF_8),
                "no guest side effect may happen before rejecting an import");
    }

    @Test
    void wildcardHttpNamespaceMustNotExportPrivateHelpers() throws Exception {
        expectNoOutputAndNoLinkedGuest("http-private-negative.atto");
    }

    @Test
    void missingReservedBuiltinMustNotResolveViaFilesystem() throws Exception {
        expectNoOutputAndNoLinkedGuest("http-unregistered-negative.atto");
    }
}
