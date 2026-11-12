package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorContractWitness;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Closed-world, manifest-owned state-loan preflight. This is a typed
 * SCHEMA/AST witness, NOT executable critical-loan MIR or an authority token.
 *
 * V2 deliberately admits one checked scalar increment in a WRITE loan or a
 * copy-only scalar send in a READ loan. It rejects nested callbacks, alias
 * export, state member spoofing, suspension and arbitrary guest side effects.
 * A subsequent MIR pass must still prove generation fencing, effect admission
 * and correct runtime loan release on every exit.
 */
public final class ActorCriticalStatePreflight {
    private ActorCriticalStatePreflight() { }

    public record CriticalOperation(
            String sourceUnit, String namespace, String actor, String method,
            Ast.CriticalStateMode mode, String field, String scalarType) { }

    public static List<CriticalOperation> verify(
            Map<String, Ast.Program> units,
            ActorContractWitness.Result contractWitness) {
        if (units == null || contractWitness == null) {
            throw deny("manifest source set or witness is missing");
        }
        List<CriticalOperation> operations = new ArrayList<>();
        for (var proof : contractWitness.actors().entrySet()) {
            var key = proof.getKey();
            String stateUnit = proof.getValue().stateUnit();
            if (stateUnit == null) continue;
            Ast.Program stateProgram = units.get(stateUnit);
            if (stateProgram == null || !key.namespace().equals(stateProgram.namespace())) {
                throw deny("attached state source is absent or in another namespace");
            }
            Ast.ActorStateAttachmentDecl schema = null;
            for (Ast.ModuleDecl module : stateProgram.modules()) {
                if (!Parser.ROOT_MODULE.equals(module.name())) continue;
                for (Ast.Decl declaration : module.declarations()) {
                    if (declaration instanceof Ast.ActorStateAttachmentDecl candidate
                            && candidate.actorName().equals(key.actor())) {
                        if (schema != null) throw deny("ambiguous actor state schema");
                        schema = candidate;
                    }
                }
            }
            if (schema == null) throw deny("verified actor state schema disappeared");
            Map<String, Ast.AttachedActorStateField> fields = new LinkedHashMap<>();
            for (var field : schema.fields()) fields.put(field.name(), field);
            for (var unit : new TreeMap<>(units).entrySet()) {
                if (!key.namespace().equals(unit.getValue().namespace())) continue;
                for (Ast.ModuleDecl module : unit.getValue().modules()) {
                    if (!Parser.ROOT_MODULE.equals(module.name())) continue;
                    for (Ast.Decl declaration : module.declarations()) {
                        if (!(declaration instanceof Ast.ExternalActorMethodDecl method)
                                || !method.actorName().equals(key.actor())) continue;
                        var aliases = new HashSet<String>();
                        for (var statement : method.body()) {
                            if (statement instanceof Ast.ActorStateAccessStmt access) {
                                aliases.add(access.viewName());
                                operations.add(validateScope(
                                        unit.getKey(), key, method, access, fields));
                            }
                        }
                        for (var statement : method.body()) {
                            if (!(statement instanceof Ast.ActorStateAccessStmt)) {
                                verifyOutside(statement, aliases, fields.keySet());
                            }
                        }
                    }
                }
            }
        }
        return List.copyOf(operations);
    }

    private static CriticalOperation validateScope(
            String sourceUnit,
            ActorContractWitness.ActorKey key,
            Ast.ExternalActorMethodDecl method,
            Ast.ActorStateAccessStmt access,
            Map<String, Ast.AttachedActorStateField> fields) {
        if (access.body().size() != 1
                || !(access.body().getFirst() instanceof Ast.ExprStmt stmt)) {
            throw deny("critical scope must contain exactly one scalar operation");
        }
        var expr = stmt.expression();
        String fieldName;
        if (access.mode() == Ast.CriticalStateMode.WRITE) {
            if (!(expr instanceof Ast.AssignExpr assignment)) {
                throw deny("critical-write requires one checked field assignment");
            }
            fieldName = viewField(assignment.target(), access.viewName());
            var field = fields.get(fieldName);
            if (field == null) throw deny("critical-write names undeclared state field");
            if (field.bindingKind() != Ast.BindingKind.LET) {
                throw deny("critical-write cannot mutate immutable val state");
            }
            if (!field.type().name().equals("int")) {
                throw deny("v2 critical-write supports only checked int fields");
            }
            if (!(assignment.value() instanceof Ast.BinaryExpr operation)
                    || !operation.operator().equals("+")
                    || !fieldName.equals(viewField(operation.left(), access.viewName()))
                    || !validScalarMessage(operation.right(), method)) {
                throw deny("critical-write requires 'view.field = view.field + int_message'");
            }
        } else {
            if (!(expr instanceof Ast.CallExpr call)
                    || call.typeArgumentsPresent() || call.arguments().size() != 1
                    || !(call.callee() instanceof Ast.MemberExpr send)
                    || !send.member().equals("send")
                    || !(send.receiver() instanceof Ast.NameExpr owner)
                    || !owner.name().equals("self")) {
                throw deny("critical-read permits only a copy-only self.send(view.field)");
            }
            fieldName = viewField(call.arguments().getFirst(), access.viewName());
            var field = fields.get(fieldName);
            if (field == null) throw deny("critical-read names undeclared state field");
            if (!field.type().name().equals("int")) {
                throw deny("v2 critical-read permits only copied int fields");
            }
        }
        return new CriticalOperation(
                sourceUnit, key.namespace(), key.actor(), method.name(),
                access.mode(), fieldName, "int");
    }

