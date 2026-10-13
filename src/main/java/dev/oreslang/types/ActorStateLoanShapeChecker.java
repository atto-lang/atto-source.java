package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Conservative, side-effect-free proof for the first scalar sealed-state
 * callback subset. This is NOT lowering authorization: TypeChecker still
 * rejects state execution until ticket admission, effect summaries and
 * guaranteed scope cleanup are integrated for both backends.
 *
 * No general callable, captured name, nested lambda, state reference escape,
 * suspension, actor send or loop is permitted in this first subset.
 */
public final class ActorStateLoanShapeChecker {
    // The first *typed* proof deliberately handles only int/bool. Other
    // scalars may be Copy, but require a separate verified conversion,
    // overflow and numeric promotion contract before lowering is enabled.
    private static final Set<String> PROVEN_SCALARS = Set.of("int", "bool");
    private static final Set<String> INT_ARITHMETIC = Set.of("+", "-", "*", "&", "|", "^");
    private static final Set<String> EQUALITY = Set.of("==", "!=", "eq", "neq");
    private static final Set<String> INT_COMPARISON = Set.of("<", "<=", ">", ">=");
    private static final Set<String> BOOL_LOGICAL = Set.of("&&", "||");

    private enum Scalar { INT, BOOL }

    private static Scalar scalarType(Ast.TypeRef ref) {
        if (ref == null || !PROVEN_SCALARS.contains(ref.name())
                || ref.inferArguments() || !ref.arguments().isEmpty()) {
            throw invalid("unproven Copy scalar type: " + ref);
        }
        return ref.name().equals("int") ? Scalar.INT : Scalar.BOOL;
    }

    private ActorStateLoanShapeChecker() { }

    /** Statically proven field effects for the restricted callback shape.
     * This is only an input to future method-row checking, never guest
     * execution authority or permission to mint a runtime state loan.
     */
    public record LoanEffects(Set<String> reads, Set<String> writes) {
        public LoanEffects {
            reads = Set.copyOf(reads);
            writes = Set.copyOf(writes);
        }
    }

    public static void validate(Ast.ClassDecl actor, Ast.ActorStateAccessExpr access) {
        analyze(actor, access);
    }

    public static LoanEffects analyze(Ast.ClassDecl actor, Ast.ActorStateAccessExpr access) {
        if (actor.actorKind() == Ast.ActorKind.NONE || actor.actorState() == null) {
            throw invalid("state callback requires a sealed actor state declaration");
        }
        Map<String, Ast.TypeRef> fields = new HashMap<>();
        for (Ast.FieldDecl field : actor.actorState().fields()) {
            try {
                scalarType(field.type());
            } catch (IllegalArgumentException error) {
                throw invalid("sealed state field requires a proven Copy scalar: " + field.name());
            }
            if (fields.putIfAbsent(field.name(), field.type()) != null) {
                throw invalid("duplicate sealed actor state field: " + field.name());
            }
            if (field.initializer() == null) {
                throw invalid("sealed actor state field requires initialization: " + field.name());
            }
            // The first scalar-only proof cannot inherit a closure, actor
            // message, native call, reference or other effect from actor
            // construction. There is no callback state loan at this point.
            Scalar valueType = validateValue(field.initializer(), "", Map.of(), new HashSet<>());
            if (valueType != scalarType(field.type())) {
                throw invalid("sealed state initializer has incompatible scalar type: " + field.name());
            }
        }
        String loan = access.callback().parameters().getFirst().name();
        Ast.LambdaExpr callback = access.callback();
        Set<String> reads = new HashSet<>();
        Set<String> writes = new HashSet<>();
        if (callback.expressionBody() != null) {
            validateValue(callback.expressionBody(), loan, fields, reads);
            return new LoanEffects(reads, writes);
        }
        List<Ast.Stmt> body = callback.blockBody();
        if (body == null || body.isEmpty()) {
            throw invalid("state callback must produce a scalar value");
        }
        for (int i = 0; i < body.size(); i++) {
            Ast.Stmt statement = body.get(i);
            if (statement instanceof Ast.ReturnStmt ret && i == body.size() - 1) {
                if (ret.value() == null) throw invalid("state callback must return a scalar");
                validateValue(ret.value(), loan, fields, reads);
            } else if (statement instanceof Ast.ExprStmt expr
                    && expr.expression() instanceof Ast.AssignExpr assign
                    && access.mode() == Ast.StateAccessMode.WRITE
                    && i < body.size() - 1) {
                if (!(assign.target() instanceof Ast.MemberExpr member)
                        || !(member.receiver() instanceof Ast.NameExpr name)
                        || !name.name().equals(loan)) {
                    throw invalid("write must target the callback's own state field");
                }
                checkScalar(member.member(), fields);
                writes.add(member.member());
                Scalar assigned = validateValue(assign.value(), loan, fields, reads);
                if (assigned != scalarType(fields.get(member.member()))) {
                    throw invalid("state write has incompatible scalar type: " + member.member());
                }
            } else if (access.mode() == Ast.StateAccessMode.READ
                    && statement instanceof Ast.ExprStmt) {
                throw invalid("critical-read is read-only; mutation and side effects are forbidden");
            } else {
                throw invalid("state callback supports only scoped writes and final scalar return");
            }
        }
        return new LoanEffects(reads, writes);
    }

