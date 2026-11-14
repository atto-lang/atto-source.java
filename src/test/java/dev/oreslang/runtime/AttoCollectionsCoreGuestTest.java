package dev.oreslang.runtime;

import dev.oreslang.stdlib.CoreLibrarySources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class AttoCollectionsCoreGuestTest {
    @TempDir Path temp;

    @Test
    void nativeAttoArrayVectorListCombinatorsExecuteInGuest() throws Exception {
        Path entry = temp.resolve("collections-main.atto");
        Files.writeString(entry, """
                import module collections from "ores.core/collections";
                pub fnc main(): void {
                  let mut Array<int> array = new Array<int>();
                  array.add(1);
                  array.add(2);
                  array.add(3);
                  val Array<int> mapped_array = collections.map_array<int, int>(
                      array, |int n| -> n * 2);
                  val Array<int> filtered_array = collections.filter_array<int>(
                      array, |int n| -> n > 1);
                  val int reduced_array = collections.reduce_array<int, int>(
                      array, 10, |int sum, int n| -> sum + n);
                  stdio.println(mapped_array.get(1));
                  stdio.println(filtered_array.size);
                  stdio.println(reduced_array);

                  let mut Vector<int> vector = new Vector<int>();
                  vector.add(1);
                  vector.add(2);
                  vector.add(3);
                  val Vector<int> mapped_vector = collections.map_vector<int, int>(
                      vector, |int n| -> n * 2);
                  val Vector<int> filtered_vector = collections.filter_vector<int>(
                      vector, |int n| -> n > 1);
                  val int reduced_vector = collections.reduce_vector<int, int>(
                      vector, 10, |int sum, int n| -> sum + n);
                  stdio.println(mapped_vector.get(1));
                  stdio.println(filtered_vector.size);
                  stdio.println(reduced_vector);

                  let mut List<int> list = new List<int>();
                  list.add(1);
                  list.add(2);
                  list.add(3);
                  val List<int> mapped_list = collections.map_list<int, int>(
                      list, |int n| -> n * 2);
                  val List<int> filtered_list = collections.filter_list<int>(
                      list, |int n| -> n > 1);
                  val int reduced_list = collections.reduce_list<int, int>(
                      list, 10, |int sum, int n| -> sum + n);
                  stdio.println(mapped_list.get(1));
                  stdio.println(filtered_list.size);
                  stdio.println(reduced_list);
                  return;
                }
                """);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        var result = LinkedProgramRunner.run(
                entry, IsolatePolicy.developer(), ExecutionProfile.serverJit(), out, err);
        assertTrue(result.units().containsKey("@ores.core/collections.atto"),
                "the compiler must resolve the trusted .atto module");
        assertEquals("4\n2\n16\n4\n2\n16\n4\n2\n16",
                out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n").trim(),
                () -> err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void emptyInputPreservesIdentityAndDoesNotEvaluateCallbacks() throws Exception {
        Path entry = temp.resolve("empty-collections.atto");
        Files.writeString(entry, """
                import module collections from "ores.core/collections.atto";
                pub fnc main(): void {
                  val Array<int> a = new Array<int>();
                  val Vector<int> v = new Vector<int>();
                  val List<int> l = new List<int>();
                  stdio.println(collections.map_array<int, int>(a, |int x| -> x * 2).size);
                  stdio.println(collections.filter_array<int>(a, |int x| -> x > 0).size);
                  stdio.println(collections.reduce_array<int, int>(a, 42,
                      |int acc, int x| -> acc + x));
                  stdio.println(collections.map_vector<int, int>(v, |int x| -> x * 2).size);
                  stdio.println(collections.filter_vector<int>(v, |int x| -> x > 0).size);
                  stdio.println(collections.reduce_vector<int, int>(v, 42,
                      |int acc, int x| -> acc + x));
                  stdio.println(collections.map_list<int, int>(l, |int x| -> x * 2).size);
                  stdio.println(collections.filter_list<int>(l, |int x| -> x > 0).size);
                  stdio.println(collections.reduce_list<int, int>(l, 42,
                      |int acc, int x| -> acc + x));
                  return;
                }
                """);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                entry, IsolatePolicy.developer(), ExecutionProfile.serverJit(), out, err);
        assertEquals("0\n0\n42\n0\n0\n42\n0\n0\n42",
                out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n").trim(),
                () -> err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void nativeCollectionSourceIsNeverShadowedByProjectFiles() throws Exception {
        Path shadow = Files.createDirectories(temp.resolve("ores.core"));
        Files.writeString(shadow.resolve("collections.atto"), "not AttoLang");
        assertTrue(CoreLibrarySources.oresCoreSource("ores.core/collections")
                .contains("pub fnc map_array"));
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.oresCoreUnitId("ores.core/collections.ores"));
        assertEquals("@ores.core/collections.atto",
                CoreLibrarySources.oresCoreUnitId("ores.core/collections.atto"));
    }
}
