package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
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

/** Construction must not bypass private fields or a private constructor. */
final class ImportedConstructorPrivacyTest {
    @TempDir Path temporary;

    private static final String SYNTHETIC_LIBRARY = """
            define pub class Vault as
              int secret: 7
              pub int visible: 3
              pub read(): int {
                return self.secret;
              }
            end
            """;

    @Test
    void typeCheckerRejectsExternalPositionalInitializationOfPrivateFields() {
        for (String spelling : new String[] {
                "import class Vault from './vault';",
                "import class Vault from './vault.ores';"
        }) {
            IncrementalCompiler compiler = new IncrementalCompiler();
            var files = Map.of(
                    "pkg/vault.ores", SYNTHETIC_LIBRARY,
                    "pkg/main.ores", spelling + """
                    
                    pub fnc main(): int {
                      val Vault vault = new Vault(99);
                      return vault.read();
                    }
                    """);
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class, () -> compiler.compile(files));
            assertTrue(error.getMessage().contains("private synthesized constructor field"),
                    error.getMessage());
        }
    }

    @Test
    void defaultInitializedPrivateFieldsAreStillUsableByPublicMethods() throws Exception {
        Path library = temporary.resolve("vault.ores");
        Path main = temporary.resolve("main.ores");
        Files.writeString(library, SYNTHETIC_LIBRARY);
        Files.writeString(main, """
                import class Vault from './vault';
                pub routine main(): void {
                  val Vault vault = new Vault();
                  stdio.stdout.write(vault.read());
                  return;
                }
                """);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LinkedProgramRunner.run(main, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), Set.of(), Map.of(),
                output, new ByteArrayOutputStream());
        assertEquals("7", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void importedExplicitPrivateConstructorIsRejectedAtCompileTime() {
        String library = """
                define pub class Vault as
                  int secret: 7
                  constructor() {
                  }
                end
                """;
        String importer = """
                import class Vault from './vault';
                pub fnc main(): void {
                  val Vault vault = new Vault();
                  return;
                }
                """;
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(
                        Map.of("pkg/vault.ores", library, "pkg/main.ores", importer)));
        assertTrue(error.getMessage().contains("private constructor"), error.getMessage());
    }

    @Test
    void sameFileSynthesizedPrivateFieldInitializationRemainsPossible() {
        assertDoesNotThrow(() -> new IncrementalCompiler().compile(Map.of(
                "pkg/main.ores", """
                define pub class Vault as
                  int secret: 7
                  pub read(): int { return self.secret; }
                end
                pub fnc main(): int {
                  val Vault vault = new Vault(99);
                  return vault.read();
                }
                """)));
    }
}
