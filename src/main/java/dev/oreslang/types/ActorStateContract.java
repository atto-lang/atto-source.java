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
        // This compiler branch does not yet join inherited actor state
        // schemas or prove that a parent actor uses the same serial lane.
        // In particular an inherited mutable field cannot silently become
        // a second state aggregate outside the sealed handle_state contract.
        if (klass.serial() && !klass.parents().isEmpty()) {
            throw new IllegalArgumentException(
                    "serial actor '" + klass.name()
                    + "' cannot extend another class until inherited state/lane "
                    + "ownership is verified; use from/implements interfaces");
        }
        Ast.FieldDecl state = stateField(klass);
        if (state == null) {
            // All actor kinds are sealed: SHARED, PRIVATE and UNTRUSTED
            // cannot mint mutable stored instance slots outside state { }.
            // Storage-free actors are legal and still checked below.
            if (!klass.fields().isEmpty()) {
                throw new IllegalArgumentException(
                        "actor '" + klass.name()
                        + "' must use one state { ... } aggregate; legacy fields are forbidden");
            }
            // Storage-free actors still require sealed member and effect checks.
            // A missing schema cannot authorize a handle_state call.
            for (Ast.MethodDecl method : klass.methods()) {
                if (method.name().equals("handle_state")) {
                    throw new IllegalArgumentException("handle_state is a sealed actor intrinsic");
                }
                visit(method.body(), klass.name(), null, null, false,
                        !method.isStatic(), Ast.CriticalMode.NONE);
            }
            return;
        }
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
            visit(method.body(), klass.name(), keys, null, false,
                    !method.isStatic(), Ast.CriticalMode.NONE);
        }
    }

    private static void visit(Object node, String actor, Set<String> keys,
                              String activeAccess, boolean nestedClosure, boolean instanceMethod,
                              Ast.CriticalMode accessMode) {
        if (node == null) return;
        if (node instanceof Ast.CallExpr call
                && call.callee() instanceof Ast.MemberExpr callee
                && isSelf(callee.receiver()) && callee.member().equals("handle_state")) {
            if (!instanceMethod) {
                throw new IllegalArgumentException(
                        "static actor method cannot acquire an instance handle_state capability");
            }
            if (nestedClosure) {
                throw new IllegalArgumentException(
                        "deferred closure cannot acquire actor state without scheduler admission");
            }
            if (call.typeArgumentsPresent() || call.arguments().size() != 1
                    || !(call.arguments().getFirst() instanceof Ast.LambdaExpr lambda)
                    || lambda.parameters().size() != 1) {
                throw new IllegalArgumentException("self.handle_state expects exactly one single-argument lambda");
            }
            if (keys == null) {
                throw new IllegalArgumentException("self.handle_state requires a declared actor state schema");
            }
            // An unmarked callback is READ. Both 'critical' and
            // 'critical write' request WRITE, never inferred from 'with'.
            Ast.CriticalMode loanMode = switch (lambda.criticalMode()) {
                case NONE, READ -> Ast.CriticalMode.READ;
                case EXCLUSIVE, WRITE -> Ast.CriticalMode.WRITE;
            };
            if (activeAccess != null) {
                throw new IllegalArgumentException("nested actor state loan is forbidden");
            }
            if (lambda.nonLexical()) {
                throw new IllegalArgumentException(
                        "critical handle_state callback must keep its lexical actor scope");
            }
            if (activeAccess != null && referencesAccess(call.arguments(), activeAccess)) {
                throw new IllegalArgumentException(
                        "outer actor state loan cannot be captured by nested handle_state");
            }
            String variable = lambda.parameters().getFirst().name();
            if (variable.equals("self")) {
                throw new IllegalArgumentException("actor state access parameter cannot shadow self");
            }
            visit(lambda.expressionBody(), actor, keys, variable, false, instanceMethod, loanMode);
            visit(lambda.blockBody(), actor, keys, variable, false, instanceMethod, loanMode);
            return;
        }
        // Never retain a live state loan through a suspension or implicit
        // carrier handoff. An effect row cannot permit an escaped loan.
        if (activeAccess != null
                && (node instanceof Ast.AwaitExpr
                    || node instanceof Ast.SelectStmt
                    || node instanceof Ast.DynamicSelectExpr
                    || node instanceof Ast.ChannelOpExpr
                    || (node instanceof Ast.RuntimeCallExpr operation
                        && operation.operation().equals("cooperate")))) {
            throw new IllegalArgumentException("actor state loan cannot cross suspension");
        }
        // The state mode is a capability; 'using state.write' never upgrades read.
        if (activeAccess != null && accessMode == Ast.CriticalMode.READ
                && node instanceof Ast.AssignExpr assign
                && referencesAccess(assign.target(), activeAccess)) {
            throw new IllegalArgumentException("critical read cannot modify actor state");
        }
        if (activeAccess != null && accessMode == Ast.CriticalMode.READ
                && node instanceof Ast.CallExpr call
                && referencesAccess(call.callee(), activeAccess)) {
            throw new IllegalArgumentException("critical read cannot call potentially mutating state methods");
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
            // A critical state callback is a sealed lexical capability and
            // cannot be passed to an arbitrary function, stored, or invoked
            // outside self.handle_state. The intrinsic case above consumes
            // the only permitted critical lambda before this generic path.
            if (lambda.critical()) {
                throw new IllegalArgumentException(
                        "critical state callback may only appear inline in self.handle_state");
            }
            visit(lambda.expressionBody(), actor, keys, activeAccess, true, instanceMethod, accessMode);
            visit(lambda.blockBody(), actor, keys, activeAccess, true, instanceMethod, accessMode);
            return;
        }
        if (node instanceof Ast.RuntimeCallExpr op && activeAccess != null
                && (op.operation().equals("borrow") || op.operation().equals("borrow_mut")
                    || op.operation().equals("borrowMut")
                    || op.operation().equals("take")
                    || op.operation().equals("share")
                    || op.operation().equals("copy"))
                && referencesAccess(op.arguments(), activeAccess)) {
            // State field projections do not transfer ownership. Even a deep
            // copy needs an independently verified typed projection/snapshot
            // contract; an ephemeral StateAccess handle is never transferable.
            throw new IllegalArgumentException(
                    "actor state-backed ownership operation requires verified snapshot lowering");
        }
        if (node instanceof Iterable<?> items) {
            for (Object item : items) visit(item, actor, keys, activeAccess, nestedClosure, instanceMethod, accessMode);
            return;
        }
        if (node instanceof java.lang.Record record
                && node.getClass().getName().startsWith("dev.oreslang.ast.")) {
            for (RecordComponent component : record.getClass().getRecordComponents()) {
                try {
                    visit(component.getAccessor().invoke(record),
                            actor, keys, activeAccess, nestedClosure, instanceMethod, accessMode);
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
