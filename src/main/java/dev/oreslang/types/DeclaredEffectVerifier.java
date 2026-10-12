package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import java.lang.reflect.RecordComponent;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Conservative first-step effect admission for explicit `with` contracts.
 * It verifies syntactically proven operations only. Unknown calls fail closed
 * rather than manufacturing a safe effect summary. Eventually this will be
 * replaced by interprocedural typed effect inference in the shared IR.
 */
public final class DeclaredEffectVerifier {
    private DeclaredEffectVerifier() { }

    public static void verify(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.FunctionDecl function) {
                    check(function.name(), function.annotations(), function.body(), false);
                } else if (declaration instanceof Ast.ClassDecl klass) {
                    for (Ast.MethodDecl method : klass.methods()) {
                        if (klass.actorKind() != Ast.ActorKind.NONE
                                && method.isStatic() && containsStateLoan(method.body())) {
                            throw new IllegalArgumentException(
                                    "static actor method cannot acquire a sealed instance state loan");
                        }
                        check(klass.name() + "." + method.name(), method.annotations(),
                                method.body(), klass.serial() && !method.isStatic());
                    }
                }
            }
        }
    }

    private static void check(String name, List<Ast.Annotation> annotations, Object body, boolean serial) {
        Ast.Annotation with = null;
        for (Ast.Annotation annotation : annotations) {
            if (annotation.name().equals("$requires$")) {
                for (Ast.TypeRef requirement : annotation.arguments()) {
                    if (requirement.name().equals("bounded")) {
                        throw new IllegalArgumentException(
                                "requires bounded needs verified target-specific WCET for " + name);
                    }
                }
            }
            if (annotation.name().equals("$using$"))
                throw new IllegalArgumentException("legacy using effect declaration is forbidden; use with");
            if (annotation.name().equals("$with$")) {
                if (with != null) throw new IllegalArgumentException("duplicate with effect declaration");
                with = annotation;
            }
        }
        boolean hasRequirements = annotations.stream()
                .anyMatch(annotation -> annotation.name().equals("$requires$"));
        // A sealed actor-state loan cannot silently bypass a function
        // effect contract by omitting 'with'. This is checked even when
        // no 'requires' guarantee was declared.
        if (with == null && !hasRequirements) {
            if (containsStateLoan(body))
                throw new IllegalArgumentException(name
                        + " requires with state.read/write effect declaration");
            return;
        }

        FunctionEffects allowed = with == null ? null : FunctionEffects.ofNames(with.arguments().stream()
                .map(Ast.TypeRef::name).toList());
        EnumSet<FunctionEffects.Effect> inferred =
                EnumSet.noneOf(FunctionEffects.Effect.class);
        collect(body, inferred, null, name, serial);

        // A no_alloc/no_block guarantee requires absence of those operations
        // in the *transitive* effect graph. This conservative first pass rejects
        // unknown calls, avoiding accidental admission.
        for (Ast.Annotation annotation : annotations) {
            if (!annotation.name().equals("$requires$")) continue;
            for (Ast.TypeRef requirement : annotation.arguments()) {
                if (requirement.name().equals("no_alloc")
                        && inferred.contains(FunctionEffects.Effect.ALLOC)) {
                    throw new IllegalArgumentException(name + " violates requires no_alloc");
                }
                if (requirement.name().equals("no_block")
                        && inferred.contains(FunctionEffects.Effect.BLOCK)) {
                    throw new IllegalArgumentException(name + " violates requires no_block");
                }
            }
        }
        for (FunctionEffects.Effect effect : inferred) {
            if (allowed != null && !allowed.contains(effect)) {
                throw new IllegalArgumentException(name + " uses effect " + effect.sourceName()
                        + " not permitted by its with signature");
            }
        }
    }

    private static boolean containsStateLoan(Object node) {
        if (node == null) return false;
        if (node instanceof Ast.CallExpr call
                && call.callee() instanceof Ast.MemberExpr callee
                && callee.receiver() instanceof Ast.NameExpr self
                && self.name().equals("self") && callee.member().equals("handle_state"))
            return true;
        if (node instanceof Iterable<?> items) {
            for (Object item : items)
                if (containsStateLoan(item)) return true;
            return false;
        }
        if (node instanceof java.lang.Record record
                && node.getClass().getName().startsWith("dev.oreslang.ast.")) {
            for (RecordComponent component : record.getClass().getRecordComponents()) {
                try {
                    if (containsStateLoan(component.getAccessor().invoke(record))) return true;
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("cannot inspect actor state effect declaration", e);
                }
            }
        }
        return false;
    }

    private static void collect(Object node, Set<FunctionEffects.Effect> effects,
                                String stateAccess, String callableName, boolean serial) {
        if (node == null) return;
        if (node instanceof Ast.CallExpr call) {
            if (call.callee() instanceof Ast.MemberExpr member
                    && member.receiver() instanceof Ast.NameExpr self
                    && self.name().equals("self")
                    && member.member().equals("handle_state")) {
                if (call.arguments().size() != 1
                        || !(call.arguments().getFirst() instanceof Ast.LambdaExpr callback)
                        || callback.parameters().size() != 1) {
                    throw new IllegalArgumentException("invalid handle_state effect callback");
                }
                if (callback.nonLexical()) {
                    throw new IllegalArgumentException(
                            "handle_state needs a lexical state callback");
                }
                String name = callback.parameters().getFirst().name();
                EnumSet<FunctionEffects.Effect> callbackEffects =
                        EnumSet.noneOf(FunctionEffects.Effect.class);
                collect(callback.expressionBody(), callbackEffects, name, callableName, serial);
                collect(callback.blockBody(), callbackEffects, name, callableName, serial);
                // Callback mode grants authority; WITH only constrains declared effects.
                if ((callback.criticalMode() == Ast.CriticalMode.NONE
                            || callback.criticalMode() == Ast.CriticalMode.READ)
                        && callbackEffects.contains(FunctionEffects.Effect.STATE_WRITE)) {
                    throw new IllegalArgumentException(
                            "read-only handle_state cannot write state, regardless of declared with effects");
                }
                effects.addAll(callbackEffects);
                return;
            }
            if (call.callee() instanceof Ast.MemberExpr member
                    && member.receiver() instanceof Ast.NameExpr namespace
                    && namespace.name().equals("stdio")
                    && (member.member().equals("println") || member.member().equals("print"))) {
                effects.add(FunctionEffects.Effect.IO);
                // Console writes are still synchronous. An IO effect alone is
                // not proof of nonblocking execution: a full pipe, slow sink,
                // or host terminal can stall the actor carrier.
                effects.add(FunctionEffects.Effect.BLOCK);
                collect(call.arguments(), effects, stateAccess, callableName, serial);
                return;
            }
            // An unknown callee might suspend, allocate, perform I/O, or
            // mutate externally reachable memory. Require a trusted summary.
            throw new IllegalArgumentException(
                    "cannot verify with effects of call in " + callableName
                    + " without a trusted callee summary");
        }
        if (node instanceof Ast.AssignExpr assign) {
            if (assign.target() instanceof Ast.MemberExpr member && stateAccess != null
                    && member.receiver() instanceof Ast.NameExpr name
                    && name.name().equals(stateAccess)) {
                // Assignment to a state field is a WRITE, not an implicit
                // READ. A read-modify-write operation contributes STATE_READ
                // separately when its right-hand side observes the field.
                effects.add(FunctionEffects.Effect.STATE_WRITE);
                collect(assign.value(), effects, stateAccess, callableName, serial);
                return;
            }
            collect(assign.target(), effects, stateAccess, callableName, serial);
            collect(assign.value(), effects, stateAccess, callableName, serial);
            return;
        }
        if (node instanceof Ast.MemberExpr member && stateAccess != null
                && member.receiver() instanceof Ast.NameExpr name
                && name.name().equals(stateAccess)) {
            effects.add(FunctionEffects.Effect.STATE_READ);
            return;
        }
        if (node instanceof Ast.AwaitExpr await) {
            effects.add(FunctionEffects.Effect.SUSPEND);
            collect(await.expression(), effects, stateAccess, callableName, serial);
            return;
        }
        // Source loops can lower into implicit cooperation at the backedge,
        // even without a spelled-out 'rt cooperate'. Their suspend effect
        // belongs in the enclosing 'with' upper bound. The sealed inline
        // state callback is a synchronous, non-yielding scope and must not
        // acquire an implicit permission to suspend with a live loan.
        if (stateAccess == null
                && (node instanceof Ast.ForStmt
                    || node instanceof Ast.ForOfStmt
                    || node instanceof Ast.ForOfDestructureStmt
                    || node instanceof Ast.LoopStmt)) {
            effects.add(FunctionEffects.Effect.SUSPEND);
        }
        if (node instanceof Ast.NewExpr || node instanceof Ast.ListExpr
                || node instanceof Ast.TupleExpr || node instanceof Ast.ObjectExpr) {
            effects.add(FunctionEffects.Effect.ALLOC);
        }
        if (node instanceof Ast.DeferStmt defer) {
            // Unlike an ordinary closure value, a deferred lambda WILL run
            // during this function's cleanup (including exceptional exits).
            // Skipping its body unsoundly admits no_block/no_alloc or a narrow
            // using clause despite a delayed blocking/allocating operation.
            // The deferred callable itself still counts as allocation until
            // lowering proves an allocation-free cleanup representation.
            if (defer.expression() instanceof Ast.LambdaExpr lambda) {
                effects.add(FunctionEffects.Effect.ALLOC);
                collect(lambda.expressionBody(), effects, stateAccess, callableName, serial);
                collect(lambda.blockBody(), effects, stateAccess, callableName, serial);
            } else {
                collect(defer.expression(), effects, stateAccess, callableName, serial);
            }
            return;
        }
        if (node instanceof Ast.LambdaExpr) {
            // Ordinary closure construction does not execute its body.
            // A deferred lambda is instead handled by the DeferStmt branch.
            effects.add(FunctionEffects.Effect.ALLOC);
            return;
        }
        if (node instanceof Ast.RuntimeCallExpr operation) {
            if (operation.operation().equals("cooperate")) effects.add(FunctionEffects.Effect.SUSPEND);
            else throw new IllegalArgumentException(
                    "runtime intrinsic in " + callableName + " lacks a trusted effect summary");
        }
        // SUSPEND means a logical continuation boundary, not necessarily a
        // thread-blocking operation. no_block must separately reject every
        // potentially waiting channel operation. A default arm proves that a
        // static select cannot wait; nonblocking registrations do not block
        // the caller. Dynamic plans remain conservative if blocking.
        if (node instanceof Ast.SelectStmt selected) {
            effects.add(FunctionEffects.Effect.SUSPEND);
            if (selected.mode() == Ast.WaitMode.BLOCKING
                    && selected.arms().stream().noneMatch(arm ->
                            arm.operation() == Ast.ChannelOperation.DEFAULT)) {
                effects.add(FunctionEffects.Effect.BLOCK);
            }
        } else if (node instanceof Ast.DynamicSelectExpr selected) {
            effects.add(FunctionEffects.Effect.SUSPEND);
            if (selected.mode() == Ast.WaitMode.BLOCKING) {
                effects.add(FunctionEffects.Effect.BLOCK);
            }
        } else if (node instanceof Ast.ChannelOpExpr channel) {
            effects.add(FunctionEffects.Effect.SUSPEND);
            if (channel.mode() == Ast.WaitMode.BLOCKING) {
                effects.add(FunctionEffects.Effect.BLOCK);
            }
        }
        if (node instanceof Iterable<?> children) {
            for (Object child : children) collect(child, effects, stateAccess, callableName, serial);
            return;
        }
        if (node instanceof java.lang.Record record
                && node.getClass().getName().startsWith("dev.oreslang.ast.")) {
            for (RecordComponent component : record.getClass().getRecordComponents()) {
                try {
                    collect(component.getAccessor().invoke(record),
                            effects, stateAccess, callableName, serial);
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("effect analyzer could not inspect AST", failure);
                }
            }
        }
    }
}
