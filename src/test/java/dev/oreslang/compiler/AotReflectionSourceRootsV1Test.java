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

    @Test
    void identityInputsAreBoundedBeforeMetadataAllocation() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.compileAotReflectionTableV1(
                PUBLIC_SOURCE, "x".repeat(513), List.of("__root__.Person")));
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.compileAotReflectionTableV1(
                PUBLIC_SOURCE, "example@revision1", List.of("x".repeat(513))));
    }
    @Test
    void namespacedAndImportedSourceMustNotForgeClosedTypeIdentities() {
        Ast.Program checked = OresCompiler.parseAndTypeCheck(PUBLIC_SOURCE);
        var namespaced = new Ast.Program("different_namespace", checked.imports(), checked.modules());
        assertThrows(IllegalArgumentException.class, () -> AotReflectionSourceRootsV1.lowerChecked(
                namespaced, "example@revision1", List.of("__root__.Person")));

        var foreignImport = new Ast.ImportDecl(Ast.ImportKind.CLASS, List.of("Alien"),
                false, null, "+external/alien");
        var imported = new Ast.Program(null, List.of(foreignImport), checked.modules());
        assertThrows(IllegalArgumentException.class, () -> AotReflectionSourceRootsV1.lowerChecked(
                imported, "example@revision1", List.of("__root__.Person")));
    }

    @Test
    void rootedModuleAnnotationsAndContractsNeedExplicitIdentityResolution() {
        Ast.Program checked = OresCompiler.parseAndTypeCheck(PUBLIC_SOURCE);
        Ast.ModuleDecl module = checked.modules().getFirst();
        var tagged = new Ast.ModuleDecl(module.name(),
                List.of(new Ast.Annotation("requires_resolution", List.of())),
                List.of(), module.declarations());
        var contracted = new Ast.ModuleDecl(module.name(), List.of(),
                List.of(Ast.TypeRef.simple("UnresolvedContract")), module.declarations());
        for (Ast.ModuleDecl unsafe : List.of(tagged, contracted)) {
            assertThrows(IllegalArgumentException.class, () -> AotReflectionSourceRootsV1.lowerChecked(
                    new Ast.Program(List.of(unsafe)), "example@revision1", List.of("__root__.Person")));
        }
    }

    @Test
    void unrootedClassesDoNotExpandOrPolluteEmittedMetadata() {
        Ast.Program checked = OresCompiler.parseAndTypeCheck(PUBLIC_SOURCE);
        byte[] expected = AotReflectionSourceRootsV1.lowerChecked(
                checked, "example@revision1", List.of("__root__.Person")).bytes();
        var declarations = new java.util.ArrayList<Ast.Decl>(
                checked.modules().getFirst().declarations());
        for (int i = 0; i < 2048; i++) {
            declarations.add(new Ast.ClassDecl("Unrooted" + i,
                    Ast.Visibility.PRIVATE, false, Ast.ActorKind.NONE,
                    List.of(), List.of(), List.of(), List.of(), null, List.of()));
        }
        var expanded = new Ast.Program(List.of(new Ast.ModuleDecl("__root__", declarations)));
        assertArrayEquals(expected, AotReflectionSourceRootsV1.lowerChecked(
                expanded, "example@revision1", List.of("__root__.Person")).bytes());
        declarations.add(checked.modules().getFirst().declarations().stream()
                .filter(d -> d instanceof Ast.ClassDecl).findFirst().orElseThrow());
        var duplicate = new Ast.Program(List.of(new Ast.ModuleDecl("__root__", declarations)));
        assertThrows(IllegalArgumentException.class, () -> AotReflectionSourceRootsV1.lowerChecked(
                duplicate, "example@revision1", List.of("__root__.Person")));
    }


    @Test
    void oneShotRootListCannotChangeEmissionAfterValidation() {
        Ast.Program checked = OresCompiler.parseAndTypeCheck(PUBLIC_SOURCE);
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        List<String> unstable = new java.util.AbstractList<>() {
            @Override public int size() { return 1; }
            @Override public String get(int index) {
                if (index != 0) throw new IndexOutOfBoundsException(index);
                return reads.incrementAndGet() == 1
                        ? "__root__.Person" : "__root__.Forged";
            }
        };
        byte[] encoded = AotReflectionSourceRootsV1.lowerChecked(
                checked, "example@revision1", unstable).bytes();
        String representation = new String(encoded, StandardCharsets.UTF_8);
        assertTrue(representation.contains("__root__.Person"));
        assertFalse(representation.contains("__root__.Forged"));
        assertEquals(1, reads.get(), "caller-owned roots may only be read during the immutable snapshot");
    }

    @Test
    void nullRootEntriesFailClosedWithStableDiagnosticClass() {
        Ast.Program checked = OresCompiler.parseAndTypeCheck(PUBLIC_SOURCE);
        var roots = new java.util.ArrayList<String>();
        roots.add(null);
        assertThrows(IllegalArgumentException.class, () -> AotReflectionSourceRootsV1.lowerChecked(
                checked, "example@revision1", roots));
    }
    @Test
    void attoFixtureIsParsedAndLoweredWithoutRuntimeReflection() throws java.io.IOException {
        byte[] sourceBytes;
        try (var fixture = getClass().getResourceAsStream("/aot-reflection/closed_nominal.atto")) {
            assertNotNull(fixture, "the real .atto source fixture must be packaged as a test resource");
            sourceBytes = fixture.readAllBytes();
        }
        byte[] encoded = OresCompiler.compileAotReflectionTableV1(
                new String(sourceBytes, StandardCharsets.UTF_8),
                "example@revision1", List.of("__root__.Person"));
        var table = AotReflectionTableV1Reader.decode(encoded);
        assertEquals(1, table.types().size());
        assertEquals("__root__.Person", table.types().getFirst().resolvedSymbol());
        assertEquals(List.of("visible"), table.types().getFirst().publicFields()
                .stream().map(AotReflectionTableV1Reader.FieldDescriptor::name).toList());
        assertFalse(new String(encoded, StandardCharsets.UTF_8).contains("hidden_secret_marker"));
    }

}
