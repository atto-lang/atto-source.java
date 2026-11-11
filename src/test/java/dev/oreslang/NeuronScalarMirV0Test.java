package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.NeuronScalarMirV0;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.oreslang.compiler.NeuronScalarMirV0.Opcode.*;
import static org.junit.jupiter.api.Assertions.*;

/** Typed MIR admission tests; no .atto guest code is executed by these. */
final class NeuronScalarMirV0Test {
    private static final String SOURCE = """
            define neuron actor LinearNeuron as
              state {
                let float weight;
                let float bias;
              }
              on_start(): void {
                self.handle_state(critical-write |s| -> {
                  s.weight = 2.0;
                  s.bias = 1.0;
                });
                return;
              }
              pub forward(float input): float {
                return self.handle_state(
                  critical-read |s| -> s.weight * input + s.bias
                );
              }
              receive(ActorMail<float> mail): void {
                self.send(self.forward(mail.value));
                self.end();
                return;
              }
            end
            """;

    private static Ast.ClassDecl actor(String source) {
        return (Ast.ClassDecl) Parser.parse(source)
                .modules().getFirst().declarations().getFirst();
    }

    @Test void writesAndReadsProduceClosedTypedStaticInstructionSequences() {
        var plan = NeuronScalarMirV0.lower(actor(SOURCE));
        assertEquals("LinearNeuron", plan.actor());
        assertEquals(List.of("weight", "bias"), plan.layout().floatFields());
        assertEquals(NeuronScalarMirV0.ExitPolicy.RELEASE_IN_FINALLY,
                plan.initialization().exit());
        assertEquals(NeuronScalarMirV0.ExitPolicy.RELEASE_IN_FINALLY,
                plan.forward().exit());
        assertEquals(NeuronScalarMirV0.GuardMode.CRITICAL_WRITE,
                plan.initialization().mode());
        assertEquals(NeuronScalarMirV0.GuardMode.CRITICAL_READ,
                plan.forward().mode());
        assertFalse(plan.initialization().returnsScalar());
        assertTrue(plan.forward().returnsScalar());

        assertEquals(List.of(CONST_F64, STORE_STATE_F64, CONST_F64, STORE_STATE_F64),
                plan.initialization().body().stream().map(
                        NeuronScalarMirV0.Instruction::opcode).toList());
        assertEquals(List.of(LOAD_STATE_F64, LOAD_INPUT_F64, MUL_F64,
                             LOAD_STATE_F64, ADD_F64),
                plan.forward().body().stream().map(
                        NeuronScalarMirV0.Instruction::opcode).toList());
        assertEquals(2.0, plan.initialization().body().getFirst().literal());
        assertEquals("weight", plan.initialization().body().get(1).symbol());
        assertEquals(1.0, plan.initialization().body().get(2).literal());
        assertEquals("bias", plan.initialization().body().get(3).symbol());
        assertEquals("input", plan.forward().body().get(1).symbol());
    }

