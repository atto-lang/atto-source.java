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
        for (String header : new String[] {
                "define module Service as",
                "private define module Service as",
                "define private module Service as"
        }) {
            Files.writeString(temp.resolve("lib.ores"),
                    header + "\n  pub fnc answer(): int { return 7; }\nend\n");
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
                        header + ": " + failure.getMessage());
            }
        }
    }

    @Test
    void wildcardMayExposeOnlyExplicitlyPublicModuleAndMember() throws Exception {
        // Public spelling is order-insensitive, but still exposes only
        // explicitly public members through a wildcard namespace.
        for (String header : new String[] {
                "pub define module Service as",
                "define pub module Service as"
        }) {
            Files.writeString(temp.resolve("lib.ores"),
                    header + "\n  pub fnc answer(): int { return 7; }\n"
                            + "  fnc internal(): int { return 99; }\nend\n");
            assertEquals("7", run("""
                    import * as external from './lib';
                    pub routine main(): void {
                      stdio.stdout.write(external.Service.answer());
                      return;
                    }
                    """), header);
            assertThrows(PolyglotException.class, () -> run("""
                    import * as external from './lib';
                    pub routine main(): void {
                      stdio.stdout.write(external.Service.internal());
                      return;
                    }
                    """), header);
        }
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
