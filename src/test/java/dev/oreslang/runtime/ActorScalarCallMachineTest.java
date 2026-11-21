package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host/runtime acceptance for the real iterative scalar call engine.
 * These methods require executed Maven/JUnit evidence before promotion.
 * They do not certify compiled .atto source or physical native isolation.
 */
final class ActorScalarCallMachineTest {
    private static final ActorScalarCallMachine.Op CONST = ActorScalarCallMachine.Op.CONST;
    private static final ActorScalarCallMachine.Op LOAD = ActorScalarCallMachine.Op.LOAD;
    private static final ActorScalarCallMachine.Op RET = ActorScalarCallMachine.Op.RET;
    private static ActorScalarCallMachine.Instruction c(long n) {
        return ActorScalarCallMachine.Instruction.constant(n);
    }
    private static ActorScalarCallMachine.Instruction x(ActorScalarCallMachine.Op op) {
        return ActorScalarCallMachine.Instruction.of(op);
    }
    private static ActorScalarCallMachine.Instruction x(ActorScalarCallMachine.Op op, int at) {
        return ActorScalarCallMachine.Instruction.at(op, at);
    }
    private static ActorScalarCallMachine.Function fn(String name, int params, int locals,
            ActorScalarCallMachine.Instruction... code) {
        return new ActorScalarCallMachine.Function(name, params, locals, 12, List.of(code));
    }
    private static ActorScalarCallMachine.Program p(ActorScalarCallMachine.Function... functions) {
        return new ActorScalarCallMachine.Program(List.of(functions));
    }
    private static ActorScalarCallMachine.Program constant(long value) {
        return p(fn("constant", 0, 0, c(value), x(RET), x(ActorScalarCallMachine.Op.TRAP)));
    }
    private static ActorScalarCallMachine.Program tailCounter() {
        return p(fn("tail", 2, 2,
                x(LOAD, 0), c(0), x(ActorScalarCallMachine.Op.LE),
                x(ActorScalarCallMachine.Op.JUMP_IF_FALSE, 6),
                x(LOAD, 1), x(RET),
                x(LOAD, 0), c(1), x(ActorScalarCallMachine.Op.SUB),
                x(LOAD, 1), c(1), x(ActorScalarCallMachine.Op.ADD),
                x(ActorScalarCallMachine.Op.TAIL_CALL, 0),
                x(ActorScalarCallMachine.Op.TRAP)));
    }
    private static ActorScalarCallMachine.Program nonTailSum() {
        return p(fn("sum", 1, 1,
                x(LOAD, 0), c(0), x(ActorScalarCallMachine.Op.LE),
                x(ActorScalarCallMachine.Op.JUMP_IF_FALSE, 6),
                c(0), x(RET),
                c(1), x(LOAD, 0), c(1), x(ActorScalarCallMachine.Op.SUB),
                x(ActorScalarCallMachine.Op.CALL, 0),
                x(ActorScalarCallMachine.Op.ADD), x(RET),
                x(ActorScalarCallMachine.Op.TRAP)));
    }

    private record Outcome(Long value, Throwable failure, int peakBytes,
                           int remainingBytes, boolean poisoned) { }

