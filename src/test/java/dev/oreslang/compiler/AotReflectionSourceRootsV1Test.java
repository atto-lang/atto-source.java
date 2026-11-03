package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Production-parser / type-check / ownership admissions for the
 * explicit-root compiler metadata bridge; still NOT guest type_of execution.
 */
final class AotReflectionSourceRootsV1Test {
    private static final String PUBLIC_SOURCE = """
            pub define class Person as
              pub int visible: 7
              int hidden_secret_marker: 9
            end
            """;

    @Test
    void parsedTypecheckedPublicClassEmitsOnlyPublicMetadata() {
        byte[] first = OresCompiler.compileAotReflectionTableV1(
                PUBLIC_SOURCE, "example@revision1", List.of("__root__.Person"));
        byte[] second = OresCompiler.compileAotReflectionTableV1(
                PUBLIC_SOURCE, "example@revision1", List.of("__root__.Person"));
        assertArrayEquals(first, second);
        String bytes = new String(first, StandardCharsets.UTF_8);
        assertTrue(bytes.contains("__root__.Person"));
        assertTrue(bytes.contains("visible"));
        assertFalse(bytes.contains("hidden_secret_marker"));
        var readBack = AotReflectionTableV1Reader.decode(first);
        assertEquals(1, readBack.types().size());
        assertEquals(1, readBack.types().getFirst().publicFields().size());
        assertEquals("visible", readBack.types().getFirst().publicFields().getFirst().name());
    }

    @Test
    void privateAndMissingRootsCannotBeUsedAsRetentionAuthority() {
        String privateSource = """
                define class Secret as
                  pub int visible: 5
                end
                """;
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.compileAotReflectionTableV1(
                privateSource, "example@revision1", List.of("__root__.Secret")));
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.compileAotReflectionTableV1(
                PUBLIC_SOURCE, "example@revision1", List.of("__root__.Nonexistent")));
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.compileAotReflectionTableV1(
                PUBLIC_SOURCE, "example@revision1", List.of("__root__.Person", "__root__.Person")));
    }

    @Test
    void unclosedOrInheritedClassShapesRejectAtSourceLoweringBoundary() {
        var generic = new Ast.ClassDecl("G", Ast.Visibility.PUBLIC, false, Ast.ActorKind.NONE,
                List.of("T"), List.of(), List.of(), List.of(), null, List.of());
        var inherited = new Ast.ClassDecl("C", Ast.Visibility.PUBLIC, false, Ast.ActorKind.NONE,
                List.of(), List.of(Ast.TypeRef.simple("Parent")), List.of(), List.of(), null, List.of());
        var actor = new Ast.ClassDecl("A", Ast.Visibility.PUBLIC, false, Ast.ActorKind.SHARED,
                List.of(), List.of(), List.of(), List.of(), null, List.of());
        var ast = new Ast.Program(List.of(new Ast.ModuleDecl("__root__", List.of(generic, inherited, actor))));
        for (String root : List.of("__root__.G", "__root__.C", "__root__.A")) {
            assertThrows(IllegalArgumentException.class, () -> AotReflectionSourceRootsV1.lowerChecked(
                    ast, "example@revision1", List.of(root)));
        }
    }

    @Test
    void forbiddenUnresolvedFieldTypeRejectsBeforeDescriptorPublication() {
        var field = new Ast.FieldDecl("dynamic", Ast.Visibility.PUBLIC, Ast.BindingKind.LET,
                Ast.TypeRef.simple("unknown"), null);
        var klass = new Ast.ClassDecl("K", Ast.Visibility.PUBLIC, false, Ast.ActorKind.NONE,
                List.of(), List.of(), List.of(), List.of(field), null, List.of());
        var ast = new Ast.Program(List.of(new Ast.ModuleDecl("__root__", List.of(klass))));
        assertThrows(IllegalArgumentException.class, () -> AotReflectionSourceRootsV1.lowerChecked(
                ast, "example@revision1", List.of("__root__.K")));
    }

    @Test
    void noDefaultWildcardRetentionOrEmptyRoots() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.compileAotReflectionTableV1(
                PUBLIC_SOURCE, "example@revision1", List.of()));
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.compileAotReflectionTableV1(
                PUBLIC_SOURCE, "example@revision1", List.of("*")));
    }
}