    private static boolean validScalarMessage(
            Ast.Expr value, Ast.ExternalActorMethodDecl method) {
        if (value instanceof Ast.LiteralExpr literal)
            return literal.value() instanceof Long;
        if (!(value instanceof Ast.MemberExpr member)
                || !member.member().equals("value")
                || !(member.receiver() instanceof Ast.NameExpr name))
            return false;
        return method.parameters().stream().anyMatch(param ->
                param.name().equals(name.name())
                        && param.type().name().equals("ActorMail")
                        && param.type().arguments().size() == 1
                        && param.type().arguments().getFirst().name().equals("int"));
    }

    private static String viewField(Ast.Expr expr, String view) {
        if (expr instanceof Ast.MemberExpr member
                && member.receiver() instanceof Ast.NameExpr owner
                && owner.name().equals(view))
            return member.member();
        throw deny("critical state access must use an authenticated view.field");
    }

    private static void verifyOutside(
            Ast.Stmt stmt, Set<String> aliases, Set<String> stateFields) {
        if (stmt instanceof Ast.ReturnStmt ret) {
            checkExpr(ret.value(), aliases, stateFields);
        } else if (stmt instanceof Ast.ExprStmt expr) {
            checkExpr(expr.expression(), aliases, stateFields);
        } else if (stmt instanceof Ast.IfStmt conditional) {
            for (var branch : conditional.branches()) {
                checkExpr(branch.condition(), aliases, stateFields);
                for (var inner : branch.body()) verifyOutside(inner, aliases, stateFields);
            }
            for (var inner : conditional.elseBody()) verifyOutside(inner, aliases, stateFields);
        } else if (stmt instanceof Ast.BlockStmt block) {
            for (var inner : block.body()) verifyOutside(inner, aliases, stateFields);
        } else if (stmt instanceof Ast.BindingStmt bind) {
            checkExpr(bind.initializer(), aliases, stateFields);
        } else if (stmt instanceof Ast.ActorStateAccessStmt) {
            throw deny("nested or hidden critical state scope is not admitted");
        } else {
            throw deny("actor state method contains unverified control-flow/effect");
        }
    }

    private static void checkExpr(Ast.Expr expr, Set<String> aliases,
                                  Set<String> stateFields) {
        if (expr == null || expr instanceof Ast.LiteralExpr) return;
        if (expr instanceof Ast.NameExpr name) {
            if (aliases.contains(name.name()))
                throw deny("critical state view escaped its lexical scope");
            return;
        }
        if (expr instanceof Ast.MemberExpr member) {
            if (member.receiver() instanceof Ast.NameExpr name
                    && name.name().equals("self")
                    && stateFields.contains(member.member())) {
                throw deny("direct actor state member access is forbidden");
            }
            checkExpr(member.receiver(), aliases, stateFields);
            return;
        }
        if (expr instanceof Ast.BinaryExpr binary) {
            checkExpr(binary.left(), aliases, stateFields);
            checkExpr(binary.right(), aliases, stateFields);
            return;
        }
        if (expr instanceof Ast.UnaryExpr unary) {
            checkExpr(unary.operand(), aliases, stateFields);
            return;
        }
        if (expr instanceof Ast.AssignExpr assign) {
            checkExpr(assign.target(), aliases, stateFields);
            checkExpr(assign.value(), aliases, stateFields);
            return;
        }
        if (expr instanceof Ast.CallExpr call) {
            checkExpr(call.callee(), aliases, stateFields);
            for (var argument : call.arguments())
                checkExpr(argument, aliases, stateFields);
            return;
        }
        throw deny("unverified actor-state expression may escape borrowed state");
    }

    private static IllegalArgumentException deny(String message) {
        return new IllegalArgumentException(
                "ACTOR_CRITICAL_STATE_PREFLIGHT_REJECTED: " + message);
    }
}
