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
 * This is a fail-closed precursor, NOT implementation of annotated injection.
 */
final class AnnotatedMutableHeapStateAdmissionTest {
    @Test
    void injected_class_state_does_not_silently_fall_back_to_ordinary_class() {
        String source = """
                @Inject(MutableStateContainer())
                define class Counter as
                  state { count: int = 0; }
                end
                """;
        var error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(source));
        assertTrue(error.getMessage().contains("@Inject"), error::getMessage);
        assertTrue(error.getMessage().contains("not implemented"), error::getMessage);
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
        assertTrue(error.getMessage().contains("@Inject"), error::getMessage);
        assertTrue(error.getMessage().contains("not implemented"), error::getMessage);
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
                  writer.available = 9; // writer is still live: the loans overlap
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
