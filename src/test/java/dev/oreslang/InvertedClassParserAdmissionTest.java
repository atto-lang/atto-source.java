package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Source-level admission gate: parse inverted layout intent without
 * pretending an ordinary class allocation provides SoA storage.
 */
final class InvertedClassParserAdmissionTest {
    @Test
    void retainsInvertedLayoutMetadataAtRootAndModuleScope() {
        Ast.Program root = Parser.parse("""
                define inverted class Particle as
                    x: float;
                    y: float;
                end
                """);
        Ast.ClassDecl classDecl = (Ast.ClassDecl) root.modules().getFirst()
                .declarations().getFirst();
        assertTrue(classDecl.inverted());
        assertEquals("Particle", classDecl.name());
        assertEquals(2, classDecl.fields().size());
        assertEquals(Ast.ActorKind.NONE, classDecl.actorKind());

        Ast.Program module = Parser.parse("""
                define module Physics as
                    define pub inverted class Position as
                        x: int;
                    end
                end
                """);
        Ast.ClassDecl nested = (Ast.ClassDecl) module.modules().getFirst()
                .declarations().getFirst();
        assertTrue(nested.inverted());
        assertEquals(Ast.Visibility.PUBLIC, nested.visibility());
        assertEquals("Position", nested.name());
    }

    @Test
    void ordinaryClassesRemainOrdinaryAndInvertedIsAnIdentifier() {
        Ast.Program p = Parser.parse("""
                define class Normal as
                    inverted: int = 1;
                end
                define class inverted as
                    value: int = 2;
                end
                """);
        assertFalse(((Ast.ClassDecl) p.modules().getFirst().declarations().get(0)).inverted());
        assertFalse(((Ast.ClassDecl) p.modules().getFirst().declarations().get(1)).inverted());
        assertDoesNotThrow(() -> TypeChecker.check(p));
    }

