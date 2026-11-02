package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.compiler.TreeShaker;
import dev.oreslang.compiler.BuildOptions;
import dev.oreslang.ast.AnnotationExpander;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-source Java/Graal executable acceptance for the first exclusive-owner
 * instance-storage annotation. This does NOT prove shared/actor injection,
 * reader/writer synchronization, LLVM/WASM/Cranelift/.NET execution or AOT.
 */
final class StateBackedOwnedInstanceV1Test {
    private static final String PRIVATE_CLASS = """
            @StateBacked(MutableStateContainer(scope = #instance))
            define class Inventory as
                let int count = 10;
                pub count_now(): int { return self.count; }
            end
            """;

    @Test
    void compilerPreservesTypedStateBackingInsteadOfDiscardingIt() throws Exception {
        String source = Files.readString(Path.of("examples", "state-backed-instance-v1.ores"));
        Ast.Program checked = OresCompiler.parseAndTypeCheck(source);
        Ast.ClassDecl klass = checked.modules().stream()
                .flatMap(m -> m.declarations().stream())
                .filter(d -> d instanceof Ast.ClassDecl)
                .map(d -> (Ast.ClassDecl) d)
                .findFirst().orElseThrow();
        assertEquals("InventoryState", klass.name());
        assertEquals(1, klass.annotations().size());
        assertEquals("StateBacked", klass.annotations().getFirst().name());
        assertEquals("$state_instance$",
                klass.annotations().getFirst().arguments().getFirst().name());
        assertEquals(Ast.ActorKind.NONE, klass.actorKind());
        assertEquals(2, klass.fields().size());
        for (Ast.FieldDecl field : klass.fields()) {
            assertEquals(Ast.Visibility.PRIVATE, field.visibility());
        }
    }

