package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;

import java.util.Objects;

/**
 * Staged compiler IR binding for bracket-indexed inverted fields.
 *
 * This is deliberately NOT an interpreter, an AST macro, or an authority to
 * execute guest code. The caller must first type-check the receiver as
 * InvertedArray<T>, the index as a supported dense integer or issued typed row
 * handle, and verify borrow/effect/escape constraints. Until that compiler
 * proof exists, the guest TypeChecker must continue to reject executable
 * inverted classes.
 *
 * Resolving a field once produces an opaque nominal Schema.FieldBinding.
 * Every expression is retained by AST identity: lowering must evaluate the
 * receiver, index and RHS exactly once in the source language's defined order.
 */
final class InvertedIndexedFieldPlan {
    enum Operation { READ, WRITE }

    private final Operation operation;
    // Exact source expression identity, not a structural equality key. This
    // staged binding is invalid after any AST rewrite, including rewrites
    // producing equal-by-value record nodes.
    private final Ast.Expr sourceSite;
    private final Ast.Expr fieldSite;
    private final InvertedArray.Schema nominalSchema;
    private final Ast.Expr receiver;
    private final Ast.Expr index;
    private final Ast.Expr value;
    private final InvertedArray.Schema.FieldBinding field;

    private InvertedIndexedFieldPlan(
            Operation operation, Ast.Expr sourceSite, Ast.Expr fieldSite,
            Ast.Expr receiver, Ast.Expr index, Ast.Expr value,
            InvertedArray.Schema nominalSchema,
            InvertedArray.Schema.FieldBinding field) {
        this.operation = Objects.requireNonNull(operation, "operation");
        this.sourceSite = Objects.requireNonNull(sourceSite, "source site");
        this.fieldSite = Objects.requireNonNull(fieldSite, "field site");
        this.nominalSchema = Objects.requireNonNull(nominalSchema, "nominal schema");
        this.receiver = Objects.requireNonNull(receiver, "receiver");
        this.index = Objects.requireNonNull(index, "index");
        this.value = value;
        this.field = Objects.requireNonNull(field, "field binding");
    }

    static InvertedIndexedFieldPlan bindRead(
            Ast.Expr expression, InvertedArray.Schema checkedSchema) {
        return bind(expression, expression, null, Operation.READ, checkedSchema);
    }

    static InvertedIndexedFieldPlan bindWrite(
            Ast.Expr expression, InvertedArray.Schema checkedSchema) {
        if (!(expression instanceof Ast.AssignExpr assignment)) {
            throw new IllegalArgumentException(
                    "inverted indexed write requires an assignment expression");
        }
        if (assignment.value() == null) {
            throw new IllegalArgumentException("inverted indexed assignment has no value");
        }
        return bind(expression, assignment.target(), assignment.value(), Operation.WRITE, checkedSchema);
    }

    private static InvertedIndexedFieldPlan bind(
            Ast.Expr sourceSite, Ast.Expr target, Ast.Expr rhs, Operation operation,
            InvertedArray.Schema checkedSchema) {
        Objects.requireNonNull(checkedSchema, "checked nominal inverted schema");
        if (!(target instanceof Ast.MemberExpr member)
                || !(member.receiver() instanceof Ast.IndexExpr indexed)
                || indexed.receiver() == null || indexed.index() == null) {
            throw new IllegalArgumentException(
                    "inverted field lowering requires a checked rows[index].field expression");
        }
        // No synthetic, guest-visible identifier is ever generated. This
        // binding is scoped to the immutable nominal schema declaration.
        InvertedArray.Schema.FieldBinding field = checkedSchema.bindField(member.member());
        return new InvertedIndexedFieldPlan(
                operation, sourceSite, target, indexed.receiver(), indexed.index(),
                rhs, checkedSchema, field);
    }

    /**
     * Fail-closed reuse check after compiler rewrites. The caller MUST re-run
     * type, index-kind, effects and ownership checking independently; passing
     * this guard is never an executable guest lowering authorization.
     */
    void requireExactSource(Ast.Expr currentSite, InvertedArray.Schema currentSchema) {
        if (currentSite != sourceSite || currentSchema != nominalSchema) {
            throw new IllegalStateException(
                    "inverted indexed field binding invalidated by AST or schema rewrite");
        }
        Ast.Expr actualFieldSite;
        if (operation == Operation.WRITE) {
            if (!(currentSite instanceof Ast.AssignExpr assignment)
                    || assignment.target() != fieldSite || assignment.value() != value) {
                throw new IllegalStateException("inverted indexed write binding changed");
            }
            actualFieldSite = assignment.target();
        } else {
            if (currentSite != fieldSite || value != null) {
                throw new IllegalStateException("inverted indexed read binding changed");
            }
            actualFieldSite = currentSite;
        }
        if (!(actualFieldSite instanceof Ast.MemberExpr member)
                || !(member.receiver() instanceof Ast.IndexExpr indexed)
                || indexed.receiver() != receiver || indexed.index() != index
                || nominalSchema.bindField(member.member()) != field) {
            throw new IllegalStateException("inverted indexed field proof no longer matches source");
        }
    }

    Operation operation() { return operation; }
    Ast.Expr receiver() { return receiver; }
    Ast.Expr index() { return index; }
    Ast.Expr value() { return value; }
    InvertedArray.Schema.FieldBinding field() { return field; }
}
