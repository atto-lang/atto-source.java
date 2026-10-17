package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Definite initialization of ordinary-class blank val fields.
 *
 * <p>The semantic state is an immutable per-field assignment-count interval
 * supplied by {@link ConstructorFlowState}. A direct write is admitted only
 * from [0,0]; successful constructor completion requires [1,1] for every
 * blank val field. Branch joins take min(min) and max(max), which is the
 * interval form of MUST-intersection/MAY-union.</p>
 *
 * <p>if/else, switch and safely exhaustive match statements participate in
 * dataflow. Repeating loops, try/catch/finally, select and defer remain
 * fail-closed until their backedge/exception/cleanup effects are represented
 * explicitly. Suspension is independently prohibited while constructing:
 * await, blocking channel waits, blocking dynamic select and async iteration
 * cannot carry a partial object across a scheduler boundary.</p>
 */
public final class ConstructorValInitialization {
    private ConstructorValInitialization() { }

    public static void check(Ast.ClassDecl klass) {
        ConstructorDelegation.validate(klass);
        if (klass.actorKind() != Ast.ActorKind.NONE || klass.constructors().isEmpty()) return;

        LinkedHashSet<String> required = new LinkedHashSet<>();
        for (Ast.FieldDecl field : klass.fields()) {
            if (field.bindingKind() == Ast.BindingKind.VAL && field.initializer() == null) {
                required.add(field.name());
            }
        }

        for (Ast.ConstructorDecl constructor : klass.constructors()) {
            checkConstructor(klass, constructor, required);
        }
    }

    /**
     * Delegating frames share the same initializing object. The terminal
     * constructor proves the blank-val obligations; on successful return to
     * a delegating frame its tail begins with all required fields initialized.
     * ConstructorDelegation.validate has already rejected cycles.
     */
    private static ConstructorFlowState checkConstructor(
            Ast.ClassDecl klass,
            Ast.ConstructorDecl constructor,
            Set<String> required) {
        Ast.ConstructorDelegateStmt delegation = ConstructorDelegation.first(constructor);
        ConstructorFlowState entry = ConstructorFlowState.blank(required);
        if (delegation != null) {
            Ast.ConstructorDecl target =
                    klass.constructorForArity(delegation.effectiveArity());
            checkConstructor(klass, target, required);
            // Successful target completion (including an explicit bare return)
            // established exactly-once initialization. An abnormal completion
            // cannot reach this delegator's tail.
            for (String field : required) entry = entry.write(field);
        }
        ConstructorFlowState normalExit = block(
                ConstructorDelegation.tail(constructor), entry, required, klass.name());
        if (normalExit != null) requireComplete(normalExit, required, klass.name());
        return normalExit;
    }

    /** A null state has no normal continuation (every path returned). */
    private static ConstructorFlowState block(
            List<Ast.Stmt> stmts,
            ConstructorFlowState state,
            Set<String> required,
            String klass) {
        for (Ast.Stmt stmt : stmts) {
            if (state == null) return null;
            state = statement(stmt, state, required, klass);
        }
        return state;
    }

