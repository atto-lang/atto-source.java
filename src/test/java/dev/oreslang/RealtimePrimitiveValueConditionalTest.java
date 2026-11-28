package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.compiler.RealtimePrimitiveCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.net.URL;
import java.net.URLClassLoader;
import static org.junit.jupiter.api.Assertions.*;

final class RealtimePrimitiveValueConditionalTest {
    @TempDir Path output;

    @Test
    void emitsAndExecutesBothPrimitiveValueArms() throws Exception {
        String source = """
                fnc choose(bool flag, bool yes, bool no): bool {
                    return if flag then return yes; else ^ no; fi;
                }
                """;
        var artifact = OresCompiler.compileRealtimePrimitiveStrictAtto(
                source, "choose", 3, new RealtimePrimitiveCompiler.Budget(5, 1), output);
        assertEquals(5L, artifact.sourceReport().maxSourceSteps());
        assertEquals(1, artifact.classfileReport().verifiedMethods());
        try (var loader = new URLClassLoader(new URL[] {output.toUri().toURL()}, null)) {
            var method = loader.loadClass(RealtimePrimitiveCompiler.CLASS_NAME)
                    .getDeclaredMethod("choose", boolean.class, boolean.class, boolean.class);
            method.setAccessible(true);
            for (boolean flag : new boolean[] {false, true}) {
                for (boolean yes : new boolean[] {false, true}) {
                    for (boolean no : new boolean[] {false, true}) {
                        assertEquals(flag ? yes : no, method.invoke(null, flag, yes, no));
                    }
                }
            }
        }
    }

    @Test
    void costIncludesWorstArmAndTransitiveCallDepth() throws Exception {
        String source = """
                fnc invert(bool x): bool { return !x; }
                fnc choose(bool flag, bool yes, bool no): bool {
                    return if flag then return invert(yes); else ^ no; fi;
                }
                """;
        var artifact = OresCompiler.compileRealtimePrimitiveStrictAtto(
                source, "choose", 3, new RealtimePrimitiveCompiler.Budget(10, 2), output);
        assertEquals(10L, artifact.sourceReport().maxSourceSteps());
        assertEquals(2, artifact.sourceReport().maxCallDepth());
        assertEquals(2, artifact.classfileReport().verifiedMethods());
        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.compileRealtimePrimitiveStrictAtto(
                        source, "choose", 3, new RealtimePrimitiveCompiler.Budget(9, 2), output));
        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.compileRealtimePrimitiveStrictAtto(
                        source, "choose", 3, new RealtimePrimitiveCompiler.Budget(10, 1), output));
    }

    @Test
    void constantConditionDoesNotExcuseUnboundedArm() {
        String source = """
                fnc choose(bool flag): bool {
                    return if true then flag else choose(flag) fi;
                }
                """;
        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.compileRealtimePrimitiveStrictAtto(
                        source, "choose", 1, new RealtimePrimitiveCompiler.Budget(100, 8), output));
    }
}