    private static Outcome run(ActorScalarCallMachine.Program program,
                               int entry, List<Long> args, Runnable checkpoint)
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            AtomicReference<Outcome> captured = new AtomicReference<>();
            var actor = runtime.<String>spawnShared(factory -> (mail, turn) -> {
                ActorFrameArena arena = ActorRuntime.currentActorFrameArena();
                Long answer = null;
                Throwable failure = null;
                try {
                    answer = ActorScalarCallMachine.execute(program, entry, args, checkpoint);
                } catch (Throwable issue) {
                    failure = issue;
                } finally {
                    captured.set(new Outcome(answer, failure, arena.peakBytes(),
                            arena.usedBytes(), arena.isPoisoned()));
                    turn.self().stop();
                }
            });
            actor.send("run");
            assertTrue(actor.awaitTermination(90, TimeUnit.SECONDS),
                    "actor scalar dispatcher must terminate or hit its quota");
            Outcome answer = captured.get();
            assertNotNull(answer, "missing physical actor execution receipt");
            assertEquals(0, answer.remainingBytes(), "all frames must be reclaimed");
            return answer;
        }
    }

    private static long success(ActorScalarCallMachine.Program program, Long... args)
            throws Exception {
        Outcome result = run(program, 0, List.of(args), () -> { });
        assertNull(result.failure(), () -> "scalar execution unexpectedly failed: "
                + result.failure());
        assertFalse(result.poisoned());
        return result.value();
    }

    @Test void literalReturnsFromActorOwnedFrame() throws Exception {
        assertEquals(42, success(constant(42)));
    }
    @Test void negativeLiteralPreservesSign() throws Exception {
        assertEquals(-17, success(constant(-17)));
    }
    @Test void longAdditionMatchesExistingGuestWraparound() throws Exception {
        assertEquals(Long.MIN_VALUE, success(p(fn("plus", 0, 0, c(Long.MAX_VALUE),
                c(1), x(ActorScalarCallMachine.Op.ADD), x(RET)))));
    }
    @Test void subtractionKeepsOperandEvaluationOrder() throws Exception {
        assertEquals(7, success(p(fn("subtract", 0, 0, c(10), c(3),
                x(ActorScalarCallMachine.Op.SUB), x(RET)))));
    }
    @Test void multiplicationUsesExactlySignedLongOperands() throws Exception {
        assertEquals(-42, success(p(fn("times", 0, 0, c(-7), c(6),
                x(ActorScalarCallMachine.Op.MUL), x(RET)))));
    }
    @Test void equalityControlsAConditionalBranch() throws Exception {
        assertEquals(99, success(p(fn("equal", 0, 0, c(7), c(7),
                x(ActorScalarCallMachine.Op.EQ),
                x(ActorScalarCallMachine.Op.JUMP_IF_FALSE, 6),
                c(99), x(RET), c(1), x(RET)))));
    }
    @Test void lessThanUsesStrictSignedComparison() throws Exception {
        assertEquals(1, success(p(fn("lt", 0, 0, c(-1), c(0),
                x(ActorScalarCallMachine.Op.LT),
                x(ActorScalarCallMachine.Op.JUMP_IF_FALSE, 6),
                c(1), x(RET), c(0), x(RET)))));
    }
    @Test void largeTailRecursionReplacesActorFrameRatherThanGrowingJvmStack()
            throws Exception {
        Outcome result = run(tailCounter(), 0, List.of(100_000L, 0L), () -> { });
        assertNull(result.failure());
        assertEquals(100_000L, result.value().longValue());
        assertTrue(result.peakBytes() < 2048, "tail recursion must remain constant space");
    }
    @Test void nonTailRecursionReturnsThroughSavedActorFramePCs() throws Exception {
        assertEquals(18, success(nonTailSum(), 18L));
    }
    @Test void nonTailRecursionExhaustsActorQuotaWithoutJvmStackOverflow()
            throws Exception {
        Outcome result = run(nonTailSum(), 0, List.of(100_000L), () -> { });
        assertInstanceOf(ActorFrameArena.BudgetExceeded.class, result.failure());
        assertTrue(result.poisoned());
        assertTrue(result.peakBytes() > 0);
    }
    @Test void siblingActorRunsAfterAnotherActorExhaustsItsCallFrames()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            AtomicReference<Throwable> broken = new AtomicReference<>();
            AtomicInteger survivor = new AtomicInteger();
            var first = runtime.<String>spawnShared(factory -> (mail, turn) -> {
                try {
                    ActorScalarCallMachine.execute(nonTailSum(), 0,
                            List.of(100_000L), () -> { });
                } catch (Throwable failure) {
                    broken.set(failure);
                } finally {
                    turn.self().stop();
                }
            });
            var second = runtime.<String>spawnShared(factory -> (mail, turn) -> {
                survivor.incrementAndGet();
                turn.self().stop();
            });
            first.send("overflow");
            assertTrue(first.awaitTermination(15, TimeUnit.SECONDS));
            assertInstanceOf(ActorFrameArena.BudgetExceeded.class, broken.get());
            second.send("alive");
            assertTrue(second.awaitTermination(15, TimeUnit.SECONDS));
            assertEquals(1, survivor.get());
            assertTrue(second.failure().isEmpty());
        }
    }
    @Test void directNonTailCallIntoDifferentFunctionReturnsToParentPC()
            throws Exception {
        var program = p(
                fn("parent", 0, 0, c(10), x(ActorScalarCallMachine.Op.CALL, 1),
                        c(2), x(ActorScalarCallMachine.Op.ADD), x(RET)),
                fn("child", 1, 1, x(LOAD, 0), c(3),
                        x(ActorScalarCallMachine.Op.MUL), x(RET)));
        assertEquals(32, success(program));
    }
    @Test void twoSiblingNonTailCallsPreserveParentOperands() throws Exception {
        var program = p(
                fn("parent", 0, 0, c(4), x(ActorScalarCallMachine.Op.CALL, 1),
                        c(5), x(ActorScalarCallMachine.Op.CALL, 1),
                        x(ActorScalarCallMachine.Op.ADD), x(RET)),
                fn("child", 1, 1, x(LOAD, 0), c(2),
                        x(ActorScalarCallMachine.Op.MUL), x(RET)));
        assertEquals(18, success(program));
    }
    @Test void deepIndirectTailCallsHaveConstantActorSpace() throws Exception {
        var program = p(
                fn("one", 1, 1, x(LOAD, 0), c(0),
                        x(ActorScalarCallMachine.Op.LE),
                        x(ActorScalarCallMachine.Op.JUMP_IF_FALSE, 6),
                        c(3), x(RET), x(LOAD, 0), c(1),
                        x(ActorScalarCallMachine.Op.SUB),
                        x(ActorScalarCallMachine.Op.TAIL_CALL, 1)),
                fn("two", 1, 1, x(LOAD, 0),
                        x(ActorScalarCallMachine.Op.TAIL_CALL, 0)));
        Outcome result = run(program, 0, List.of(30_000L), () -> { });
        assertNull(result.failure());
        assertEquals(3L, result.value().longValue());
        assertTrue(result.peakBytes() < 2048);
    }
    @Test void scalarLocalStoreThenLoadReadsActorFrameBytes() throws Exception {
        assertEquals(31, success(p(fn("locals", 0, 1, c(31),
                x(ActorScalarCallMachine.Op.STORE, 0),
                x(LOAD, 0), x(RET)))));
    }
    @Test void savedCallerPCResumesAfterMultipleChildFrames() throws Exception {
        var program = p(
                fn("root", 0, 0, c(7), x(ActorScalarCallMachine.Op.CALL, 1), x(RET)),
                fn("middle", 1, 1, x(LOAD, 0), x(ActorScalarCallMachine.Op.CALL, 2), x(RET)),
                fn("leaf", 1, 1, x(LOAD, 0), c(11),
                        x(ActorScalarCallMachine.Op.ADD), x(RET)));
        assertEquals(18, success(program));
    }
    @Test void safepointRunsOnlyOncePer128Instructions() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        assertEquals(256L, run(tailCounter(), 0, List.of(256L, 0L),
                calls::incrementAndGet).value().longValue());
        assertTrue(calls.get() > 0);
        assertTrue(calls.get() < 256, "no slow-path poll on every instruction");
    }
    @Test void safepointFailureAlwaysReclaimsEveryActorActivation()
            throws Exception {
        Outcome result = run(tailCounter(), 0, List.of(100_000L, 0L),
                () -> { throw new IllegalStateException("forced cancellation checkpoint"); });
        assertInstanceOf(IllegalStateException.class, result.failure());
        assertFalse(result.poisoned(), "cooperative cancellation is not quota corruption");
    }
    @Test void deliberateFallthroughTrapPoisonsOnlyCurrentActor() throws Exception {
        Outcome result = run(p(fn("trap", 0, 0, x(ActorScalarCallMachine.Op.TRAP))),
                0, List.of(), () -> { });
        assertInstanceOf(IllegalStateException.class, result.failure());
        assertTrue(result.poisoned());
    }
    @Test void operandUnderflowFailsClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> p(fn("underflow", 0, 0, x(RET))));
    }
    @Test void boolReturnFromIntFunctionFailsClosed() {
        assertThrows(IllegalArgumentException.class, () -> p(fn("badReturn", 0, 0,
                new ActorScalarCallMachine.Instruction(
                        ActorScalarCallMachine.Op.CONST_BOOL, 0, 1),
                x(RET))));
    }
    @Test void hostCannotMintActorScalarFrameWithoutActorTurn() {
        assertThrows(SecurityException.class,
                () -> ActorScalarCallMachine.execute(constant(1), 0,
                        List.of(), () -> { }));
    }
    @Test void negativeOrMissingEntryIsDeniedBeforeFrameAdmission() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> ActorScalarCallMachine.execute(constant(1), -1,
                        List.of(), () -> { }));
        assertThrows(IllegalArgumentException.class,
                () -> ActorScalarCallMachine.execute(constant(1), 4,
                        List.of(), () -> { }));
    }
    @Test void wrongFunctionArityDoesNotLeakActorFrames() throws Exception {
        Outcome result = run(tailCounter(), 0, List.of(5L), () -> { });
        assertInstanceOf(IllegalArgumentException.class, result.failure());
        assertFalse(result.poisoned());
    }
    @Test void invalidLocalIndexRejectedByImmutableProgramVerifier() {
        assertThrows(IllegalArgumentException.class,
                () -> fn("bad", 0, 0, x(LOAD, 0), x(RET)));
    }
    @Test void invalidJumpRejectedBeforeAnyActorRun() {
        assertThrows(IllegalArgumentException.class,
                () -> fn("bad", 0, 0, x(ActorScalarCallMachine.Op.JUMP, 4), x(RET)));
    }
    @Test void invalidCallTargetRejectedBeforeAnyActorRun() {
        assertThrows(IllegalArgumentException.class,
                () -> p(fn("bad", 0, 0, x(ActorScalarCallMachine.Op.CALL, 10), x(RET))));
    }
    @Test void invalidBooleanLiteralRejectedBeforeActorRun() {
        assertThrows(IllegalArgumentException.class, () -> fn("bad", 0, 0,
                new ActorScalarCallMachine.Instruction(
                        ActorScalarCallMachine.Op.CONST_BOOL, 0, 3), x(RET)));
    }
    @Test void excessiveFrameRegistersRejectedByCompilerAbi() {
        assertThrows(IllegalArgumentException.class, () -> new ActorScalarCallMachine.Function(
                "many", 0, 33, 4, List.of(c(1), x(RET))));
    }
    @Test void immutableInstructionImageDoesNotReflectExternalEdits() {
        ArrayList<ActorScalarCallMachine.Instruction> source = new ArrayList<>();
        source.add(c(17));
        source.add(x(RET));
        var fn = new ActorScalarCallMachine.Function("stable", 0, 0, 4, source);
        source.clear();
        assertEquals(2, fn.code().size());
        assertThrows(UnsupportedOperationException.class,
                () -> fn.code().add(c(99)));
    }
    @Test void repeatedActorInvocationsDoNotLeakFrames() throws Exception {
        for (int n = 0; n < 4; n++)
            assertEquals(n, success(tailCounter(), (long) n, 0L));
    }
    private static void corruptTopActorFrameByte(int position, byte value) {
        try {
            ActorFrameArena arena = ActorRuntime.currentActorFrameArena();
            assertNotNull(arena, "test-only mutation requires an actor dispatch");
            synchronized (arena) {
                var stacksField = ActorFrameArena.class.getDeclaredField("stacks");
                stacksField.setAccessible(true);
                @SuppressWarnings("unchecked")
                var stacks = (java.util.Map<Object, java.util.ArrayDeque<Object>>)
                        stacksField.get(arena);
                assertEquals(1, stacks.size(), "one machine logical stack expected");
                Object frame = stacks.values().iterator().next().peekLast();
                assertNotNull(frame);
                var bytesField = frame.getClass().getDeclaredField("bytes");
                bytesField.setAccessible(true);
                byte[] bytes = (byte[]) bytesField.get(frame);
                bytes[position] = value;
            }
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("test-only corruption precondition", failure);
        }
    }

    @Test void changedActivationCalleeIdentityPoisonsOwnedActor() throws Exception {
        AtomicInteger ticks = new AtomicInteger();
        Outcome result = run(tailCounter(), 0, List.of(1000L, 0L), () -> {
            if (ticks.incrementAndGet() == 1) {
                // ABI header [magic, PC, operand SP, function ID].
                // This valid header-sized mutation attempts to substitute
                // another callee while keeping the actual frame capability.
                corruptTopActorFrameByte(15, (byte) 7);
            }
        });
        assertInstanceOf(IllegalStateException.class, result.failure());
        assertTrue(result.poisoned());
        assertEquals(1, ticks.get());
    }

    @Test void outOfRangeReturnPCFailsClosedWithoutNativeRecursion()
            throws Exception {
        AtomicInteger ticks = new AtomicInteger();
        Outcome result = run(tailCounter(), 0, List.of(1000L, 0L), () -> {
            if (ticks.incrementAndGet() == 1) {
                // The low byte of the persisted program counter is field 7.
                // A jump beyond the immutable function code must never
                // restart arbitrary bytecode or follow a Java return address.
                corruptTopActorFrameByte(7, (byte) 127);
            }
        });
        assertInstanceOf(IllegalStateException.class, result.failure());
        assertTrue(result.poisoned());
        assertEquals(1, ticks.get());
    }

    @Test void forgedOperandPointerFailsBeforeNextInstruction() throws Exception {
        AtomicInteger checkpoints = new AtomicInteger();
        Outcome result = run(tailCounter(), 0, List.of(1000L, 0L), () -> {
            if (checkpoints.incrementAndGet() == 1) {
                // Header: magic(0-3), PC(4-7), SP(8-11), callee ID(12-15).
                // Changing the low SP byte cannot be treated as a valid
                // operand-stack address even if the PC itself remains valid.
                corruptTopActorFrameByte(11, (byte) 127);
            }
        });
        assertInstanceOf(IllegalStateException.class, result.failure());
        assertTrue(result.poisoned());
        assertEquals(1, checkpoints.get());
    }

    @Test void integerArithmeticRejectsBooleanTagInsteadOfCoercing() {
        assertThrows(IllegalArgumentException.class, () -> p(fn("bad-int", 0, 0,
                new ActorScalarCallMachine.Instruction(
                        ActorScalarCallMachine.Op.CONST_BOOL, 0, 1),
                c(1), x(ActorScalarCallMachine.Op.ADD), x(RET))));
    }

    @Test void mixedScalarEqualityFailsClosedInsteadOfReturningJvmFalse() {
        assertThrows(IllegalArgumentException.class, () -> p(fn("mixed", 0, 0, c(1),
                new ActorScalarCallMachine.Instruction(
                        ActorScalarCallMachine.Op.CONST_BOOL, 0, 1),
                x(ActorScalarCallMachine.Op.EQ), c(3), x(RET))));
    }

    @Test void booleanPassedToAnIntCalleeFailsClosed() {
        assertThrows(IllegalArgumentException.class, () -> p(
                fn("root", 0, 0,
                        new ActorScalarCallMachine.Instruction(
                                ActorScalarCallMachine.Op.CONST_BOOL, 0, 1),
                        x(ActorScalarCallMachine.Op.CALL, 1), x(RET)),
                fn("callee", 1, 1, x(LOAD, 0), x(RET))));
    }

    @Test void equalBooleanTagsUseTypedEqualityWithoutImplicitIntConversion()
            throws Exception {
        var program = p(fn("boolean-predicate", 0, 0,
                new ActorScalarCallMachine.Instruction(
                        ActorScalarCallMachine.Op.CONST_BOOL, 0, 1),
                new ActorScalarCallMachine.Instruction(
                        ActorScalarCallMachine.Op.CONST_BOOL, 0, 1),
                x(ActorScalarCallMachine.Op.EQ),
                x(ActorScalarCallMachine.Op.JUMP_IF_FALSE, 6),
                c(9), x(RET), c(0), x(RET)));
        assertEquals(9L, success(program));
    }

}
