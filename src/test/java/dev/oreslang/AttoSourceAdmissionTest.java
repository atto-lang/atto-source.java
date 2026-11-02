package dev.oreslang;

import dev.oreslang.interop.MixedSourceUnit;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AttoSourceAdmissionTest {
    @TempDir Path temp;

    @Test
    void attoIsNativeGuestSourceNotJavaHostSource() {
        MixedSourceUnit unit = MixedSourceUnit.parse(
                temp.resolve("cache.atto"),
                "pub fnc identity(int x): int { return x; }");
        assertEquals(MixedSourceUnit.PrimaryLanguage.ORES, unit.primaryLanguage());
        assertTrue(unit.hasOresSource());
        assertFalse(unit.hasJavaSource());
        assertTrue(unit.foreignIslands().isEmpty());
    }

    @Test
    void executesAttoGuestWithExtensionlessAttoImport() throws Exception {
        Path helper = temp.resolve("value.atto");
        Path entry = temp.resolve("main.atto");
        Files.writeString(helper, """
                pub fnc answer(): int {
                  return 42;
                }
                """);
        Files.writeString(entry, """
                import fnc answer from "./value";

                pub fnc main(): void {
                  stdio.stdout.write(answer());
                  return;
                }
                """);
        assertEquals(2, LinkedProgramRunner.validate(entry).units().size());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        var run = LinkedProgramRunner.run(
                entry, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), Set.of(), Map.of(),
                out, new ByteArrayOutputStream());
        assertEquals(2, run.units().size());
        assertEquals("42", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void explicitAttoImportNeverResolvesToLegacyOresTwin() throws Exception {
        Path helper = temp.resolve("value.atto");
        Path legacy = temp.resolve("value.ores");
        Path entry = temp.resolve("main.atto");
        Files.writeString(helper, "pub fnc answer(): int { return 42; }");
        Files.writeString(legacy, "pub fnc answer(): int { return 17; }");
        Files.writeString(entry, """
                import fnc answer from "./value.atto";
                pub fnc main(): void {
                  stdio.stdout.write(answer());
                  return;
                }
                """);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), Set.of(), Map.of(),
                out, new ByteArrayOutputStream());
        assertEquals("42", out.toString(StandardCharsets.UTF_8));
    }
}
