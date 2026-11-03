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

    private static final String READ_COUNTER = """
            define state container Counter as
              state { let int count: 11; }
              peek(int ignored) -> int ::
                return handle_state(critical-read |region| -> {
                  region.count;
                });
              end
            end
            """;

    @Test void checkedReadRegionReturnsOnlyOwnedScalarWithoutMutatingState() {
        var read = OresCompiler.compileProtectedScalarI64(READ_COUNTER);
        assertEquals(ProtectedScalarMirV1.Kind.STATE_CONTAINER, read.kind());
        assertTrue(read.readOnly());
        assertEquals(ProtectedScalarMirV1.Instruction.ACQUIRE_READ,
                read.instructions().getFirst());
        assertEquals(ProtectedScalarMirV1.Instruction.RELEASE_READ,
                read.instructions().get(2));
        assertEquals(4, read.instructions().size());
        assertEquals(read, ProtectedScalarMirV1.lower(Parser.parse(READ_COUNTER)));
        try (var state = read.new_state_container()) {
            assertEquals(11L, read.invoke(state, Long.MIN_VALUE));
            assertEquals(11L, read.invoke(state, Long.MAX_VALUE));
            assertEquals(11L, state.tryRead().orElseThrow(),
                    "a READ plan cannot modify protected state");
            assertEquals(23L, state.tryAddChecked(12).orElseThrow());
            assertEquals(23L, read.invoke(state, 1));
            state.close();
            assertThrows(IllegalStateException.class, () -> read.invoke(state, 0));
            assertTrue(state.readyForReclamation());
        }
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(READ_COUNTER),
                "general TypeChecker must not silently admit protected source");
    }

    @Test void mutationUnderReadLeaseHasDistinctSemanticDiagnostic() {
        for (String mutation : java.util.List.of(
                READ_COUNTER.replace("region.count;", "region.count = region.count;"),
                READ_COUNTER.replace("region.count;", "region.count = region.count + ignored;"),
                COUNTER.replace("critical-write", "critical-read"))) {
            IllegalArgumentException denied = assertThrows(
                    IllegalArgumentException.class,
                    () -> ProtectedScalarMirV1.lower(mutation), mutation);
            assertTrue(denied.getMessage().contains("E_CRITICAL_READ_MUTATION"),
                    denied::toString);
        }
    }

    @Test void protectedRegionAliasCannotEscapeReadOrWriteScope() {
        for (String escape : java.util.List.of(
                READ_COUNTER.replace("region.count;", "region;"),
                COUNTER.replace("s.count;\n", "s;\n"))) {
            IllegalArgumentException denied = assertThrows(
                    IllegalArgumentException.class,
                    () -> OresCompiler.compileProtectedScalarI64(escape), escape);
            assertTrue(denied.getMessage().contains("E_STATE_BORROW_ESCAPE"),
                    denied::toString);
        }
    }

    @Test void unsupportedProtectedEffectsAndUnsafeModeChangesRemainClosed() {
        // An unknown method/call effect does not receive an invented READ
        // proof. Other guard kinds and unverified returns are also denied.
        assertRejected(READ_COUNTER.replace("region.count;", "region.count.get(ignored);"));
        assertRejected(READ_COUNTER.replace("critical-read", "critical-write"));
        assertRejected(READ_COUNTER.replace("critical-read", "critical-unknown"));
        assertRejected(READ_COUNTER.replace("region.count;", "region.unknown;"));
        assertRejected(READ_COUNTER.replace("|region|", "|ignored|"));
        assertRejected(CART.replace("critical-write", "critical-read"));
        var forged = assertThrows(IllegalArgumentException.class, () ->
                new ProtectedScalarMirV1.Plan(
                        ProtectedScalarMirV1.Kind.STATE_CONTAINER, "Counter",
                        "count", "peek", "unused", 11L,
                        java.util.List.of(
                                ProtectedScalarMirV1.Instruction.ACQUIRE_READ,
                                ProtectedScalarMirV1.Instruction.STORE_STATE_I64,
                                ProtectedScalarMirV1.Instruction.RELEASE_READ,
                                ProtectedScalarMirV1.Instruction.RETURN_I64)));
        assertTrue(forged.getMessage().contains("invalid acquire/release"),
                forged::toString);
    }

    @Test void actualAttoFilesUseBoundedSourceReaderAndRestrictedMir() throws Exception {
        var sourcePath = java.nio.file.Path.of("examples/attolang/protected-read-scalar.atto");
        var mutationPath = java.nio.file.Path.of("examples/attolang/negative-read-mutation.atto");
        var escapePath = java.nio.file.Path.of("examples/attolang/negative-region-alias-escape.atto");

        // The .atto files are read through the bounded, strict UTF-8 gate.
        // Only the dedicated opt-in scalar MIR is eligible to execute.
        String source = dev.oreslang.runtime.BoundedSourceReader.readUtf8(sourcePath);
        var plan = OresCompiler.compileProtectedScalarI64(source);
        assertTrue(plan.readOnly());
        try (var state = plan.new_state_container()) {
            assertEquals(41L, plan.invoke(state, -1));
            assertEquals(41L, state.tryRead().orElseThrow());
        }
        var mutation = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.compileProtectedScalarI64(
                        dev.oreslang.runtime.BoundedSourceReader.readUtf8(mutationPath)));
        assertTrue(mutation.getMessage().contains("E_CRITICAL_READ_MUTATION"),
                mutation::toString);
        var escape = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.compileProtectedScalarI64(
                        dev.oreslang.runtime.BoundedSourceReader.readUtf8(escapePath)));
        assertTrue(escape.getMessage().contains("E_STATE_BORROW_ESCAPE"),
                escape::toString);
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source),
                "general protected-state MIR remains deliberately closed");
    }

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
