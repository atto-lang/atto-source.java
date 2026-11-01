package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * First executable owned-state injection slice.
 * This is NOT shared state, actors, or critical[read/write] runtime lowering.
 * No synchronization/GC guarantees are inferred from scalar guest execution.
 */
final class InjectedOwnedStateV0Test {
    private static final String COUNTER = """
            @Inject(MutableStateContainer())
            define class Counter as
              state {
                count: int = 0;
              }

              pub increment(mut self Counter)(): void {
                self.count = self.count + 1;
                return;
              }

              pub get_count(): int {
                return self.count;
              }
            end
            """;

    private static void parseReject(String source, String diagnostic) {
        var error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(source));
        assertTrue(error.getMessage().contains(diagnostic), error::getMessage);
    }

    private static void analyzeReject(String source, String diagnostic) {
        var parsed = assertDoesNotThrow(() -> Parser.parse(source),
                "semantic rejection cannot be passed by a parser error");
        var error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.analyze(parsed));
        assertTrue(error.getMessage().toLowerCase().contains(diagnostic),
                error::getMessage);
    }

    @Test
    void two_private_owned_instances_execute_independently_as_real_guest()
            throws Exception {
        String source = COUNTER + """
                pub routine main(): void {
                  let mut Counter first = new Counter();
                  let mut Counter second = new Counter();
                  first.increment();
                  second.increment();
                  second.increment();
                  stdio.stdout.write(first.get_count());
                  stdio.stdout.write("|");
                  stdio.stdout.write(second.get_count());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        var out = new ByteArrayOutputStream();
        try (var context = Context.newBuilder("ores").allowAllAccess(true).out(out).build()) {
            context.eval(Source.newBuilder("ores", source, "injected-owned-state-v0.ores").build());
        }
        assertEquals("1|2", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void private_state_read_is_denied_outside_owner() {
        analyzeReject(COUNTER + """
                fnc invalid(): int {
                  let mut Counter counter = new Counter();
                  return counter.count;
                }
                """, "private field");
    }

    @Test
    void private_state_write_is_denied_outside_owner_even_with_mutable_loan() {
        analyzeReject(COUNTER + """
                fnc invalid(borrow mut Counter borrowed): void {
                  borrowed.count = 100;
                  return;
                }
                """, "private field");
    }

    @Test
    void immutable_heap_loan_cannot_call_mutating_state_method() {
        analyzeReject(COUNTER + """
                fnc invalid(borrow Counter borrowed): void {
                  borrowed.increment();
                  return;
                }
                """, "mutab");
    }

    @Test
    void unannotated_class_cannot_sneak_in_state_block() {
        parseReject("""
                define class Counter as
                  state { count: int = 0; }
                end
                """, "requires @Inject");
    }

    @Test
    void annotated_class_requires_a_state_block() {
        parseReject("""
                @Inject(MutableStateContainer())
                define class Counter as
                  let int count = 0;
                end
                """, "inside state");
    }

    @Test
    void custom_constructor_and_inheritance_are_not_lowered_yet() {
        parseReject("""
                @Inject(MutableStateContainer())
                define class Counter extends Base as
                  state { count: int = 0; }
                end
                """, "non-inheriting");
        parseReject("""
                @Inject(MutableStateContainer())
                define class Counter as
                  state { count: int = 0; }
                  constructor() { return; }
                end
                """, "custom constructors");
    }

    @Test
    void duplicate_blocks_and_fields_are_not_accepted() {
        parseReject("""
                @Inject(MutableStateContainer())
                define class Counter as
                  state { count: int = 0; }
                  state { other: int = 0; }
                end
                """, "duplicate injected state");
        parseReject("""
                @Inject(MutableStateContainer())
                define class Counter as
                  state { count: int = 0; count: int = 1; }
                end
                """, "duplicate injected mutable state field");
    }

    @Test
    void no_uninitialized_or_unproved_nested_heap_fields() {
        parseReject("""
                @Inject(MutableStateContainer())
                define class Counter as
                  state { count: int; }
                end
                """, "initialized scalar");
        parseReject("""
                @Inject(MutableStateContainer())
                define class Counter as
                  state { values: Array<int> = new Array<int>(); }
                end
                """, "initialized scalar");
    }

    @Test
    void mutable_state_cannot_be_public_or_declared_outside_state_region() {
        parseReject("""
                @Inject(MutableStateContainer())
                define class Counter as
                  state { count: int = 0; }
                  pub let int leaked = 0;
                end
                """, "inside state");
        parseReject("""
                @Inject(MutableStateContainer())
                define class Counter as
                  pub state { count: int = 0; }
                end
                """, "cannot carry modifiers");
    }

    @Test
    void malformed_annotation_parameters_are_rejected() {
        parseReject("""
                @Inject(MutableStateContainer(scope = #singleton))
                define class Counter as
                  state { count: int = 0; }
                end
                """, "no constructor arguments");
    }
}