    @Test void malformedInstructionsCannotForgeReadWritesOrMissingFinally() {
        var example = NeuronScalarMirV0.Instruction.scalar(2.0);
        assertEquals(CONST_F64, example.opcode());
        assertThrows(IllegalArgumentException.class,
                () -> new NeuronScalarMirV0.Instruction(
                        LOAD_STATE_F64, null, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new NeuronScalarMirV0.Instruction(
                        MUL_F64, "symbol", 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new NeuronScalarMirV0.Instruction(
                        MUL_F64, null, 1.0));
        assertThrows(IllegalArgumentException.class, () ->
                new NeuronScalarMirV0.GuardedScope(
                        NeuronScalarMirV0.GuardMode.CRITICAL_READ,
                        List.of(NeuronScalarMirV0.Instruction.symbol(
                                STORE_STATE_F64, "weight")),
                        NeuronScalarMirV0.ExitPolicy.RELEASE_IN_FINALLY, true));
        assertThrows(IllegalArgumentException.class, () ->
                new NeuronScalarMirV0.GuardedScope(
                        NeuronScalarMirV0.GuardMode.CRITICAL_READ,
                        List.of(example),
                        NeuronScalarMirV0.ExitPolicy.RELEASE_IN_FINALLY, false));
    }

    @Test void ASTPreflightAndMirRejectEscapesUnknownOperationsAndEarlyReads() {
        String[] invalid = {
                SOURCE.replace("s.weight * input + s.bias", "s"),
                SOURCE.replace("s.weight * input + s.bias", "foo(s.weight)"),
                SOURCE.replace("s.weight * input + s.bias", "s.weight / input"),
                SOURCE.replace("s.weight = 2.0;", "s.weight = s.weight + 1.0;"),
                SOURCE.replace("s.weight = 2.0;", "s.weight = await fetch();")
        };
        for (String source : invalid) {
            assertThrows(IllegalArgumentException.class,
                    () -> NeuronScalarMirV0.lower(actor(source)), source);
        }
    }

    private static NeuronScalarMirV0.GuardedScope write(
            NeuronScalarMirV0.Instruction... instructions) {
        return new NeuronScalarMirV0.GuardedScope(
                NeuronScalarMirV0.GuardMode.CRITICAL_WRITE, List.of(instructions),
                NeuronScalarMirV0.ExitPolicy.RELEASE_IN_FINALLY, false);
    }

    private static NeuronScalarMirV0.GuardedScope read(
            NeuronScalarMirV0.Instruction... instructions) {
        return new NeuronScalarMirV0.GuardedScope(
                NeuronScalarMirV0.GuardMode.CRITICAL_READ, List.of(instructions),
                NeuronScalarMirV0.ExitPolicy.RELEASE_IN_FINALLY, true);
    }

    private static NeuronScalarMirV0.Instruction op(NeuronScalarMirV0.Opcode code) {
        return NeuronScalarMirV0.Instruction.arithmetic(code);
    }

    private static NeuronScalarMirV0.Instruction field(
            NeuronScalarMirV0.Opcode code, String symbol) {
        return NeuronScalarMirV0.Instruction.symbol(code, symbol);
    }

    @Test void constructedScopeCannotForgeStackUnderflowOrDanglingValues() {
        assertThrows(IllegalArgumentException.class, () -> read(op(NEG_F64)));
        assertThrows(IllegalArgumentException.class, () -> read(op(ADD_F64)));
        assertThrows(IllegalArgumentException.class,
                () -> write(field(STORE_STATE_F64, "weight")));
        assertThrows(IllegalArgumentException.class,
                () -> read(NeuronScalarMirV0.Instruction.scalar(1.0),
                        NeuronScalarMirV0.Instruction.scalar(2.0)));
        assertThrows(IllegalArgumentException.class,
                () -> write(NeuronScalarMirV0.Instruction.scalar(1.0)));
        assertThrows(IllegalArgumentException.class,
                () -> write(NeuronScalarMirV0.Instruction.scalar(1.0),
                        field(STORE_STATE_F64, "weight"),
                        NeuronScalarMirV0.Instruction.scalar(2.0)));
        assertThrows(IllegalArgumentException.class,
                () -> write(field(LOAD_INPUT_F64, "input"),
                        field(STORE_STATE_F64, "weight")));
        assertThrows(IllegalArgumentException.class,
                () -> read(NeuronScalarMirV0.Instruction.scalar(1.0),
                        field(STORE_STATE_F64, "weight")));
    }

    @Test void constructedPlanCannotForgeUnknownOrUninitializedStateSlots() {
        var layout = new dev.oreslang.compiler.NeuronStateLayoutV0(
                "LinearNeuron", List.of("weight", "bias"));
        var readOnlyWeight = read(field(LOAD_STATE_F64, "weight"));
        var initialWeight = write(NeuronScalarMirV0.Instruction.scalar(2.0),
                field(STORE_STATE_F64, "weight"));

        assertThrows(IllegalArgumentException.class, () ->
                new NeuronScalarMirV0.Plan("LinearNeuron", layout,
                        initialWeight, readOnlyWeight));

        var unknownWrite = write(NeuronScalarMirV0.Instruction.scalar(2.0),
                field(STORE_STATE_F64, "missing"),
                NeuronScalarMirV0.Instruction.scalar(1.0),
                field(STORE_STATE_F64, "bias"));
        assertThrows(IllegalArgumentException.class, () ->
                new NeuronScalarMirV0.Plan("LinearNeuron", layout,
                        unknownWrite, readOnlyWeight));

        var duplicateWrite = write(NeuronScalarMirV0.Instruction.scalar(2.0),
                field(STORE_STATE_F64, "weight"),
                NeuronScalarMirV0.Instruction.scalar(1.0),
                field(STORE_STATE_F64, "weight"));
        assertThrows(IllegalArgumentException.class, () ->
                new NeuronScalarMirV0.Plan("LinearNeuron", layout,
                        duplicateWrite, readOnlyWeight));

        var readBeforeWrite = write(field(LOAD_STATE_F64, "weight"),
                field(STORE_STATE_F64, "weight"),
                NeuronScalarMirV0.Instruction.scalar(1.0),
                field(STORE_STATE_F64, "bias"));
        assertThrows(IllegalArgumentException.class, () ->
                new NeuronScalarMirV0.Plan("LinearNeuron", layout,
                        readBeforeWrite, readOnlyWeight));

        var fullWrite = write(NeuronScalarMirV0.Instruction.scalar(2.0),
                field(STORE_STATE_F64, "weight"),
                NeuronScalarMirV0.Instruction.scalar(1.0),
                field(STORE_STATE_F64, "bias"));
        var unknownRead = read(field(LOAD_STATE_F64, "secret"));
        assertThrows(IllegalArgumentException.class, () ->
                new NeuronScalarMirV0.Plan("LinearNeuron", layout,
                        fullWrite, unknownRead));
        var multipleInputAliases = read(
                field(LOAD_INPUT_F64, "first"),
                field(LOAD_INPUT_F64, "second"),
                op(ADD_F64));
        assertThrows(IllegalArgumentException.class, () ->
                new NeuronScalarMirV0.Plan("LinearNeuron", layout,
                        fullWrite, multipleInputAliases));
        assertDoesNotThrow(() -> new NeuronScalarMirV0.Plan(
                "LinearNeuron", layout, fullWrite,
                read(field(LOAD_STATE_F64, "weight"),
                        field(LOAD_INPUT_F64, "input"), op(MUL_F64),
                        field(LOAD_STATE_F64, "bias"), op(ADD_F64))));
    }

    @Test void GeneratedMirDescriptorCannotBypassProductionGuestAdmission() {
        var descriptor = NeuronScalarMirV0.lower(actor(SOURCE));
        assertNotNull(descriptor.forward());
        IllegalArgumentException blocked = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(SOURCE)));
        assertTrue(blocked.getMessage().contains("NEURON_STATE_MIR_UNSUPPORTED"),
                blocked::getMessage);
    }
}
