package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;
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

              pub reserve(mut self InventoryState)(int amount): void {
                self.available = self.available - amount;
                self.reserved = self.reserved + amount;
                return;
              }
            end
            """;

    @Test
    void exclusive_owned_container_can_mutate_joint_invariant_without_a_mutex() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(INVENTORY + """
                pub async fnc main(): void {
                  let mut InventoryState state = new InventoryState();
                  state.reserve(2);
                  rt cooperate;
                  state.reserve(3);
                  return;
                }
                """));
    }

    @Test
    void injected_mutable_borrow_can_call_state_operation_without_exposing_ownership() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(INVENTORY + """
                fnc checkout(borrow mut InventoryState state): void {
                  state.reserve(2);
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
                  state.reserve(2);
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
}
