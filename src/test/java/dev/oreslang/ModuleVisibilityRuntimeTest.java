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

    @Test
    void privateModuleFacadePassedThroughAnyDoesNotGrantCrossFileMemberAccess() throws Exception {
        Files.writeString(temp.resolve("lib.ores"), """
                define private module Internal as
                  pub fnc answer(): int { return 123; }
                end
                pub fnc expose(): Any { return Internal; }
                """);

        String main = """
                import fnc expose from './lib';
                pub routine main(): void {
                  val Any leaked = expose();
                  stdio.stdout.write(leaked.answer());
                  return;
                }
                """;
        // If module references are rejected as first-class values at the
        // typechecker, that is secure too. If they are admitted through Any,
        // the runtime foreign-module facade check must reject the call.
        RuntimeException failure = assertThrows(RuntimeException.class, () -> run(main));
        String message = String.valueOf(failure.getMessage());
        assertTrue(message.contains("private module")
                        || message.contains("module") && message.contains("value")
                        || message.contains("unknown") && message.contains("answer"),
                message);
    }

    @Test
    void moduleFacadeDisplayCannotSerializePrivateSourceMetadata() throws Exception {
        Files.writeString(temp.resolve("lib.ores"), """
                define module Internal as
                  pub fnc SECRET_IMPL_SENTINEL(): int { return 77; }
                end
                pub fnc expose(): Any { return Internal; }
                """);
        String source = """
                import fnc expose from './lib';
                pub routine main(): void {
                  stdio.stdout.write(expose());
                  return;
                }
                """;
        // The current compatibility runtime can represent module receiver
        // facades; their Java record toString must never print the AST.
        try {
            String output = run(source);
            assertEquals("<module>", output);
            assertFalse(output.contains("SECRET_IMPL_SENTINEL"));
            assertFalse(output.contains("ModuleDecl"));
        } catch (RuntimeException denied) {
            // Future compilers may statically forbid returning a module as
            // an ordinary value. That is an equally safe outcome.
            assertTrue(String.valueOf(denied.getMessage()).contains("module"),
                    denied.getMessage());
        }
    }

    @Test
    void publicModuleFacadeStillSupportsAccessAcrossSourceUnits() throws Exception {
        Files.writeString(temp.resolve("lib.ores"), """
                define pub module Exposed as
                  pub fnc answer(): int { return 13; }
                end
                """);
        assertEquals("13", run("""
                import module Exposed from './lib';
                pub routine main(): void {
                  stdio.stdout.write(Exposed.answer());
                  return;
                }
                """));
    }

}