    private static Scalar validateValue(Ast.Expr expression, String loan,
                                        Map<String, Ast.TypeRef> fields,
                                        Set<String> reads) {
        if (expression instanceof Ast.LiteralExpr literal) {
            if (literal.value() instanceof Long || literal.value() instanceof Integer) {
                return Scalar.INT;
            }
            if (literal.value() instanceof Boolean) return Scalar.BOOL;
            throw invalid("state callback literal lacks a proven scalar type");
        }
        if (expression instanceof Ast.MemberExpr member
                && member.receiver() instanceof Ast.NameExpr root
                && root.name().equals(loan)) {
            checkScalar(member.member(), fields);
            reads.add(member.member());
            return scalarType(fields.get(member.member()));
        }
        if (expression instanceof Ast.BinaryExpr binary) {
            String op = binary.operator();
            // Always descend into both operands, even for unsupported ops:
            // no nested call, capture or state-capability escape can hide.
            Scalar lhs = validateValue(binary.left(), loan, fields, reads);
            Scalar rhs = validateValue(binary.right(), loan, fields, reads);
            if (lhs != rhs) throw invalid("state expression has mixed scalar operand types");
            if (lhs == Scalar.INT && INT_ARITHMETIC.contains(op)) return Scalar.INT;
            if (EQUALITY.contains(op)) return Scalar.BOOL;
            if (lhs == Scalar.INT && INT_COMPARISON.contains(op)) return Scalar.BOOL;
            if (lhs == Scalar.BOOL && BOOL_LOGICAL.contains(op)) return Scalar.BOOL;
            // Division/modulo can trap at runtime; lifting them requires a
            // proven abnormal-exit cleanup path and typed numeric semantics.
            throw invalid("unproven state callback binary operator: " + op);
        }
        if (expression instanceof Ast.UnaryExpr unary) {
            Scalar operand = validateValue(unary.operand(), loan, fields, reads);
            if (operand == Scalar.INT && Set.of("+", "-", "~").contains(unary.operator())) {
                return Scalar.INT;
            }
            if (operand == Scalar.BOOL && unary.operator().equals("!")) {
                return Scalar.BOOL;
            }
            throw invalid("unproven state callback unary operator: " + unary.operator());
        }
        if (expression instanceof Ast.NameExpr name && name.name().equals(loan)) {
            throw invalid("state loan cannot escape the lexical callback");
        }
        if (expression instanceof Ast.AwaitExpr) {
            throw invalid("state loan must not cross await or suspension");
        }
        if (expression instanceof Ast.CallExpr || expression instanceof Ast.RuntimeCallExpr
                || expression instanceof Ast.ActorStateAccessExpr) {
            throw invalid("nested calls, actor hops and state callbacks require effect proofs");
        }
        throw invalid("state callback cannot capture names, return references or perform effects");
    }

    private static void checkScalar(String name, Map<String, Ast.TypeRef> fields) {
        Ast.TypeRef type = fields.get(name);
        if (type == null) throw invalid("unknown sealed actor state field: " + name);
        scalarType(type);
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("sealed state-loan effect violation: " + reason);
    }
}
