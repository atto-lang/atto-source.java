package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class ImportHardeningTest {
    @TempDir
    Path tempDir;

    @Test
    void actorImportsSupportAliasesAndExplicitNamespaces() {
        Ast.Program program = Parser.parse("""
                import actor Foo from "./actors";
                import actor (Bar as B) from "./actors";
                import actor {Baz: Z, Qux as Q} from "./actors";
                import module Helpers as H from "./helpers";
                import * as Everything from "./all";

                pub fnc main() => void { return; }
                """);

        assertEquals(5, program.imports().size());
        assertEquals(Ast.ImportKind.ACTOR, program.imports().get(0).kind());
        assertEquals("Foo", program.imports().get(0).localName("Foo"));
        assertEquals("B", program.imports().get(1).localName("Bar"));
        assertEquals("Z", program.imports().get(2).localName("Baz"));
        assertEquals("Q", program.imports().get(2).localName("Qux"));
        assertEquals("H", program.imports().get(3).localName("Helpers"));
        assertEquals(Ast.ImportKind.ALL, program.imports().get(4).kind());
        assertEquals("Everything", program.imports().get(4).namespace());
    }

    @Test
    void javascriptStyleDefaultImportsRemainRejected() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        import Foo from "./actors";
                        pub fnc main() => void { return; }
                        """));

        assertTrue(failure.getMessage().contains("expected module, class, actor, fnc, entry, or *"));
    }

    @Test
    void duplicateLocalAliasesFailClosed() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        import actor Foo as X from "./actors";
                        import class Bar as X from "./types";
                        pub fnc main() => void { return; }
                        """)));

        assertTrue(failure.getMessage().contains("duplicate imported name 'X'"));
    }

    @Test
    void linkedImportsDistinguishActorsFromOrdinaryClasses() {
        String exports = """
                define actor Foo as
                  pub run(int message): void { return; }
                end

                define class Plain as
                  run(): void { return; }
                end
                """;

        IncrementalCompiler compiler = new IncrementalCompiler();

        Map<String, String> good = new LinkedHashMap<>();
        good.put("/project/actors.ores", exports);
        good.put("/project/main.ores", """
                import actor (Foo as F) from "./actors.ores";
                import class Plain from "./actors.ores";
                pub fnc main() => void { return; }
                """);
        assertDoesNotThrow(() -> compiler.compile(good));

        Map<String, String> actorAsClass = new LinkedHashMap<>();
        actorAsClass.put("/project/actors.ores", exports);
        actorAsClass.put("/project/main.ores", """
                import class Foo from "./actors.ores";
                pub fnc main() => void { return; }
                """);
        IllegalArgumentException wrongClass = assertThrows(
                IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(actorAsClass));
        assertTrue(wrongClass.getMessage().contains("does not match an exported declaration"));

        Map<String, String> classAsActor = new LinkedHashMap<>();
        classAsActor.put("/project/actors.ores", exports);
        classAsActor.put("/project/main.ores", """
                import actor Plain from "./actors.ores";
                pub fnc main() => void { return; }
                """);
        IllegalArgumentException wrongActor = assertThrows(
                IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(classAsActor));
        assertTrue(wrongActor.getMessage().contains("does not match an exported declaration"));
    }

    @Test
    void entryExportAndImportAreExplicitAndUnique() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                export entry Worker;

                define actor Worker as
                  pub run(int message): void { return; }
                end
                """));

        Ast.EntryExportDecl entry = (Ast.EntryExportDecl) program.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.EntryExportDecl.class::isInstance)
                .findFirst()
                .orElseThrow();
        assertEquals("Worker", entry.name());

        Ast.Program imported = TypeChecker.check(Parser.parse("""
                import entry Plugin from "./worker";
                pub fnc main() => void { return; }
                """));
        assertEquals(Ast.ImportKind.ENTRY, imported.imports().getFirst().kind());
        assertEquals("Plugin", imported.imports().getFirst().localName("$entry$"));

        IllegalArgumentException duplicate = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        export entry Worker;
                        export entry main;

                        define actor Worker as
                          pub run(int message): void { return; }
                        end
                        pub fnc main() => void { return; }
                        """)));
        assertTrue(duplicate.getMessage().contains("at most one export entry"));

        IllegalArgumentException missing = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        export entry Missing;
                        pub fnc main() => void { return; }
                        """)));
        assertTrue(missing.getMessage().contains("must name a top-level"));

        IllegalArgumentException actorCallable = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        export entry worker;
                        pub actor fnc worker(int value) => int { return value; }
                        """)));
        assertTrue(actorCallable.getMessage().contains("cannot be a code-unit entry"));
    }

    @Test
    void entryImportResolvesAndExecutesTheExplicitTarget() throws Exception {
        Path plugin = tempDir.resolve("plugin.ores");
        Path main = tempDir.resolve("main.ores");

        Files.writeString(plugin, """
                export entry run;

                pub fnc run(int value) => int {
                  return value + 1;
                }
                """);
        Files.writeString(main, """
                import entry op from "./plugin.ores";

                pub routine main() => void {
                  stdio.stdout.write(op(41));
                  return;
                }
                """);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                main,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                output,
                new ByteArrayOutputStream());

        assertEquals("42", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void untypedEntryRejectsAmbiguousDeclarationKinds() {
        IllegalArgumentException ambiguous = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        export entry Plugin;

                        define class Plugin as
                          run(): void { return; }
                        end

                        pub fnc Plugin() => void { return; }
                        """)));

        assertTrue(ambiguous.getMessage().contains("ambiguous across declaration kinds"));
    }

    @Test
    void entryExportCannotBeHiddenInsideAModule() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define module plugin
                          export entry run;
                          pub fnc run() => void { return; }
                        end
                        """));
    }
}
