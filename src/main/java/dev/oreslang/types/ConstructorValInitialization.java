package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Definite initialization of ordinary-class instance fields.
 *
 * <p>Tracks MUST and MAY initialized fields on normal constructor paths.
 * A blank immutable val slot can be written only if it is not MAY-initialized;
 * mutable let slots may be reassigned after their first initialization.
 * Construction completes only when every declaration-uninitialized field is MUST-initialized. Each explicit
 * constructor is checked independently. Conditions do not publish a partially
 * constructed receiver, and constructor writes are direct self.field = value
 * statements, not arbitrary expression side effects.</p>
 *
 * <p>Loops, try/catch/finally, select, defer and match are conservatively
 * allowed only once all declaration-uninitialized fields are initialized. This avoids
 * claiming exactly-once proofs across exceptional, suspended or iterative
 * edges that the current frontend does not model yet.</p>
 */
public final class ConstructorValInitialization {
    private ConstructorValInitialization() { }

    public static void check(Ast.ClassDecl klass) {
        if (klass.actorKind() != Ast.ActorKind.NONE || klass.constructors().isEmpty()) return;

        LinkedHashSet<String> required = new LinkedHashSet<>();
        LinkedHashSet<String> fixed = new LinkedHashSet<>();
        for (Ast.FieldDecl field : klass.fields()) {
            if (field.initializer() == null) {
                required.add(field.name());
                if (field.bindingKind() != Ast.BindingKind.LET) fixed.add(field.name());
            }
        }
        // Even classes with no blank val slots must not publish constructing
        // self. Ownership checks must not be the only protection: run the
        // constructor expression walker independently for every constructor.
        for (Ast.ConstructorDecl constructor : klass.constructors()) {
            State normalExit = block(constructor.body(), new State(required, fixed), required, klass.name());
            if (normalExit != null) requireComplete(normalExit, required, klass.name());
        }
    }


    /** Mutable local cursor over immutable backend-neutral assignment intervals. */
    private static final class State {
        ConstructorFlowState flow;
        final Set<String> fixed;
        State(Set<String> required, Set<String> fixed) {
            this(ConstructorFlowState.blank(required), Set.copyOf(fixed));
        }
        State(ConstructorFlowState flow, Set<String> fixed) {
            this.flow = flow;
            this.fixed = fixed;
        }
        State copy() { return new State(flow, fixed); }
        boolean initialized(String field) { return flow.definitelyInitialized(field); }
        boolean maybeInitialized(String field) { return flow.maybeInitialized(field); }
        void write(String field) { flow = flow.write(field); }
    }

    /** A null state has no normal continuation (every path returned). */
    private static State block(List<Ast.Stmt> stmts, State state, Set<String> required, String klass) {
        for (Ast.Stmt stmt : stmts) {
            if (state == null) return null;
            state = statement(stmt, state, required, klass);
        }
        return state;
    }

    private static State statement(Ast.Stmt stmt, State state, Set<String> required, String klass) {
        if (stmt instanceof Ast.ExprStmt expression) {
            if (expression.expression() instanceof Ast.AssignExpr assignment
                    && uninitializedFieldTarget(assignment.target(), required) != null) {
                String field = uninitializedFieldTarget(assignment.target(), required);
                // RHS executes before the slot is written: reject reads of a missing slot.
                scan(assignment.value(), state, required, klass);
                if (state.fixed.contains(field) && state.maybeInitialized(field)) {
                    throw error(klass, field, "is immutable; may be initialized more than once");
                }
                state.write(field);
            } else {
                scan(expression.expression(), state, required, klass);
            }
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
            List<State> normalExits = new ArrayList<>();
            for (Ast.IfBranch branch : conditional.branches()) {
                // Branch conditions may read initialized slots, but not write them.
                scan(branch.condition(), state, required, klass);
                State exit = block(branch.body(), state.copy(), required, klass);
                if (exit != null) normalExits.add(exit);
            }
            State otherwise = block(conditional.elseBody(), state.copy(), required, klass);
            if (otherwise != null) normalExits.add(otherwise);
            return merge(normalExits);
        }
        if (stmt instanceof Ast.SwitchStmt switched) {
            scan(switched.subject(), state, required, klass);
            List<State> normalExits = new ArrayList<>();
            for (Ast.SwitchCase arm : switched.cases()) {
                for (Ast.Expr constant : arm.constants()) scan(constant, state, required, klass);
                State exit = block(arm.body(), state.copy(), required, klass);
                if (exit != null) normalExits.add(exit);
            }
            // An empty default may mean no case matches; include the entry path.
            State defaultExit = block(switched.defaultBody(), state.copy(), required, klass);
            if (defaultExit != null) normalExits.add(defaultExit);
            return merge(normalExits);
        }
        if (stmt instanceof Ast.MatchStmt matched) {
            scan(matched.subject(), state, required, klass);
            List<State> normalExits = new ArrayList<>();
            boolean catchAll = false;
            for (Ast.MatchArm arm : matched.arms()) {
                scan(arm.guard(), state, required, klass);
                State exit = block(arm.body(), state.copy(), required, klass);
                if (exit != null) normalExits.add(exit);
                if (arm.guard() == null && (arm.pattern() instanceof Ast.WildcardPattern
                        || arm.pattern() instanceof Ast.BindingPattern)) catchAll = true;
            }
            if (!catchAll) normalExits.add(state);
            return merge(normalExits);
        }
        if (stmt instanceof Ast.ForOfStmt loop && loop.asyncIteration()) {
            throw constructionSuspension(klass, "async iteration");
        }
        if (stmt instanceof Ast.ForOfDestructureStmt loop && loop.asyncIteration()) {
            throw constructionSuspension(klass, "async iteration");
        }
        if (stmt instanceof Ast.SelectStmt select && select.mode() == Ast.WaitMode.BLOCKING) {
            throw constructionSuspension(klass, "blocking select");
        }
        if (stmt instanceof Ast.DeferStmt defer) {
            requireComplete(state, required, klass);
            if (defer.expression() instanceof Ast.LambdaExpr lambda) {
                scan(lambda.expressionBody(), state, required, klass, true);
                scan(lambda.blockBody(), state, required, klass, true);
            } else {
                scan(defer.expression(), state, required, klass, true);
            }
            return state;
        }

        // Until the frontend has a sound CFG for loop backedges, exceptional
        // completion, deferred cleanup and detached arms, initialize before
        // entering those constructs. Scan their entire AST for illicit writes.
        requireComplete(state, required, klass);
        scan(stmt, state, required, klass);
        return state;
    }

