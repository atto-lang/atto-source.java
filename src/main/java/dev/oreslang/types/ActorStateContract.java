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
        Map<String, Ast.TypeRef> keys = null;
        if (state != null) {
            Ast.TypeRef schema = keySchema(state);
            if (!schema.isRecordType()) {
                throw new IllegalArgumentException("actor state schema must declare typed keys");
            }
            keys = schema.recordMembers();
            // No continuation-bound alias may be smuggled into actor-state defaults.
            if (state.initializer() instanceof Ast.ObjectExpr defaults) {
                for (Ast.ObjectField def : defaults.fields()) {
                    if (!keys.containsKey(def.name())) {
                        throw new IllegalArgumentException("unknown actor state default key '" + def.name() + "'");
                    }
                    if (!detachedScalarStateType(keys.get(def.name()))
                            && !isInlineActorStateDefault(def.value())) {
                        // Actor constructors have no state loan to fence
                        // external aliases. Reject reference-valued defaults
                        // sourced from globals, calls, member access or FFI.
                        // Inline literals still need per-actor allocation proof
                        // at the runtime lowering boundary.
                        throw new IllegalArgumentException(
                                "actor state reference default requires verified owned initialization");
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

    private static void visit(Object node, String actor, Map<String, Ast.TypeRef> keys,
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
                    || node instanceof Ast.YieldStmt
                    || (node instanceof Ast.RuntimeCallExpr op
                        && op.operation().equals("cooperate")))) {
            throw new IllegalArgumentException(
                    "actor state loan cannot cross await, select, or suspension");
        }
        // A local binding or assignment of a reference-bearing state
        // projection can launder a live actor-owned alias even when the
        // enclosing handle_state callback returns only an int. Until typed
        // detachment is proven, reject that materialization at the source
        // boundary. Direct immutable scalar snapshots remain legal.
        if (activeAccess != null && keys != null) {
            if (node instanceof Ast.BindingStmt binding
                    && containsReferenceBearingStateProjection(
                            binding.initializer(), activeAccess, keys)) {
                throw new IllegalArgumentException(
                        "actor state-backed reference alias requires verified snapshot lowering");
            }
            if (node instanceof Ast.AssignExpr assignment
                    && !isDirectStateFieldTarget(assignment.target(), activeAccess)
                    && containsReferenceBearingStateProjection(
                            assignment.value(), activeAccess, keys)) {
                // Merely mentioning s somewhere in an indexed/derived target
                // does not make the destination actor-owned.
                throw new IllegalArgumentException(
                        "actor state-backed reference alias requires verified snapshot lowering");
            }
            if (node instanceof Ast.AssignExpr assignment
                    && !isDirectStateFieldTarget(assignment.target(), activeAccess)
                    && containsReferenceBearingStateProjection(
                            assignment.target(), activeAccess, keys)) {
                // Nested list/map/record writes need element-level provenance
                // plus write-borrow lowering, not merely a critical outer
                // admission. This includes s.items[i] = external and
                // s.items.element = external.
                throw new IllegalArgumentException(
                        "actor state nested reference write requires verified element ownership");
            }
            if (node instanceof Ast.TypeTestExpr test
                    && containsReferenceBearingStateProjection(
                            test.value(), activeAccess, keys)) {
                // The 'is Type alias' form can bind the object by reference.
                throw new IllegalArgumentException(
                        "actor state-backed reference pattern binding requires verified no-escape lowering");
            }
            if (node instanceof Ast.PatternTestExpr test
                    && containsReferenceBearingStateProjection(
                            test.value(), activeAccess, keys)) {
                throw new IllegalArgumentException(
                        "actor state-backed reference pattern binding requires verified no-escape lowering");
            }
            if (node instanceof Ast.MatchStmt match
                    && containsReferenceBearingStateProjection(
                            match.subject(), activeAccess, keys)) {
                // Match arm variables may retain the matched subject.
                throw new IllegalArgumentException(
                        "actor state-backed reference match requires verified pattern ownership");
            }
            if (node instanceof Ast.AssignExpr assignment
                    && isDirectStateFieldTarget(assignment.target(), activeAccess)
                    && !detachedScalarStateType(keys.get(
                            ((Ast.MemberExpr) assignment.target()).member()))
                    && !isDirectActorOwnedStateValue(
                            assignment.value(), activeAccess, keys)) {
                // An exclusive write loan protects the actor's state, but
                // cannot adopt an arbitrary external reference into its heap.
                // Reference-valued field writes must originate in the same
                // scoped actor-owned map until owned allocation/move lowering
                // can prove detached storage at this exact assignment.
                throw new IllegalArgumentException(
                        "actor state reference write requires verified owned-value lowering");
            }
            if (node instanceof Ast.NewExpr allocation
                    && containsReferenceBearingStateProjection(
                            allocation.arguments(), activeAccess, keys)) {
                // Constructor/FFI code may retain or publish the arguments.
                throw new IllegalArgumentException(
                        "actor state-backed reference construction requires verified no-escape lowering");
            }
            if (node instanceof Ast.RuntimeCallExpr runtime
                    && containsReferenceBearingStateProjection(
                            runtime.arguments(), activeAccess, keys)) {
                // Never silently trust a new or unknown rt intrinsic to
                // detach, read-only-wrap, or avoid retaining an actor ref.
                throw new IllegalArgumentException(
                        "actor state-backed runtime reference requires verified no-escape lowering");
            }
            if (node instanceof Ast.CallExpr call
                    && (containsReferenceBearingStateProjection(
                                call.callee(), activeAccess, keys)
                        || containsReferenceBearingStateProjection(
                                call.arguments(), activeAccess, keys))) {
                // A guest/interop callee may retain its receiver or arguments,
                // even if the callback's eventual result is a detached int.
                // Only future no-escape/effect-proven calls may take live refs.
                throw new IllegalArgumentException(
                        "actor state-backed reference call requires verified no-escape lowering");
            }
            // Destructuring and for-of bind new locals just as a plain
            // BindingStmt does. They cannot smuggle a live actor-owned
            // container/element reference into ordinary lexical variables.
            if (node instanceof Ast.DestructureStmt destructure
                    && containsReferenceBearingStateProjection(
                            destructure.initializer(), activeAccess, keys)) {
                throw new IllegalArgumentException(
                        "actor state-backed reference destructure requires verified snapshot lowering");
            }
            if (node instanceof Ast.ForOfStmt loop
                    && containsReferenceBearingStateProjection(
                            loop.iterable(), activeAccess, keys)) {
                throw new IllegalArgumentException(
                        "actor state-backed reference iteration requires verified snapshot lowering");
            }
            if (node instanceof Ast.ForOfDestructureStmt loop
                    && containsReferenceBearingStateProjection(
                            loop.iterable(), activeAccess, keys)) {
                throw new IllegalArgumentException(
                        "actor state-backed reference iteration requires verified snapshot lowering");
            }
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
            if (!keys.containsKey(member.member())) {
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
        if (node instanceof Ast.RuntimeCallExpr op && activeAccess != null
                && (op.operation().equals("borrow") || op.operation().equals("borrow_mut")
                    || op.operation().equals("borrowMut") || op.operation().equals("take")
                    || op.operation().equals("share"))
                && referencesAccess(op.arguments(), activeAccess)) {
            // A borrowed state projection cannot be moved out of the map or
            // published as Shared<T>. A primitive copy is permitted, but
            // ownership-changing operations need a proven snapshot lowering.
            throw new IllegalArgumentException(
                    "actor state-backed borrow/take/share cannot escape its lexical loan");
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

    /**
     * Only a closed tree of new inline literal containers may initialize a
     * reference-valued state key. There must be no external source, callback,
     * constructor, or runtime intrinsic that can inject a live alias. This
     * source check alone does not prove runtime actor-local allocation.
     */
    private static boolean isInlineActorStateDefault(Ast.Expr value) {
        if (value instanceof Ast.LiteralExpr literal) {
            Object v = literal.value();
            return v == null || v instanceof String || v instanceof Boolean
                    || v instanceof Byte || v instanceof Short
                    || v instanceof Integer || v instanceof Long
                    || v instanceof Float || v instanceof Double
                    || v instanceof java.math.BigInteger
                    || v instanceof java.math.BigDecimal
                    || v instanceof Ast.Imaginary;
        }
        if (value instanceof Ast.ListExpr list) {
            return list.elements().stream().allMatch(ActorStateContract::isInlineActorStateDefault);
        }
        if (value instanceof Ast.TupleExpr tuple) {
            return tuple.elements().stream().allMatch(ActorStateContract::isInlineActorStateDefault);
        }
        if (value instanceof Ast.ObjectExpr object) {
            return object.fields().stream().allMatch(
                    field -> isInlineActorStateDefault(field.value()));
        }
        if (value instanceof Ast.UnaryExpr unary
                && (unary.operator().equals("+") || unary.operator().equals("-"))) {
            return isInlineActorStateDefault(unary.operand());
        }
        return false;
    }

    /** A direct sibling state field is already actor-owned; an arbitrary
     * local, call result, constructor, or expression is not proven owned. */
    private static boolean isDirectActorOwnedStateValue(
            Object value, String access, Map<String, Ast.TypeRef> keys) {
        if (!(value instanceof Ast.MemberExpr member)
                || !(member.receiver() instanceof Ast.NameExpr receiver)
                || !receiver.name().equals(access)) return false;
        Ast.TypeRef sourceType = keys.get(member.member());
        return sourceType != null && !detachedScalarStateType(sourceType);
    }

    /** Only the actor's own direct state key is an authorized assignment sink. */
    private static boolean isDirectStateFieldTarget(Object target, String access) {
        return target instanceof Ast.MemberExpr member
                && member.receiver() instanceof Ast.NameExpr receiver
                && receiver.name().equals(access);
    }

    /**
     * Conservative source provenance check: any reference-bearing state
     * projection copied into an ordinary binding could escape or mutate the
     * actor-owned heap under a read grant. Complex containers count, including
     * fields nested in tuple/list/record literals. This does not substitute
     * for future typed SSA ownership/alias analysis.
     */
    private static boolean containsReferenceBearingStateProjection(
            Object node, String access, Map<String, Ast.TypeRef> keys) {
        if (node == null) return false;
        if (node instanceof Ast.MemberExpr member
                && member.receiver() instanceof Ast.NameExpr receiver
                && receiver.name().equals(access)) {
            Ast.TypeRef fieldType = keys.get(member.member());
            return fieldType == null || !detachedScalarStateType(fieldType);
        }
        if (node instanceof Ast.LambdaExpr lambda
                && lambda.parameters().stream().anyMatch(p -> p.name().equals(access))) {
            return false;
        }
        if (node instanceof Iterable<?> items) {
            for (Object item : items) {
                if (containsReferenceBearingStateProjection(item, access, keys)) return true;
            }
            return false;
        }
        if (node instanceof java.lang.Record record
                && node.getClass().getName().startsWith("dev.oreslang.ast.")) {
            for (RecordComponent component : record.getClass().getRecordComponents()) {
                try {
                    if (containsReferenceBearingStateProjection(
                            component.getAccessor().invoke(record), access, keys)) return true;
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("failed to verify actor state alias origin", failure);
                }
            }
        }
        return false;
    }

    private static boolean detachedScalarStateType(Ast.TypeRef type) {
        if (type == null || !type.arguments().isEmpty() || type.isBorrow()) return false;
        return switch (type.name()) {
            case "int", "uint", "float", "decimal", "complex",
                 "bool", "boolean", "string", "String" -> true;
            default -> false;
        };
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
