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
    void i32LiteralAndWildcardExecuteAtSignedBoundaries() throws Exception {
        String source = """
                fnc choose(i32 number): bool {
                    return match number over
                        on 7 -> { return true; }
                        default -> { return false; }
                    end;
                }
                """;
        var artifact = compile(source, 1, 7, 1);
        assertEquals(7L, artifact.sourceReport().maxSourceSteps());
        try (var loader = new URLClassLoader(new URL[] {output.toUri().toURL()}, null)) {
            var method = loader.loadClass(RealtimePrimitiveCompiler.CLASS_NAME)
                    .getDeclaredMethod("choose", int.class);
            method.setAccessible(true);
            for (int value : new int[] {Integer.MIN_VALUE, -1, 0, 7, 8, Integer.MAX_VALUE}) {
                assertEquals(value == 7, method.invoke(null, value));
            }
        }
    }

    @Test
    void multiArmReturnUsesSingleSubjectSlotAndBoundedForwardComparisons() throws Exception {
        String source = """
                fnc identity(i32 number): i32 { return number; }
                fnc choose(i32 number): bool {
                    return match identity(number) over
                        on 0 -> { return true; }
                        on 7 -> { return false; }
                        default -> { return true; }
                    end;
                }
                """;
        var artifact = compile(source, 1, 16, 2);
        assertEquals(16L, artifact.sourceReport().maxSourceSteps());
        assertEquals(2, artifact.sourceReport().maxCallDepth());
        assertEquals(2, artifact.classfileReport().verifiedMethods());
        String generated = java.nio.file.Files.readString(
                output.resolve(RealtimePrimitiveCompiler.CLASS_NAME + ".java"));
        assertEquals(1, generated.split("identity\\(number\\)", -1).length - 1);
        assertTrue(generated.contains("final int $matchSubject = identity(number);"));
        try (var loader = new URLClassLoader(new URL[] {output.toUri().toURL()}, null)) {
            var method = loader.loadClass(RealtimePrimitiveCompiler.CLASS_NAME)
                    .getDeclaredMethod("choose", int.class);
            method.setAccessible(true);
            for (int value : new int[] {Integer.MIN_VALUE, -1, 0, 7, 8, Integer.MAX_VALUE}) {
                assertEquals(value != 7, method.invoke(null, value));
            }
        }
        assertThrows(IllegalArgumentException.class, () -> compile(source, 1, 15, 2));
        assertThrows(IllegalArgumentException.class, () -> compile(source, 1, 16, 1));
    }

    @Test
    void multiArmExpressionsPreserveShortCircuitAndDistinctScratchSlots() throws Exception {
        String source = """
                fnc identity(i32 number): i32 { return number; }
                fnc choose(i32 number, bool gate): bool {
                    const bool hit = gate && (match identity(number) over
                        on 0 -> { return true; }
                        on 7 -> { return false; }
                        default -> { return true; }
                    end);
                    return hit || (match identity(number) over
                        on 1 -> { return true; }
                        on 8 -> { return false; }
                        default -> { return false; }
                    end);
                }
                """;
        var artifact = compile(source, 2, 100, 2);
        assertEquals(2, artifact.classfileReport().verifiedMethods());
        String generated = java.nio.file.Files.readString(
                output.resolve(RealtimePrimitiveCompiler.CLASS_NAME + ".java"));
        assertTrue(generated.contains("int $matchExpr0;"));
        assertTrue(generated.contains("int $matchExpr1;"));
        assertTrue(generated.contains("&& (($matchExpr0 = identity(number))")
                || generated.contains("&& ((($matchExpr0 = identity(number))"));
        assertTrue(generated.contains("$matchExpr1 = identity(number)"));
        try (var loader = new URLClassLoader(new URL[] {output.toUri().toURL()}, null)) {
            var method = loader.loadClass(RealtimePrimitiveCompiler.CLASS_NAME)
                    .getDeclaredMethod("choose", int.class, boolean.class);
            method.setAccessible(true);
            for (int value : new int[] {Integer.MIN_VALUE, 0, 1, 7, 8, Integer.MAX_VALUE}) {
                for (boolean gate : new boolean[] {false, true}) {
                    assertEquals((gate && value != 7) || value == 1,
                            method.invoke(null, value, gate));
                }
            }
        }
    }

    @Test
    void multiArmExpressionInDirectCallArgument() throws Exception {
        String source = """
                fnc invert(bool flag): bool { return !flag; }
                fnc choose(i32 number): bool {
                    return invert(match number over
                        on 0 -> { return true; }
                        on 7 -> { return false; }
                        default -> { return true; }
                    end);
                }
                """;
        var artifact = compile(source, 1, 100, 2);
        assertEquals(2, artifact.classfileReport().verifiedMethods());
        try (var loader = new URLClassLoader(new URL[] {output.toUri().toURL()}, null)) {
            var method = loader.loadClass(RealtimePrimitiveCompiler.CLASS_NAME)
                    .getDeclaredMethod("choose", int.class);
            method.setAccessible(true);
            assertEquals(false, method.invoke(null, 0));
            assertEquals(true, method.invoke(null, 7));
            assertEquals(false, method.invoke(null, Integer.MIN_VALUE));
        }
    }

    @Test
    void multiArmUnselectedRecursionIsNotAdmitted() {
        String source = """
                fnc choose(i32 number): bool {
                    return match number over
                        on 0 -> { return true; }
                        on 7 -> { return false; }
                        default -> { return choose(number); }
                    end;
                }
                """;
        assertThrows(IllegalArgumentException.class, () -> compile(source, 1, 100, 8));
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
