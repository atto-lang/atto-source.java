package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.compiler.ProtectedScalarMirV1;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.LocalVirtualActorI64;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the opt-in production AST -> restricted typed MIR bridge, while
 * keeping the general TypeChecker fail-closed for unlowered ownership effects.
 */
final class ProtectedScalarMirV1Test {
    private static final String COUNTER = """
            define state container Counter as
              state { let int count: 0; }
              increment(int amount) -> int ::
                return handle_state(critical-write |s| -> {
                  s.count = s.count + amount;
                  s.count;
                });
              end
            end
            """;
    private static final String CART = """
            define virtual actor ShoppingCart as
              state { let int quantity: 0; }
              add(int n) -> void ::
                handle_state(critical-write |s| -> {
                  s.quantity = s.quantity + n;
                });
              end
            end
            """;

    @Test void actualParserAndCompilerEntryPointProduceCheckedMIR() {
        var checked = OresCompiler.compileProtectedScalarI64(COUNTER);
        assertEquals(ProtectedScalarMirV1.Kind.STATE_CONTAINER, checked.kind());
        assertEquals(ProtectedScalarMirV1.Instruction.ACQUIRE_WRITE,
                checked.instructions().getFirst());
        assertEquals(ProtectedScalarMirV1.Instruction.RETURN_I64,
                checked.instructions().getLast());
        assertTrue(checked.instructions().contains(ProtectedScalarMirV1.Instruction.RELEASE_WRITE));
        assertEquals(checked, ProtectedScalarMirV1.lower(Parser.parse(COUNTER)));
        try (var state = checked.new_state_container()) {
            assertEquals(8L, checked.invoke(state, 8));
            state.trySet(Long.MAX_VALUE);
            assertThrows(ArithmeticException.class, () -> checked.invoke(state, 1));
            assertEquals(Long.MAX_VALUE, state.tryRead().orElseThrow());
        }
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(COUNTER));
    }

    @Test void virtualActorUsesFencedMailboxAndSettlesOverflowControl() {
        var plan = ProtectedScalarMirV1.lower(CART);
        assertEquals(ProtectedScalarMirV1.Kind.VIRTUAL_ACTOR, plan.kind());
        assertEquals(ProtectedScalarMirV1.Instruction.RETURN_VOID,
                plan.instructions().getLast());
        var notices = new ArrayList<LocalVirtualActorI64.Notice>();
        try (var runtime = plan.new_virtual_runtime("local", 3, 2, notices::add)) {
            var ref = runtime.resolve("cart");
            var a = runtime.try_send(ref, Long.MAX_VALUE);
            var b = runtime.try_send(ref, 1);
            assertTrue(a.admitted() && b.admitted());
            assertTrue(runtime.run_one(ref));
            assertEquals(LocalVirtualActorI64.Outcome.PROCESSED, a.completion().join());
            assertTrue(runtime.run_one(ref));
            assertEquals(LocalVirtualActorI64.Outcome.FAILED_CONTROL_NOTIFIED,
                    b.completion().join());
            assertEquals(1, notices.size());
            assertEquals(1, notices.getFirst().failedAcceptedCount());
        }
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(CART));
    }

    @Test void tokenKindSpoofingAndParameterShadowingFailClosed() {
        for (String source : java.util.List.of(
                COUNTER.replace("return handle_state", "\"return\" handle_state"),
                COUNTER.replace("handle_state(", "\"handle_state\"("),
                COUNTER.replace("critical-write", "\"critical\"-write"),
                COUNTER.replace("critical-write", "critical-\"write\""),
                COUNTER.replace("handle_state(", "#handle_state("),
                COUNTER.replace("critical-write", "#critical-write"),
                COUNTER.replace("|s|", "|\"s\"|"),
                COUNTER.replace("s.count + amount", "s.count + \"amount\""),
                COUNTER.replace("|s|", "|amount|").replace("s.count", "amount.count"))) {
            assertRejected(source);
        }
        var method = ((Ast.StateContainerDecl) Parser.parse(COUNTER).modules().getFirst()
                .declarations().getFirst()).methods().getFirst();
        assertEquals(method.bodyTokens().size(), method.bodyTokenKinds().size());
        assertTrue(method.bodyTokenKinds().contains("RETURN"));
        assertTrue(method.bodyTokenKinds().contains("IDENT"));
    }

    @Test void manuallyConstructedTokenlessMethodCannotLower() {
        Ast.Program parsed = Parser.parse(COUNTER);
        var module = parsed.modules().getFirst();
        var original = (Ast.StateContainerDecl) module.declarations().getFirst();
        var method = original.methods().getFirst();
        var unverified = new Ast.ProtectedMethod(method.name(), method.parameters(),
                method.returnType(), method.bodyTokens());
        var declaration = new Ast.StateContainerDecl(original.name(), original.visibility(),
                original.stateFields(), java.util.List.of(unverified));
        var program = new Ast.Program(parsed.namespace(), parsed.imports(),
                java.util.List.of(new Ast.ModuleDecl(module.name(), module.annotations(),
                        module.contracts(), java.util.List.of(declaration))));
        assertThrows(IllegalArgumentException.class, () -> ProtectedScalarMirV1.lower(program));
    }

    @Test void noUnprovenHeapOrEscapedStateCanBeLowered() {
        assertRejected(COUNTER.replace("critical-write", "critical-read"));
        assertRejected(COUNTER.replace("s.count = s.count + amount", "s.count = amount"));
        assertRejected(COUNTER.replace("s.count + amount", "s.count + 9"));
        assertRejected(COUNTER.replace("let int count: 0", "let Vec<int> count: 0"));
        assertRejected(COUNTER.replace("let int count: 0", "let int count: make()"));
        assertRejected(COUNTER.replace("handle_state(", "await handle_state("));
        assertRejected(COUNTER.replace("  end\nend",
                "  end\n  another(int x) -> void :: return; end\nend"));
        assertRejected(COUNTER + "define actor Other as end");
        assertRejected("define grain ShoppingCart as end");
    }

    private static void assertRejected(String source) {
        assertThrows(IllegalArgumentException.class, () -> ProtectedScalarMirV1.lower(source));
    }
}
