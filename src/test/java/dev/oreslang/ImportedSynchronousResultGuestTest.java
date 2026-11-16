package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * P0 issue #3054: imported synchronous source functions must expose their
 * declared object result, never a hidden Future. These tests really link and
 * execute multi-file .atto guests rather than asserting only source shape.
 */
final class ImportedSynchronousResultGuestTest {
    @TempDir Path temp;

    @BeforeEach
    void copyGuestFixtures() throws Exception {
        for (String name : new String[] {
                "decode.atto", "main-single.atto", "main-sequential.atto", "main-private.atto",
                "main-explicit-future.atto", "main-future-without-await.atto"
        }) {
            try (InputStream input = getClass().getResourceAsStream(
                    "/linked-sync-function/" + name)) {
                assertNotNull(input, "missing guest fixture " + name);
                Files.copy(input, temp.resolve(name));
            }
        }
    }

    private String run(String name) throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                temp.resolve(name),
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                Set.of(),
                Map.of(),
                stdout,
                stderr);
        return stdout.toString(StandardCharsets.UTF_8);
    }

    @Test
    void importedLoopingSynchronousFunctionReturnsConcreteObject() throws Exception {
        assertEquals("42", run("main-single.atto"),
                "a declared DecodeAttempt must not turn into an implicit Future");
    }

    @Test
    void consecutiveImportedSynchronousCallsReturnDistinctValues() throws Exception {
        assertEquals("2,41", run("main-sequential.atto"),
                "both imported source-task completions must publish the concrete result once");
    }

    @Test
    void explicitlyAsyncImportedFunctionRemainsFutureUntilAwaited() throws Exception {
        assertEquals("42", run("main-explicit-future.atto"));
    }

    @Test
    void explicitlyAsyncImportedFunctionCannotImplicitlyBecomeInt() {
        assertThrows(RuntimeException.class, () -> run("main-future-without-await.atto"),
                "imported Future must require explicit await");
    }

    @Test
    void importingUnexportedFunctionStillFailsClosed() {
        assertThrows(RuntimeException.class, () -> run("main-private.atto"),
                "runtime fix must not bypass import visibility checks");
    }
}