    @Test
    void guestActuallyMutatesOneContainerWithoutAffectingAnother() throws Exception {
        String source = Files.readString(Path.of("examples", "state-backed-instance-v1.ores"));
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder("ores")
                .allowAllAccess(true).out(stdout).build()) {
            context.eval(Source.newBuilder("ores", source,
                    "state-backed-instance-v1.ores").build());
        }
        assertEquals("7|3|10|true|false|false|false",
                stdout.toString(StandardCharsets.UTF_8));
    }

    @Test
    void anInjectedReadBorrowCannotReadPrivateRepresentationDirectly() {
        String source = PRIVATE_CLASS + """
                fnc leak(borrow Inventory state): int {
                    return state.count;
                }
                """;
        Ast.Program parsed = assertDoesNotThrow(() -> Parser.parse(source));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.analyze(parsed));
        assertTrue(error.getMessage().contains("private field"), error::getMessage);
    }

    @Test
    void anInjectedMutableBorrowCannotWritePrivateRepresentationDirectly() {
        String source = PRIVATE_CLASS + """
                fnc leak(borrow mut Inventory state): void {
                    state.count = 100;
                    return;
                }
                """;
        Ast.Program parsed = assertDoesNotThrow(() -> Parser.parse(source));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.analyze(parsed));
        assertTrue(error.getMessage().contains("private field"), error::getMessage);
    }

    @Test
    void compilerRewritersDoNotEraseStateBackingCapability() {
        String source = """
                @StateBacked(MutableStateContainer(scope = #instance))
                define pub class Store as
                    let int value = 5;
                    pub read(): int { return self.value; }
                end
                """;
        Ast.Program checked = OresCompiler.parseAndTypeCheck(source);
        Ast.Program expanded = AnnotationExpander.expand(checked);
        Ast.Program optimized = TreeShaker.shake(
                expanded, BuildOptions.library(Map.of())).program();
        Ast.ClassDecl retained = optimized.modules().stream()
                .flatMap(m -> m.declarations().stream())
                .filter(d -> d instanceof Ast.ClassDecl)
                .map(d -> (Ast.ClassDecl) d)
                .filter(k -> k.name().equals("Store"))
                .findFirst().orElseThrow();
        assertEquals("StateBacked", retained.annotations().getFirst().name());
        assertEquals("$state_instance$",
                retained.annotations().getFirst().arguments().getFirst().name());
    }

    @Test
    void readonlyBorrowCannotInvokeExclusiveMutableOperation() {
        String source = """
                @StateBacked(MutableStateContainer(scope = #instance))
                define class Inventory as
                    let int available = 10;
                    pub reserve(mut self Inventory)(int amount): bool {
                        self.available = self.available - amount;
                        return true;
                    }
                end
                fnc invalid(borrow Inventory state): void {
                    state.reserve(1);
                    return;
                }
                """;
        Ast.Program parsed = assertDoesNotThrow(() -> Parser.parse(source));
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.analyze(parsed));
        assertTrue(denied.getMessage().toLowerCase()
                .matches("(?s).*(mutab|borrow|receiver).*"), denied::getMessage);
    }

    @Test
    void moveOnlyBackingCannotBeImplicitlyShared() {
        String source = """
                @StateBacked(MutableStateContainer(scope = #instance))
                define class Inventory as
                    let int available = 10;
                    pub read(): int { return self.available; }
                end
                pub fnc main(): int {
                    let mut Inventory state = new Inventory();
                    rt share state;
                    return 0;
                }
                """;
        Ast.Program parsed = assertDoesNotThrow(() -> Parser.parse(source));
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.analyze(parsed));
        assertTrue(denied.getMessage().contains("rt share"), denied::getMessage);
    }

    @Test
    void stateBackingRejectsReferenceReachabilityAndOpaqueFieldStorage() {
        for (Ast.TypeRef unsafe : new Ast.TypeRef[]{
                Ast.TypeRef.simple("Pointer"),
                new Ast.TypeRef("Pointer", java.util.List.of(Ast.TypeRef.simple("int")), false),
                Ast.TypeRef.simple("Channel"),
                Ast.TypeRef.simple("Inventory"),
                Ast.TypeRef.borrowed(Ast.TypeRef.simple("int"), true),
                Ast.TypeRef.inferred()
        }) {
            Ast.FieldDecl field = new Ast.FieldDecl(
                    "slot", Ast.Visibility.PRIVATE, Ast.BindingKind.LET,
                    unsafe, new Ast.LiteralExpr(1L));
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> backedClass(java.util.List.of(), field));
            assertTrue(error.getMessage().contains("scalar field"), error::getMessage);
        }
    }

    @Test
    void generatedAccessorAnnotationsCannotExposeBackedPrivateState() {
        Ast.FieldDecl annotated = new Ast.FieldDecl(
                "slot", Ast.Visibility.PRIVATE, Ast.BindingKind.LET,
                Ast.TypeRef.simple("int"),
                java.util.List.of(new Ast.Annotation("FromJson",
                        java.util.List.of(Ast.TypeRef.stringLiteral("slot")))),
                new Ast.LiteralExpr(1L));
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> backedClass(java.util.List.of(), annotated));
        assertTrue(denied.getMessage().contains("generated field accessors"),
                denied::getMessage);
    }

    @Test
    void ordinaryClassFromJsonAnnotationIsUnaffectedByStateBackingRestrictions() {
        String source = """
                define class Plain as
                    @FromJson("slot")
                    let int slot = 1;
                end
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void stateBackingRejectsUninitializedAndImmutableStorageAndGenericLayouts() {
        Ast.FieldDecl mutable = new Ast.FieldDecl(
                "slot", Ast.Visibility.PRIVATE, Ast.BindingKind.LET,
                Ast.TypeRef.simple("int"), new Ast.LiteralExpr(1L));
        assertThrows(IllegalArgumentException.class,
                () -> backedClass(java.util.List.of("T"), mutable));
        assertThrows(IllegalArgumentException.class,
                () -> backedClass(java.util.List.of(),
                        new Ast.FieldDecl("slot", Ast.Visibility.PRIVATE,
                                Ast.BindingKind.LET, Ast.TypeRef.simple("int"), null)));
        assertThrows(IllegalArgumentException.class,
                () -> backedClass(java.util.List.of(),
                        new Ast.FieldDecl("slot", Ast.Visibility.PRIVATE,
                                Ast.BindingKind.CONST, Ast.TypeRef.simple("int"),
                                new Ast.LiteralExpr(1L))));
    }

    private static Ast.ClassDecl backedClass(
            java.util.List<String> generics, Ast.FieldDecl field) {
        return new Ast.ClassDecl(
                "Probe", Ast.Visibility.PRIVATE, false, Ast.ActorKind.NONE,
                generics, java.util.List.of(), java.util.List.of(),
                java.util.List.of(field), null, java.util.List.of(),
                Ast.ActorConcurrency.serial(Ast.ActorTransport.GENERIC),
                java.util.List.of(new Ast.Annotation("StateBacked",
                        java.util.List.of(Ast.TypeRef.simple("$state_instance$")))));
    }

    @Test
    void inactiveAnnotationsInNestedCommentsDoNotGrantStateBackingAuthority() {
        String source = """
                /* @StateBacked(MutableStateContainer(scope = #shared))
                   /* @Inject(SecretProvider) is also a comment */
                */
                define class Plain as
                    let int value = 5;
                end
                """;
        Ast.Program checked = OresCompiler.parseAndTypeCheck(source);
        Ast.ClassDecl plain = checked.modules().stream()
                .flatMap(m -> m.declarations().stream())
                .filter(d -> d instanceof Ast.ClassDecl)
                .map(d -> (Ast.ClassDecl) d).findFirst().orElseThrow();
        assertTrue(plain.annotations().isEmpty(),
                "commented annotations cannot manufacture backing capabilities");
    }

    @Test
    void unannotatedClassStillRetainsPreviousFrontendBehavior() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(
                "define class Plain as let int count = 0; end\n"));
    }
}