    private static State merge(List<State> states) {
        if (states.isEmpty()) return null;
        State joined = states.getFirst().copy();
        for (int i = 1; i < states.size(); i++) {
            joined.flow = joined.flow.join(states.get(i).flow);
        }
        return joined;
    }

    private static void requireComplete(State state, Set<String> required, String klass) {
        for (String field : required) {
            if (!state.initialized(field)) {
                throw new IllegalArgumentException("constructor for " + klass
                        + " did not initialize field '" + field + "' on every normal path");
            }
        }
    }

    private static String uninitializedFieldTarget(Ast.Expr expr, Set<String> required) {
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
     * flow, nested statements, or callbacks. Explicit AST visiting is AOT-safe;
     * newly introduced AST forms must be handled instead of silently skipped.
     */
    private static void scan(Object node, State state, Set<String> required, String klass) {
        if (node == null) return;
        if (node instanceof Ast.AssignExpr assignment) {
            String field = uninitializedFieldTarget(assignment.target(), required);
            if (field != null
                    && (state.fixed.contains(field) || !state.initialized(field))) {
                throw error(klass, field,
                        "must be assigned directly in a constructor statement, not through a nested expression or callback");
            }
        }
        if (node instanceof Ast.MemberExpr member
                && member.receiver() instanceof Ast.NameExpr receiver
                && receiver.name().equals("self")) {
            // A direct field projection is not publication of the receiver itself.
            if (required.contains(member.member()) && !state.initialized(member.member())) {
                throw error(klass, member.member(), "is read before constructor initialization");
            }
            return;
        }
        if (node instanceof Ast.NameExpr name && name.name().equals("self")) {
            // Only direct self.field projections / writes may use construction self.
            // In particular, self hidden inside a tuple, list or struct must not
            // bypass the no-publication rule.
            throw new IllegalArgumentException(
                    "constructor for '" + klass + "': partially initialized self cannot escape");
        }
        if (node instanceof Iterable<?> values) {
            for (Object value : values) scan(value, state, required, klass);
            return;
        }
        if (node instanceof Ast.LiteralExpr || node instanceof Ast.NameExpr
                || node instanceof Ast.BreakStmt || node instanceof Ast.ContinueStmt) return;
        if (node instanceof Ast.BinaryExpr item) {
            scan(item.left(), state, required, klass);
            scan(item.right(), state, required, klass);
            return;
        }
        if (node instanceof Ast.UnaryExpr item) {
            scan(item.operand(), state, required, klass);
            return;
        }
        if (node instanceof Ast.ConditionalExpr item) {
            scan(item.condition(), state, required, klass);
            scan(item.whenTrue(), state, required, klass);
            scan(item.whenFalse(), state, required, klass);
            return;
        }
        if (node instanceof Ast.MatchExpr item) {
            scan(item.subject(), state, required, klass);
            scan(item.arms(), state, required, klass);
            return;
        }
        if (node instanceof Ast.MatchValueArm item) {
            scan(item.guard(), state, required, klass);
            scan(item.value(), state, required, klass);
            return;
        }
        if (node instanceof Ast.TypeTestExpr item) {
            scan(item.value(), state, required, klass);
            return;
        }
        if (node instanceof Ast.PatternTestExpr item) {
            scan(item.value(), state, required, klass);
            return;
        }
        if (node instanceof Ast.CastExpr item) {
            scan(item.value(), state, required, klass);
            return;
        }
        if (node instanceof Ast.SpreadExpr item) {
            scan(item.expression(), state, required, klass);
            return;
        }
        if (node instanceof Ast.CallExpr item) {
            scan(item.callee(), state, required, klass);
            scan(item.arguments(), state, required, klass);
            return;
        }
        if (node instanceof Ast.RuntimeCallExpr item) {
            scan(item.arguments(), state, required, klass);
            return;
        }
        if (node instanceof Ast.IndexExpr item) {
            scan(item.receiver(), state, required, klass);
            scan(item.index(), state, required, klass);
            return;
        }
        if (node instanceof Ast.NewExpr item) {
            scan(item.arguments(), state, required, klass);
            return;
        }
        if (node instanceof Ast.AwaitExpr item) {
            scan(item.expression(), state, required, klass);
            return;
        }
        if (node instanceof Ast.TrapExpr item) {
            scan(item.expression(), state, required, klass);
            return;
        }
        if (node instanceof Ast.ChannelOpExpr item) {
            scan(item.channel(), state, required, klass);
            scan(item.value(), state, required, klass);
            scan(item.callbackBody(), state, required, klass);
            return;
        }
        if (node instanceof Ast.DynamicSelectExpr item) {
            scan(item.cases(), state, required, klass);
            return;
        }
        if (node instanceof Ast.ListExpr item) {
            scan(item.elements(), state, required, klass);
            return;
        }
        if (node instanceof Ast.TupleExpr item) {
            scan(item.elements(), state, required, klass);
            return;
        }
        if (node instanceof Ast.ObjectExpr item) {
            scan(item.fields(), state, required, klass);
            return;
        }
        if (node instanceof Ast.ObjectField item) {
            scan(item.value(), state, required, klass);
            return;
        }
        if (node instanceof Ast.LambdaExpr item) {
            scan(item.expressionBody(), state, required, klass);
            scan(item.blockBody(), state, required, klass);
            return;
        }
        if (node instanceof Ast.BindingStmt item) {
            scan(item.initializer(), state, required, klass);
            return;
        }
        if (node instanceof Ast.DestructureStmt item) {
            scan(item.initializer(), state, required, klass);
            return;
        }
        if (node instanceof Ast.ExprStmt item) {
            scan(item.expression(), state, required, klass);
            return;
        }
        if (node instanceof Ast.ReturnStmt item) {
            scan(item.value(), state, required, klass);
            return;
        }
        if (node instanceof Ast.YieldStmt item) {
            scan(item.value(), state, required, klass);
            return;
        }
        if (node instanceof Ast.DeferStmt item) {
            scan(item.expression(), state, required, klass);
            return;
        }
        if (node instanceof Ast.BlockStmt item) {
            scan(item.body(), state, required, klass);
            return;
        }
        if (node instanceof Ast.IfStmt item) {
            scan(item.branches(), state, required, klass);
            scan(item.elseBody(), state, required, klass);
            return;
        }
        if (node instanceof Ast.IfBranch item) {
            scan(item.condition(), state, required, klass);
            scan(item.body(), state, required, klass);
            return;
        }
        if (node instanceof Ast.MatchStmt item) {
            scan(item.subject(), state, required, klass);
            scan(item.arms(), state, required, klass);
            return;
        }
        if (node instanceof Ast.MatchArm item) {
            scan(item.guard(), state, required, klass);
            scan(item.body(), state, required, klass);
            return;
        }
        if (node instanceof Ast.SwitchStmt item) {
            scan(item.subject(), state, required, klass);
            scan(item.cases(), state, required, klass);
            scan(item.defaultBody(), state, required, klass);
            return;
        }
        if (node instanceof Ast.SwitchCase item) {
            scan(item.constants(), state, required, klass);
            scan(item.body(), state, required, klass);
            return;
        }
        if (node instanceof Ast.TryStmt item) {
            scan(item.body(), state, required, klass);
            scan(item.catchBody(), state, required, klass);
            scan(item.finallyBody(), state, required, klass);
            return;
        }
        if (node instanceof Ast.ForOfStmt item) {
            scan(item.iterable(), state, required, klass);
            scan(item.body(), state, required, klass);
            return;
        }
        if (node instanceof Ast.ForOfDestructureStmt item) {
            scan(item.iterable(), state, required, klass);
            scan(item.body(), state, required, klass);
            return;
        }
        if (node instanceof Ast.ForStmt item) {
            scan(item.initializer(), state, required, klass);
            scan(item.condition(), state, required, klass);
            scan(item.update(), state, required, klass);
            scan(item.body(), state, required, klass);
            return;
        }
        if (node instanceof Ast.LoopStmt item) {
            scan(item.body(), state, required, klass);
            return;
        }
        if (node instanceof Ast.SelectStmt item) {
            scan(item.arms(), state, required, klass);
            return;
        }
        if (node instanceof Ast.SelectArm item) {
            scan(item.channel(), state, required, klass);
            scan(item.value(), state, required, klass);
            scan(item.body(), state, required, klass);
            return;
        }
        throw new IllegalArgumentException("unhandled AST node in constructor val analysis: " + node.getClass().getName());

    }

    private static IllegalArgumentException error(String klass, String field, String message) {
        return new IllegalArgumentException(
                "constructor for '" + klass + "': val field '" + field + "' " + message);
    }
}
