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
            // Resolve explicit same-module actor parents before checking any
            // subclass, including forward declarations. Ambient names and
            // imported classes do not grant hidden actor storage authority.
            Map<String, Ast.ClassDecl> declared = new java.util.HashMap<>();
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.ClassDecl klass
                        && declared.putIfAbsent(klass.name(), klass) != null) {
                    throw new IllegalArgumentException(
                            "duplicate class/actor symbol '" + klass.name() + "'");
                }
            }
            for (Ast.ClassDecl klass : declared.values()) {
                if (klass.actorKind() != Ast.ActorKind.NONE) verifyActor(klass, declared);
            }
        }
    }

    private static void checkInheritedActorLayout(
            Ast.ClassDecl child, Ast.TypeRef parentRef,
            Map<String, Ast.ClassDecl> declared, Set<String> active) {
        if (!parentRef.arguments().isEmpty()) {
            throw new IllegalArgumentException(
                    "actor superclass type arguments require verified layout lowering");
        }
        Ast.ClassDecl parent = declared.get(parentRef.name());
        if (parent == null || parent.actorKind() == Ast.ActorKind.NONE) {
            throw new IllegalArgumentException(
                    "actor superclass must resolve to an explicit same-module actor");
        }
        if (!active.add(parent.name())) {
            throw new IllegalArgumentException("cyclic actor superclass inheritance");
        }
        try {
            if (parent.actorKind() != child.actorKind() || parent.serial() != child.serial()) {
                throw new IllegalArgumentException(
                        "actor superclass isolation kind or serial lane mismatch");
            }
            // The one state map belongs to the concrete actor. Method-only
            // actor parents are permitted, but any inherited state map or
            // legacy field would otherwise bypass sealed handle_state.
            if (!parent.fields().isEmpty()) {
                throw new IllegalArgumentException(
                        "actor superclass inherited storage is forbidden until "
                        + "sealed state-layout composition is verified");
            }
            if (parent.parents().size() > 1) {
                throw new IllegalArgumentException(
                        "multiple actor superclass layouts are unsupported");
            }
            for (Ast.TypeRef ancestor : parent.parents()) {
                checkInheritedActorLayout(child, ancestor, declared, active);
            }
        } finally {
            active.remove(parent.name());
        }
    }

    private static void verifyActor(
            Ast.ClassDecl klass, Map<String, Ast.ClassDecl> declared) {
        // Serial superclass lane ownership is not yet proven; retain the
        // existing serial fail-closed rule. Non-serial actors may explicitly
        // inherit methods from same-kind, storage-free actor ancestors only.
        if (klass.serial() && !klass.parents().isEmpty()) {
            throw new IllegalArgumentException(
                    "serial actor superclass lane ownership is not verified");
        }
        if (klass.parents().size() > 1) {
            throw new IllegalArgumentException(
                    "multiple actor superclass layouts are unsupported");
        }
        Set<String> active = new java.util.HashSet<>();
        active.add(klass.name());
        for (Ast.TypeRef parent : klass.parents()) {
            checkInheritedActorLayout(klass, parent, declared, active);
        }
        // Compiler passes must not introduce a constructor after parsing: it would
        // create a second, unchecked initialization/write authority.
        if (klass.constructor() != null) {
            throw new IllegalArgumentException("actor constructors cannot bypass sealed state initialization");
        }
        Ast.FieldDecl state = stateField(klass);
        // Actors without state are legal, but their methods must still be
        // checked: an early return here lets a state-less actor reference
        // self.state or even define a counterfeit handle_state method.
        if (state == null && !klass.fields().isEmpty()) {
            throw new IllegalArgumentException(
                    "actor '" + klass.name()
                    + "' must use one state { ... } aggregate; legacy fields are forbidden");
        }
        if (state != null && klass.fields().size() != 1) {
            throw new IllegalArgumentException(
                    "actor '" + klass.name() + "' may declare only the state schema");
        }
        // Null here intentionally means no state map, different from a
        // declared map with zero keys. Both cases are still inspected.
        Set<String> keys = null;
        if (state != null) {
            // The hidden state slot is a compiler-owned layout, not an ordinary
            // field whose visibility, mutability or annotation can be changed
            // by another AST producer/annotation expansion pass.
            if (state.visibility() != Ast.Visibility.PRIVATE
                    || state.bindingKind() != Ast.BindingKind.VAL
                    || !state.mutableReferent()
                    || state.annotations().size() != 1
                    || !state.annotations().getFirst().name().equals("$actor_state$")
                    || !state.annotations().getFirst().arguments().isEmpty()) {
                throw new IllegalArgumentException("actor state requires canonical sealed compiler-owned layout");
            }
            if (state.initializer() != null && !(state.initializer() instanceof Ast.ObjectExpr)) {
                throw new IllegalArgumentException("actor state initializer must be a closed key/value aggregate");
            }
            if (state.type().name().equals("Map")) {
                throw new IllegalArgumentException(
                        "actor state requires statically known typed keys; unbounded dynamic Map is not sealed");
            }
            Ast.TypeRef schema = keySchema(state);
            if (!schema.isRecordType()) {
                throw new IllegalArgumentException("actor state schema must declare typed keys");
            }
            keys = schema.recordMembers().keySet();
            // No continuation-bound alias may be smuggled into actor-state defaults.
            Set<String> seenDefaults = new java.util.HashSet<>();
            if (state.initializer() instanceof Ast.ObjectExpr defaults) {
                for (Ast.ObjectField def : defaults.fields()) {
                    if (!seenDefaults.add(def.name()))
                        throw new IllegalArgumentException("duplicate actor state default key '" + def.name() + "'");
                    if (!keys.contains(def.name())) {
                        throw new IllegalArgumentException("unknown actor state default key '" + def.name() + "'");
                    }
                    // Initialization is a runtime-owned exceptional write, not
                    // a user critical-section lease. Deny calls/aliases/FFI,
                    // and admit only freshly constructed literal aggregates.
                    if (!sealedDefault(def.value())) {
                        throw new IllegalArgumentException(
                                "actor state default requires an owned constant expression: " + def.name());
                    }
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

    private static boolean sealedDefault(Ast.Expr expression) {
        if (expression instanceof Ast.LiteralExpr literal) {
            Object value = literal.value();
            return value == null || value instanceof String || value instanceof Boolean
                    || value instanceof Integer || value instanceof Long
                    || value instanceof Double || value instanceof Float
                    || value instanceof java.math.BigInteger
                    || value instanceof java.math.BigDecimal;
        }
        if (expression instanceof Ast.UnaryExpr unary) {
            return (unary.operator().equals("-") || unary.operator().equals("+"))
                    && unary.operand() instanceof Ast.LiteralExpr literal
                    && literal.value() instanceof Number;
        }
        if (expression instanceof Ast.ListExpr list)
            return list.elements().stream().allMatch(ActorStateContract::sealedDefault);
        if (expression instanceof Ast.TupleExpr tuple)
            return tuple.elements().stream().allMatch(ActorStateContract::sealedDefault);
        if (expression instanceof Ast.ObjectExpr object)
            return object.fields().stream().allMatch(field -> sealedDefault(field.value()));
        // In particular no NameExpr, MemberExpr, CallExpr, NewExpr, alias,
        // pointer, ownership intrinsic, coroutine or externally owned value.
        return false;
    }

    private static void visit(Object node, String actor, Set<String> keys,
                              String activeAccess, boolean nestedClosure, boolean instanceMethod,
                              Ast.CriticalMode accessMode) {
        if (node == null) return;
        if (node instanceof Ast.CallExpr call
                && call.callee() instanceof Ast.MemberExpr callee
                && isSelf(callee.receiver()) && callee.member().equals("handle_state")) {
            if (keys == null) {
                throw new IllegalArgumentException(
                        "actor '" + actor + "' has no state { ... } schema for handle_state");
            }
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
            Ast.CriticalMode loanMode = switch (lambda.criticalMode()) {
                case NONE, READ -> Ast.CriticalMode.READ;
                case EXCLUSIVE, WRITE -> Ast.CriticalMode.WRITE;
            };
            // No nested state loan may reenter an already borrowed map,
            // including read/read with a shadowed lambda parameter.
            if (activeAccess != null)
                throw new IllegalArgumentException("nested actor state loan is forbidden");
            if (accessMode == Ast.CriticalMode.READ
                    && loanMode == Ast.CriticalMode.WRITE)
                throw new IllegalArgumentException("cannot upgrade an active read loan to write");
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
        if (activeAccess != null
                && (node instanceof Ast.AwaitExpr || node instanceof Ast.SelectStmt
                    || node instanceof Ast.DynamicSelectExpr || node instanceof Ast.ChannelOpExpr
                    || node instanceof Ast.YieldStmt || node instanceof Ast.DeferStmt
                    || node instanceof Ast.ForStmt || node instanceof Ast.ForOfStmt
                    || node instanceof Ast.ForOfDestructureStmt || node instanceof Ast.LoopStmt
                    || (node instanceof Ast.RuntimeCallExpr op
                        && op.operation().equals("cooperate")))) {
            throw new IllegalArgumentException(
                    "actor state loan cannot cross await, select, or suspension");
        }
        // The state mode is a capability; using never upgrades read.
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
        // A live actor-state projection is not yet a compiler-proven owned
        // snapshot. In particular, rt copy(s.field) must not silently
        // manufacture an escaping value before typed snapshot lowering.
        // Unrelated owned rt copy expressions remain permitted.
        // The parser lowers both legacy '&s.field' and canonical
        // 'rt borrow mut s.field' to UnaryExpr ("&" / "&mut").
        // Checking only RuntimeCallExpr("borrow") would miss those paths.
        // A live state projection is not an owned value and cannot acquire
        // an additional escaping borrow, immutable or mutable.
        if (node instanceof Ast.UnaryExpr borrow && activeAccess != null
                && (borrow.operator().equals("&") || borrow.operator().equals("&mut"))
                && referencesAccess(borrow.operand(), activeAccess)) {
            throw new IllegalArgumentException(
                    "actor state-backed borrow cannot escape its lexical loan");
        }
        if (node instanceof Ast.RuntimeCallExpr copy && activeAccess != null
                && copy.operation().equals("copy")
                && referencesAccess(copy.arguments(), activeAccess)) {
            throw new IllegalArgumentException(
                    "actor state-backed rt copy requires verified snapshot lowering");
        }
        // Unknown ownership/pointer intrinsics cannot silently gain direct
        // state references as their argument. The only trusted access path
        // remains compiler-lowered scoped read/write.
        if (node instanceof Ast.RuntimeCallExpr op && activeAccess != null
                && referencesAccess(op.arguments(), activeAccess)) {
            // A borrowed state projection cannot be moved out of the map or
            // published as Shared<T>. A primitive copy is permitted, but
            // ownership-changing operations need a proven snapshot lowering.
            throw new IllegalArgumentException(
                    "actor state-backed runtime ownership/pointer operation lacks verified scoped lowering");
        }
        if (node instanceof Map<?, ?> entries) {
            for (Object value : entries.values())
                visit(value, actor, keys, activeAccess, nestedClosure, instanceMethod, accessMode);
            return;
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
        if (node instanceof Map<?, ?> entries) {
            for (Object value : entries.values()) if (referencesAccess(value, access)) return true;
            return false;
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
