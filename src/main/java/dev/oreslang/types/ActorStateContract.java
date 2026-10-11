package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fail-closed source gate for the actor-state *prototype*. No codegen or
 * scheduler continuation semantics are implied by passing this AST check.
 *
 * The state capability belongs to a handle_state callback, never to the
 * actor's ordinary member namespace or to an escaped closure/argument.
 */
public final class ActorStateContract {
    private ActorStateContract() { }

    public static boolean isStateField(Ast.FieldDecl field) {
        return field.name().equals("state") && field.annotations().stream()
                .anyMatch(a -> a.name().equals("$actor_state$"));
    }

    public static Ast.FieldDecl stateField(Ast.ClassDecl owner) {
        if (owner == null || owner.actorKind() == Ast.ActorKind.NONE) return null;
        for (Ast.FieldDecl field : owner.fields()) {
            if (isStateField(field)) return field;
        }
        return null;
    }

    public static Ast.TypeRef keySchema(Ast.FieldDecl field) {
        Ast.TypeRef type = field.type();
        if (type.name().equals("$actor_state_map$")) return type.arguments().get(1);
        // A completely dynamic homogeneous Map has no statically named keys.
        // It remains sealed; its dynamic get/set operations need separate lowering.
        if (type.name().equals("Map")) return Ast.TypeRef.recordType(Map.of());
        return type;
    }

