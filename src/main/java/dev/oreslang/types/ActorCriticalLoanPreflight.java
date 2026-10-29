package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Source-AST admission preflight for typed actor state-loan callbacks.
 *
 * <p>This deliberately does not grant execution authority. TypeChecker must
 * still reject state-schema actors until the runtime enforces physical arenas,
 * exclusive loans, effect proofs and unwind/retirement fencing. This visitor
 * supplies deterministic early diagnostics and a regression surface without
 * borrowing permissions from a user-declared method or annotation.</p>
 */
public final class ActorCriticalLoanPreflight {
    private ActorCriticalLoanPreflight() { }

    public static void auditClass(Ast.ClassDecl klass) {
        Map<String, Ast.TypeRef> fields = new LinkedHashMap<>();
        if (klass.actorStateSchema() != null) {
            for (Ast.FieldDecl field : klass.actorStateSchema().fields()) {
                if (fields.putIfAbsent(field.name(), field.type()) != null) {
                    throw error("duplicate sealed actor state field");
                }
            }
        }
        if (fields.isEmpty()) return;
        for (Ast.MethodDecl method : klass.methods()) {
            for (Ast.Stmt statement : method.body()) scanOutside(statement, fields, method.effects().stateEffect());
        }
    }

    private static boolean isIntrinsic(Ast.Expr expr) {
        return expr instanceof Ast.MemberExpr member
                && member.member().equals("handle_state")
                && member.receiver() instanceof Ast.NameExpr name
                && name.name().equals("self");
    }

    private static void scanOutside(Ast.Stmt stmt, Map<String, Ast.TypeRef> fields, Ast.StateEffect effects) {
        if (stmt instanceof Ast.ExprStmt expression) {
            scanOutside(expression.expression(), fields, effects);
        } else if (stmt instanceof Ast.ReturnStmt returned) {
            scanOutside(returned.value(), fields, effects);
        } else if (stmt instanceof Ast.BindingStmt binding) {
            scanOutside(binding.initializer(), fields, effects);
        } else if (stmt instanceof Ast.BlockStmt block) {
            for (Ast.Stmt nested : block.body()) scanOutside(nested, fields, effects);
        } else if (stmt instanceof Ast.IfStmt conditional) {
            for (Ast.IfBranch branch : conditional.branches()) {
                scanOutside(branch.condition(), fields, effects);
                for (Ast.Stmt nested : branch.body()) scanOutside(nested, fields, effects);
            }
            for (Ast.Stmt nested : conditional.elseBody()) scanOutside(nested, fields, effects);
        }
        // Other methods and all unknown statements remain runtime-denied
        // by TypeChecker. Do not treat this source visitor as a proof.
    }

    private static void scanOutside(Ast.Expr expr, Map<String, Ast.TypeRef> fields, Ast.StateEffect effects) {
        if (expr == null) return;
        if (expr instanceof Ast.CallExpr call) {
            if (isIntrinsic(call.callee())) {
                if (call.typeArgumentsPresent() || call.arguments().size() != 1
                        || !(call.arguments().getFirst() instanceof Ast.LambdaExpr lambda)
                        || lambda.criticalLoanMode() == Ast.CriticalLoanMode.NONE) {
                    throw error("handle_state requires one checked critical read/write callback");
                }
                if (lambda.criticalLoanMode() == Ast.CriticalLoanMode.WRITE
                        && !effects.write()) {
                    throw error("critical write loan requires with state.write");
                }
                if (lambda.criticalLoanMode() == Ast.CriticalLoanMode.READ
                        && !effects.read()) {
                    throw error("critical read loan requires with state.read");
                }
                validateCallback(lambda, fields);
                return;
            }
            scanOutside(call.callee(), fields, effects);
            for (Ast.Expr arg : call.arguments()) scanOutside(arg, fields, effects);
        } else if (expr instanceof Ast.BinaryExpr binary) {
            scanOutside(binary.left(), fields, effects);
            scanOutside(binary.right(), fields, effects);
        } else if (expr instanceof Ast.UnaryExpr unary) {
            scanOutside(unary.operand(), fields, effects);
        } else if (expr instanceof Ast.AssignExpr assign) {
            scanOutside(assign.target(), fields, effects);
            scanOutside(assign.value(), fields, effects);
        } else if (expr instanceof Ast.ConditionalExpr conditional) {
            scanOutside(conditional.condition(), fields, effects);
            scanOutside(conditional.whenTrue(), fields, effects);
            scanOutside(conditional.whenFalse(), fields, effects);
        } else if (expr instanceof Ast.MemberExpr member) {
            scanOutside(member.receiver(), fields, effects);
        } else if (expr instanceof Ast.IndexExpr index) {
            scanOutside(index.receiver(), fields, effects);
            scanOutside(index.index(), fields, effects);
        } else if (expr instanceof Ast.LambdaExpr lambda) {
            scanOutside(lambda.expressionBody(), fields, effects);
            if (lambda.blockBody() != null) {
                for (Ast.Stmt nested : lambda.blockBody()) scanOutside(nested, fields, effects);
            }
        }
    }

