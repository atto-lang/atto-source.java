package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A narrow, independently typed, NON-EXECUTABLE staged-call proof for source
 * arguments that are immutable primitive scalar literals.
 *
 * <p>Unlike a caller-provided List<TypeRef>, literal types come directly from
 * the parsed AST. A literal value cannot be retagged to manufacture a
 * WRAP_SOME or PASS_REQUIRED action. The existing binder proves source/formal
 * correspondence; the evaluation schedule proves source-order temporaries.
 *
 * <p>Important: this proves ONLY scalar argument shapes and local adaptation.
 * It does NOT prove the callee's lexical declaration, overload, visibility,
 * effects, ownership, captures or return type. It cannot authorize a runtime
 * call; TypeChecker, OwnershipChecker and interpreter staged-call admission
 * continue failing closed until complete semantic lowering is implemented.
 */
public final class OptionalCallLiteralStage {
    private OptionalCallLiteralStage() { }

    /** An opaque, factory-owned literal-only argument plan, not a runtime token. */
    public static final class LiteralPlan {
        private final OptionalCallAbiPreview.InvocationPreview invocation;
        private final OptionalCallEvaluationSchedule.Schedule evaluation;
        private final List<Ast.TypeRef> independentlyDerivedTypes;

        private LiteralPlan(OptionalCallAbiPreview.InvocationPreview invocation,
                            OptionalCallEvaluationSchedule.Schedule evaluation,
                            List<Ast.TypeRef> independentlyDerivedTypes) {
            this.invocation = Objects.requireNonNull(invocation, "invocation");
            this.evaluation = Objects.requireNonNull(evaluation, "evaluation");
            this.independentlyDerivedTypes = List.copyOf(independentlyDerivedTypes);
        }

        public OptionalCallAbiPreview.InvocationPreview invocation() { return invocation; }
        public OptionalCallEvaluationSchedule.Schedule evaluation() { return evaluation; }
        public List<Ast.TypeRef> independentlyDerivedTypes() { return independentlyDerivedTypes; }
    }

    /**
     * Only direct-name calls with literal argument expressions qualify.
     * The direct name is still UNRESOLVED: this proof must never select a
     * declaration based on name spelling, labels or guessed parameter types.
     */
    public static LiteralPlan prepareDirectLiteralCall(
            Ast.StagedCallExpr call, List<Ast.Param> candidateFormals) {
        Objects.requireNonNull(call, "staged call");
        Objects.requireNonNull(candidateFormals, "candidate formals");
        if (!(call.callee() instanceof Ast.NameExpr)) {
            throw new IllegalArgumentException(
                    "method, member, dynamic or effectful callee needs resolved target proof");
        }
        if (call.typeArgumentsPresent()) {
            throw new IllegalArgumentException(
                    "generic call needs independent instantiation proof");
        }

        List<Ast.TypeRef> checked = new ArrayList<>();
        for (Ast.CallArgument item : call.arguments()) {
            Objects.requireNonNull(item, "call item");
            if (item.spread()) {
                throw new IllegalArgumentException("spread requires checked pack ownership");
            }
            if (item.omitted()) continue;
            if (!(item.value() instanceof Ast.LiteralExpr literal)) {
                throw new IllegalArgumentException(
                        "nonliteral argument needs independent type/effect/ownership proof");
            }
            checked.add(exactScalarLiteralType(literal));
        }
        var invocation = OptionalCallAbiPreview.prepareStagedCallExact(
                call, candidateFormals, checked);
        var evaluation = OptionalCallEvaluationSchedule.from(invocation);
        return new LiteralPlan(invocation, evaluation, checked);
    }

    /**
     * Ensure a supplied exact type is compatible with any independently
     * inspectable literal AST. Nonliterals are deliberately unproved here.
     */
    static void requireMatchingLiteralType(Ast.Expr source, Ast.TypeRef claimed) {
        Objects.requireNonNull(source, "source expression");
        Objects.requireNonNull(claimed, "claimed type");
        if (source instanceof Ast.LiteralExpr literal
                && !exactScalarLiteralType(literal).equals(claimed)) {
            throw new IllegalArgumentException(
                    "checked type assertion contradicts scalar literal AST");
        }
    }

    static Ast.TypeRef exactScalarLiteralType(Ast.LiteralExpr literal) {
        Object value = literal.value();
        if (value instanceof Long) return Ast.TypeRef.simple("int");
        if (value instanceof Double) return Ast.TypeRef.simple("float");
        if (value instanceof Boolean) return Ast.TypeRef.simple("bool");
        if (value instanceof String) return Ast.TypeRef.simple("string");
        // In particular, null is never an Option<T>.None value.
        // Other value literal classes require a dedicated canonical type
        // proof (Symbol, complex, etc.) and are not enabled here.
        throw new IllegalArgumentException(
                "literal has no independently proved scalar ABI type");
    }
}
