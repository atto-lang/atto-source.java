package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.HashMap;
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
    private static final Set<String> COPY_SCALARS =
            Set.of("int", "uint", "bool", "float", "decimal");
    private static final Set<String> PURE_BINARY =
            Set.of("+", "-", "*", "/", "%", "==", "!=", "eq", "neq",
                    "<", "<=", ">", ">=", "&&", "||", "&", "|", "^");
    private static final Set<String> PURE_UNARY =
            Set.of("+", "-", "!", "~");

    private ActorStateLoanShapeChecker() { }

    public static void validate(Ast.ClassDecl actor, Ast.ActorStateAccessExpr access) {
        if (actor.actorKind() == Ast.ActorKind.NONE || actor.actorState() == null) {
            throw invalid("state callback requires a sealed actor state declaration");
        }
        Map<String, Ast.TypeRef> fields = new HashMap<>();
        for (Ast.FieldDecl field : actor.actorState().fields()) {
            if (fields.putIfAbsent(field.name(), field.type()) != null) {
                throw invalid("duplicate sealed actor state field: " + field.name());
            }
        }
        String loan = access.callback().parameters().getFirst().name();
        Ast.LambdaExpr callback = access.callback();
        if (callback.expressionBody() != null) {
            validateValue(callback.expressionBody(), loan, fields);
            return;
        }
        List<Ast.Stmt> body = callback.blockBody();
        if (body == null || body.isEmpty()) {
            throw invalid("state callback must produce a scalar value");
        }
        for (int i = 0; i < body.size(); i++) {
            Ast.Stmt statement = body.get(i);
            if (statement instanceof Ast.ReturnStmt ret && i == body.size() - 1) {
                if (ret.value() == null) throw invalid("state callback must return a scalar");
                validateValue(ret.value(), loan, fields);
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
                validateValue(assign.value(), loan, fields);
            } else if (access.mode() == Ast.StateAccessMode.READ
                    && statement instanceof Ast.ExprStmt) {
                throw invalid("critical-read is read-only; mutation and side effects are forbidden");
            } else {
                throw invalid("state callback supports only scoped writes and final scalar return");
            }
        }
    }

    private static void validateValue(Ast.Expr expression, String loan,
                                      Map<String, Ast.TypeRef> fields) {
        if (expression instanceof Ast.LiteralExpr literal) {
            if (!(literal.value() instanceof Number) && !(literal.value() instanceof Boolean)) {
                throw invalid("state callback literal must be a Copy scalar");
            }
            return;
        }
        if (expression instanceof Ast.MemberExpr member
                && member.receiver() instanceof Ast.NameExpr root
                && root.name().equals(loan)) {
            checkScalar(member.member(), fields);
            return;
        }
        if (expression instanceof Ast.BinaryExpr binary
                && PURE_BINARY.contains(binary.operator())) {
            validateValue(binary.left(), loan, fields);
            validateValue(binary.right(), loan, fields);
            return;
        }
        if (expression instanceof Ast.UnaryExpr unary
                && PURE_UNARY.contains(unary.operator())) {
            validateValue(unary.operand(), loan, fields);
            return;
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
        if (!COPY_SCALARS.contains(type.name()) || !type.arguments().isEmpty()
                || type.inferArguments()) {
            throw invalid("non-Copy state field requires ownership and escape analysis: " + name);
        }
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("sealed state-loan effect violation: " + reason);
    }
}
