package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.AnnotationRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class AnnotationRegistryTest {
    private static Ast.Annotation doc(Ast.TypeRef... values) {
        return new Ast.Annotation("Doc", List.of(values));
    }

    @Test
    void documentsCanonicalIdentityAndAllowedTargets() {
        AnnotationRegistry.Definition definition = AnnotationRegistry.resolveImplemented("Doc");
        assertNotNull(definition);
        assertEquals("atto.core.Doc", definition.qualifiedName());
        assertEquals(AnnotationRegistry.Phase.SOURCE_METADATA, definition.phase());
        assertFalse(definition.repeatable());
        assertEquals(List.of(AnnotationRegistry.ArgumentKind.STRING_LITERAL), definition.arguments());
        for (AnnotationRegistry.Target target : AnnotationRegistry.Target.values()) {
            assertDoesNotThrow(() -> AnnotationRegistry.validateSourceMetadata(
                    doc(Ast.TypeRef.stringLiteral("safe documentation")), target));
        }
    }

    @Test
    void refusesUnregisteredIdentityAndUnimplementedAuthority() {
        assertNull(AnnotationRegistry.resolveImplemented("GrantPermission"));
        assertNull(AnnotationRegistry.resolveImplemented("derive"));
        assertNull(AnnotationRegistry.resolveImplemented("doc"));
        for (String name : List.of("GrantPermission", "derive", "doc", "Ret")) {
            Ast.Annotation annotation = new Ast.Annotation(name, List.of());
            assertThrows(IllegalArgumentException.class,
                    () -> AnnotationRegistry.validateSourceMetadata(
                            annotation, AnnotationRegistry.Target.CLASS));
        }
    }

    @Test
    void rejectsWrongArityTypeNamedArgumentsAndUnsafeMetadata() {
        Ast.TypeRef string = Ast.TypeRef.stringLiteral("hello");
        for (Ast.Annotation bad : List.of(
                doc(),
                doc(string, string),
                doc(Ast.TypeRef.intLiteral("123")),
                doc(Ast.TypeRef.boolLiteral(true)),
                doc(Ast.TypeRef.simple("String")),
                doc(Ast.TypeRef.namedParameter("value", string)),
                doc(Ast.TypeRef.stringLiteral("x".repeat(4097))),
                doc(Ast.TypeRef.stringLiteral("abc\u0000def")))) {
            assertThrows(IllegalArgumentException.class,
                    () -> AnnotationRegistry.validateSourceMetadata(
                            bad, AnnotationRegistry.Target.ACTOR));
        }
    }

    @Test
    void immutableDefinitionCannotAcceptEmptyTargetInventory() {
        assertThrows(IllegalArgumentException.class, () -> new AnnotationRegistry.Definition(
                "atto.core.Empty", "Empty", java.util.Set.of(),
                AnnotationRegistry.Phase.SOURCE_METADATA,
                List.of(), false));
    }
}
