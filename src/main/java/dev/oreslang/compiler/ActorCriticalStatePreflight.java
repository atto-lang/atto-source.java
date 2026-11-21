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
 * V2 admits one checked scalar increment in a WRITE loan, followed by
 * a detached Copy i64 expression in a READ loan. Mailbox send is only
 * admitted after that critical-read loan ends. This is a source/typed
 * witness, not permission to lower authenticated actor state into machine
 * code. Old in-loan sends and borrowed view escape are rejected.
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
                        var readSnapshots = new HashSet<String>();
                        for (var statement : method.body()) {
                            if (statement instanceof Ast.ActorStateAccessStmt access) {
                                if (access.viewName().equals(method.receiverName())
                                        || method.parameters().stream().anyMatch(
                                                param -> param.name().equals(access.viewName()))) {
                                    throw deny("critical state loan shadows actor receiver or mail");
                                }
                                aliases.add(access.viewName());
                            }
                            if (statement instanceof Ast.BindingStmt bind
                                    && bind.initializer() instanceof Ast.ActorStateReadExpr read)
                                aliases.add(read.viewName());
                        }
                        String pendingSnapshot = null;
                        for (var statement : method.body()) {
                            if (pendingSnapshot != null) {
                                verifyDetachedSend(statement, pendingSnapshot);
                                // The closed v2 profile consumes a detached Copy
                                // exactly once; no second use, rebind or escape.
                                aliases.add(pendingSnapshot);
                                pendingSnapshot = null;
                            } else if (statement instanceof Ast.ActorStateAccessStmt access) {
                                operations.add(validateScope(
                                        unit.getKey(), key, method, access, fields));
                            } else if (statement instanceof Ast.BindingStmt bind
                                    && bind.initializer() instanceof Ast.ActorStateReadExpr read) {
                                if (!readSnapshots.isEmpty()) {
                                    throw deny("closed v2 receive admits one detached Copy read only");
                                }
                                if (read.viewName().equals(method.receiverName())
                                        || read.viewName().equals(bind.name())) {
                                    throw deny("critical-read view cannot shadow a receiver or Copy binding");
                                }
                                if (bind.kind() != Ast.BindingKind.VAL
                                        || bind.declaredType() == null
                                        || !bind.declaredType().name().equals("int")
                                        || !bind.declaredType().arguments().isEmpty()
                                        || bind.declaredType().inferArguments()) {
                                    throw deny("detached critical-read requires val int Copy binding");
                                }
                                if (bind.name().equals("self")
                                        || bind.name().equals("handle_state")
                                        || bind.name().equals(read.viewName())
                                        || bind.name().equals(method.receiverName())
                                        || readSnapshots.contains(bind.name())
                                        || fields.containsKey(bind.name())
                                        || method.parameters().stream().anyMatch(
                                                p -> p.name().equals(bind.name())
                                                        || p.name().equals(read.viewName()))) {
                                    throw deny("detached state Copy binding shadows source authority");
                                }
                                var field = fields.get(read.fieldName());
                                if (field == null || !field.type().name().equals("int")
                                        || !field.type().arguments().isEmpty()
                                        || field.type().inferArguments()) {
                                    throw deny("detached critical-read requires declared scalar int field");
                                }
                                readSnapshots.add(bind.name());
                                operations.add(new CriticalOperation(
                                        unit.getKey(), key.namespace(), key.actor(),
                                        method.name(), Ast.CriticalStateMode.READ,
                                        read.fieldName(), "int"));
                                pendingSnapshot = bind.name();
                            } else {
                                verifyOutside(statement, aliases, fields.keySet());
                            }
                        }
                        if (pendingSnapshot != null)
                            throw deny("detached critical-read must be followed by self.send(Copy)");
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
            // Never send while a critical-read view is held. The only allowed
            // source form is a detached Copy scalar expression followed by
            // self.send(snapshot) after the authenticated loan ends.
            throw deny("critical-read mailbox send inside borrowed scope forbidden; "
                    + "use val int snapshot = self.handle_state(critical-read |s| -> s.field)");
        }
        return new CriticalOperation(
                sourceUnit, key.namespace(), key.actor(), method.name(),
                access.mode(), fieldName, "int");
    }

    private static void verifyDetachedSend(Ast.Stmt statement, String copied) {
        if (!(statement instanceof Ast.ExprStmt expression)
                || !(expression.expression() instanceof Ast.CallExpr call)
                || call.typeArgumentsPresent()
                || call.arguments().size() != 1
                || !(call.arguments().getFirst() instanceof Ast.NameExpr value)
                || !value.name().equals(copied)
                || !(call.callee() instanceof Ast.MemberExpr send)
                || !send.member().equals("send")
                || !(send.receiver() instanceof Ast.NameExpr receiver)
                || !receiver.name().equals("self")) {
            throw deny("detached critical-read must be followed by self.send(Copy) "
                    + "outside the critical loan");
        }
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
