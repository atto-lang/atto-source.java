package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Experimental lexical callback registration-order analysis.
 *
 * Detects callbacks that may emit an event after being handed to an API of
 * UNKNOWN invocation timing, before a later listener registration. This does
 * not infer API timing guarantees or certify a library's listener semantics.
 * Production admission needs typed effects, CFG, alias and ownership proofs.
 */
public final class CallbackOrderingAnalyzer {
    private CallbackOrderingAnalyzer() { }

    private static final int MAX_VISITS = 200_000;
    private record Event(String emitter, String event) { }
    private record Exposure(Event event, String invoker) { }

    private static final class State {
        final Map<Event, Exposure> pending = new LinkedHashMap<>();
        final Set<Event> registered = new HashSet<>();
        final Map<String, Ast.LambdaExpr> lambdas = new HashMap<>();
        final Map<String, String> aliases = new HashMap<>();
        State copy() {
            State copy = new State();
            copy.pending.putAll(pending);
            copy.registered.addAll(registered);
            copy.lambdas.putAll(lambdas);
            copy.aliases.putAll(aliases);
            return copy;
        }
    }

    public static List<StaticSafetyAnalyzer.Finding> analyze(Ast.Program program) {
        var scanner = new Scanner();
        for (Ast.ModuleDecl module : program.modules()) {
            String prefix = module.name() == null ? "__root__" : module.name();
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl fn)
                    scanner.scan(prefix + "::" + fn.name(), fn.body());
                if (decl instanceof Ast.ClassDecl cls) {
                    for (Ast.MethodDecl method : cls.methods())
                        scanner.scan(prefix + "::" + cls.name() + "." + method.name(), method.body());
                    if (cls.constructor() != null)
                        scanner.scan(prefix + "::" + cls.name() + ".constructor", cls.constructor().body());
                }
            }
        }
        scanner.findings.sort(Comparator.comparing(StaticSafetyAnalyzer.Finding::rule)
                .thenComparing(StaticSafetyAnalyzer.Finding::callable)
                .thenComparing(StaticSafetyAnalyzer.Finding::message));
        return List.copyOf(scanner.findings);
    }

    private static final class Scanner {
        final List<StaticSafetyAnalyzer.Finding> findings = new ArrayList<>();
        String callable;
        int visits;
        int depth;

        void check() {
            if (++visits > MAX_VISITS || depth > 256)
                throw new IllegalArgumentException("callback ordering analysis complexity budget exceeded");
        }

        void scan(String name, List<Ast.Stmt> statements) {
            callable = name;
            State state = new State();
            block(statements, state);
        }

        void block(List<Ast.Stmt> statements, State state) {
            if (++depth > 256) throw new IllegalArgumentException("callback nesting too deep");
            try {
                for (Ast.Stmt statement : statements) step(statement, state);
            } finally {
                depth--;
            }
        }

        private boolean listener(String name) {
            return name.equals("on") || name.equals("once")
                    || name.equals("addListener") || name.equals("add_listener");
        }

        private String receiver(Ast.Expr expression, State state) {
            if (expression instanceof Ast.NameExpr name) {
                String value = name.name();
                Set<String> seen = new HashSet<>();
                while (state.aliases.containsKey(value) && seen.add(value))
                    value = state.aliases.get(value);
                return value;
            }
            if (expression instanceof Ast.MemberExpr m) {
                String value = receiver(m.receiver(), state);
                return value == null ? null : value + "." + m.member();
            }
            return null;
        }

        private String event(Ast.Expr expr) {
            if (expr instanceof Ast.LiteralExpr l && l.value() instanceof String s && !s.isEmpty())
                return s;
            return "*"; // dynamic event IDs alias any event with the same receiver
        }

        private Event eventCall(Ast.CallExpr call, String member, State state) {
            if (!(call.callee() instanceof Ast.MemberExpr m)
                    || !m.member().equals(member) || call.arguments().isEmpty()) return null;
            String obj = receiver(m.receiver(), state);
            return obj == null ? null : new Event(obj, event(call.arguments().get(0)));
        }

        private boolean matches(Event left, Event right) {
            return left.emitter().equals(right.emitter())
                    && (left.event().equals(right.event())
                    || left.event().equals("*") || right.event().equals("*"));
        }

        private boolean registered(Event key, State state) {
            for (Event prior : state.registered) if (matches(prior, key)) return true;
            return false;
        }

        private void publish(Event event, String invoker, State state) {
            if (!registered(event, state))
                state.pending.putIfAbsent(event, new Exposure(event, invoker));
        }

        private void register(Event event, State state) {
            for (Exposure prior : state.pending.values()) {
                if (matches(event, prior.event)) {
                    findings.add(new StaticSafetyAnalyzer.Finding(
                            "ORES-ZALGO-001", "potential", callable,
                            "callback passed to " + prior.invoker
                                    + " can emit " + prior.event.emitter() + "."
                                    + prior.event.event()
                                    + " before its listener is registered; callback timing is unknown",
                            List.of(prior.invoker,
                                    prior.event.emitter() + ".emit(" + prior.event.event() + ")",
                                    event.emitter() + ".on(" + event.event() + ")")));
                }
            }
            state.pending.entrySet().removeIf(entry -> matches(entry.getKey(), event));
            state.registered.add(event);
        }

        private void emissionExpressions(Ast.Expr expr, State state, List<Event> result) {
            if (expr == null) return;
            check();
            if (expr instanceof Ast.CallExpr call) {
                Event emitted = eventCall(call, "emit", state);
                if (emitted != null) result.add(emitted);
                for (Ast.Expr arg : call.arguments()) emissionExpressions(arg, state, result);
            } else if (expr instanceof Ast.LambdaExpr lambda) {
                // Nested lambdas are not necessarily invoked by this callback.
                // Analyze their body only when separately published.
            } else if (expr instanceof Ast.BinaryExpr b) {
                emissionExpressions(b.left(), state, result);
                emissionExpressions(b.right(), state, result);
            } else if (expr instanceof Ast.UnaryExpr u) emissionExpressions(u.operand(), state, result);
            else if (expr instanceof Ast.AwaitExpr a) emissionExpressions(a.expression(), state, result);
            else if (expr instanceof Ast.TrapExpr t) emissionExpressions(t.expression(), state, result);
            else if (expr instanceof Ast.AssignExpr a) emissionExpressions(a.value(), state, result);
            else if (expr instanceof Ast.RuntimeCallExpr rt)
                for (Ast.Expr arg : rt.arguments()) emissionExpressions(arg, state, result);
        }

        private void emissionStatements(List<Ast.Stmt> body, State state, List<Event> result) {
            if (++depth > 256) throw new IllegalArgumentException("callback nesting too deep");
            try {
                for (Ast.Stmt stmt : body) {
                    check();
                    if (stmt instanceof Ast.ExprStmt ex)
                        emissionExpressions(ex.expression(), state, result);
                    else if (stmt instanceof Ast.ReturnStmt r)
                        emissionExpressions(r.value(), state, result);
                    else if (stmt instanceof Ast.BindingStmt b)
                        emissionExpressions(b.initializer(), state, result);
                    else if (stmt instanceof Ast.BlockStmt b)
                        emissionStatements(b.body(), state, result);
                    else if (stmt instanceof Ast.IfStmt i) {
                        for (Ast.IfBranch arm : i.branches())
                            emissionStatements(arm.body(), state, result);
                        emissionStatements(i.elseBody(), state, result);
                    } else if (stmt instanceof Ast.LoopStmt l)
                        emissionStatements(l.body(), state, result);
                    else if (stmt instanceof Ast.ForStmt f)
                        emissionStatements(f.body(), state, result);
                    else if (stmt instanceof Ast.ForOfStmt f)
                        emissionStatements(f.body(), state, result);
                    else if (stmt instanceof Ast.DoWhileStmt d)
                        emissionStatements(d.body(), state, result);
                }
            } finally {
                depth--;
            }
        }

        private List<Event> eventsFromLambda(Ast.LambdaExpr lambda, State state) {
            List<Event> result = new ArrayList<>();
            emissionExpressions(lambda.expressionBody(), state, result);
            if (lambda.blockBody() != null)
                emissionStatements(lambda.blockBody(), state, result);
            return result;
        }

        private void inspect(Ast.Expr expr, State state) {
            if (expr == null) return;
            check();
            if (expr instanceof Ast.CallExpr call) {
                String invoker = "unknown API";
                if (call.callee() instanceof Ast.NameExpr n) invoker = n.name();
                else if (call.callee() instanceof Ast.MemberExpr m)
                    invoker = m.member();

                boolean isSubscription = call.callee() instanceof Ast.MemberExpr member
                        && listener(member.member());
                for (Ast.Expr arg : call.arguments()) {
                    Ast.LambdaExpr lambda = null;
                    if (arg instanceof Ast.LambdaExpr direct) lambda = direct;
                    else if (arg instanceof Ast.NameExpr named) lambda = state.lambdas.get(named.name());
                    if (lambda != null) {
                        // Event registration stores a listener; it is not an
                        // arbitrary callback invocation. The library still
                        // must certify its install semantics before strict admission.
                        if (!isSubscription) {
                            for (Event event : eventsFromLambda(lambda, state))
                                publish(event, invoker, state);
                        }
                    } else inspect(arg, state);
                }

                if (call.callee() instanceof Ast.MemberExpr member
                        && listener(member.member()) && !call.arguments().isEmpty()) {
                    String obj = receiver(member.receiver(), state);
                    if (obj != null) register(new Event(obj, event(call.arguments().get(0))), state);
                }
                // Non-lambda callee may have side effects; source-level source order
                // cannot establish a runtime happens-before relation here.
            } else if (expr instanceof Ast.AssignExpr a) {
                inspect(a.value(), state);
                if (a.target() instanceof Ast.NameExpr n) {
                    state.lambdas.remove(n.name());
                    state.aliases.remove(n.name());
                    state.registered.removeIf(e -> e.emitter().equals(n.name()));
                }
            } else if (expr instanceof Ast.BinaryExpr b) {
                inspect(b.left(), state); inspect(b.right(), state);
            } else if (expr instanceof Ast.ConditionalExpr c) {
                inspect(c.condition(), state);
                State a = state.copy(), b = state.copy();
                inspect(c.whenTrue(), a); inspect(c.whenFalse(), b);
                merge(state, List.of(a, b));
            } else if (expr instanceof Ast.UnaryExpr u) inspect(u.operand(), state);
            else if (expr instanceof Ast.AwaitExpr a) inspect(a.expression(), state);
            else if (expr instanceof Ast.TrapExpr t) inspect(t.expression(), state);
            else if (expr instanceof Ast.RuntimeCallExpr rt) {
                for (Ast.Expr arg : rt.arguments()) inspect(arg, state);
            } else if (expr instanceof Ast.NewExpr n) {
                for (Ast.Expr arg : n.arguments()) inspect(arg, state);
            } else if (expr instanceof Ast.MemberExpr m) inspect(m.receiver(), state);
            else if (expr instanceof Ast.IndexExpr i) {
                inspect(i.receiver(), state); inspect(i.index(), state);
            } else if (expr instanceof Ast.ListExpr l) {
                for (Ast.Expr e : l.elements()) inspect(e, state);
            } else if (expr instanceof Ast.TupleExpr t) {
                for (Ast.Expr e : t.elements()) inspect(e, state);
            } else if (expr instanceof Ast.ObjectExpr o) {
                for (Ast.ObjectField f : o.fields()) inspect(f.value(), state);
            } else if (expr instanceof Ast.ChannelOpExpr ch) {
                inspect(ch.channel(), state); inspect(ch.value(), state);
                branch(state, List.of(ch.callbackBody()));
            }
        }

        private void merge(State target, List<State> alternatives) {
            if (alternatives.isEmpty()) return;
            Set<Event> definite = new HashSet<>(alternatives.get(0).registered);
            for (State a : alternatives) definite.retainAll(a.registered);
            target.registered.clear();
            target.registered.addAll(definite);
            for (State a : alternatives)
                for (var e : a.pending.entrySet()) target.pending.putIfAbsent(e.getKey(), e.getValue());
            // Local lambda / alias definitions become unknown after divergence,
            // rather than inventing a cross-branch definite assignment.
            target.lambdas.clear();
            target.aliases.clear();
        }

        private void branch(State parent, List<List<Ast.Stmt>> alternatives) {
            List<State> states = new ArrayList<>();
            for (List<Ast.Stmt> body : alternatives) {
                State branch = parent.copy();
                block(body, branch);
                states.add(branch);
            }
            merge(parent, states);
        }

        private void step(Ast.Stmt stmt, State state) {
            check();
            if (stmt instanceof Ast.BindingStmt b) {
                inspect(b.initializer(), state);
                if (b.initializer() instanceof Ast.LambdaExpr lambda)
                    state.lambdas.put(b.name(), lambda);
                else state.lambdas.remove(b.name());
                if (b.initializer() instanceof Ast.NameExpr n)
                    state.aliases.put(b.name(), receiver(n, state));
                else state.aliases.remove(b.name());
            } else if (stmt instanceof Ast.ExprStmt e) inspect(e.expression(), state);
            else if (stmt instanceof Ast.ReturnStmt r) inspect(r.value(), state);
            else if (stmt instanceof Ast.YieldStmt y) inspect(y.value(), state);
            else if (stmt instanceof Ast.DestructureStmt d) inspect(d.initializer(), state);
            else if (stmt instanceof Ast.DeferStmt d) inspect(d.expression(), state);
            else if (stmt instanceof Ast.BlockStmt b) block(b.body(), state);
            else if (stmt instanceof Ast.IfStmt i) {
                for (Ast.IfBranch arm : i.branches()) inspect(arm.condition(), state);
                List<List<Ast.Stmt>> bodies = new ArrayList<>();
                for (Ast.IfBranch arm : i.branches()) bodies.add(arm.body());
                bodies.add(i.elseBody()); // implicit no-branch path
                branch(state, bodies);
            } else if (stmt instanceof Ast.LoopStmt l) branch(state, List.of(l.body(), List.of()));
            else if (stmt instanceof Ast.ForStmt f) {
                step(f.initializer(), state);
                inspect(f.condition(), state);
                branch(state, List.of(f.body(), List.of()));
                inspect(f.update(), state);
            } else if (stmt instanceof Ast.ForOfStmt f) {
                inspect(f.iterable(), state);
                branch(state, List.of(f.body(), List.of()));
            } else if (stmt instanceof Ast.ForOfDestructureStmt f) {
                inspect(f.iterable(), state);
                branch(state, List.of(f.body(), List.of()));
            } else if (stmt instanceof Ast.DoWhileStmt d)
                branch(state, List.of(d.body(), List.of()));
            else if (stmt instanceof Ast.TryStmt t) {
                branch(state, List.of(t.body(), t.catchBody(), t.finallyBody()));
            } else if (stmt instanceof Ast.SelectStmt select) {
                List<List<Ast.Stmt>> arms = new ArrayList<>();
                for (Ast.SelectArm arm : select.arms()) arms.add(arm.body());
                branch(state, arms);
            } else if (stmt instanceof Ast.MatchStmt m) {
                inspect(m.subject(), state);
                List<List<Ast.Stmt>> bodies = new ArrayList<>();
                for (Ast.MatchArm arm : m.arms()) bodies.add(arm.body());
                bodies.add(List.of());
                branch(state, bodies);
            } else if (stmt instanceof Ast.SwitchStmt sw) {
                inspect(sw.subject(), state);
                List<List<Ast.Stmt>> bodies = new ArrayList<>();
                for (Ast.SwitchCase c : sw.cases()) bodies.add(c.body());
                bodies.add(sw.defaultBody());
                branch(state, bodies);
            }
        }
    }
}
