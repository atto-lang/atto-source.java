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

    @Test
    void functionalSequenceOperatorsAreEagerTypePreservingAndNonMutating() throws Exception {
        Path source = Files.createTempFile("ores-native-functional-collections-", ".ores");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        try {
            Files.writeString(source, """
                    import module collections from "std/collections";

                    fnc twice(int value): int {
                      return value * 2;
                    }

                    fnc is_even(int value): bool {
                      return value % 2 == 0;
                    }

                    fnc add_ints(int total, int value): int {
                      return total + value;
                    }

                    fnc add_float(float total, int value): float {
                      return total + value;
                    }

                    pub routine main(): void {
                      let list = new collections.List<int>();
                      list.add(1);
                      list.add(2);
                      list.add(3);
                      val collections.List<int> list_mapped = list.map(twice);
                      val collections.List<int> list_filtered = list.filter(is_even);
                      stdio.println(list.size());
                      stdio.println(list_mapped.get(2));
                      stdio.println(list_filtered.size());
                      stdio.println(list_filtered.get(0));
                      stdio.println(list.reduce(0, add_ints));
                      stdio.println(list.reduce(add_ints).unwrap());

                      let array_list = new collections.ArrayList<int>();
                      array_list.add(4);
                      array_list.add(5);
                      val collections.ArrayList<int> array_mapped = array_list.map(twice);
                      val collections.ArrayList<int> array_filtered = array_list.filter(is_even);
                      stdio.println(array_list.size());
                      stdio.println(array_mapped.get(1));
                      stdio.println(array_filtered.get(0));
                      stdio.println(array_list.reduce(0.5, add_float));

                      let vector = new collections.Vector<int>();
                      vector.add(6);
                      vector.add(7);
                      val collections.Vector<bool> vector_mapped = vector.map(is_even);
                      val collections.Vector<int> vector_filtered = vector.filter(is_even);
                      stdio.println(vector.size());
                      stdio.println(vector_mapped.get(0));
                      stdio.println(vector_filtered.get(0));
                      stdio.println(vector.reduce(add_ints).unwrap());

                      let empty = new collections.Vector<int>();
                      stdio.println(empty.reduce(add_ints).is_none());
                      return;
                    }
                    """);

            assertDoesNotThrow(() -> LinkedProgramRunner.run(
                    source,
                    IsolatePolicy.developer(),
                    ExecutionProfile.serverJit(),
                    out,
                    err));

            String errors = err.toString(StandardCharsets.UTF_8);
            String output = out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");

            assertTrue(output.contains("3\n6\n1\n2\n6\n6\n"),
                    () -> "List map/filter/reduce failed or mutated source, stdout=" + output + ", stderr=" + errors);
            assertTrue(output.contains("2\n10\n4\n9.5\n"),
                    () -> "ArrayList map/filter/reduce failed, stdout=" + output + ", stderr=" + errors);
            assertTrue(output.contains("2\ntrue\n6\n13\ntrue\n"),
                    () -> "Vector map/filter/reduce failed, stdout=" + output + ", stderr=" + errors);
        } finally {
            Files.deleteIfExists(source);
        }
    }

}