    /** Check a source callback before checked loan lowering exists. */
    public static void validateCallback(
            Ast.LambdaExpr lambda, Map<String, Ast.TypeRef> stateFields) {
        if (lambda.criticalLoanMode() == Ast.CriticalLoanMode.NONE
                || lambda.nonLexical() || lambda.parameters().size() != 1) {
            throw error("state loan requires one lexical critical callback parameter");
        }
        Ast.Param parameter = lambda.parameters().getFirst();
        // The state view is compiler-provided. A source type annotation,
        // explicit mutable referent or structural marker must not authorize
        // a forged state capability.
        if (!parameter.type().equals(Ast.TypeRef.inferred())
                || parameter.mutable() || parameter.structural()) {
            throw error("critical state loan parameter must be an untyped "
                    + "compiler-bound lexical name");
        }
        String name = parameter.name();
        if (name.equals("_") || name.isBlank()) {
            throw error("state loan requires a named state parameter");
        }
        if (lambda.expressionBody() != null) {
            checkExpr(lambda.expressionBody(), name, lambda.criticalLoanMode(), stateFields);
        } else {
            for (Ast.Stmt stmt : lambda.blockBody()) {
                checkStmt(stmt, name, lambda.criticalLoanMode(), stateFields);
            }
        }
    }

    private static boolean scalar(Ast.TypeRef type) {
        if (type == null || !type.arguments().isEmpty()) return false;
        return switch (type.name()) {
            case "int", "uint", "i8", "i16", "i32", "i64",
                    "u8", "u16", "u32", "u64",
                    "bool", "String", "string",
                    "float", "f32", "f64", "decimal" -> true;
            default -> false;
        };
    }

    private static void checkStmt(Ast.Stmt stmt, String loanName,
            Ast.CriticalLoanMode mode, Map<String, Ast.TypeRef> stateFields) {
        if (stmt instanceof Ast.ExprStmt expression) {
            checkExpr(expression.expression(), loanName, mode, stateFields);
        } else if (stmt instanceof Ast.ReturnStmt returned) {
            if (returned.value() != null) {
                checkExpr(returned.value(), loanName, mode, stateFields);
            }
        } else if (stmt instanceof Ast.BindingStmt binding) {
            // Reject lexical shadowing of the borrowed state name in nested
            // blocks; otherwise a string-based source preflight could inspect
            // the wrong object and misclassify state reads/writes.
            if (binding.name().equals(loanName)) {
                throw error("critical state loan parameter cannot be shadowed");
            }
            checkExpr(binding.initializer(), loanName, mode, stateFields);
        } else if (stmt instanceof Ast.BlockStmt block) {
            for (Ast.Stmt nested : block.body()) {
                checkStmt(nested, loanName, mode, stateFields);
            }
        } else if (stmt instanceof Ast.IfStmt conditional) {
            for (Ast.IfBranch branch : conditional.branches()) {
                checkExpr(branch.condition(), loanName, mode, stateFields);
                for (Ast.Stmt nested : branch.body()) {
                    checkStmt(nested, loanName, mode, stateFields);
                }
            }
            for (Ast.Stmt nested : conditional.elseBody()) {
                checkStmt(nested, loanName, mode, stateFields);
            }
        } else {
            throw error("critical state loan forbids suspension, defer, loops, "
                    + "nested scopes and unsupported statements until effect checking");
        }
    }

    private static void checkExpr(Ast.Expr expr, String loanName,
            Ast.CriticalLoanMode mode, Map<String, Ast.TypeRef> fields) {
        if (expr == null || expr instanceof Ast.LiteralExpr) return;
        if (expr instanceof Ast.NameExpr name) {
            if (name.name().equals(loanName)) {
                throw error("state loan reference cannot escape as a value");
            }
            return;
        }
        if (expr instanceof Ast.MemberExpr member) {
            if (member.receiver() instanceof Ast.NameExpr name
                    && name.name().equals(loanName)) {
                Ast.TypeRef field = fields.get(member.member());
                if (field == null) throw error("undeclared actor state field " + member.member());
                if (!scalar(field)) throw error(
                        "reference-valued actor state cannot escape a critical loan");
                return;
            }
            // Never permit s.field.child or arbitrary host member access
            // without a transitive state-borrow proof.
            throw error("critical state loan forbids nested or foreign member access");
        }
        if (expr instanceof Ast.AssignExpr assignment) {
            if (mode != Ast.CriticalLoanMode.WRITE) {
                throw error("critical[read] cannot mutate actor state");
            }
            if (!(assignment.target() instanceof Ast.MemberExpr member)
                    || !(member.receiver() instanceof Ast.NameExpr name)
                    || !name.name().equals(loanName)) {
                throw error("critical write loan allows only direct actor-state assignments");
            }
            Ast.TypeRef type = fields.get(member.member());
            if (type == null) throw error("undeclared actor state field " + member.member());
            if (!scalar(type)) throw error("reference-valued state write lacks ownership proof");
            checkExpr(assignment.value(), loanName, mode, fields);
            return;
        }
        if (expr instanceof Ast.BinaryExpr binary) {
            checkExpr(binary.left(), loanName, mode, fields);
            checkExpr(binary.right(), loanName, mode, fields);
            return;
        }
        if (expr instanceof Ast.UnaryExpr unary) {
            checkExpr(unary.operand(), loanName, mode, fields);
            return;
        }
        if (expr instanceof Ast.ConditionalExpr conditional) {
            checkExpr(conditional.condition(), loanName, mode, fields);
            checkExpr(conditional.whenTrue(), loanName, mode, fields);
            checkExpr(conditional.whenFalse(), loanName, mode, fields);
            return;
        }
        // Calls can hide I/O, spawn, state re-entry, effects or suspension;
        // closures can retain the borrow. Deny every unproven expression.
        throw error("critical state loan forbids calls, suspension, nested "
                + "closures and unverified expression effects");
    }

    private static IllegalArgumentException error(String message) {
        return new IllegalArgumentException("actor critical state preflight: " + message);
    }
}