    public static void verify(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.ClassDecl klass
                        && klass.actorKind() != Ast.ActorKind.NONE) verifyActor(klass);
            }
        }
    }

    private static void verifyActor(Ast.ClassDecl klass) {
        Ast.FieldDecl state = stateField(klass);
        if (state == null) return; // migration: legacy actor fields retain old behavior
        if (klass.fields().size() != 1) {
            throw new IllegalArgumentException(
                    "actor '" + klass.name() + "' may declare only the state schema");
        }
        Ast.TypeRef schema = keySchema(state);
        if (!schema.isRecordType()) {
            throw new IllegalArgumentException("actor state schema must declare typed keys");
        }
        Set<String> keys = schema.recordMembers().keySet();
        // No continuation-bound alias may be smuggled into actor-state defaults.
        if (state.initializer() instanceof Ast.ObjectExpr defaults) {
            for (Ast.ObjectField def : defaults.fields()) {
                if (!keys.contains(def.name())) {
                    throw new IllegalArgumentException("unknown actor state default key '" + def.name() + "'");
                }
            }
        }
        for (Ast.MethodDecl method : klass.methods()) {
            if (method.name().equals("handle_state")) {
                throw new IllegalArgumentException("handle_state is a sealed actor intrinsic");
            }
            visit(method.body(), klass.name(), keys, null, false);
        }
    }

    private static void visit(Object node, String actor, Set<String> keys,
                              String activeAccess, boolean nestedClosure) {
        if (node == null) return;
        if (node instanceof Ast.CallExpr call
                && call.callee() instanceof Ast.MemberExpr callee
                && isSelf(callee.receiver()) && callee.member().equals("handle_state")) {
            if (nestedClosure) {
                throw new IllegalArgumentException(
                        "deferred closure cannot acquire actor state without scheduler admission");
            }
            if (call.typeArgumentsPresent() || call.arguments().size() != 1
                    || !(call.arguments().getFirst() instanceof Ast.LambdaExpr lambda)
                    || lambda.parameters().size() != 1) {
                throw new IllegalArgumentException("self.handle_state expects exactly one single-argument lambda");
            }
            if (activeAccess != null && referencesAccess(call.arguments(), activeAccess)) {
                throw new IllegalArgumentException(
                        "outer actor state loan cannot be captured by nested handle_state");
            }
            String variable = lambda.parameters().getFirst().name();
            if (variable.equals("self")) {
                throw new IllegalArgumentException("actor state access parameter cannot shadow self");
            }
            visit(lambda.expressionBody(), actor, keys, variable, false);
            visit(lambda.blockBody(), actor, keys, variable, false);
            return;
        }
        // A live actor-state capability is a non-suspending, lexical borrow.
        // Until the compiler can segment/revalidate loans on resume, admitting
        // any suspension point would let another continuation mutate the same
        // memory while this callback retains its old view.
        if (activeAccess != null) {
            if (node instanceof Ast.AwaitExpr
                    || node instanceof Ast.SelectStmt
                    || node instanceof Ast.DynamicSelectExpr
                    || node instanceof Ast.ChannelOpExpr
                    || node instanceof Ast.RuntimeCallExpr) {
                throw new IllegalArgumentException(
                        "actor state access cannot cross await, select, rt operations or other suspension boundaries");
            }
            if (node instanceof Ast.CallExpr) {
                // The sealed self.handle_state nested call is handled above.
                // Calls with no transitive, verified no-suspend summary are
                // unsafe: even a syntactically synchronous callee may yield.
                throw new IllegalArgumentException(
                        "actor state callback cannot call an unverified callee; "
                        + "continuation-safe effect and borrow checking are required");
            }
        }
        if (node instanceof Ast.MemberExpr member
                && isSelf(member.receiver()) && member.member().equals("state")) {
            throw new IllegalArgumentException(
                    "actor '" + actor + "' state cannot be accessed as self.state; use self.handle_state");
        }
        if (node instanceof Ast.MemberExpr member
                && activeAccess != null
                && member.receiver() instanceof Ast.NameExpr name
                && name.name().equals(activeAccess)) {
            if (nestedClosure) {
                throw new IllegalArgumentException(
                        "actor state access capability cannot be captured by nested closures");
            }
            if (!keys.contains(member.member())) {
                throw new IllegalArgumentException("undeclared actor state key '" + member.member() + "'");
            }
            return; // scoped direct field operation, subject to normal type checking
        }
        if (node instanceof Ast.NameExpr name && activeAccess != null
                && name.name().equals(activeAccess)) {
            throw new IllegalArgumentException(
                    "actor state access capability cannot escape handle_state callback");
        }
        if (node instanceof Ast.LambdaExpr lambda) {
            visit(lambda.expressionBody(), actor, keys, activeAccess, true);
            visit(lambda.blockBody(), actor, keys, activeAccess, true);
            return;
        }
        if (node instanceof Ast.RuntimeCallExpr op && activeAccess != null
                && (op.operation().equals("borrow") || op.operation().equals("borrow_mut")
                    || op.operation().equals("borrowMut"))
                && referencesAccess(op.arguments(), activeAccess)) {
            throw new IllegalArgumentException(
                    "actor state-backed borrow requires continuation-aware loan lowering");
        }
        if (node instanceof Iterable<?> items) {
            for (Object item : items) visit(item, actor, keys, activeAccess, nestedClosure);
            return;
        }
        if (node instanceof java.lang.Record record
                && node.getClass().getName().startsWith("dev.oreslang.ast.")) {
            for (RecordComponent component : record.getClass().getRecordComponents()) {
                try {
                    visit(component.getAccessor().invoke(record),
                            actor, keys, activeAccess, nestedClosure);
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("failed to validate actor state AST", failure);
                }
            }
        }
    }

    private static boolean referencesAccess(Object node, String access) {
        if (node == null) return false;
        if (node instanceof Ast.NameExpr name) return name.name().equals(access);
        if (node instanceof Ast.LambdaExpr lambda) {
            // A nested callback may intentionally shadow the outer loan name.
            // Its accesses then belong to its own scoped state capability.
            if (lambda.parameters().stream().anyMatch(p -> p.name().equals(access))) return false;
            return referencesAccess(lambda.expressionBody(), access)
                    || referencesAccess(lambda.blockBody(), access);
        }
        if (node instanceof Iterable<?> items) {
            for (Object item : items) if (referencesAccess(item, access)) return true;
            return false;
        }
        if (node instanceof java.lang.Record record
                && node.getClass().getName().startsWith("dev.oreslang.ast.")) {
            for (RecordComponent component : record.getClass().getRecordComponents()) {
                try {
                    if (referencesAccess(component.getAccessor().invoke(record), access)) return true;
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("failed to validate state loan", failure);
                }
            }
        }
        return false;
    }

    private static boolean isSelf(Ast.Expr expr) {
        return expr instanceof Ast.NameExpr name && name.name().equals("self");
    }
}