    @Test
    void executableInvertedClassesFailClosedUntilSoaLowering() {
        for (String source : new String[] {
                """
                define inverted class Particle as
                    x: float;
                end
                """,
                """
                define module M as
                    define inverted class Particle as
                        x: float;
                    end
                end
                """
        }) {
            Ast.Program program = Parser.parse(source);
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(program));
            assertTrue(ex.getMessage().contains("requires typed InvertedArray ownership"),
                    ex.getMessage());
        }
    }

    @Test
    void rejectsIncompatibleModifiersInheritanceAndConstructors() {
        for (String source : new String[] {
                "define inverted interface Foo as end",
                "define abstract inverted class Foo as end",
                "define inverted abstract class Foo as end",
                "define inverted class Foo extends Bar as end",
                "define inverted class Foo implements A as end",
                "define actor inverted class Foo as end",
                "define shared actor inverted class Foo as end",
                "define private actor inverted class Foo as end",
                "define untrusted actor inverted class Foo as end",
                """
                define inverted class Foo as
                    constructor() {}
                end
                """
        }) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), source);
        }
    }

    @Test
    void actorPrefixedInvertedDeclarationsCannotSneakThrough() {
        for (String source : new String[] {
                "define shared inverted class Bad as x: i32; end",
                "define untrusted inverted class Bad as x: i32; end",
                "define inverted actor Bad as end",
                "define inverted isoactor Bad as end",
                "define actor inverted class Bad as end",
                "define isoactor inverted class Bad as end"
        }) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), source);
        }

        for (Ast.ActorKind kind : new Ast.ActorKind[] {
                Ast.ActorKind.SHARED, Ast.ActorKind.PRIVATE, Ast.ActorKind.UNTRUSTED
        }) {
            assertThrows(IllegalArgumentException.class, () -> new Ast.ClassDecl(
                    "Bad" + kind, Ast.Visibility.PRIVATE, false, kind,
                    java.util.List.of(), java.util.List.of(), java.util.List.of(),
                    java.util.List.of(), null, java.util.List.of(),
                    Ast.ActorConcurrency.serial(Ast.ActorTransport.GENERIC), true));
        }
    }

    @Test
    void reconstructedInvertedAstCannotBypassAnyTypeCheckerEntrypoint() {
        Ast.ClassDecl parsed = (Ast.ClassDecl) Parser.parse("""
                define inverted class DirectSoa as
                    x: i32;
                end
                """).modules().getFirst().declarations().getFirst();

        Ast.ClassDecl reconstructed = new Ast.ClassDecl(
                parsed.name(), parsed.visibility(), parsed.isAbstract(), parsed.actorKind(),
                parsed.genericParameters(), parsed.parents(), parsed.interfaces(),
                parsed.fields(), parsed.constructor(), parsed.methods(),
                parsed.actorConcurrency(), true);
        Ast.Program direct = new Ast.Program(java.util.List.of(
                new Ast.ModuleDecl("Direct", java.util.List.of(reconstructed))));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(direct));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.checkTypes(direct));
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.checkTypesWithDiagnostics(direct));

        Ast.Program ordinary = Parser.parse("""
                define class Ordinary as
                    x: i32;
                end
                """);
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.checkTypesWithImportedClasses(
                        ordinary, java.util.Map.of("DirectSoa", reconstructed),
                        java.util.Map.of()));
    }

    @Test
    void annotationExpansionCannotEraseInvertedLayoutAndCheckerEntrypointsRejectIt() {
        Ast.Program original = Parser.parse("""
                define inverted class Particle as
                    x: float;
                end
                """);
        Ast.Program rewritten = dev.oreslang.ast.AnnotationExpander.expand(original);
        Ast.ClassDecl after = (Ast.ClassDecl) rewritten.modules().getFirst()
                .declarations().getFirst();
        assertTrue(after.inverted(), "AST rewrite must preserve physical layout intent");
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.checkTypes(original));
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.checkTypesWithDiagnostics(original));
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.checkTypes(rewritten));
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.checkTypesWithDiagnostics(rewritten));
    }

    @Test
    void linkedInvertedClassCannotSlipThroughImportedSignatureChecking() {
        Ast.Program ordinary = Parser.parse("""
                define class Ordinary as
                    x: int;
                end
                """);
        Ast.Program declaration = Parser.parse("""
                define pub inverted class RemoteSoa as
                    x: int;
                end
                """);
        Ast.ClassDecl linked = (Ast.ClassDecl) declaration.modules().getFirst()
                .declarations().getFirst();
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.checkTypesWithImportedClasses(
                        ordinary, java.util.Map.of("RemoteSoa", linked),
                        java.util.Map.of()));
    }

    @Test
    void oldAstConstructorsDefaultToOrdinaryLayout() {
        Ast.ClassDecl classDecl = new Ast.ClassDecl(
                "Legacy", Ast.Visibility.PRIVATE, false, Ast.ActorKind.NONE,
                java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(), null, java.util.List.of());
        assertFalse(classDecl.inverted());
        assertThrows(IllegalArgumentException.class, () -> new Ast.ClassDecl(
                "Invalid", Ast.Visibility.PRIVATE, true, Ast.ActorKind.NONE,
                java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(), null, java.util.List.of(), true));
        assertThrows(IllegalArgumentException.class, () -> new Ast.ClassDecl(
                "InvalidActor", Ast.Visibility.PRIVATE, false, Ast.ActorKind.SHARED,
                java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(), null, java.util.List.of(), true));
        assertThrows(IllegalArgumentException.class, () -> new Ast.ClassDecl(
                "InvalidInterface", Ast.Visibility.PRIVATE, false, Ast.ActorKind.NONE,
                java.util.List.of(), java.util.List.of(),
                java.util.List.of(Ast.TypeRef.simple("I")),
                java.util.List.of(), null, java.util.List.of(), true));
        Ast.ConstructorDecl ctor = new Ast.ConstructorDecl(
                Ast.Visibility.PRIVATE, java.util.List.of(),
                java.util.List.of(), java.util.List.of());
        assertThrows(IllegalArgumentException.class, () -> new Ast.ClassDecl(
                "InvalidConstructor", Ast.Visibility.PRIVATE, false, Ast.ActorKind.NONE,
                java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(), ctor, java.util.List.of(), true));
    }
}
