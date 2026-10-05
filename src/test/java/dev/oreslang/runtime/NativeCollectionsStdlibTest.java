package dev.oreslang.runtime;

import dev.oreslang.compiler.IncrementalCompiler;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NativeCollectionsStdlibTest {

    @Test
    void collectionSemanticsExecuteFromBundledOreslangSource() throws Exception {
        Path source = Files.createTempFile("ores-native-collections-", ".ores");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        try {
            Files.writeString(source, """
                    import module collections from "std/collections";

                    pub routine main(): void {
                      let values = new collections.ArrayList<int>();
                      values.add(10);
                      values.add(20);
                      values.push(30);

                      stdio.println(values.size());
                      stdio.println(values.contains(20));
                      stdio.println(values.index_of(30));
                      stdio.println(values.pop().unwrap());
                      stdio.println(values.first().unwrap());

                      let table = new collections.Map<string, int>();
                      stdio.println(table.put("answer", 41).is_none());
                      stdio.println(table.put("answer", 42).unwrap());
                      stdio.println(table.get("answer").unwrap());
                      stdio.println(table.contains_key("answer"));
                      stdio.println(table.remove("answer").unwrap());
                      stdio.println(table.is_empty());

                      let unique = new collections.Set<int>();
                      stdio.println(unique.add(7));
                      stdio.println(unique.add(7));
                      stdio.println(unique.size());
                      return;
                    }
                    """);

            IncrementalCompiler.BuildResult build = assertDoesNotThrow(
                    () -> LinkedProgramRunner.run(
                            source,
                            IsolatePolicy.developer(),
                            ExecutionProfile.serverJit(),
                            out,
                            err));

            assertTrue(build.units().containsKey("std/collections.ores"));

            String output = out.toString(StandardCharsets.UTF_8);
            String errors = err.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("3"), () -> "expected list size, stderr=" + errors);
            assertTrue(output.contains("true"), () -> "expected collection truth results, stderr=" + errors);
            assertTrue(output.contains("30"), () -> "expected list pop result, stderr=" + errors);
            assertTrue(output.contains("42"), () -> "expected map replacement result, stderr=" + errors);
            assertTrue(output.contains("false"), () -> "expected set duplicate result, stderr=" + errors);
        } finally {
            Files.deleteIfExists(source);
        }
    }
}
