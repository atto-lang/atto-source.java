package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A linked type signature must never bypass full compiler admission.
 * The caller-provided linked signature must originate at the trusted linker;
 * the source import declaration alone is not an authenticity proof.
 */
final class LinkedActorCompilerAdmissionTest {
    private static Ast.ClassDecl exportedWorker() {
        Ast.Program exported = Parser.parse("""
                define pub actor Worker as
                    receive(ActorMail<int> mail): void {
                        self.end();
                        return;
                    }
                end
                """);
        return exported.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(decl -> decl instanceof Ast.ClassDecl)
                .map(decl -> (Ast.ClassDecl) decl)
                .filter(decl -> decl.name().equals("Worker"))
                .findFirst().orElseThrow();
    }

    @Test
    void linkedTypeCheckDoesNotBypassFinallyReturnRule() {
        Ast.Program importer = Parser.parse("""
                import class Worker from "./worker.ores";
                fnc value(): int {
                    try { return 1; }
                    catch (err) { return 2; }
                    finally { return 3; }
                }
                """);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypesWithImportedClasses(
                        importer, Map.of("Worker", exportedWorker()), Map.of()));
        assertTrue(error.getMessage().contains("return is not allowed inside finally"),
                error::getMessage);
    }

    @Test
    void executableLinkedCompilerAdmissionAlsoEnforcesFinallyReturnRule() {
        Ast.Program importer = Parser.parse("""
                import class Worker from "./worker.ores";
                fnc value(): int {
                    try { return 1; }
                    catch (err) { return 2; }
                    finally { return 3; }
                }
                """);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.analyzeWithImportedClasses(
                        importer, Map.of("Worker", exportedWorker()), Map.of()));
        assertTrue(error.getMessage().contains("return is not allowed inside finally"),
                error::getMessage);
    }

    @Test
    void executableLinkedCompilerAdmissionRejectsUntrustedActorRef() {
        Ast.Program importer = Parser.parse("""
                import class Worker from "./worker.ores";
                define untrusted actor Inbox as
                    receive(ActorMail<ActorRef<Worker>> mail): void {
                        self.end();
                        return;
                    }
                end
                """);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.analyzeWithImportedClasses(
                        importer, Map.of("Worker", exportedWorker()), Map.of()));
        assertTrue(error.getMessage().contains("untrusted actor"), error::getMessage);
    }

    @Test
    void executableLinkedCompilerAdmissionFailsClosedWithoutLinkedSignature() {
        Ast.Program importer = Parser.parse("""
                import class Worker from "./worker.ores";
                define actor Inbox as
                    receive(ActorMail<ActorRef<Worker>> mail): void {
                        self.end();
                        return;
                    }
                end
                """);
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.analyzeWithImportedClasses(
                        importer, Map.of(), Map.of()));
    }
}
