package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M3.3c independent pre-execution CFG proof.
 * All rejection cases build immutable bytecode images directly, without
 * executing an actor, mutating frame bytes, or depending on the source parser.
 */
final class ActorScalarBytecodeVerifierTest {
    private static ActorScalarCallMachine.Instruction c(long value) {
        return ActorScalarCallMachine.Instruction.constant(value);
    }
    private static ActorScalarCallMachine.Instruction b(boolean value) {
        return new ActorScalarCallMachine.Instruction(
                ActorScalarCallMachine.Op.CONST_BOOL, 0, value ? 1 : 0);
    }
    private static ActorScalarCallMachine.Instruction o(ActorScalarCallMachine.Op op) {
        return ActorScalarCallMachine.Instruction.of(op);
    }
    private static ActorScalarCallMachine.Instruction at(
            ActorScalarCallMachine.Op op, int value) {
        return ActorScalarCallMachine.Instruction.at(op, value);
    }
    private static ActorScalarCallMachine.Function fn(
            String name, int parameters, int locals, int maxStack,
            ActorScalarCallMachine.Instruction... code) {
        return new ActorScalarCallMachine.Function(
                name, parameters, locals, maxStack, List.of(code));
    }
    private static ActorScalarCallMachine.Program program(
            ActorScalarCallMachine.Function... functions) {
        return new ActorScalarCallMachine.Program(List.of(functions));
    }
    private static ActorScalarCallMachine.Function leaf() {
        return fn("identity", 1, 1, 2, at(ActorScalarCallMachine.Op.LOAD, 0),
                o(ActorScalarCallMachine.Op.RET));
    }

