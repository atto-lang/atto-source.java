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

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Real multi-file Atto guest execution for Java issue #3054.
 * A synchronous imported callable must return its declared object directly,
 * not leak a SourceTask/OresFuture through the module boundary.
 */
final class ImportedSynchronousResultGuestTest {
    @TempDir Path temp;

    @Test
    void importedSynchronousLoopFunctionReturnsConcreteObject() throws Exception {
        Files.writeString(temp.resolve("parser.atto"), """
                pub define class ParseResult as
                  pub val int status;
                end

                pub fnc parse_record(int input): ParseResult {
                  let int index = 0;
                  loop do
                    if index >= 3 then break; fi
                    index = index + 1;
                  done
                  return new ParseResult(input);
                }
                """);

        Path entry = temp.resolve("main.atto");
        Files.writeString(entry, """
                import class ParseResult from "./parser.atto";
                import fnc parse_record from "./parser.atto";

                pub fnc main(): void {
                  val ParseResult one = parse_record(7);
                  stdio.stdout.write(one.status);
                  stdio.stdout.write("|");
                  val ParseResult two = parse_record(11);
                  stdio.stdout.write(two.status);
                  return;
                }
                """);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), Set.of(), Map.of(),
                output, new ByteArrayOutputStream());
        assertEquals("7|11", output.toString(StandardCharsets.UTF_8));
    }
}
