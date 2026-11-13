package dev.oreslang.runtime;

import dev.oreslang.stdlib.CoreLibrarySources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class NativeAttoCollectionCombinatorsTest {
    @TempDir Path temp;

    @Test
    void compilerOwnsNativeAttoSourceAndKeepsLegacyNumericIdentity() throws Exception {
        assertTrue(CoreLibrarySources.packagedCorePaths().contains("collections.atto"));
        assertEquals("@ores.core/collections.atto",
                CoreLibrarySources.oresCoreUnitId("ores.core/collections"));
        assertEquals(CoreLibrarySources.oresCoreUnitId("ores.core/collections"),
                CoreLibrarySources.oresCoreUnitId("ores.core/collections.atto"));
        assertEquals("@ores.core/numeric.ores",
                CoreLibrarySources.oresCoreUnitId("ores.core/numeric"));
        String code = CoreLibrarySources.oresCoreSource("ores.core/collections");
        assertTrue(code.contains("define module collections"));
        for (String kind : new String[] {"array", "vector", "list"}) {
            for (String operation : new String[] {"map", "filter", "reduce"}) {
                assertTrue(code.contains("pub fnc " + operation + "_" + kind + "<"));
            }
        }
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.oresCoreSource("ores.core/../../collections"));
    }

    @Test
    void nativeAttoMapFilterReduceExecutesInOrderForEverySequenceKind() throws Exception {
        for (String kind : new String[] {"Array", "Vector", "List"}) {
            String suffix = kind.toLowerCase(java.util.Locale.ROOT);
            // Vector is currently lowered to the same dynamic list storage as
            // Array. A new Vector<T>() intrinsic has not been introduced.
            String allocator = kind.equals("Vector") ? "Array" : kind;
            String program = """
                    import module collections from "ores.core/collections";

                    pub fnc main(): void {
                      let mut %s<int> values = new %s<int>();
                      values.add(1);
                      values.add(2);
                      values.add(3);
                      val %s<int> doubled =
                          collections.map_%s<int, int>(values,
                              |int x| -> int { return x * 2; });
                      val %s<int> selected =
                          collections.filter_%s<int>(doubled,
                              |int x| -> bool { return x > 2; });
                      val int total =
                          collections.reduce_%s<int, int>(selected, 0,
                              |int acc, int x| -> int { return acc + x; });
                      stdio.println(selected.size);
                      stdio.println(total);
                      return;
                    }
                    """.formatted(kind, allocator, kind, suffix, kind, suffix, suffix);
            Path entry = temp.resolve("functional-" + suffix + ".atto");
            Files.writeString(entry, program);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                    ExecutionProfile.serverJit(), out, err);
            assertEquals("2\n10", out.toString(StandardCharsets.UTF_8)
                            .replace("\r\n", "\n").trim(),
                    () -> kind + " failed: " + err.toString(StandardCharsets.UTF_8));
        }
    }
}