    private static ConstructorFlowState statement(
            Ast.Stmt stmt,
            ConstructorFlowState state,
            Set<String> required,
            String klass) {
        if (stmt instanceof Ast.ExprStmt expression) {
            if (expression.expression() instanceof Ast.AssignExpr assignment
                    && blankValTarget(assignment.target(), required) != null) {
                String field = blankValTarget(assignment.target(), required);
                // RHS executes before the slot is written: reject premature reads,
                // hidden writes and suspension before committing this assignment.
                scan(assignment.value(), state, required, klass);
                if (state.maybeInitialized(field)) {
                    throw error(klass, field, "is immutable; may be initialized more than once");
                }
                return state.write(field);
            }
            scan(expression.expression(), state, required, klass);
            return state;
        }
        if (stmt instanceof Ast.BindingStmt binding) {
            scan(binding.initializer(), state, required, klass);
            return state;
        }
        if (stmt instanceof Ast.DestructureStmt destructure) {
            scan(destructure.initializer(), state, required, klass);
            return state;
        }
        if (stmt instanceof Ast.BlockStmt nested) {
            return block(nested.body(), state, required, klass);
        }
        if (stmt instanceof Ast.ReturnStmt returned) {
            scan(returned.value(), state, required, klass);
            requireComplete(state, required, klass);
            return null;
        }
        if (stmt instanceof Ast.IfStmt conditional) {
            List<ConstructorFlowState> normalExits = new ArrayList<>();
            for (Ast.IfBranch branch : conditional.branches()) {
                scan(branch.condition(), state, required, klass);
                ConstructorFlowState exit = block(branch.body(), state, required, klass);
                if (exit != null) normalExits.add(exit);
            }
            ConstructorFlowState otherwise = block(
                    conditional.elseBody(), state, required, klass);
            if (otherwise != null) normalExits.add(otherwise);
            return ConstructorFlowState.join(normalExits);
        }
        if (stmt instanceof Ast.SwitchStmt switched) {
            scan(switched.subject(), state, required, klass);
            List<ConstructorFlowState> normalExits = new ArrayList<>();
            for (Ast.SwitchCase arm : switched.cases()) {
                for (Ast.Expr constant : arm.constants()) {
                    scan(constant, state, required, klass);
                }
                ConstructorFlowState exit = block(arm.body(), state, required, klass);
                if (exit != null) normalExits.add(exit);
            }
            // Empty default preserves the unmatched entry path.
            ConstructorFlowState defaultExit = block(
                    switched.defaultBody(), state, required, klass);
            if (defaultExit != null) normalExits.add(defaultExit);
            return ConstructorFlowState.join(normalExits);
        }
        if (stmt instanceof Ast.MatchStmt matched) {
            scan(matched.subject(), state, required, klass);
            List<ConstructorFlowState> normalExits = new ArrayList<>();
            boolean catchAll = false;
            for (Ast.MatchArm arm : matched.arms()) {
                if (arm.guard() != null) scan(arm.guard(), state, required, klass);
                ConstructorFlowState exit = block(arm.body(), state, required, klass);
                if (exit != null) normalExits.add(exit);
                if (arm.guard() == null && isCatchAll(arm.pattern())) catchAll = true;
            }
            // TypeChecker has richer finite-domain exhaustiveness analysis, but
            // this standalone pass intentionally proves only an explicit catch-all.
            if (!catchAll) normalExits.add(state);
            return ConstructorFlowState.join(normalExits);
        }
        if (stmt instanceof Ast.ForOfStmt loop && loop.asyncIteration()) {
            throw constructionSuspension(klass, "async iteration");
        }
        if (stmt instanceof Ast.ForOfDestructureStmt loop && loop.asyncIteration()) {
            throw constructionSuspension(klass, "async iteration");
        }
        if (stmt instanceof Ast.SelectStmt select
                && select.mode() == Ast.WaitMode.BLOCKING) {
            throw constructionSuspension(klass, "blocking select");
        }
        if (stmt instanceof Ast.DeferStmt defer) {
            // Defer executes before the constructor can complete, so its
            // cleanup remains inside the construction region even when the
            // deferred expression is represented by a lambda.
            requireComplete(state, required, klass);
            scanDeferred(defer.expression(), state, required, klass);
            return state;
        }

        // Until the frontend has a sound CFG for loop backedges, exceptional
        // completion, deferred cleanup and nonblocking detached arms, all
        // required slots must already be complete before entering them.
        requireComplete(state, required, klass);
        scan(stmt, state, required, klass);
        return state;
    }

    private static boolean isCatchAll(Ast.Pattern pattern) {
        return pattern instanceof Ast.WildcardPattern
                || pattern instanceof Ast.BindingPattern;
    }

    private static void requireComplete(
            ConstructorFlowState state,
            Set<String> required,
            String klass) {
        for (String field : required) {
            if (!state.count(field).exactlyOnce()) {
                throw new IllegalArgumentException("constructor for " + klass
                        + " did not initialize field '" + field + "' exactly once on every normal path"
                        + " (state " + state.count(field) + ")");
            }
        }
    }

    private static String blankValTarget(Ast.Expr expr, Set<String> required) {
        if (expr instanceof Ast.MemberExpr member
                && member.receiver() instanceof Ast.NameExpr receiver
                && receiver.name().equals("self")
                && required.contains(member.member())) {
            return member.member();
        }
        return null;
    }

    /**
     * Check reads and reject hidden writes through expression-valued control
     * flow, nested statements, or callbacks. The `immediate` bit distinguishes
     * code executing as part of this constructor activation from a callable
     * body that is merely being created. Deferred callable bodies are still
     * inspected for construction-sensitive field access, but an await inside
     * such a body is not itself a suspension of the constructor.
     */
    private static void scan(
            Object node,
            ConstructorFlowState state,
            Set<String> required,
            String klass) {
        scan(node, state, required, klass, true);
    }

