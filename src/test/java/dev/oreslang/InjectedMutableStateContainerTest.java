package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Existing native Oreslang classes are an incremental state-container
 * substrate: the owner controls mutation; an injected borrow grants scoped
 * mutation authority without introducing a shared lock or heap-backed guard.
 * These tests do not implement partitioned concurrency or durable state.
 */
final class InjectedMutableStateContainerTest {
    private static final String INVENTORY = """
            define class InventoryState as
              pub let int available = 10;
              pub let int reserved = 0;

              pub try_reserve(mut self InventoryState)(int amount): bool {
                if amount < 0; then return false; fi
                if amount > self.available; then return false; fi
                self.available = self.available - amount;
                self.reserved = self.reserved + amount;
                return true;
              }
            end
            """;

    @Test
    void exclusive_owned_container_can_mutate_joint_invariant_without_a_mutex() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(INVENTORY + """
                pub async fnc main(): void {
                  let mut InventoryState state = new InventoryState();
                  state.try_reserve(2);
                  rt cooperate;
                  state.try_reserve(3);
                  return;
                }
                """));
    }

    @Test
    void injected_mutable_borrow_can_call_state_operation_without_exposing_ownership() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(INVENTORY + """
                fnc checkout(borrow mut InventoryState state): void {
                  state.try_reserve(2);
                  return;
                }
                pub fnc main(): void {
                  let mut InventoryState state = new InventoryState();
                  checkout(rt mut borrow state);
                  return;
                }
                """));
    }

    @Test
    void injected_readonly_borrow_cannot_call_mutable_state_operation() {
        String source = INVENTORY + """
                fnc invalid(borrow InventoryState state): void {
                  state.try_reserve(2);
                  return;
                }
                """;
        var parsed = assertDoesNotThrow(() -> dev.oreslang.parser.Parser.parse(source),
                "a parse error must not pass as a borrow-safety test");
        var error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.analyze(parsed));
        assertTrue(error.getMessage().toLowerCase().matches("(?s).*(mutab|borrow|receiver).*"),
                error::getMessage);
    }
    @Test
    void guest_reservation_is_atomic_with_respect_to_its_two_field_invariant() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder("ores").allowAllAccess(true).out(out).build()) {
            context.eval(Source.newBuilder("ores", INVENTORY + """
                    pub routine main(): void {
                      let mut InventoryState state = new InventoryState();
                      const bool first = state.try_reserve(3);
                      const bool invalid = state.try_reserve(20);
                      stdio.stdout.write(state.available);
                      stdio.stdout.write("|");
                      stdio.stdout.write(state.reserved);
                      stdio.stdout.write("|");
                      stdio.stdout.write(first);
                      stdio.stdout.write("|");
                      stdio.stdout.write(invalid);
                      return;
                    }
                    """, "injected-state-container.ores").build());
        }
        assertEquals("7|3|true|false", out.toString(StandardCharsets.UTF_8));
    }

}
