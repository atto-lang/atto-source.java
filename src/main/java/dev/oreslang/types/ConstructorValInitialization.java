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
 * <p>Tracks MUST and MAY initialized fields on normal constructor paths.
 * A write is legal only when the field is not MAY-initialized; construction
 * completes only when every blank val field is MUST-initialized. Each explicit
 * constructor is checked independently. Conditions do not publish a partially
 * constructed receiver, and constructor writes are direct self.field = value
 * statements, not arbitrary expression side effects.</p>
 *
 * <p>Loops, try/catch/finally, select, defer and match are conservatively
 * allowed only once all required val slots are initialized. This avoids
 * claiming exactly-once proofs across exceptional, suspended or iterative
 * edges that the current frontend does not model yet.</p>
 */
public final class ConstructorValInitialization {
    private ConstructorValInitialization() { }

    public static void check(Ast.ClassDecl klass) {
        if (klass.actorKind() != Ast.ActorKind.NONE || klass.constructors().isEmpty()) return;

        LinkedHashSet<String> required = new LinkedHashSet<>();
        for (Ast.FieldDecl field : klass.fields()) {
            if (field.bindingKind() == Ast.BindingKind.VAL && field.initializer() == null) {
                required.add(field.name());
            }
        }
        if (required.isEmpty()) return;

        for (Ast.ConstructorDecl constructor : klass.constructors()) {
            State normalExit = block(constructor.body(), new State(), required, klass.name());
            if (normalExit != null) requireComplete(normalExit, required, klass.name());
        }
    }

    private static final class State {
        final LinkedHashSet<String> must;
        final LinkedHashSet<String> may;

        State() {
            this.must = new LinkedHashSet<>();
            this.may = new LinkedHashSet<>();
        }

        State(Set<String> must, Set<String> may) {
            this.must = new LinkedHashSet<>(must);
            this.may = new LinkedHashSet<>(may);
        }

        State copy() {
            return new State(must, may);
        }
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
                    && blankValTarget(assignment.target(), required) != null) {
                String field = blankValTarget(assignment.target(), required);
                // RHS executes before the slot is written: reject reads of a missing slot.
                scan(assignment.value(), state, required, klass);
                if (!state.may.add(field)) {
                    throw error(klass, field, "may be initialized more than once");
                }
                state.must.add(field);
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
            joined.must.retainAll(states.get(i).must);
            joined.may.addAll(states.get(i).may);
        }
        return joined;
    }

    private static void requireComplete(State state, Set<String> required, String klass) {
        for (String field : required) {
            if (!state.must.contains(field)) {
                throw error(klass, field, "is not definitely initialized on every normal constructor exit");
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
     * flow, nested statements, or callbacks. All Ast syntax nodes are records;
     * traverse their record components so new expression forms are scanned
     * instead of silently bypassing the checker.
     */
    private static void scan(Object node, State state, Set<String> required, String klass) {
        if (node == null) return;
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
                && !state.must.contains(member.member())) {
            throw error(klass, member.member(), "is read before constructor initialization");
        }
        if (node instanceof Iterable<?> values) {
            for (Object value : values) scan(value, state, required, klass);
            return;
        }
        if (!(node instanceof Record record) || node.getClass().getEnclosingClass() != Ast.class) return;
        for (RecordComponent component : record.getClass().getRecordComponents()) {
            try {
                scan(component.getAccessor().invoke(record), state, required, klass);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                        "unable to inspect constructor syntax " + record.getClass().getSimpleName(), e);
            }
        }
    }

    private static IllegalArgumentException error(String klass, String field, String message) {
        return new IllegalArgumentException(
                "constructor for '" + klass + "': val field '" + field + "' " + message);
    }
}
