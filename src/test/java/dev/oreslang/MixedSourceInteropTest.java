package dev.oreslang;

import dev.oreslang.interop.MixedSourceUnit;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class MixedSourceInteropTest {
    @TempDir Path temp;

    @Test
    void parserCanStillSplitCompilerInternalOresJavaIslands() {
        MixedSourceUnit unit = MixedSourceUnit.parse("/tmp/demo.ores", "demo.ores", """
                pub fnc main(): void {
                  stdio.println(Hashing.decorate("hello"));
                  return;
                }

                java {
                  final class Hashing {
                    public static String decorate(String value) {
                      return value + "}";
                    }
                  }
                }
                """);

        assertEquals(MixedSourceUnit.PrimaryLanguage.ORES, unit.primaryLanguage());
        assertEquals(1, unit.javaBindings().size());
        assertEquals("Hashing", unit.javaBindings().getFirst().simpleName());
        assertEquals(MixedSourceUnit.IslandKind.DECLARATION, unit.foreignIslands().getFirst().kind());
        assertFalse(unit.oresSource().contains("module demo"));
        assertDoesNotThrow(() -> Parser.parse(unit.oresSource()));
    }

    @Test
    void parserCanStillSplitCompilerInternalJavaOresIslands() {
        MixedSourceUnit unit = MixedSourceUnit.parse("/tmp/MixedDemo.java", "MixedDemo.java", """
                public final class MixedDemo {
                  static String brace() { return "}"; }

                  ores {
                    pub fnc add(int a, int b): int {
                      return a + b;
                    }
                  }
                }
                """);

        assertEquals(MixedSourceUnit.PrimaryLanguage.JAVA, unit.primaryLanguage());
        assertEquals(1, unit.foreignIslands().size());
        assertTrue(unit.javaSource().contains("public final class MixedDemo"));
        assertFalse(unit.javaSource().contains("pub fnc add"));
        assertDoesNotThrow(() -> Parser.parse(unit.oresSource()));
    }

    @Test
    void declarationIslandCannotDeclareItsOwnJavaPackage() {
        assertThrows(IllegalArgumentException.class, () -> MixedSourceUnit.parse(
                "/tmp/demo.ores", "demo.ores", """
                        pub fnc main(): void { return; }
                        java {
                          package wrong.identity;
                          public final class Helper {}
                        }
                        """));
    }

    @Test
    void declarationAndExecutionIslandScopesRemainWellFormedForInternalTooling() {
        IllegalArgumentException nestedDeclaration = assertThrows(
                IllegalArgumentException.class,
                () -> MixedSourceUnit.parse("/tmp/bad.ores", "bad.ores", """
                        pub fnc main(): void {
                          java { final class Hidden {} }
                          return;
                        }
                        """));
        assertTrue(nestedDeclaration.getMessage().contains("use do java"));

        IllegalArgumentException topLevelExecution = assertThrows(
                IllegalArgumentException.class,
                () -> MixedSourceUnit.parse("/tmp/bad2.ores", "bad2.ores", """
                        do java { System.out.println("no"); }
                        pub fnc main(): void { return; }
                        """));
        assertTrue(topLevelExecution.getMessage().contains("inside an Oreslang callable"));
    }

    @Test
    void doJavaLoweringRemainsDeterministicButGuestRunnerRejectsIt() throws Exception {
        Path source = temp.resolve("do-java.ores");
        Files.writeString(source, """
                java {
                  final class State {
                    public static void increment() {}
                  }
                }

                pub fnc main(): void {
                  do java { State.increment(); }
                  return;
                }
                """);

        MixedSourceUnit parsed = MixedSourceUnit.parse(source, Files.readString(source));
        assertEquals(2, parsed.foreignIslands().size());
        assertEquals(MixedSourceUnit.IslandKind.DECLARATION, parsed.foreignIslands().get(0).kind());
        assertEquals(MixedSourceUnit.IslandKind.EXECUTION, parsed.foreignIslands().get(1).kind());
        assertTrue(parsed.oresSource().contains(".run();"));

        SecurityException denied = assertThrows(
                SecurityException.class,
                () -> LinkedProgramRunner.run(
                        source,
                        legacyHostInteropPolicy(),
                        ExecutionProfile.serverJit(),
                        Set.of(),
                        new ByteArrayOutputStream(),
                        new ByteArrayOutputStream()));
        assertTrue(denied.getMessage().contains("guest/user source cannot contain Java/FFI source islands"));
    }

    @Test
    void guestMixedJavaIsDeniedEvenWithLegacyHostInteropCapabilities() throws Exception {
        Path source = temp.resolve("mixed.ores");
        Files.writeString(source, """
                pub fnc main(): void { return; }
                java { final class Helper {} }
                """);

        SecurityException denied = assertThrows(
                SecurityException.class,
                () -> LinkedProgramRunner.run(
                        source,
                        legacyHostInteropPolicy(),
                        ExecutionProfile.serverJit(),
                        Set.of(),
                        new ByteArrayOutputStream(),
                        new ByteArrayOutputStream()));
        assertTrue(denied.getMessage().contains("guest/user source cannot contain Java/FFI source islands"));
    }

    @Test
    void guestJavaPrimaryMixedFileIsAlsoDenied() throws Exception {
        Path source = temp.resolve("MixedDemo.java");
        Files.writeString(source, """
                public final class MixedDemo {
                  public static void main(String[] args) {}
                  ores { pub fnc helper(): void { return; } }
                }
                """);

        SecurityException denied = assertThrows(
                SecurityException.class,
                () -> LinkedProgramRunner.run(
                        source,
                        legacyHostInteropPolicy(),
                        ExecutionProfile.serverJit(),
                        Set.of(),
                        new ByteArrayOutputStream(),
                        new ByteArrayOutputStream()));
        assertTrue(denied.getMessage().contains("guest/user source cannot contain Java/FFI source islands"));
    }

    private static IsolatePolicy legacyHostInteropPolicy() {
        return IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.JAVA_INTEROP,
                IsolatePolicy.Capability.JAVA_SOURCE_INTEROP);
    }
}
