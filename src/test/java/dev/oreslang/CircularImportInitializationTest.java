package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class CircularImportInitializationTest {
    @TempDir
    Path tempDir;

    @Test
    void twoFilesMayImportEachOtherAndDependencyInitWaitsForFirstCall() throws Exception {
        Path a = tempDir.resolve("a.ores");
        Path b = tempDir.resolve("b.ores");

        Files.writeString(a, """
                import fnc {b_value} from "./b.ores";

                pub fnc a_value(): String {
                  return "A";
                }

                fnc init(): void {
                  stdio.stdout.write("init-a|");
                  return;
                }

                pub routine main(): void {
                  stdio.stdout.write("main:");
                  stdio.stdout.write(a_value());
                  stdio.stdout.write(b_value());
                  return;
                }
                """);

        Files.writeString(b, """
                import fnc {a_value} from "./a.ores";

                pub fnc b_value(): String {
                  return "B";
                }

                fnc init(): void {
                  stdio.stdout.write("init-b|");
                  return;
                }
                """);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        IncrementalCompiler.BuildResult build = LinkedProgramRunner.run(
                a,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                output,
                error);

        assertEquals("init-a|main:Ainit-b|B", output.toString(StandardCharsets.UTF_8));

        List<List<String>> groups = build.initializationGroups();
        assertEquals(1, groups.size(), "the A<->B cycle should form one initialization barrier");
        assertEquals(2, groups.getFirst().size());
        assertTrue(groups.getFirst().contains(a.toAbsolutePath().normalize().toString().replace('\\', '/')));
        assertTrue(groups.getFirst().contains(b.toAbsolutePath().normalize().toString().replace('\\', '/')));
    }


    @Test
    void threeFileCycleResolvesBeforeAnyCrossFileInvocation() throws Exception {
        Path a = tempDir.resolve("a.ores");
        Path b = tempDir.resolve("b.ores");
        Path c = tempDir.resolve("c.ores");

        Files.writeString(a, """
                import fnc b_value from "./b.ores";
                pub fnc a_value(): void {
                  stdio.stdout.write("A");
                  return;
                }
                pub routine main(): void {
                  b_value();
                  return;
                }
                """);
        Files.writeString(b, """
                import fnc c_value from "./c.ores";
                pub fnc b_value(): void {
                  c_value();
                  return;
                }
                """);
        Files.writeString(c, """
                import fnc a_value from "./a.ores";
                pub fnc c_value(): void {
                  a_value();
                  return;
                }
                """);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        var build = LinkedProgramRunner.run(
                a, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                output, new ByteArrayOutputStream());

        assertEquals("A", output.toString(StandardCharsets.UTF_8));
        assertEquals(1, build.initializationGroups().size());
        assertEquals(3, build.initializationGroups().getFirst().size());
    }

    @Test
    void importedOverloadFamilyInCycleIsOneExportedSymbol() throws Exception {
        Path a = tempDir.resolve("a.ores");
        Path b = tempDir.resolve("b.ores");

        Files.writeString(a, """
                import fnc b_value from "./b.ores";
                pub fnc a_value(): String { return "A"; }
                pub routine main(): void {
                  stdio.stdout.write(b_value());
                  stdio.stdout.write(b_value(7));
                  return;
                }
                """);
        Files.writeString(b, """
                import fnc a_value from "./a.ores";
                pub fnc b_value(): String { return "B"; }
                pub fnc b_value(int ignored): String { return "C"; }
                """);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                a, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                output, new ByteArrayOutputStream());
        assertEquals("BC", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void duplicateWildcardImportAliasesAreRejectedBeforeLinking() throws Exception {
        Path a = tempDir.resolve("a.ores");
        Path b = tempDir.resolve("b.ores");
        Path c = tempDir.resolve("c.ores");
        Files.writeString(a, """
                import * as conflicting from "./b.ores";
                import * as conflicting from "./c.ores";
                pub routine main(): void { return; }
                """);
        Files.writeString(b, """
                import * as reverse from "./a.ores";
                pub fnc helper(): int { return 7; }
                """);
        Files.writeString(c, """
                pub fnc helper(): int { return 8; }
                """);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> LinkedProgramRunner.validate(a));
        assertTrue(failure.getMessage().contains("duplicate import binding 'conflicting'"),
                failure.getMessage());
    }

    @Test
    void wildcardAndNamedAliasesShareTheSameImportNamespace() throws Exception {
        Path a = tempDir.resolve("a.ores");
        Path b = tempDir.resolve("b.ores");
        Files.writeString(a, """
                import * as helper from "./b.ores";
                import fnc helper from "./b.ores";
                pub routine main(): void { return; }
                """);
        Files.writeString(b, """
                import * as reverse from "./a.ores";
                pub fnc helper(): int { return 7; }
                """);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> LinkedProgramRunner.validate(a));
        assertTrue(failure.getMessage().contains("duplicate import binding 'helper'"),
                failure.getMessage());
    }

    @Test
    void cyclesDoNotMakePrivateNamesImportable() throws Exception {
        Path a = tempDir.resolve("a.ores");
        Path b = tempDir.resolve("b.ores");
        Files.writeString(a, """
                import fnc hidden from "./b.ores";
                pub fnc a_value(): String { return "A"; }
                pub routine main(): void { return; }
                """);
        Files.writeString(b, """
                import fnc a_value from "./a.ores";
                fnc hidden(): String { return a_value(); }
                """);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(a));
        assertTrue(failure.getMessage().contains("does not match an exported declaration"));
    }

    @Test
    void importWithoutCallNeverExecutesImportedInit() throws Exception {
        Path a = tempDir.resolve("a.ores");
        Path b = tempDir.resolve("b.ores");

        Files.writeString(a, """
                import fnc b_value from "./b.ores";
                pub fnc a_value(): String { return "A"; }
                pub routine main(): void {
                  stdio.stdout.write("main");
                  return;
                }
                """);
        Files.writeString(b, """
                import fnc a_value from "./a.ores";
                fnc init(): void {
                  stdio.stdout.write("forbidden-init");
                  return;
                }
                pub fnc b_value(): String { return "B"; }
                """);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                a, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                output, new ByteArrayOutputStream());
        assertEquals("main", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void firstCallInitializesImportedModuleExactlyOnce() throws Exception {
        Path a = tempDir.resolve("a.ores");
        Path b = tempDir.resolve("b.ores");
        Files.writeString(a, """
                import fnc b_value from "./b.ores";
                pub fnc a_value(): String { return "A"; }
                pub routine main(): void {
                  stdio.stdout.write(b_value());
                  stdio.stdout.write(b_value());
                  return;
                }
                """);
        Files.writeString(b, """
                import fnc a_value from "./a.ores";
                fnc init(): void {
                  stdio.stdout.write("init-b|");
                  return;
                }
                pub fnc b_value(): String { return a_value(); }
                """);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                a, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                output, new ByteArrayOutputStream());
        assertEquals("init-b|AA", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void extractingImportedFunctionValueIsInertUntilInvoked() throws Exception {
        Path a = tempDir.resolve("a.ores");
        Path b = tempDir.resolve("b.ores");
        Files.writeString(a, """
                import fnc b_value from "./b.ores";
                pub fnc a_value(): void { return; }
                pub routine main(): void {
                  val callback = b_value;
                  stdio.stdout.write("before|");
                  callback();
                  return;
                }
                """);
        Files.writeString(b, """
                import fnc a_value from "./a.ores";
                fnc init(): void {
                  stdio.stdout.write("init-b|");
                  return;
                }
                pub fnc b_value(): void {
                  stdio.stdout.write("called");
                  return;
                }
                """);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                a, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                output, new ByteArrayOutputStream());
        assertEquals("before|init-b|called", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void constructingImportedClassTriggersInitializationOnlyWhenConstructed() throws Exception {
        Path a = tempDir.resolve("a.ores");
        Path b = tempDir.resolve("b.ores");
        Files.writeString(a, """
                import class Box from "./b.ores";
                pub fnc a_value(): void { return; }
                pub routine main(): void {
                  stdio.stdout.write("before|");
                  val Box box = new Box();
                  stdio.stdout.write("after");
                  return;
                }
                """);
        Files.writeString(b, """
                import fnc a_value from "./a.ores";
                fnc init(): void {
                  stdio.stdout.write("init-b|");
                  return;
                }
                pub define class Box as
                  pub constructor() {
                    stdio.stdout.write("constructed|");
                    return;
                  }
                end
                """);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                a, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                output, new ByteArrayOutputStream());
        assertEquals("before|init-b|constructed|after", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void recursiveInitializationViaCycleFailsClosed() throws Exception {
        Path a = tempDir.resolve("a.ores");
        Path b = tempDir.resolve("b.ores");
        Files.writeString(a, """
                import fnc b_value from "./b.ores";
                pub fnc a_value(): String { return "A"; }
                fnc init(): void {
                  stdio.stdout.write(b_value());
                  return;
                }
                pub routine main(): void { return; }
                """);
        Files.writeString(b, """
                import fnc a_value from "./a.ores";
                fnc init(): void {
                  stdio.stdout.write(a_value());
                  return;
                }
                pub fnc b_value(): String { return "B"; }
                """);
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> LinkedProgramRunner.run(
                        a, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                        new ByteArrayOutputStream(), new ByteArrayOutputStream()));
        assertTrue(String.valueOf(failure).contains("recursive initialization")
                        || String.valueOf(failure.getCause()).contains("recursive initialization"),
                () -> "expected recursive init diagnostic, got " + failure);
    }

    @Test
    void failingLazyImportedInitDoesNotRunImportedFunctionBody() throws Exception {
        Path a = tempDir.resolve("a.ores");
        Path b = tempDir.resolve("b.ores");
        Files.writeString(a, """
                import fnc b_value from "./b.ores";
                pub fnc a_value(): String { return "A"; }
                pub routine main(): void {
                  stdio.stdout.write("before|");
                  b_value();
                  return;
                }
                """);
        Files.writeString(b, """
                import fnc a_value from "./a.ores";
                fnc init(): void {
                  val values = arr[1];
                  val invalid = values[9];
                  return;
                }
                pub fnc b_value(): void {
                  stdio.stdout.write("should-not-run");
                  return;
                }
                """);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThrows(RuntimeException.class,
                () -> LinkedProgramRunner.run(
                        a, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                        output, new ByteArrayOutputStream()));
        assertEquals("before|", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void importedFoldedConstantDoesNotInitializeDependency() throws Exception {
        Path entry = tempDir.resolve("constant-entry.ores");
        Path dependency = tempDir.resolve("constant-dep.ores");
        Files.writeString(entry, """
                import * as dep from "./constant-dep.ores";
                pub routine main(): void {
                  stdio.stdout.write("before|");
                  stdio.stdout.write(dep.tag);
                  stdio.stdout.write("after|");
                  dep.start();
                  return;
                }
                """);
        Files.writeString(dependency, """
                pub const String tag = "sta" + "tic|";
                fnc init(): void {
                  stdio.stdout.write("init|");
                  return;
                }
                pub fnc start(): void {
                  stdio.stdout.write("called");
                  return;
                }
                """);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LinkedProgramRunner.run(entry,
                IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                output, new ByteArrayOutputStream());
        assertEquals("before|static|after|init|called",
                output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void importedNonLoweredConstantFailsClosedWithoutRunningInitializer() throws Exception {
        Path entry = tempDir.resolve("not-lowered-entry.ores");
        Path dependency = tempDir.resolve("not-lowered-dep.ores");
        Files.writeString(entry, """
                import * as dep from "./not-lowered-dep.ores";
                pub routine main(): void {
                  stdio.stdout.write("before|");
                  stdio.stdout.write(dep.tag);
                  return;
                }
                """);
        Files.writeString(dependency, """
                pub const int tag = arbitraryRuntimeCall();
                fnc arbitraryRuntimeCall(): int {
                  stdio.stdout.write("runtime-should-not-run");
                  return 1;
                }
                fnc init(): void {
                  stdio.stdout.write("init-should-not-run");
                  return;
                }
                """);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> LinkedProgramRunner.run(entry,
                        IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                        output, new ByteArrayOutputStream()));
        assertTrue(String.valueOf(failure).contains("file/module const requires")
                        || String.valueOf(failure.getCause()).contains("file/module const requires"),
                () -> "expected compile-time non-execution diagnostic: " + failure);
        assertEquals("", output.toString(StandardCharsets.UTF_8),
                "non-static consts must be rejected before any guest entrypoint runs");
    }

    @Test
    void initHookHasAClosedLifecycleSignature() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc init(): void { return; }
                pub routine main(): void { return; }
                """)));

        IllegalArgumentException withArgs = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc init(int value): void { return; }
                        """)));
        assertTrue(withArgs.getMessage().contains("init hook"));

        IllegalArgumentException publicInit = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc init(): void { return; }
                        """)));
        assertTrue(publicInit.getMessage().contains("init hook"));
    }
}
