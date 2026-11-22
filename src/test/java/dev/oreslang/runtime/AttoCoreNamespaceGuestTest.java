package dev.oreslang.runtime;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Atto namespace-first source profile on Java, separate from compiler-owned
 * virtual-library registration. The untouched library source is compiled in
 * each actual guest; the harness only appends a source-local main entry.
 */
final class AttoCoreNamespaceGuestTest {
    @TempDir Path temp;

    private String compileGuest(String stem, String main) throws Exception {
        String source = Files.readString(Path.of("core", "namespace", stem + ".atto"));
        assertTrue(source.contains("use namespace " + stem + ";"));
        var parsed = Parser.parse(source);
        assertEquals(stem, parsed.namespace());
        Path guest = temp.resolve(stem + "-guest.atto");
        Files.writeString(guest, source + "\n" + main);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        var linked = LinkedProgramRunner.run(guest, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), out, err);
        assertEquals(1, linked.units().size());
        return out.toString(StandardCharsets.UTF_8).trim().replace("\r", "");
    }

    @Test
    void slice_namespace_source_compiles_and_executes() throws Exception {
        assertEquals("true\nfalse\nfalse\ntrue", compileGuest("slice", """
                pub fnc main(): void {
                  stdio.println(valid_range(0, 3, 3));
                  stdio.println(valid_range(3, 1, 3));
                  stdio.println(valid_range(-1, 1, 3));
                  stdio.println(can_split_at(3, 3));
                  return;
                }
                """));
    }

    @Test
    void iter_namespace_source_compiles_and_executes() throws Exception {
        assertEquals("1\n2\n-1", compileGuest("iter", """
                pub fnc main(): void {
                  stdio.println(next_index_or_negative_one(1, 2, 0, 3));
                  stdio.println(next_back_index_or_negative_one(1, 2, 0, 3));
                  stdio.println(next_index_or_negative_one(1, 2, 2, 3));
                  return;
                }
                """));
    }

    @Test
    void cmp_namespace_source_compiles_and_executes() throws Exception {
        assertEquals("-1\n1\n-1\n4", compileGuest("cmp", """
                pub fnc main(): void {
                  stdio.println(compare_int(-9223372036854775807, 9223372036854775807));
                  stdio.println(compare_int(9223372036854775807, -9223372036854775807));
                  stdio.println(compare_bool(false, true));
                  stdio.println(min_int(9, 4));
                  return;
                }
                """));
    }

    @Test
    void dotted_and_duplicate_namespace_headers_fail_closed() throws Exception {
        String source = Files.readString(Path.of("core", "namespace", "slice.atto"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(source.replace("use namespace slice;",
                        "use namespace atto.core.slice;")));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(source + "\nuse namespace injected;"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(source.replace("use namespace slice;",
                        "use namespace slice;\nuse namespace slice;")));
    }
}
