package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
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

final class AttoPortableBoundsGuestTest {
    @TempDir Path temp;

    private String run(String fixture) throws Exception {
        // Exercise the real top-level AttoLang standard-library source, not a
        // separately maintained Java copy or a host-only reference model.
        Files.copy(Path.of("stdlib", "bounds.atto"), temp.resolve("bounds.atto"));
        try (InputStream input = getClass().getResourceAsStream("/" + fixture)) {
            assertNotNull(input, "missing linked guest fixture " + fixture);
            Files.copy(input, temp.resolve("main.atto"));
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                temp.resolve("main.atto"),
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                Set.of(),
                Map.of(),
                output,
                new ByteArrayOutputStream());
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    void canonicalAttoBoundsHelpersExecuteWithOverflowSafeLimits() throws Exception {
        assertEquals("checked-bounds-ok", run("portable-bounds-main.atto"));
    }

    @Test
    void canonicalAttoBoundsRejectsIncorrectTypeAtImportCall() {
        assertThrows(RuntimeException.class, () -> run("portable-bounds-invalid.atto"));
    }
}
