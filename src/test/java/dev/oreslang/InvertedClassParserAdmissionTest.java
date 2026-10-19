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
    }
}
