package dev.oreslang;

import dev.oreslang.ast.AnnotationExpander;
import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.BuildOptions;
import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.compiler.TreeShaker;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class ExternalActorBoundaryCompilerTest {

    private static Ast.ClassDecl onlyClass(String source) {
        Ast.Program program = Parser.parse(source);
        return program.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.ClassDecl.class::isInstance)
                .map(Ast.ClassDecl.class::cast)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void declarationKindAndBoundaryMatrixIsOrthogonalAndFailClosed() {
        record Accepted(
                String source,
                Ast.ActorKind kind,
                Ast.ActorBoundary boundary) { }

        List<Accepted> accepted = List.of(
                new Accepted("define actor A as end",
                        Ast.ActorKind.SHARED, Ast.ActorBoundary.LOCAL),
                new Accepted("define shared actor A as end",
                        Ast.ActorKind.SHARED, Ast.ActorBoundary.LOCAL),
                new Accepted("define isoactor A as end",
                        Ast.ActorKind.PRIVATE, Ast.ActorBoundary.LOCAL),
                new Accepted("define untrusted actor A as end",
                        Ast.ActorKind.UNTRUSTED, Ast.ActorBoundary.LOCAL),
                new Accepted("define ipc actor A as end",
                        Ast.ActorKind.PRIVATE, Ast.ActorBoundary.IPC),
                new Accepted("define untrusted ipc actor A as end",
                        Ast.ActorKind.UNTRUSTED, Ast.ActorBoundary.IPC),
                new Accepted("define ffi actor A as end",
                        Ast.ActorKind.PRIVATE, Ast.ActorBoundary.FFI));

        for (Accepted fixture : accepted) {
            Ast.ClassDecl actor = onlyClass(fixture.source());
            assertEquals(fixture.kind(), actor.actorKind(), fixture.source());
            assertEquals(fixture.boundary(), actor.actorBoundary(), fixture.source());
        }

        for (String rejected : List.of(
                "define untrusted ffi actor A as end",
                "define shared ipc actor A as end",
                "define shared ffi actor A as end",
                "define ipc ffi actor A as end",
                "define ipc isoactor A as end",
                "define ffi isoactor A as end",
                "define ipc actor Box<T> as end",
                "define ipc actor Child extends Base as end",
                "define ffi actor Child implements Api as end",
                "ipc fnc not_an_actor(): void { return; }",
                "ffi routine not_an_actor(): void { return; }")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Parser.parse(rejected),
                    rejected);
        }
    }

    @Test
    void externalBoundaryKeywordsAreReservedBeforeRuntimeLowering() {
        List<Token.Type> types = new Lexer("ipc ffi extern").scan().stream()
                .map(Token::type)
                .filter(type -> type != Token.Type.EOF)
                .toList();
        assertEquals(
                List.of(Token.Type.IPC, Token.Type.FFI, Token.Type.EXTERN),
                types);

        for (String source : List.of(
                "pub routine main(): void { val ipc = 1; return; }",
                "pub routine main(): void { val ffi = 1; return; }",
                "pub routine main(): void { val extern = 1; return; }")) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source));
        }
    }

    @Test
    void externInsideFfiActorIsReservedForForeignDeclNotNormalMethod() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define ffi actor NativeWorker as
                          extern suspends fnc native_call(int value): int;
                        end
                        """));
        assertTrue(failure.getMessage().contains("ForeignDecl"), failure::getMessage);
    }

    @Test
    void astTransformsPreserveExternalActorBoundary() {
        String source = """
                define pub ipc actor Worker as
                  receive(ActorMail<String> mail): void {
                    return;
                  }
                end
                """;

        Ast.Program expanded = AnnotationExpander.expand(Parser.parse(source));
        Ast.ClassDecl expandedActor = expanded.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.ClassDecl.class::isInstance)
                .map(Ast.ClassDecl.class::cast)
                .findFirst().orElseThrow();
        assertEquals(Ast.ActorBoundary.IPC, expandedActor.actorBoundary());

        Ast.Program checked = TypeChecker.check(expanded);
        TreeShaker.Result shaken =
                TreeShaker.shake(checked, BuildOptions.library(Map.of()));
        Ast.ClassDecl shakenActor = shaken.program().modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.ClassDecl.class::isInstance)
                .map(Ast.ClassDecl.class::cast)
                .findFirst().orElseThrow();
        assertEquals(Ast.ActorKind.PRIVATE, shakenActor.actorKind());
        assertEquals(Ast.ActorBoundary.IPC, shakenActor.actorBoundary());
    }

    @Test
    void publicAbiDigestCommitsToExternalActorBoundary() {
        IncrementalCompiler localCompiler = new IncrementalCompiler();
        IncrementalCompiler ipcCompiler = new IncrementalCompiler();
        IncrementalCompiler ffiCompiler = new IncrementalCompiler();

        String body = """
                  receive(ActorMail<String> mail): void {
                    return;
                  }
                end
                """;

        String local = "define pub isoactor Worker as\n" + body;
        String ipc = "define pub ipc actor Worker as\n" + body;
        String ffi = "define pub ffi actor Worker as\n" + body;

        String localAbi = localCompiler.compile(Map.of("worker.ores", local))
                .units().get("worker.ores").abiDigest();
        String ipcAbi = ipcCompiler.compile(Map.of("worker.ores", ipc))
                .units().get("worker.ores").abiDigest();
        String ffiAbi = ffiCompiler.compile(Map.of("worker.ores", ffi))
                .units().get("worker.ores").abiDigest();

        assertNotEquals(localAbi, ipcAbi);
        assertNotEquals(localAbi, ffiAbi);
        assertNotEquals(ipcAbi, ffiAbi);
    }

    @Test
    void externalActorSpawnFailsClosedUntilAuthenticatedBackendExists() {
        for (String declaration : List.of(
                "define ipc actor Worker as end",
                "define untrusted ipc actor Worker as end",
                "define ffi actor Worker as end")) {
            String source = declaration + """

                    pub routine main(): void {
                      val worker = spawn Worker();
                      return;
                    }
                    """;
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)),
                    declaration);
            assertTrue(
                    failure.getMessage().contains("authenticated external-actor"),
                    failure::getMessage);
        }
    }

    @Test
    void astConstructorRejectsImpossibleBoundaryCombinations() {
        assertThrows(IllegalArgumentException.class, () -> new Ast.ClassDecl(
                "Bad",
                Ast.Visibility.PRIVATE,
                false,
                Ast.ActorKind.UNTRUSTED,
                Ast.ActorBoundary.FFI,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of()));

        assertThrows(IllegalArgumentException.class, () -> new Ast.ClassDecl(
                "Bad",
                Ast.Visibility.PRIVATE,
                false,
                Ast.ActorKind.SHARED,
                Ast.ActorBoundary.IPC,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of()));
    }
}
