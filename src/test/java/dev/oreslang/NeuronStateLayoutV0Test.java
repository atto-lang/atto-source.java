package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.NeuronStateLayoutV0;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Host-side type/layout tests: these are NOT compiled guest inference tests. */
final class NeuronStateLayoutV0Test {
    private static final String SOURCE = """
            define neuron actor LinearNeuron as
              state {
                let float weight;
                let float bias;
              }
              pub forward(float input): float { return input; }
              receive(ActorMail<float> mail): void { self.end(); return; }
            end
            """;

    private static Ast.ClassDecl actor(String source) {
        Ast.Program parsed = Parser.parse(source);
        return (Ast.ClassDecl) parsed.modules().getFirst().declarations().getFirst();
    }

    @Test void checkedLayoutAllocatesIsolatedGenerationScopedHostState() {
        var layout = NeuronStateLayoutV0.fromActor(actor(SOURCE));
        assertEquals("LinearNeuron", layout.actorName());
        assertEquals(java.util.List.of("weight", "bias"), layout.floatFields());
        Object firstHandler = new Object(), secondHandler = new Object();
        try (var first = layout.newHostState(12, 3);
             var next = layout.newHostState(13, 3)) {
            try (var loan = first.acquireWriteAsync(firstHandler, 12).join()) {
                first.writeFloat(loan, firstHandler, 12, "weight", 2.0);
                first.writeFloat(loan, firstHandler, 12, "bias", 1.0);
            }
            try (var reader = first.acquireReadAsync(secondHandler, 12).join()) {
                assertEquals(7.0,
                        3.0 * first.readFloat(reader, secondHandler, 12, "weight")
                                + first.readFloat(reader, secondHandler, 12, "bias"));
            }
            assertTrue(next.acquireReadAsync(secondHandler, 12).isCompletedExceptionally());
            try (var fresh = next.acquireReadAsync(secondHandler, 13).join()) {
                assertThrows(IllegalStateException.class,
                        () -> next.readFloat(fresh, secondHandler, 13, "weight"),
                        "restarted generation must not inherit old neuron weights");
            }
        }
    }

    @Test void scalarSubsetRejectsNonFloatStateAndImplicitInitializerSideEffects() {
        String[] mutations = {
                SOURCE.replace("let float bias;", "let int bias;"),
                SOURCE.replace("let float weight;", "let f64 weight;"),
                SOURCE.replace("let float weight;", "let float weight = 2.0;"),
                SOURCE.replace("let float bias;", "let float bias = 1.0;")
        };
        for (String mutated : mutations) {
            var schema = assertThrows(IllegalArgumentException.class,
                    () -> NeuronStateLayoutV0.fromActor(actor(mutated)));
            assertTrue(schema.getMessage().contains("NEURON_STATE_LAYOUT_UNSUPPORTED"),
                    schema::getMessage);
            var compiler = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(mutated)));
            assertTrue(compiler.getMessage().contains("NEURON_STATE_LAYOUT_UNSUPPORTED"),
                    compiler::getMessage);
        }
    }

    @Test void standardCompilerStillRejectsUnloweredNeuronExecutableGuestCode() {
        var blocked = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(SOURCE)));
        assertTrue(blocked.getMessage().contains("NEURON_STATE_MIR_UNSUPPORTED"));
    }

    @Test void forgedRegularActorCannotConstructNeuronStateLayout() {
        assertThrows(IllegalArgumentException.class, () ->
                NeuronStateLayoutV0.fromActor(actor("""
                        define actor Ordinary as
                          let float weight;
                        end
                        """)));
        assertThrows(IllegalArgumentException.class,
                () -> new NeuronStateLayoutV0("X", java.util.List.of("x", "x")));
        assertThrows(IllegalArgumentException.class,
                () -> new NeuronStateLayoutV0("X", java.util.List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new NeuronStateLayoutV0("X", java.util.List.of("$internal")));
        assertThrows(IllegalArgumentException.class,
                () -> new NeuronStateLayoutV0("X".repeat(129), java.util.List.of("weight")));
        assertThrows(IllegalArgumentException.class,
                () -> new NeuronStateLayoutV0("X", java.util.List.of("w".repeat(129))));
    }
}