    @Test void scalarIntReturnIsAdmitted() {
        assertEquals(1, program(fn("ret", 0, 0, 1,
                c(42), o(ActorScalarCallMachine.Op.RET))).functions().size());
    }
    @Test void formalParameterStartsDefinitelyInitialized() {
        assertDoesNotThrow(() -> program(leaf()));
    }
    @Test void localCannotBeReadBeforeStore() {
        assertThrows(IllegalArgumentException.class, () -> program(fn("read", 0, 1, 1,
                at(ActorScalarCallMachine.Op.LOAD, 0), o(ActorScalarCallMachine.Op.RET))));
    }
    @Test void storeThenReadMarksLocalInitialized() {
        assertDoesNotThrow(() -> program(fn("stored", 0, 1, 1,
                c(4), at(ActorScalarCallMachine.Op.STORE, 0),
                at(ActorScalarCallMachine.Op.LOAD, 0), o(ActorScalarCallMachine.Op.RET))));
    }
    @Test void scalarLocalStoreRequiresIntNotBoolean() {
        assertThrows(IllegalArgumentException.class, () -> program(fn("wrong", 0, 1, 1,
                b(true), at(ActorScalarCallMachine.Op.STORE, 0),
                c(0), o(ActorScalarCallMachine.Op.RET))));
    }
    @Test void returnRequiresExactlyOneIntValue() {
        assertThrows(IllegalArgumentException.class, () -> program(fn("empty", 0, 0, 1,
                o(ActorScalarCallMachine.Op.RET))));
        assertThrows(IllegalArgumentException.class, () -> program(fn("bool", 0, 0, 1,
                b(true), o(ActorScalarCallMachine.Op.RET))));
        assertThrows(IllegalArgumentException.class, () -> program(fn("extra", 0, 0, 2,
                c(1), c(2), o(ActorScalarCallMachine.Op.RET))));
    }
    @Test void branchConditionMustBeBoolean() {
        assertThrows(IllegalArgumentException.class, () -> program(fn("intBranch", 0, 0, 1,
                c(1), at(ActorScalarCallMachine.Op.JUMP_IF_FALSE, 4),
                c(2), o(ActorScalarCallMachine.Op.RET),
                c(3), o(ActorScalarCallMachine.Op.RET))));
    }
    @Test void validBooleanBranchHasBalancedJoin() {
        assertDoesNotThrow(() -> program(fn("branches", 0, 0, 1,
                b(true), at(ActorScalarCallMachine.Op.JUMP_IF_FALSE, 4),
                c(1), o(ActorScalarCallMachine.Op.RET),
                c(2), o(ActorScalarCallMachine.Op.RET))));
    }
    @Test void joinWithDifferentOperandDepthIsRejected() {
        // Deliberately end both paths in a loop, not RET: a simple
        // underflow at the join must not satisfy this test in place of
        // actual cross-path type/depth reconciliation.
        var rejected = assertThrows(ActorScalarBytecodeVerifier.Rejected.class,
                () -> program(fn("depth", 0, 0, 2,
                        b(true), at(ActorScalarCallMachine.Op.JUMP_IF_FALSE, 4),
                        c(1), at(ActorScalarCallMachine.Op.JUMP, 5),
                        at(ActorScalarCallMachine.Op.JUMP, 5),
                        at(ActorScalarCallMachine.Op.JUMP, 5))));
        assertTrue(rejected.getMessage().contains("control-flow join"));
    }
    @Test void joinWithIntVersusBooleanOperandIsRejected() {
        var rejected = assertThrows(ActorScalarBytecodeVerifier.Rejected.class,
                () -> program(fn("kinds", 0, 0, 2,
                        b(true), at(ActorScalarCallMachine.Op.JUMP_IF_FALSE, 4),
                        c(1), at(ActorScalarCallMachine.Op.JUMP, 5),
                        b(true), o(ActorScalarCallMachine.Op.POP),
                        c(8), o(ActorScalarCallMachine.Op.RET))));
        assertTrue(rejected.getMessage().contains("control-flow join"));
    }
    @Test void localAssignedOnOnlyOneBranchIsNotDefinitelyInitialized() {
        assertThrows(IllegalArgumentException.class, () -> program(fn("partial", 0, 1, 1,
                b(true), at(ActorScalarCallMachine.Op.JUMP_IF_FALSE, 5),
                c(1), at(ActorScalarCallMachine.Op.STORE, 0),
                at(ActorScalarCallMachine.Op.JUMP, 7),
                at(ActorScalarCallMachine.Op.JUMP, 7),
                o(ActorScalarCallMachine.Op.TRAP),
                at(ActorScalarCallMachine.Op.LOAD, 0),
                o(ActorScalarCallMachine.Op.RET))));
    }
    @Test void localAssignedOnBothBranchesIsDefinitelyInitialized() {
        assertDoesNotThrow(() -> program(fn("both", 0, 1, 1,
                b(true), at(ActorScalarCallMachine.Op.JUMP_IF_FALSE, 5),
                c(1), at(ActorScalarCallMachine.Op.STORE, 0),
                at(ActorScalarCallMachine.Op.JUMP, 7),
                c(2), at(ActorScalarCallMachine.Op.STORE, 0),
                at(ActorScalarCallMachine.Op.LOAD, 0),
                o(ActorScalarCallMachine.Op.RET))));
    }
    @Test void backedgeCannotManufactureDefiniteAssignment() {
        assertThrows(IllegalArgumentException.class, () -> program(fn("loop", 0, 1, 2,
                b(true), at(ActorScalarCallMachine.Op.JUMP_IF_FALSE, 5),
                c(7), at(ActorScalarCallMachine.Op.STORE, 0),
                at(ActorScalarCallMachine.Op.JUMP, 0),
                at(ActorScalarCallMachine.Op.LOAD, 0),
                o(ActorScalarCallMachine.Op.RET))));
    }
    @Test void validLoopWithInitializedLocalIsAccepted() {
        assertDoesNotThrow(() -> program(fn("countDown", 0, 1, 2,
                c(10), at(ActorScalarCallMachine.Op.STORE, 0),
                at(ActorScalarCallMachine.Op.LOAD, 0), c(0),
                o(ActorScalarCallMachine.Op.LE),
                at(ActorScalarCallMachine.Op.JUMP_IF_FALSE, 8),
                at(ActorScalarCallMachine.Op.LOAD, 0), o(ActorScalarCallMachine.Op.RET),
                at(ActorScalarCallMachine.Op.LOAD, 0), c(1),
                o(ActorScalarCallMachine.Op.SUB),
                at(ActorScalarCallMachine.Op.STORE, 0),
                at(ActorScalarCallMachine.Op.JUMP, 2))));
    }
    @Test void reachableStackPeakCannotExceedDeclaredCapacity() {
        assertThrows(IllegalArgumentException.class, () -> program(fn("tooDeep", 0, 0, 1,
                c(1), c(2), o(ActorScalarCallMachine.Op.ADD),
                o(ActorScalarCallMachine.Op.RET))));
    }
    @Test void adequateStackCapacityAllowsArithmetic() {
        assertDoesNotThrow(() -> program(fn("enough", 0, 0, 2,
                c(1), c(2), o(ActorScalarCallMachine.Op.ADD),
                o(ActorScalarCallMachine.Op.RET))));
    }
    @Test void unreachableInstructionsDoNotForgeAnExecutionPath() {
        assertDoesNotThrow(() -> program(fn("unreachable", 0, 1, 1,
                c(1), o(ActorScalarCallMachine.Op.RET),
                at(ActorScalarCallMachine.Op.LOAD, 0),
                o(ActorScalarCallMachine.Op.RET))));
    }
    @Test void reachableBytecodeCannotFallOffImage() {
        assertThrows(IllegalArgumentException.class, () -> program(fn("fallthrough", 0, 0, 1,
                c(1))));
    }
    @Test void JumpToValidPCIsAccepted() {
        assertDoesNotThrow(() -> program(fn("jump", 0, 0, 1,
                at(ActorScalarCallMachine.Op.JUMP, 2),
                o(ActorScalarCallMachine.Op.RET),
                c(9), o(ActorScalarCallMachine.Op.RET))));
    }
    @Test void popWithoutOperandIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> program(fn("popEmpty", 0, 0, 1,
                o(ActorScalarCallMachine.Op.POP), c(1),
                o(ActorScalarCallMachine.Op.RET))));
    }
    @Test void poppingOneTemporaryIsAllowed() {
        assertDoesNotThrow(() -> program(fn("pop", 0, 0, 1,
                c(3), o(ActorScalarCallMachine.Op.POP), c(4),
                o(ActorScalarCallMachine.Op.RET))));
    }
    @Test void arithmeticNeedsTwoIntOperands() {
        assertThrows(IllegalArgumentException.class, () -> program(fn("badAdd", 0, 0, 2,
                c(1), b(true), o(ActorScalarCallMachine.Op.ADD),
                o(ActorScalarCallMachine.Op.RET))));
        assertThrows(IllegalArgumentException.class, () -> program(fn("missingAdd", 0, 0, 2,
                c(1), o(ActorScalarCallMachine.Op.ADD),
                o(ActorScalarCallMachine.Op.RET))));
    }
    @Test void comparisonRequiresIntOperandsAndProducesBoolean() {
        assertThrows(IllegalArgumentException.class, () -> program(fn("badCompare", 0, 0, 2,
                b(true), c(1), o(ActorScalarCallMachine.Op.LT),
                o(ActorScalarCallMachine.Op.RET))));
        assertDoesNotThrow(() -> program(fn("goodCompare", 0, 0, 2,
                c(1), c(2), o(ActorScalarCallMachine.Op.LT),
                at(ActorScalarCallMachine.Op.JUMP_IF_FALSE, 6),
                c(1), o(ActorScalarCallMachine.Op.RET),
                c(0), o(ActorScalarCallMachine.Op.RET))));
    }
    @Test void mixedPrimitiveEqualityIsRejectedBeforeRuntime() {
        assertThrows(IllegalArgumentException.class, () -> program(fn("mixedEq", 0, 0, 2,
                c(1), b(true), o(ActorScalarCallMachine.Op.EQ),
                at(ActorScalarCallMachine.Op.JUMP_IF_FALSE, 6),
                c(1), o(ActorScalarCallMachine.Op.RET),
                c(0), o(ActorScalarCallMachine.Op.RET))));
    }
    @Test void sameTypeBooleanEqualityIsPermitted() {
        assertDoesNotThrow(() -> program(fn("boolEq", 0, 0, 2,
                b(true), b(false), o(ActorScalarCallMachine.Op.EQ),
                at(ActorScalarCallMachine.Op.JUMP_IF_FALSE, 6),
                c(1), o(ActorScalarCallMachine.Op.RET),
                c(0), o(ActorScalarCallMachine.Op.RET))));
    }
    @Test void callArityUnderflowRejectsWholeProgram() {
        assertThrows(IllegalArgumentException.class, () -> program(
                fn("caller", 0, 0, 1,
                        at(ActorScalarCallMachine.Op.CALL, 1),
                        o(ActorScalarCallMachine.Op.RET)), leaf()));
    }
    @Test void callArgumentsMustBeIntNotBoolean() {
        assertThrows(IllegalArgumentException.class, () -> program(
                fn("caller", 0, 0, 1, b(true),
                        at(ActorScalarCallMachine.Op.CALL, 1),
                        o(ActorScalarCallMachine.Op.RET)), leaf()));
    }
    @Test void validDirectCallPushesExactlyOneIntReturn() {
        assertDoesNotThrow(() -> program(fn("caller", 0, 0, 1,
                c(5), at(ActorScalarCallMachine.Op.CALL, 1),
                o(ActorScalarCallMachine.Op.RET)), leaf()));
    }
    @Test void tailCallMustNotDiscardParentOperands() {
        assertThrows(IllegalArgumentException.class, () -> program(
                fn("caller", 0, 0, 2, c(1), c(2),
                        at(ActorScalarCallMachine.Op.TAIL_CALL, 1)), leaf()));
    }
    @Test void tailCallWithExactIntArgumentsIsValid() {
        assertDoesNotThrow(() -> program(
                fn("caller", 0, 0, 1, c(2),
                        at(ActorScalarCallMachine.Op.TAIL_CALL, 1)), leaf()));
    }
    @Test void tailCallWithBooleanArgumentIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> program(
                fn("caller", 0, 0, 1, b(true),
                        at(ActorScalarCallMachine.Op.TAIL_CALL, 1)), leaf()));
    }
    @Test void trapWithLiveOperandsIsNotAValidCompilerImage() {
        assertThrows(IllegalArgumentException.class, () -> program(fn("badTrap", 0, 0, 1,
                c(1), o(ActorScalarCallMachine.Op.TRAP))));
    }
    @Test void explicitTrapWithEmptyStackIsValid() {
        assertDoesNotThrow(() -> program(fn("trap", 0, 0, 1,
                o(ActorScalarCallMachine.Op.TRAP))));
    }
    @Test void loopWithIdenticalEntryStateIsSafeToVerifyWithoutRecursion() {
        // Runtime cancellation/fuel still applies; verifier only proves
        // bytecode memory and type safety, not loop termination.
        assertDoesNotThrow(() -> program(fn("loop", 0, 0, 1,
                at(ActorScalarCallMachine.Op.JUMP, 0))));
    }
    @Test void extraArgumentFieldOnArithmeticIsNotCanonical() {
        assertThrows(IllegalArgumentException.class, () -> program(fn("hidden", 0, 0, 2,
                c(1), c(2),
                new ActorScalarCallMachine.Instruction(
                        ActorScalarCallMachine.Op.ADD, 12, 0L),
                o(ActorScalarCallMachine.Op.RET))));
    }
    @Test void unusedConstArgumentFieldIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> program(fn("badConst", 0, 0, 1,
                new ActorScalarCallMachine.Instruction(
                        ActorScalarCallMachine.Op.CONST, 17, 2L),
                o(ActorScalarCallMachine.Op.RET))));
    }
    @Test void unusedCallValueFieldCannotSmuggleMetadata() {
        assertThrows(IllegalArgumentException.class, () -> program(
                fn("caller", 0, 0, 1, c(3),
                        new ActorScalarCallMachine.Instruction(
                                ActorScalarCallMachine.Op.CALL, 1, 42L),
                        o(ActorScalarCallMachine.Op.RET)), leaf()));
    }
    @Test void unreachableInstructionsStillRequireCanonicalEncoding() {
        assertThrows(IllegalArgumentException.class, () -> program(fn("hidden", 0, 0, 1,
                c(1), o(ActorScalarCallMachine.Op.RET),
                new ActorScalarCallMachine.Instruction(
                        ActorScalarCallMachine.Op.TRAP, 0, 1L))));
    }
    @Test void canonicalUnusedFieldsDoNotRejectWorkingPrograms() {
        assertDoesNotThrow(() -> program(fn("canonical", 0, 0, 2,
                c(1), c(2), o(ActorScalarCallMachine.Op.ADD),
                o(ActorScalarCallMachine.Op.RET))));
    }

}
