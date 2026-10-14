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
                    // A default initializer runs outside a proved actor-owned
                    // loan. It cannot import an arbitrary heap reference into
                    // the actor's sealed storage. Reference defaults need an
                    // explicit verified fresh-allocation/move lowering.
                    if (!isDetachedScalar(keys.get(def.name()))) {
                        throw new IllegalArgumentException(
                                "actor state reference default requires verified "
                                + "actor-owned allocation or ownership transfer");
                    }
                    if (!isDetachedLiteralDefault(def.value())) {
                        throw new IllegalArgumentException(
                                "actor state default must be a detached scalar literal; "
                                + "calls, external bindings and evaluated allocations "
                                + "require verified actor-owned initialization lowering");
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
            // A slim-arrow callback has an implicit return expression, not
            // an Ast.ReturnStmt. Applying escape checks only to ReturnStmt
            // silently exposes s.referenceField via |s| -> s.referenceField.
            if (projectsReferenceState(lambda.expressionBody(), variable, keys)) {
                throw new IllegalArgumentException(
                        "actor state reference projection cannot escape expression-bodied "
                        + "state callback without verified detached snapshot lowering");
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
        // Nested member/index accesses and calls can mutate or materialize
        // references to an object reachable through s.key. No typed graph
        // ownership or method-effect summaries exist for these projections
        // yet. Unlike direct scalar reads/writes, all nested reference
        // operations must fail closed, including through a parenthesized
        // receiver or a call that returns a nested reference.
        if (activeAccess != null && keys != null) {
            Object receiver = null;
            if (node instanceof Ast.MemberExpr member) {
                receiver = member.receiver();
            } else if (node instanceof Ast.IndexExpr index) {
                receiver = index.receiver();
            } else if (node instanceof Ast.CallExpr call) {
                receiver = call.callee();
            }
            if (receiver != null && projectsReferenceState(receiver, activeAccess, keys)) {
                throw new IllegalArgumentException(
                        "nested actor state reference access requires verified "
                        + "ownership and method effects");
            }
        }
        // A critical WRITE grant proves exclusion, not that the right-hand
        // side belongs to this actor's heap. Assigning a host/other-actor Map,
        // class instance or arbitrary reference into state creates a mutable
        // cross-actor alias. Until a typed move/fresh-allocation proof exists,
        // reject replacement of reference-valued state fields entirely.
        if (activeAccess != null && keys != null
                && node instanceof Ast.AssignExpr assignment
                && isDirectStateMember(assignment.target(), activeAccess)) {
            Ast.MemberExpr member = (Ast.MemberExpr) assignment.target();
            if (!isDetachedScalar(keys.get(member.member()))) {
                throw new IllegalArgumentException(
                        "actor state reference field replacement requires "
                        + "verified actor-owned allocation or ownership transfer");
            }
        }
        // A direct s.key read of an immutable scalar is a detached value,
        // but a reference-valued projection aliases actor-owned heap storage.
        // Until the compiler proves detached snapshots/linear transfers in
        // both Java and LLVM, fail closed at every escape boundary.
        if (activeAccess != null && keys != null) {
            Object escaping = null;
            if (node instanceof Ast.ReturnStmt ret) {
                escaping = ret.value();
            } else if (node instanceof Ast.BindingStmt binding) {
                escaping = binding.initializer();
            } else if (node instanceof Ast.DestructureStmt binding) {
                escaping = binding.initializer();
            } else if (node instanceof Ast.AssignExpr assign
                    && !isDirectStateMember(assign.target(), activeAccess)) {
                escaping = assign.value();
            } else if (node instanceof Ast.CallExpr call) {
                escaping = call.arguments();
            } else if (node instanceof Ast.NewExpr created) {
                // A constructor may retain its input in externally reachable
                // storage even when its returned object is discarded.
                escaping = created.arguments();
            } else if (node instanceof Ast.RuntimeCallExpr runtime
                    && !Set.of("borrow", "borrow_mut", "borrowMut", "take",
                            "share", "copy").contains(runtime.operation())) {
                // Unknown runtime intrinsics do not have a trusted effect or
                // lifetime summary for references to actor-owned fields.
                escaping = runtime.arguments();
            }
            if (escaping != null && projectsReferenceState(escaping, activeAccess, keys)) {
                throw new IllegalArgumentException(
                        "actor state reference projection cannot escape its lexical loan; "
                        + "copy/snapshot lowering requires an explicit ownership proof");
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
        if (node instanceof Ast.RuntimeCallExpr op && activeAccess != null
                && (op.operation().equals("borrow") || op.operation().equals("borrow_mut")
                    || op.operation().equals("borrowMut") || op.operation().equals("take")
                    || op.operation().equals("share")
                    || op.operation().equals("copy"))
                && referencesAccess(op.arguments(), activeAccess)) {
            // State-backed borrow/move/share/copy are not detached snapshots
            // without typed ownership proof for the entire value graph.
            // Reject even rt copy until both backends prove safe lowering.
            throw new IllegalArgumentException(
                    "actor state-backed borrow/take/share/copy cannot escape its lexical loan");
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
     * Actor defaults currently enter the scalar-only sealed storage at actor
     * construction. Only context-free, immutable scalar literals have a
     * checked lowering; even a well-typed function call may allocate, observe
     * a foreign heap or run arbitrary code before actor ownership is bound.
     */
    private static boolean isDetachedLiteralDefault(Ast.Expr expression) {
        if (expression instanceof Ast.LiteralExpr literal) {
            Object value = literal.value();
            return value instanceof String || value instanceof Boolean
                    || value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long
                    || value instanceof Float || value instanceof Double
                    || value instanceof java.math.BigInteger
                    || value instanceof java.math.BigDecimal
                    || value instanceof Character;
        }
        if (expression instanceof Ast.UnaryExpr unary
                && (unary.operator().equals("-") || unary.operator().equals("+"))
                && unary.operand() instanceof Ast.LiteralExpr literal) {
            Object value = literal.value();
            return value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long
                    || value instanceof Float || value instanceof Double
                    || value instanceof java.math.BigInteger
                    || value instanceof java.math.BigDecimal;
        }
        return false;
    }

    private static boolean isDirectStateMember(Object node, String access) {
        return node instanceof Ast.MemberExpr member
                && member.receiver() instanceof Ast.NameExpr name
                && name.name().equals(access);
    }

    private static boolean isDetachedScalar(Ast.TypeRef type) {
        if (type == null || !type.arguments().isEmpty()) return false;
        // Immutable, by-value primitive representations only. A structural
        // record, class, map, array, Option or user-defined type is not
        // automatically a detached snapshot.
        return Set.of("int", "i8", "i16", "i32", "i64", "u8", "u16",
                "u32", "u64", "float", "double", "f32", "f64", "bool",
                "byte", "short", "long", "char", "string", "String")
                .contains(type.name());
    }

    private static boolean projectsReferenceState(Object node, String access,
                                                    Map<String, Ast.TypeRef> keys) {
        if (node == null) return false;
        if (node instanceof Ast.MemberExpr member && isDirectStateMember(member, access)) {
            return !isDetachedScalar(keys.get(member.member()));
        }
        if (node instanceof Ast.LambdaExpr lambda) {
            // Shadowing does not grant access to the outer loan. An ordinary
            // closure still undergoes the existing nestedClosure check.
            if (lambda.parameters().stream().anyMatch(p -> p.name().equals(access))) return false;
        }
        if (node instanceof Iterable<?> items) {
            for (Object child : items) {
                if (projectsReferenceState(child, access, keys)) return true;
            }
            return false;
        }
        if (node instanceof java.lang.Record record
                && node.getClass().getName().startsWith("dev.oreslang.ast.")) {
            for (RecordComponent component : record.getClass().getRecordComponents()) {
                try {
                    if (projectsReferenceState(component.getAccessor().invoke(record),
                            access, keys)) return true;
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException(
                            "failed to check actor state projection for escaping references", failure);
                }
            }
        }
        return false;
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
