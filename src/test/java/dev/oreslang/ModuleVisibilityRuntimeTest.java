package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Runtime negative tests for import * and direct module facades. */
final class ModuleVisibilityRuntimeTest {
    @TempDir Path temp;

    private String run(String mainSource) throws Exception {
        Path main = temp.resolve("main.ores");
        Files.writeString(main, mainSource);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        LinkedProgramRunner.run(main,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                Set.of(),
                Map.of(),
                stdout,
                new ByteArrayOutputStream());
        return stdout.toString(StandardCharsets.UTF_8);
    }

    @Test
    void wildcardCannotExposePrivateModuleOrItsPublicMembers() throws Exception {
        Files.writeString(temp.resolve("lib.ores"), """
                define module Service as
                  pub fnc answer(): int { return 7; }
                end
                """);

        for (String expression : new String[] {
                "external.Service.answer()",
                "external.answer()"
        }) {
            String source = "import * as external from './lib';\n"
                    + "pub routine main(): void {\n"
                    + "  stdio.stdout.write(" + expression + ");\n"
                    + "  return;\n"
                    + "}\n";
            PolyglotException failure = assertThrows(
                    PolyglotException.class, () -> run(source), expression);
            assertTrue(failure.getMessage().contains("does not export"),
                    failure.getMessage());
        }
    }

    @Test
    void wildcardMayExposeOnlyExplicitlyPublicModuleAndMember() throws Exception {
        Files.writeString(temp.resolve("lib.ores"), """
                pub define module Service as
                  pub fnc answer(): int { return 7; }
                  fnc internal(): int { return 99; }
                end
                """);
        assertEquals("7", run("""
                import * as external from './lib';
                pub routine main(): void {
                  stdio.stdout.write(external.Service.answer());
                  return;
                }
                """));
        assertThrows(PolyglotException.class, () -> run("""
                import * as external from './lib';
                pub routine main(): void {
                  stdio.stdout.write(external.Service.internal());
                  return;
                }
                """));
    }

    @Test
    void wildcardStillExportsPubFileRootCallable() throws Exception {
        Files.writeString(temp.resolve("lib.ores"),
                "pub fnc answer(): int { return 5; }");
        assertEquals("5", run("""
                import * as external from './lib';
                pub routine main(): void {
                  stdio.stdout.write(external.answer());
                  return;
                }
                """));
    }
}
