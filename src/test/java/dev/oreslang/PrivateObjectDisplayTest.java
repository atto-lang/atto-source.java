package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Implicit stringification must not grant private field access. */
final class PrivateObjectDisplayTest {
    @TempDir Path temp;

    @Test
    void defaultDisplayRedactsDirectAndInheritedPrivateFields() throws Exception {
        Path main = temp.resolve("main.ores");
        Files.writeString(main, """
                define class Base as
                  String inherited_secret: "DO-NOT-PRINT-INHERITED"
                end

                define class Vault extends Base as
                  String direct_secret: "DO-NOT-PRINT-DIRECT"
                  pub String label: "safe-label"
                end

                pub routine main(): void {
                  val Vault vault = new Vault();
                  stdio.stdout.write(vault);
                  return;
                }
                """);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LinkedProgramRunner.run(main, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), Set.of(), Map.of(),
                output, new ByteArrayOutputStream());
        String display = output.toString(StandardCharsets.UTF_8);
        assertTrue(display.contains("safe-label"), display);
        assertFalse(display.contains("DO-NOT-PRINT-INHERITED"), display);
        assertFalse(display.contains("DO-NOT-PRINT-DIRECT"), display);
        assertFalse(display.contains("inherited_secret"), display);
        assertFalse(display.contains("direct_secret"), display);
    }
}
