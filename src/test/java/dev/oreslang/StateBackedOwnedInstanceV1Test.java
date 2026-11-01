package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
    void unannotatedClassStillRetainsPreviousFrontendBehavior() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(
                "define class Plain as let int count = 0; end\n"));
    }
}
