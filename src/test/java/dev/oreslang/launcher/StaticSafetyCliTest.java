package dev.oreslang.launcher;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class StaticSafetyCliTest {
    @Test
    void reportIsAdvisoryButCanFailCiOnWarnings() throws Exception {
        Path source = Files.createTempFile("ores-static-safety-", ".ores");
        Files.writeString(source, """
                pub routine main(): void {
                    loop {
                        rt cooperate;
                    }
                }
                """);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            PrintStream prior = System.out;
            int status;
            try (PrintStream captured = new PrintStream(output, true, StandardCharsets.UTF_8)) {
                System.setOut(captured);
                status = OresMain.exitStatusForArgs(new String[] {
                        "--static-safety", "--fail-on=warning", source.toString()
                });
            } finally {
                System.setOut(prior);
            }
            assertEquals(1, status);
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("ORES-TERM-002"));
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("\"proof_complete\":false"));
            assertEquals(0, OresMain.exitStatusForArgs(new String[] {
                    "--static-safety", "--fail-on=never", source.toString()
            }));
        } finally {
            Files.deleteIfExists(source);
        }
    }

    @Test
    void refuseRuntimeAndIsolateFlagsInAdvisoryAnalysisMode() throws Exception {
        Path source = Files.createTempFile("ores-static-safety-invalid-", ".ores");
        Files.writeString(source, "pub routine main(): void { return; }");
        try {
            assertEquals(2, OresMain.exitStatusForArgs(new String[] {
                    "--static-safety", "--strict-isolate", source.toString()
            }));
            assertEquals(2, OresMain.exitStatusForArgs(new String[] {
                    "--static-safety", "--allow-all", source.toString()
            }));
            assertEquals(2, OresMain.exitStatusForArgs(new String[] {
                    "--static-safety", "--fail-on=error", source.toString()
            }));
        } finally {
            Files.deleteIfExists(source);
        }
    }
}
