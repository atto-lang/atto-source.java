package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LinkedProgramRunnerCapabilityAdmissionTest {
    @TempDir Path temp;

    @Test
    void runAppliesStaticCapabilityAdmissionBeforeGuestExecution() throws Exception {
        Path main = temp.resolve("main.ores");
        Files.writeString(main, """
                pub fnc main(): void {
                    stdio.println("must-not-run");
                    process.exit(0);
                    return;
                }
                """);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SecurityException failure = assertThrows(
                SecurityException.class,
                () -> LinkedProgramRunner.run(
                        main,
                        IsolatePolicy.strictFaas(),
                        ExecutionProfile.serverJit(),
                        Set.of(),
                        Map.of(),
                        out,
                        new ByteArrayOutputStream()));

        assertTrue(failure.getMessage().contains("PROCESS_EXIT"));
        assertEquals(
                "",
                out.toString(StandardCharsets.UTF_8),
                "static admission must fail before the first guest statement executes");
    }

    @Test
    void validateUsesTheSameCheckedCapabilityAdmission() throws Exception {
        Path main = temp.resolve("validate-main.ores");
        Files.writeString(main, """
                pub fnc main(): void {
                    process.exit(0);
                    return;
                }
                """);

        SecurityException failure = assertThrows(
                SecurityException.class,
                () -> LinkedProgramRunner.validate(
                        main,
                        Map.of(),
                        IsolatePolicy.strictFaas(),
                        PermissionCheckMode.COMPILE));

        assertTrue(failure.getMessage().contains("PROCESS_EXIT"));
    }
}