    private static void scan(
            Object node,
            ConstructorFlowState state,
            Set<String> required,
            String klass,
            boolean immediate) {
        if (node == null) return;

        if (node instanceof Ast.LambdaExpr lambda) {
            scanLambdaBody(lambda, state, required, klass, false);
            return;
        }
        if (node instanceof Ast.AwaitExpr awaited) {
            if (immediate) throw constructionSuspension(klass, "await");
            scan(awaited.expression(), state, required, klass, false);
            return;
        }
        if (node instanceof Ast.ChannelOpExpr channel) {
            if (immediate && channel.mode() == Ast.WaitMode.BLOCKING) {
                throw constructionSuspension(klass, "blocking channel operation");
            }
            scan(channel.channel(), state, required, klass, immediate);
            scan(channel.value(), state, required, klass, immediate);
            // Nonblocking write callbacks are registered for later execution;
            // their bodies are not part of this constructor activation.
            scan(channel.callbackBody(), state, required, klass, false);
            return;
        }
        if (node instanceof Ast.DynamicSelectExpr select) {
            if (immediate && select.mode() == Ast.WaitMode.BLOCKING) {
                throw constructionSuspension(klass, "blocking dynamic select");
            }
            scan(select.cases(), state, required, klass, immediate);
            return;
        }
        if (node instanceof Ast.ForOfStmt loop && immediate && loop.asyncIteration()) {
            throw constructionSuspension(klass, "async iteration");
        }
        if (node instanceof Ast.ForOfDestructureStmt loop && immediate && loop.asyncIteration()) {
            throw constructionSuspension(klass, "async iteration");
        }
        if (node instanceof Ast.SelectStmt select
                && immediate
                && select.mode() == Ast.WaitMode.BLOCKING) {
            throw constructionSuspension(klass, "blocking select");
        }
        if (node instanceof Ast.AssignExpr assignment) {
            String field = blankValTarget(assignment.target(), required);
            if (field != null) {
                throw error(klass, field,
                        "must be assigned directly in a constructor statement, not through a nested expression or callback");
            }
        }
        if (node instanceof Ast.MemberExpr member
                && member.receiver() instanceof Ast.NameExpr receiver
                && receiver.name().equals("self")
                && required.contains(member.member())
                && !state.definitelyInitialized(member.member())) {
            throw error(klass, member.member(), "is read before constructor initialization");
        }
        if (node instanceof Iterable<?> values) {
            for (Object value : values) scan(value, state, required, klass, immediate);
            return;
        }
        if (!(node instanceof Record record)) {
            if (node instanceof Ast.Expr || node instanceof Ast.Stmt) {
                throw new IllegalArgumentException("unsupported constructor AST node: "
                        + node.getClass().getName());
            }
            return;
        }
        if (node.getClass().getEnclosingClass() != Ast.class) return;
        for (RecordComponent component : record.getClass().getRecordComponents()) {
            try {
                scan(component.getAccessor().invoke(record), state, required, klass, immediate);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                        "unable to inspect constructor syntax "
                                + record.getClass().getSimpleName(), e);
            }
        }
    }

    private static void scanLambdaBody(
            Ast.LambdaExpr lambda,
            ConstructorFlowState state,
            Set<String> required,
            String klass,
            boolean immediate) {
        if (lambda.expressionBody() != null) {
            scan(lambda.expressionBody(), state, required, klass, immediate);
        } else {
            scan(lambda.blockBody(), state, required, klass, immediate);
        }
    }

    private static void scanDeferred(
            Ast.Expr expression,
            ConstructorFlowState state,
            Set<String> required,
            String klass) {
        if (expression instanceof Ast.LambdaExpr lambda) {
            scanLambdaBody(lambda, state, required, klass, true);
        } else {
            scan(expression, state, required, klass, true);
        }
    }

    private static IllegalArgumentException error(String klass, String field, String message) {
        return new IllegalArgumentException(
                "constructor for '" + klass + "': val field '" + field + "' " + message);
    }

    private static IllegalArgumentException constructionSuspension(String klass, String operation) {
        return new IllegalArgumentException(
                "constructor for '" + klass + "' cannot suspend through " + operation
                        + "; construction-scoped self must not cross a scheduler boundary");
    }
}
