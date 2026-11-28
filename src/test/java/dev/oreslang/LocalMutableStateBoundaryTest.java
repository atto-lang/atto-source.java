package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Local mutation is legal without locks; allocation placement never bypasses
 * lifetime, alias-exclusion, or cooperation admission. These tests exercise
 * real source admission, plus one guest continuation execution.
 */
final class LocalMutableStateBoundaryTest {
    private static final String COUNTER = """
            define class Counter as
              pub let int value = 0;
            end
            """;

    private static void accepts(String source) {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
    }

    private static void rejects(String source, String diagnostic) {
        // Parse independently so syntax rejection cannot masquerade as a
        // successful ownership rejection.
        var parsed = assertDoesNotThrow(() -> dev.oreslang.parser.Parser.parse(source));
        var error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.analyze(parsed));
        assertTrue(error.getMessage().contains(diagnostic), error::getMessage);
    }

    @Test
    void local_scalar_mutation_needs_no_mutex_and_survives_cooperation() {
        accepts("""
                pub async fnc main(): void {
                  let int count = 1;
                  count = count + 1;
                  rt cooperate;
                  count = count + 1;
                  return;
                }
                """);
    }

    @Test
    void exclusively_owned_heap_state_also_needs_no_mutex() {
        accepts(COUNTER + """
                pub async fnc main(): void {
                  let mut Counter state = new Counter();
                  state.value = 1;
                  rt cooperate;
                  state.value = 2;
                  return;
                }
                """);
    }

    @Test
    void lexical_read_loan_ends_before_owner_mutation_and_cooperation() {
        accepts(COUNTER + """
                pub async fnc main(): void {
                  let mut Counter state = new Counter();
                  if true; do
                    const view = rt borrow state;
                    const int observed = view.value;
                  fi
                  state.value = 2;
                  rt cooperate;
                  return;
                }
                """);
    }

    @Test
    void exclusive_parameter_allows_a_synchronous_state_operation() {
        accepts(COUNTER + """
                fnc increment(borrow mut Counter state): void {
                  state.value = state.value + 1;
                  return;
                }
                fnc valid(): void {
                  let mut Counter state = new Counter();
                  increment(rt mut borrow state);
                  state.value = 2;
                  return;
                }
                """);
    }

    @Test
    void scalar_owner_cannot_be_reassigned_while_read_borrowed() {
        rejects("""
                fnc invalid(): void {
                  let int count = 1;
                  const view = rt borrow count;
                  count = 2;
                  return;
                }
                """, "borrow");
    }

    @Test
    void local_exclusive_loan_excludes_a_second_loan() {
        rejects(COUNTER + """
                fnc invalid(): void {
                  let mut Counter state = new Counter();
                  const writer = rt mut borrow state;
                  const reader = rt borrow state;
                  return;
                }
                """, "borrow");
    }

    @Test
    void stack_candidate_reference_cannot_escape_by_return() {
        rejects("""
                fnc invalid(): borrow int {
                  let int count = 1;
                  return rt borrow count;
                }
                """, "borrow");
    }

    @Test
    void local_read_loan_cannot_escape_into_a_closure() {
        rejects(COUNTER + """
                fnc invalid(): void {
                  let mut Counter state = new Counter();
                  const view = rt borrow state;
                  const callback = || -> view.value;
                  return;
                }
                """, "borrow");
    }

    @Test
    void local_read_loan_must_end_before_explicit_cooperation() {
        rejects(COUNTER + """
                pub async fnc main(): void {
                  let mut Counter state = new Counter();
                  const view = rt borrow state;
                  rt cooperate;
                  return;
                }
                """, "borrow");
    }

    @Test
    void local_exclusive_loan_must_end_before_explicit_cooperation() {
        rejects(COUNTER + """
                pub async fnc main(): void {
                  let mut Counter state = new Counter();
                  const writer = rt mut borrow state;
                  rt cooperate;
                  return;
                }
                """, "borrow");
    }

    @Test
    void acquired_guard_cannot_cross_explicit_cooperation() {
        rejects(COUNTER + """
                pub async fnc main(): void {
                  const mutex = Mutex.new(new Counter());
                  const guard = mutex.lock();
                  rt cooperate;
                  guard.release();
                  return;
                }
                """, "MutexGuard");
    }

    @Test
    void released_guard_allows_explicit_cooperation() {
        accepts(COUNTER + """
                pub async fnc main(): void {
                  const mutex = Mutex.new(new Counter());
                  const guard = mutex.lock();
                  guard.value = 7;
                  guard.release();
                  rt cooperate;
                  return;
                }
                """);
    }

    @Test
    void private_scalar_and_owned_state_keep_values_across_real_guest_cooperation()
            throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder("ores")
                .allowAllAccess(true).out(output).build()) {
            context.eval(Source.newBuilder("ores", COUNTER + """
                    pub async routine main(): void {
                      let int count = 1;
                      let mut Counter state = new Counter();
                      state.value = 7;
                      count = count + 1;
                      rt cooperate;
                      count = count + 1;
                      state.value = state.value + 2;
                      stdio.stdout.write(count);
                      stdio.stdout.write("|");
                      stdio.stdout.write(state.value);
                      return;
                    }
                    """, "local-mutable-state-boundary.ores").build());
        }
        assertEquals("3|9", output.toString(StandardCharsets.UTF_8));
    }
}
