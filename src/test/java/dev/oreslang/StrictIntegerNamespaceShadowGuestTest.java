package dev.oreslang;

import dev.oreslang.parser.Parser;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Positive-witness tests for lexical and imported Integer namespace shadowing.
 * Merely rejecting an invalid call is insufficient: the same binding must
 * first be shown to work through a real linked guest program.
 * This class requires actual JUnit and Graal/Truffle execution in CI.
 */
final class StrictIntegerNamespaceShadowGuestTest {
    @TempDir Path temp;

    private Path source(String name, String body) throws Exception {
        Path entry = temp.resolve(name + ".atto");
        Files.writeString(entry, body, StandardCharsets.UTF_8);
        return entry;
    }

    private String execute(String name, String body) throws Exception {
        Path entry = source(name, body);
        assertNotNull(LinkedProgramRunner.validate(entry, Map.of()),
                "valid binding must compile: " + name);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), Set.of(), Map.of(), out, err);
        assertEquals("", err.toString(StandardCharsets.UTF_8), name + " guest stderr");
        return out.toString(StandardCharsets.UTF_8);
    }

    private void compilerRejects(String name, String body) throws Exception {
        assertNotNull(Parser.parse(body), "rejected source must be syntactically valid");
        Path entry = source(name, body);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(entry, Map.of()), name);
        assertNotNull(failure.getMessage(), "rejection needs a diagnostic");
        assertFalse(failure.getMessage().isBlank(), "rejection cannot be silent");
    }

    @Test
    void importedIntegerAliasIsUsableButCannotFallThroughToSealedIntrinsic() throws Exception {
        String valid = """
                import * as Integer from "ores.core/numeric";
                pub routine main(): void {
                  stdio.println(Integer.rem_int(-9, 4));
                  stdio.println(Integer.mod_int(-9, 4));
                  stdio.println(Integer.bit_xor_int(5, 3));
                  return;
                }
                """;
        assertEquals("-1\n3\n6\n", execute("imported-integer-positive", valid));

        String invalid = """
                import * as Integer from "ores.core/numeric";
                pub routine main(): void {
                  stdio.println(Integer.mod(-9, 4));
                  return;
                }
                """;
        compilerRejects("imported-integer-intrinsic-deny", invalid);
    }

    @Test
    void lexicalIntegerValueIsUsableButCannotFallThroughToSealedIntrinsic() throws Exception {
        String valid = """
                pub routine main(): void {
                  const Integer = 7;
                  stdio.println(Integer);
                  return;
                }
                """;
        assertEquals("7\n", execute("lexical-integer-positive", valid));

        String invalid = """
                pub routine main(): void {
                  const Integer = 7;
                  stdio.println(Integer.mod(9, 4));
                  return;
                }
                """;
        compilerRejects("lexical-integer-intrinsic-deny", invalid);
    }
}
