package dev.oreslang;

import dev.oreslang.interop.MixedSourceUnit;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class MixedSourceInteropTest {
    @TempDir Path temp;

    @Test
    void splitsOresJavaIslandsWithoutInventingAnOresModule() {
        MixedSourceUnit unit = MixedSourceUnit.parse("/tmp/demo.ores", "demo.ores", """
                pub fnc main() => void {
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
        assertFalse(unit.oresSource().contains("module demo"));
        assertDoesNotThrow(() -> Parser.parse(unit.oresSource()));
    }

    @Test
    void splitsJavaOresIslandsWhileIgnoringBracesInsideJavaStrings() {
        MixedSourceUnit unit = MixedSourceUnit.parse("/tmp/MixedDemo.java", "MixedDemo.java", """
                public final class MixedDemo {
                  static String brace() { return "}"; }

                  ores {
                    pub fnc add(int a, int b) => int {
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
    void oresJavaIslandCannotDeclareItsOwnPackage() {
        assertThrows(IllegalArgumentException.class, () -> MixedSourceUnit.parse(
                "/tmp/demo.ores", "demo.ores", """
                        pub fnc main() => void { return; }
                        java {
                          package wrong.identity;
                          public final class Helper {}
                        }
                        """));
    }

    @Test
    void doJavaIsAnExecutionIslandAndRewritesToAStatementCall() {
        MixedSourceUnit unit = MixedSourceUnit.parse("/tmp/run-java.ores", "run-java.ores", """
                pub fnc main() => void {
                  do java {
                    Ores.ping();
                  }
                  return;
                }

                pub fnc ping() => void {
                  return;
                }
                """);

        assertEquals(1, unit.foreignIslands().size());
        assertEquals(MixedSourceUnit.IslandKind.EXECUTION, unit.foreignIslands().getFirst().kind());
        assertEquals("OresJavaExec0", unit.javaBindings().getFirst().simpleName());
        assertTrue(unit.oresSource().contains("OresJavaExec0.run();"));
        assertTrue(unit.javaSources().getFirst().sourceText().contains("public static void run()"));
        assertDoesNotThrow(() -> Parser.parse(unit.oresSource()));
    }

    @Test
    void ambiguousConditionalDoJavaIsRejected() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> MixedSourceUnit.parse("/tmp/ambiguous.ores", "ambiguous.ores", """
                        pub fnc main(bool ready) => void {
                          if ready; do java {
                            System.out.println("ambiguous");
                          }
                          fi
                          return;
                        }
                        """));

        assertTrue(failure.getMessage().contains("conditional delimiter separately"));
    }

    @Test
    void doJavaExecutesExactlyAtTheOresControlFlowPoint() throws Exception {
        Path source = temp.resolve("do-java.ores");
        Files.writeString(source, """
                pub fnc signal() => void {
                  stdio.println("java-ran");
                  return;
                }

                pub fnc main() => void {
                  do java {
                    Ores.signal();
                  }
                  return;
                }
                """);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                source,
                trustedMixedPolicy(),
                ExecutionProfile.serverJit(),
                Set.of(),
                out,
                new ByteArrayOutputStream());

        assertTrue(out.toString(StandardCharsets.UTF_8).contains("java-ran"));
    }

    @Test
    void doJavaDoesNotImplicitlyCaptureOresLocals() throws Exception {
        Path source = temp.resolve("no-capture.ores");
        Files.writeString(source, """
                pub fnc main() => void {
                  val value = 42;
                  do java {
                    System.out.println(value);
                  }
                  return;
                }
                """);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(source));
        assertTrue(failure.getMessage().contains("value"));
    }

    @Test
    void doJavaIsStatementOnlyAndRejectsValueReturns() throws Exception {
        Path source = temp.resolve("statement-only.ores");
        Files.writeString(source, """
                pub fnc main() => void {
                  do java {
                    return 42;
                  }
                  return;
                }
                """);

        assertThrows(IllegalArgumentException.class, () -> LinkedProgramRunner.validate(source));
    }

    @Test
    void oresCallsJavaDeclaredInSameFile() throws Exception {
        Path source = temp.resolve("same-file.ores");
        Files.writeString(source, """
                pub fnc main() => void {
                  stdio.println(Hashing.decorate("hello"));
                  return;
                }

                java {
                  final class Hashing {
                    public static String decorate(String value) {
                      return value + ":java";
                    }
                  }
                }
                """);

        IsolatePolicy policy = trustedMixedPolicy();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                source,
                policy,
                ExecutionProfile.serverJit(),
                Set.of(),
                out,
                new ByteArrayOutputStream());

        assertTrue(out.toString(StandardCharsets.UTF_8).contains("hello:java"));
    }

    @Test
    void javaCallsOresAndPreservesJavaObjectIdentity() throws Exception {
        Path source = temp.resolve("MixedDemo.java");
        Files.writeString(source, """
                import java.util.ArrayList;

                public final class MixedDemo {
                  public static void main(String[] args) {
                    var values = new ArrayList<String>();
                    values.add("java");

                    Object returned = Ores.identity(values);
                    if (returned != values) {
                      throw new AssertionError("Java object identity was not preserved");
                    }

                    int size = Ores.count(values);
                    if (size != 1) {
                      throw new AssertionError("Oreslang did not receive the original Java object");
                    }
                  }

                  ores {
                    import class ArrayList as JArrayList from "java:java.util.ArrayList";

                    pub fnc identity(JArrayList value) => JArrayList {
                      return value;
                    }

                    pub fnc count(JArrayList value) => int {
                      return value.size();
                    }
                  }
                }
                """);

        assertDoesNotThrow(() -> LinkedProgramRunner.run(
                source,
                trustedMixedPolicy(),
                ExecutionProfile.serverJit(),
                Set.of("java.util.ArrayList"),
                new ByteArrayOutputStream(),
                new ByteArrayOutputStream()));
    }

    @Test
    void mixedJavaSourceRequiresSeparateTrustedCapability() throws Exception {
        Path source = temp.resolve("capability.ores");
        Files.writeString(source, """
                pub fnc main() => void { return; }
                java { final class Helper {} }
                """);

        IsolatePolicy onlyHostInterop = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.JAVA_INTEROP);

        SecurityException denied = assertThrows(SecurityException.class, () -> LinkedProgramRunner.run(
                source,
                onlyHostInterop,
                ExecutionProfile.serverJit(),
                Set.of(),
                new ByteArrayOutputStream(),
                new ByteArrayOutputStream()));
        assertTrue(denied.getMessage().contains("JAVA_SOURCE_INTEROP"));
    }

    @Test
    void mixedJavaSourceIsJitOnlyUntilJavaIsPrecompiled() throws Exception {
        Path source = temp.resolve("mode.ores");
        Files.writeString(source, """
                pub fnc main() => void { return; }
                java { final class Helper {} }
                """);

        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class, () -> LinkedProgramRunner.run(
                source,
                trustedMixedPolicy(),
                new ExecutionProfile(ExecutionProfile.Mode.AOT, ExecutionProfile.Platform.SERVER),
                Set.of(),
                new ByteArrayOutputStream(),
                new ByteArrayOutputStream()));
        assertTrue(denied.getMessage().contains("require --mode=jit"));
    }

    @Test
    void adversarialPolicyCannotAcquireJavaSourceInterop() {
        assertThrows(IllegalArgumentException.class, () ->
                IsolatePolicy.strictFaas().withCapabilities(IsolatePolicy.Capability.JAVA_SOURCE_INTEROP));
    }

    private static IsolatePolicy trustedMixedPolicy() {
        return IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.JAVA_INTEROP,
                IsolatePolicy.Capability.JAVA_SOURCE_INTEROP);
    }
}
