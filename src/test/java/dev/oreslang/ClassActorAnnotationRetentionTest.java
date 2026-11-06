package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class ClassActorAnnotationRetentionTest {
    private static Ast.ClassDecl declaration(String source) {
        Ast.Program program = Parser.parse(source);
        return (Ast.ClassDecl) program.modules().getFirst().declarations().getFirst();
    }

    @Test
    void retainsTypedSourceDocumentationAcrossClassAndActorSpelling() {
        String[] declarations = {
                "@Doc(\"class description\") define class Person as end",
                "@Doc(\"class description\") class Person define as end",
                "@Doc(\"actor description\") define actor Worker as end",
                "@Doc(\"actor description\") define isoactor Worker as end"
        };
        for (String source : declarations) {
            Ast.ClassDecl result = assertDoesNotThrow(() -> declaration(source), source);
            assertEquals(1, result.annotations().size(), source);
            Ast.Annotation doc = result.annotations().getFirst();
            assertEquals("Doc", doc.name(), source);
            assertTrue(doc.arguments().getFirst().isStringLiteral(), source);
            assertEquals("Doc", result.annotations().getFirst().name());
        }
    }

    @Test
    void discardsNoActorMetadataWhenRetainingOtherFields() {
        Ast.ClassDecl actor = declaration("""
                @Doc("actor metadata") define actor Worker as
                    let int count = 0;
                end
                """);
        assertNotEquals(Ast.ActorKind.NONE, actor.actorKind());
        assertEquals("actor metadata",
                actor.annotations().getFirst().arguments().getFirst().stringLiteralValue());
        assertEquals(1, actor.fields().size());
    }

    @Test
    void rejectsUnknownOrEffectfulDeclarationsInsteadOfTreatingAsDocumentation() {
        for (String source : List.of(
                "@GrantPermission(\"ffi\") define class Person as end",
                "@actor_policy(serial=true) define actor Worker as end",
                "@Doc(42) define class Person as end",
                "@Doc(value=\"x\") define actor Worker as end",
                "@Doc define class Person as end",
                "@Doc(\"x\") @Doc(\"y\") define actor Worker as end")) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(source), source);
        }
    }

    @Test
    void keepsOldClassRecordConstructionAbiAndMakesMetadataImmutable() {
        Ast.ClassDecl old = new Ast.ClassDecl(
                "Person", Ast.Visibility.PRIVATE, false, Ast.ActorKind.NONE,
                List.of(), List.of(), List.of(), List.of(), null, List.of(),
                Ast.ActorConcurrency.serial(Ast.ActorTransport.GENERIC));
        assertTrue(old.annotations().isEmpty());
        Ast.ClassDecl annotated = declaration("@Doc(\"doc\") define class Person as end");
        assertThrows(UnsupportedOperationException.class,
                () -> annotated.annotations().add(new Ast.Annotation("Doc", List.of())));
    }
}
