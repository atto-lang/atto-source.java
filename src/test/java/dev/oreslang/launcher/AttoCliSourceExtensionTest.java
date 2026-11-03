package dev.oreslang.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** CLI source detection must admit .atto before applying durable execution guards. */
final class AttoCliSourceExtensionTest {
    @TempDir Path temp;

    @Test
    void buildAnalysisRecognizesAttoThenRejectsUnloweredDurableWorkflow() throws Exception {
        Path path = temp.resolve("durable.atto");
        Files.writeString(path, "define workflow Checkout as run(int id) -> int { return id; } end");
        PrintStream previousErr = System.err;
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int exit;
        try (PrintStream captured = new PrintStream(errors, true, StandardCharsets.UTF_8)) {
            System.setErr(captured);
            try {
                exit = OresMain.exitStatusForArgs(new String[] {
                        "--build-analysis", path.toString()
                });
            } finally {
                System.setErr(previousErr);
            }
        }
        String diagnostic = errors.toString(StandardCharsets.UTF_8);
        assertEquals(1, exit, "compiler must not execute unlowered durable workflows");
        assertTrue(diagnostic.contains("WORKFLOW") || diagnostic.contains("DURABLE"), diagnostic);
        assertFalse(diagnostic.contains("requires an AttoLang .atto source file"), diagnostic);
    }
}
