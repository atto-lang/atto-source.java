package dev.oreslang;

import dev.oreslang.compiler.ScalarDeclarationI64Compiler;
import dev.oreslang.runtime.LocalVirtualActorI64;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

final class ScalarDeclarationI64CompilerTest {
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

    @Test void checkedStateSourceReallyExecutes() {
        var def = ScalarDeclarationI64Compiler.compile(COUNTER);
        assertEquals(ScalarDeclarationI64Compiler.Kind.STATE_CONTAINER, def.kind());
        assertEquals("Counter", def.name());
        try (var counter = def.new_state_container()) {
            assertEquals(5, def.invoke(counter, 5));
            assertEquals(12, def.invoke(counter, 7));
            assertEquals(12, counter.tryRead().orElseThrow());
        }
    }

    @Test void virtualActorSourceAdmitsMessagesAndReportsCheckedOverflowAsPanic() {
        var def = ScalarDeclarationI64Compiler.compile(CART);
        var notices = new ArrayList<LocalVirtualActorI64.Notice>();
        try (var runtime = def.new_virtual_runtime("local", 5, 2, notices::add)) {
            var ref = runtime.resolve("cart-42");
            var a = runtime.try_send(ref, Long.MAX_VALUE);
            var b = runtime.try_send(ref, 1);
            assertTrue(a.admitted() && b.admitted());
            assertTrue(runtime.run_one(ref));
            assertEquals(LocalVirtualActorI64.Outcome.PROCESSED, a.completion().join());
            assertTrue(runtime.run_one(ref));
            assertEquals(LocalVirtualActorI64.Outcome.FAILED_CONTROL_NOTIFIED, b.completion().join());
            assertEquals(1, notices.size());
            assertEquals(1, notices.getFirst().failedAcceptedCount());
            assertEquals(ArithmeticException.class.getName(), notices.getFirst().errorClass());
        }
    }

    @Test void contextualWordsMustBeIdentifierTokensNotQuotedStringLookalikes() {
        for (String word : java.util.List.of("state", "container", "int", "handle_state", "critical", "write")) {
            String spoofed = COUNTER.replace(word, Character.toString(34) + word + Character.toString(34));
            var error = assertThrows(IllegalArgumentException.class,
                    () -> ScalarDeclarationI64Compiler.compile(spoofed), word);
            assertTrue(error.getMessage().contains("SCALAR_DECLARATION_UNSUPPORTED"), word);
        }
    }

    @Test void badAliasesBorrowOrUnknownSourceFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> ScalarDeclarationI64Compiler.compile(COUNTER.replace("s.count + amount", "s.count + 5")));
        assertThrows(IllegalArgumentException.class,
                () -> ScalarDeclarationI64Compiler.compile(COUNTER.replace("s.count = s.count", "s.other = s.count")));
        assertThrows(IllegalArgumentException.class,
                () -> ScalarDeclarationI64Compiler.compile(COUNTER.replace("let int count", "let Vec<int> count")));
        assertThrows(IllegalArgumentException.class,
                () -> ScalarDeclarationI64Compiler.compile(CART.replace("handle_state(", "await handle_state(")));
        assertThrows(IllegalArgumentException.class,
                () -> ScalarDeclarationI64Compiler.compile(COUNTER + "define actor Unrelated as end"));
        assertThrows(IllegalArgumentException.class,
                () -> ScalarDeclarationI64Compiler.compile("define grain Cart as end"));
        assertThrows(IllegalArgumentException.class,
                () -> ScalarDeclarationI64Compiler.compile(COUNTER.replace("critical-write", "critical-read")));
    }
}
