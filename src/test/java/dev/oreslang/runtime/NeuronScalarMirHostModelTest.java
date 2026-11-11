package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.NeuronScalarMirV0;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host-only MIR model using real ActorRuntime envelopes and scalar loans.
 * This does NOT execute a compiled .atto program, nor authorize JVM, LLVM,
 * .NET NativeAOT, Wasm or Forge guest lowering. The explicit interpreter here
 * exists ONLY in test sources and must not become guest execution authority.
 */
@Timeout(20)
final class NeuronScalarMirHostModelTest {
    private static final String FIXTURE = """
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

    private static NeuronScalarMirV0.Plan plan() {
        Ast.ClassDecl actor = (Ast.ClassDecl) Parser.parse(FIXTURE)
                .modules().getFirst().declarations().getFirst();
        return NeuronScalarMirV0.lower(actor);
    }

    /** Test-only stack-machine evaluator. Every memory operation uses its
     * current physical turn's validated scope, never an exposed state array. */
    private static double interpret(
            NeuronActorScalarTurnStateV0.ReadScope scope,
            NeuronActorScalarTurnStateV0.WriteScope writer,
            NeuronScalarMirV0.GuardedScope guarded,
            double input, int failBeforeIndex) {
        double[] stack = new double[256];
        int top = 0;
        List<NeuronScalarMirV0.Instruction> ops = guarded.body();
        for (int i = 0; i < ops.size(); i++) {
            if (i == failBeforeIndex)
                throw new IllegalStateException("injected MIR execution failure");
            var ins = ops.get(i);
            switch (ins.opcode()) {
                case CONST_F64 -> stack[top++] = ins.literal();
                case LOAD_STATE_F64 -> stack[top++] = scope.readFloat(ins.symbol());
                case LOAD_INPUT_F64 -> stack[top++] = input;
                case NEG_F64 -> stack[top - 1] = -stack[top - 1];
                case ADD_F64 -> {
                    double rhs = stack[--top];
                    stack[top - 1] += rhs;
                }
                case SUB_F64 -> {
                    double rhs = stack[--top];
                    stack[top - 1] -= rhs;
                }
                case MUL_F64 -> {
                    double rhs = stack[--top];
                    stack[top - 1] *= rhs;
                }
                case STORE_STATE_F64 -> {
                    if (writer == null) throw new AssertionError("read scope attempted a write");
                    writer.writeFloat(ins.symbol(), stack[--top]);
                }
            }
        }
        return guarded.returnsScalar() ? stack[top - 1] : 0.0;
    }

    private static double run(
            NeuronActorScalarTurnStateV0 state,
            NeuronScalarMirV0.GuardedScope guarded,
            double input, int failureInstruction) {
        // try-with-resources mirrors the compiler's proposed
        // RELEASE_IN_FINALLY lowering. The production emitter must still
        // provide that guarantee on traps, cancellation and suspension.
        if (guarded.mode() == NeuronScalarMirV0.GuardMode.CRITICAL_WRITE) {
            try (var scope = state.tryWrite()) {
                assertNotNull(scope, "state write loan must be admitted on actor turn");
                return interpret(scope, scope, guarded, input, failureInstruction);
            }
        }
        try (var scope = state.tryRead()) {
            assertNotNull(scope, "state read loan must be admitted on actor turn");
            return interpret(scope, null, guarded, input, failureInstruction);
        }
    }

    @Test void parsedMirComputesSevenOnlyThroughRealActorBoundLoanScopes()
            throws Exception {
        var descriptor = plan();
        var stateRef = new AtomicReference<NeuronActorScalarTurnStateV0>();
        var result = new AtomicReference<Double>();
        var failure = new AtomicReference<Throwable>();
        var completed = new CountDownLatch(1);

        try (var runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 2, 8, 3),
                ActorRuntime.TurnExecutor.direct())) {
            var actor = runtime.<Integer>spawnShared(() -> {
                var state = NeuronActorScalarTurnStateV0.createOnCurrentTurn(
                        8, 2, descriptor.layout().floatFields());
                stateRef.set(state);
                return (message, context) -> {
                    try {
                        if (message == 0) {
                            run(state, descriptor.initialization(), 0.0, -1);
                            assertEquals(0, state.activeLoanCount());
                            context.self().send(1);
                        } else if (message == 1) {
                            result.set(run(state, descriptor.forward(), 3.0, -1));
                            assertEquals(0, state.activeLoanCount());
                            context.self().stop();
                            completed.countDown();
                        } else {
                            throw new AssertionError("unexpected neuron host-model message");
                        }
                    } catch (Throwable problem) {
                        failure.compareAndSet(null, problem);
                        completed.countDown();
                        context.self().stop();
                    }
                };
            });
            actor.send(0);
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            if (failure.get() != null)
                throw new AssertionError("host scalar MIR execution model failed", failure.get());
            assertEquals(7.0, result.get());
            assertThrows(SecurityException.class, stateRef.get()::tryRead,
                    "host thread cannot mint a post-STOP actor loan");
            assertTrue(stateRef.get().tryClearAfterQuiescence());
        }
    }

    @Test void injectedWriteAndReadFailuresReleaseLoansBeforeActorStop()
            throws Exception {
        var descriptor = plan();
        var stateRef = new AtomicReference<NeuronActorScalarTurnStateV0>();
        var failure = new AtomicReference<Throwable>();
        var completed = new CountDownLatch(1);
        try (var runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 2, 1, 2),
                ActorRuntime.TurnExecutor.direct())) {
            var actor = runtime.<Integer>spawnShared(() -> {
                var state = NeuronActorScalarTurnStateV0.createOnCurrentTurn(
                        4, 2, descriptor.layout().floatFields());
                stateRef.set(state);
                return (message, context) -> {
                    try {
                        assertEquals(Integer.valueOf(1), message);
                        assertThrows(IllegalStateException.class,
                                () -> run(state, descriptor.initialization(), 0.0, 1));
                        assertEquals(0, state.activeLoanCount(),
                                "write failure must not retain the physical loan");
                        run(state, descriptor.initialization(), 0.0, -1);
                        assertThrows(IllegalStateException.class,
                                () -> run(state, descriptor.forward(), 3.0, 2));
                        assertEquals(0, state.activeLoanCount(),
                                "read failure must not block STOP/finalization");
                        assertEquals(7.0, run(state, descriptor.forward(), 3.0, -1));
                        context.self().stop();
                    } catch (Throwable problem) {
                        failure.compareAndSet(null, problem);
                        context.self().stop();
                    } finally {
                        completed.countDown();
                    }
                };
            });
            actor.send(1);
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            if (failure.get() != null)
                throw new AssertionError("MIR model failure cleanup", failure.get());
            assertTrue(stateRef.get().tryClearAfterQuiescence());
            assertEquals(0, stateRef.get().activeLoanCount());
        }
    }
}
