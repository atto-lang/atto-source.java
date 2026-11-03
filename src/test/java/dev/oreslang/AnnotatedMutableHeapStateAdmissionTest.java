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
 * @Inject(MutableStateContainer()) must never be treated as inert metadata.
 * Existing owned heap mutation remains legal; live borrows keep exclusive
 * ownership and escape restrictions regardless of allocation placement.
 *
 * Stage 1 compiles only exclusively owned initialized scalar state; shared
 * state and runtime-minted critical read/write loans remain unsupported.
 */
final class AnnotatedMutableHeapStateAdmissionTest {
    @Test
    void injected_scalar_state_is_lowered_to_private_mutable_class_storage() {
        String source = """
                @Inject(MutableStateContainer())
                define class Counter as
                  state { count: int = 0; }
                end
                """;
        var checked = assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        var counter = assertInstanceOf(dev.oreslang.ast.Ast.ClassDecl.class,
                checked.modules().getFirst().declarations().getFirst());
        assertEquals(1, counter.fields().size());
        var field = counter.fields().getFirst();
        assertEquals("count", field.name());
        assertEquals(dev.oreslang.ast.Ast.Visibility.PRIVATE, field.visibility());
        assertEquals(dev.oreslang.ast.Ast.BindingKind.LET, field.bindingKind());
    }

    @Test
    void injection_onto_an_ordinary_class_does_not_erase_storage_policy() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        @Inject(MutableStateContainer())
                        define class Counter as
                          let int value = 0;
                        end
                        """));
        assertTrue(error.getMessage().contains("@Inject"), error::getMessage);
    }

    @Test
    void angle_annotation_arguments_cannot_bypass_injected_state_gate() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        @Inject<MutableStateContainer()>
                        define class Counter as
                          let int value = 0;
                        end
                        """));
        assertTrue(error.getMessage().contains("STATE_INJECTION_UNSUPPORTED: @Inject"), error::getMessage);
    }

    @Test
    void injected_mutable_state_with_whitespace_still_rejects() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        @Inject (
                          MutableStateContainer()
                        )
                        define class Counter as
                          let int value = 0;
                        end
                        """));
        assertTrue(error.getMessage().contains("@Inject"), error::getMessage);
        assertTrue(error.getMessage().contains("inside state"), error::getMessage);
    }

    @Test
    void borrowed_heap_object_cannot_be_written_through_another_alias() {
        String source = """
                define class Inventory as
                  pub let int available = 10;
                end

                fnc invalid(): void {
                  let mut Inventory owner = new Inventory();
                  const view = rt borrow owner;
                  owner.available = 5;
                  stdio.stdout.write(view.available);
                  return;
                }
                """;
        var parsed = assertDoesNotThrow(() -> Parser.parse(source));
        var error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.analyze(parsed));
        assertTrue(error.getMessage().toLowerCase().contains("borrow"), error::getMessage);
    }

    @Test
    void two_mutable_heap_loans_may_not_coexist() {
        String source = """
                define class Inventory as
                  pub let int available = 10;
                end

                fnc invalid(): void {
                  let mut Inventory owner = new Inventory();
                  const writer = rt mut borrow owner;
                  const another_writer = rt mut borrow owner;
                  return;
                }
                """;
        var parsed = assertDoesNotThrow(() -> Parser.parse(source));
        var error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.analyze(parsed));
        assertTrue(error.getMessage().toLowerCase().contains("borrow"), error::getMessage);
    }

    @Test
    void exclusive_owned_heap_mutation_and_ended_read_loan_execute_as_guest()
            throws Exception {
        String source = """
                define class Inventory as
                  pub let int available = 10;
                end

                pub routine main(): void {
                  let mut Inventory owner = new Inventory();
                  owner.available = 7;
                  if true; do
                    const view = rt borrow owner;
                    stdio.stdout.write(view.available);
                  fi
                  owner.available = 8;
                  stdio.stdout.write(owner.available);
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder("ores").allowAllAccess(true).out(out).build()) {
            context.eval(Source.newBuilder("ores", source, "owned-heap-state.ores").build());
        }
        assertEquals("78", out.toString(StandardCharsets.UTF_8));
    }
}
