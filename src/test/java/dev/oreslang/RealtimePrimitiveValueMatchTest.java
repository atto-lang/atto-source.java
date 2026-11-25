package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.compiler.RealtimePrimitiveCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.net.URL;
import java.net.URLClassLoader;
import static org.junit.jupiter.api.Assertions.*;

final class RealtimePrimitiveValueMatchTest {
    @TempDir Path output;

    private RealtimePrimitiveCompiler.Artifact compile(String source, int arity,
            long steps, int depth) throws Exception {
        return OresCompiler.compileRealtimePrimitiveStrictAtto(source, "choose", arity,
                new RealtimePrimitiveCompiler.Budget(steps, depth), output);
    }

    @Test
    void exhaustiveBooleanComplementLowersToPrimitiveBranches() throws Exception {
        String source = """
                fnc choose(bool flag, bool yes, bool no): bool {
                    return match flag over
                        on true -> { return yes; }
                        on false -> { ^ no; }
                    end;
                }
                """;
        var artifact = compile(source, 3, 7, 1);
        assertEquals(7L, artifact.sourceReport().maxSourceSteps());
        assertEquals(1, artifact.classfileReport().verifiedMethods());
        try (var loader = new URLClassLoader(new URL[] {output.toUri().toURL()}, null)) {
            var method = loader.loadClass(RealtimePrimitiveCompiler.CLASS_NAME)
                    .getDeclaredMethod("choose", boolean.class, boolean.class, boolean.class);
            method.setAccessible(true);
            for (boolean flag : new boolean[] {false, true})
                for (boolean yes : new boolean[] {false, true})
                    for (boolean no : new boolean[] {false, true})
                        assertEquals(flag ? yes : no, method.invoke(null, flag, yes, no));
        }
    }

    @Test
    void wildcardFallbackAndDirectSubjectCallUseOneSubject() throws Exception {
        String source = """
                fnc invert(bool x): bool { return !x; }
                fnc choose(bool flag): bool {
                    return match invert(flag) over
                        on false -> { return false; }
                        default -> { return true; }
                    end;
                }
                """;
        var artifact = compile(source, 1, 12, 2);
        assertEquals(12L, artifact.sourceReport().maxSourceSteps());
        assertEquals(2, artifact.sourceReport().maxCallDepth());
        assertEquals(2, artifact.classfileReport().verifiedMethods());
        try (var loader = new URLClassLoader(new URL[] {output.toUri().toURL()}, null)) {
            var method = loader.loadClass(RealtimePrimitiveCompiler.CLASS_NAME)
                    .getDeclaredMethod("choose", boolean.class);
            method.setAccessible(true);
            assertEquals(true, method.invoke(null, false));
            assertEquals(false, method.invoke(null, true));
        }
        assertThrows(IllegalArgumentException.class, () -> compile(source, 1, 11, 2));
        assertThrows(IllegalArgumentException.class, () -> compile(source, 1, 12, 1));
    }

    @Test
    void unselectedRecursiveBranchRemainsRejected() {
        String source = """
                fnc choose(bool flag): bool {
                    return match true over
                        on true -> { return flag; }
                        on false -> { return choose(flag); }
                    end;
                }
                """;
        assertThrows(IllegalArgumentException.class, () -> compile(source, 1, 100, 8));
    }

    @Test
    void lexicalBlockPrefixFailsClosedUntilDropLoweringIsQualified() {
        String source = """
                fnc choose(bool flag): bool {
                    return match flag over
                        on true -> { const bool answer = true; return answer; }
                        on false -> { return false; }
                    end;
                }
                """;
        var error = assertThrows(IllegalArgumentException.class, () -> compile(source, 1, 100, 8));
        assertTrue(error.getMessage().contains("primitive value match does not admit guards or block prefixes"),
                error::getMessage);
    }
}
