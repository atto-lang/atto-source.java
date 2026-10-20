package dev.oreslang.launcher;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

final class StaticFactsCliTest {
    @TempDir Path temp;

    @Test
    void commandExportsCompilerProducedFactsFromRealFileWithoutExecutingIt() throws Exception {
        Path source = temp.resolve("program.ores");
        Files.writeString(source, """
                pub routine main(): int { return 42; }
                """);
        PrintStream previous = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            assertEquals(0, OresMain.exitStatusForArgs(new String[] {
                    "--static-facts", source.toString() }));
        } finally {
            System.setOut(previous);
        }
        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.startsWith("{\"version\":\"ores-static-facts/v0\""), output);
        assertTrue(output.contains("\"__root__.main/0\""), output);
        assertTrue(output.contains("\"permission_checks\":[]"), output);
    }

    @Test
    void advisoryFactsCannotPretendToEnforceIsolatePolicy() throws Exception {
        Path source = temp.resolve("program.ores");
        Files.writeString(source, "pub routine main(): int { return 42; }");
        assertEquals(2, OresMain.exitStatusForArgs(new String[] {
                "--static-facts", "--strict-isolate", source.toString() }));
        assertEquals(2, OresMain.exitStatusForArgs(new String[] {
                "--static-facts", "--allow-ffi", source.toString() }));
        assertEquals(2, OresMain.exitStatusForArgs(new String[] {
                "--static-facts", "--mode=aot", source.toString() }));
        assertEquals(2, OresMain.exitStatusForArgs(new String[] {
                "--static-facts", "--platform=server", source.toString() }));
    }

    @Test
    void sourceExecutionAndAnalysisModesCannotBeMixed() throws Exception {
        Path source = temp.resolve("program.ores");
        Files.writeString(source, "pub routine main(): int { return 42; }");
        assertEquals(2, OresMain.exitStatusForArgs(new String[] {
                "--static-facts", "--check", source.toString() }));
    }
}
