package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Experimental AST-backed static warnings for source-level Oreslang.
 * All findings are potential; this is not a CFG, permission proof, or taint solver.
 * Uses typed AST traversal, never guest execution, source text regexes or reflection.
 */
public final class StaticSafetyAnalyzer {
    private StaticSafetyAnalyzer() { }

    public record Finding(String rule, String confidence, String callable,
                          String message, List<String> witness) {
        public Finding { witness = List.copyOf(witness); }
    }

    private record Edge(String from, String to, String kind) { }
    private static final int MAX_NODES = 250_000;

    public static List<Finding> analyze(Ast.Program program) {
        ArrayList<Finding> all = new ArrayList<>(new Scanner(program).run());
        all.addAll(CallbackOrderingAnalyzer.analyze(program));
        all.sort(Comparator.comparing(Finding::rule)
                .thenComparing(Finding::callable).thenComparing(Finding::message));
        return List.copyOf(all);
    }

    private static final class Scanner {
        private final Ast.Program program;
        private final Map<String, List<Ast.Stmt>> bodies = new LinkedHashMap<>();
        private final Map<String, String> actorHandlers = new HashMap<>();
        private final Map<String, List<Edge>> outgoing = new HashMap<>();
        private final List<Finding> findings = new ArrayList<>();
        private String current;
        private String module;
        private int loopDepth;
        private int visited;
        private int terminationVisits;

        Scanner(Ast.Program program) { this.program = java.util.Objects.requireNonNull(program); }

        private static String key(String mod, String name, int arity) {
            return mod + "::" + name + "/" + arity;
        }

        private void addBody(String qualifiedName, List<Ast.Stmt> body) {
            String actual = qualifiedName;
            for (int overload = 1; bodies.containsKey(actual); overload++) {
                actual = qualifiedName + "#overload" + overload;
            }
            bodies.put(actual, body);
        }

        List<Finding> run() {
            for (Ast.ModuleDecl m : program.modules()) {
                String mod = m.name() == null ? "__root__" : m.name();
                for (Ast.Decl decl : m.declarations()) {
                    if (decl instanceof Ast.FunctionDecl fn) {
                        addBody(key(mod, fn.name(), fn.parameters().size()), fn.body());
                    } else if (decl instanceof Ast.ClassDecl cls) {
                        for (Ast.MethodDecl method : cls.methods()) {
                            String k = key(mod, cls.name() + "." + method.name(), method.parameters().size());
                            addBody(k, method.body());
                            if (cls.actorKind() != Ast.ActorKind.NONE
                                    && (method.name().equals("receive") || method.name().equals("on_message"))) {
                                actorHandlers.put(mod + "::" + cls.name(), k);
                            }
                        }
                        if (cls.constructor() != null) {
                            addBody(key(mod, cls.name() + ".constructor", cls.constructor().parameters().size()),
                                    cls.constructor().body());
                        }
                    }
                }
            }
            for (var entry : bodies.entrySet()) {
                current = entry.getKey();
                module = current.substring(0, current.indexOf("::"));
                loopDepth = 0;
                walkBody(entry.getValue());
            }
            detectCycles();
            findings.sort(Comparator.comparing(Finding::rule)
                    .thenComparing(Finding::callable)
                    .thenComparing(Finding::message));
            return List.copyOf(findings);
        }

        private void visit() {
            if (++visited > MAX_NODES) {
                throw new IllegalArgumentException("static analysis complexity budget exceeded");
            }
        }

        private void warn(String id, String message, List<String> witness) {
            findings.add(new Finding(id, "potential", current, message, witness));
        }

        private boolean literalTrue(Ast.Expr expr) {
            return expr == null || (expr instanceof Ast.LiteralExpr lit && Boolean.TRUE.equals(lit.value()));
        }

        private void enterLoop(String kind, boolean unboundedCandidate, List<Ast.Stmt> body) {
            if (unboundedCandidate) {
                if (!hasEscape(body)) {
                    warn("ORES-TERM-002", kind + " has no apparent reachable exit; termination is not proven",
                            List.of(current));
                } else if (!guaranteedImmediateExit(body)) {
                    warn("ORES-TERM-003",
                            kind + " has a possible exit, but its reachability and termination are unproven",
                            List.of(current));
                }
            }
            loopDepth++;
            try { walkBody(body); }
            finally { loopDepth--; }
        }

        /**
         * A first unconditional break or return suffices for the local loop
         * only when reached before executing any potentially diverging work.
         * This is a minimal syntactic exemption, NOT a general ranking proof.
         */
        private boolean guaranteedImmediateExit(List<Ast.Stmt> body) {
            return guaranteedImmediateExit(body, 0);
        }

        private boolean guaranteedImmediateExit(List<Ast.Stmt> body, int depth) {
            terminationBudget(depth);
            if (body.isEmpty()) return false;
            Ast.Stmt first = body.get(0);
            if (first instanceof Ast.BreakStmt) return true;
            if (first instanceof Ast.ReturnStmt r) {
                // Evaluating a return expression could itself call a
                // diverging routine. Only effect-free trivial returns are
                // exempt from the advisory loop-termination warning.
                return r.value() == null || r.value() instanceof Ast.LiteralExpr;
            }
            return first instanceof Ast.BlockStmt b && guaranteedImmediateExit(b.body(), depth + 1);
        }

        /** Presence of a reachable-looking exit is never proof of termination. */
        private void terminationBudget(int depth) {
            if (depth > 256 || ++terminationVisits > MAX_NODES)
                throw new IllegalArgumentException("termination scan depth/work budget exceeded");
        }

        private boolean hasEscape(List<Ast.Stmt> body) {
            return hasEscape(body, 0);
        }

        private boolean hasEscape(List<Ast.Stmt> body, int depth) {
            terminationBudget(depth);
            for (Ast.Stmt stmt : body) {
                terminationBudget(depth);
                if (stmt instanceof Ast.BreakStmt || stmt instanceof Ast.ReturnStmt) return true;
                if (stmt instanceof Ast.BlockStmt block && hasEscape(block.body(), depth + 1)) return true;
                if (stmt instanceof Ast.IfStmt conditional) {
                    boolean alwaysTaken = false;
                    for (Ast.IfBranch arm : conditional.branches()) {
                        // Constant false branches cannot establish an exit.
                        if (arm.condition() instanceof Ast.LiteralExpr lit
                                && Boolean.FALSE.equals(lit.value())) continue;
                        if (hasEscape(arm.body(), depth + 1)) return true;
                        if (arm.condition() instanceof Ast.LiteralExpr lit
                                && Boolean.TRUE.equals(lit.value())) {
                            alwaysTaken = true;
                            break;
                        }
                    }
                    if (!alwaysTaken && hasEscape(conditional.elseBody(), depth + 1)) return true;
                }
                if (stmt instanceof Ast.TryStmt t
                        && (hasEscape(t.body(), depth + 1) || hasEscape(t.catchBody(), depth + 1)
                            || hasEscape(t.finallyBody(), depth + 1))) return true;
            }
            return false;
        }

        private void walkBody(List<Ast.Stmt> body) {
            for (Ast.Stmt stmt : body) walk(stmt);
        }

        private void walk(Ast.Stmt stmt) {
            if (stmt == null) return;
            visit();
            if (stmt instanceof Ast.BlockStmt b) walkBody(b.body());
            else if (stmt instanceof Ast.BindingStmt b) walk(b.initializer());
            else if (stmt instanceof Ast.DestructureStmt d) walk(d.initializer());
            else if (stmt instanceof Ast.ReturnStmt r) walk(r.value());
            else if (stmt instanceof Ast.YieldStmt y) walk(y.value());
            else if (stmt instanceof Ast.ExprStmt e) walk(e.expression());
            else if (stmt instanceof Ast.DeferStmt d) walk(d.expression());
            else if (stmt instanceof Ast.ForStmt f) {
                walk(f.initializer()); walk(f.condition()); walk(f.update());
                enterLoop("for loop", literalTrue(f.condition()), f.body());
            } else if (stmt instanceof Ast.DoWhileStmt d) {
                walk(d.condition()); enterLoop("do-while loop", literalTrue(d.condition()), d.body());
            } else if (stmt instanceof Ast.LoopStmt l) enterLoop("loop", true, l.body());
            else if (stmt instanceof Ast.ForOfStmt f) {
                walk(f.iterable()); enterLoop("for-of loop", false, f.body());
            } else if (stmt instanceof Ast.ForOfDestructureStmt f) {
                walk(f.iterable()); enterLoop("for-of destructure loop", false, f.body());
            } else if (stmt instanceof Ast.IfStmt i) {
                for (Ast.IfBranch b : i.branches()) { walk(b.condition()); walkBody(b.body()); }
                walkBody(i.elseBody());
            } else if (stmt instanceof Ast.MatchStmt m) {
                walk(m.subject());
                for (Ast.MatchArm arm : m.arms()) { walk(arm.guard()); walkBody(arm.body()); }
            } else if (stmt instanceof Ast.SwitchStmt sw) {
                walk(sw.subject());
                for (Ast.SwitchCase c : sw.cases()) {
                    for (Ast.Expr v : c.constants()) walk(v);
                    walkBody(c.body());
                }
                walkBody(sw.defaultBody());
            } else if (stmt instanceof Ast.TryStmt t) {
                walkBody(t.body()); walkBody(t.catchBody()); walkBody(t.finallyBody());
            } else if (stmt instanceof Ast.SelectStmt select) {
                for (Ast.SelectArm arm : select.arms()) {
                    walk(arm.channel()); walk(arm.value()); walkBody(arm.body());
                }
            } else if (!(stmt instanceof Ast.BreakStmt || stmt instanceof Ast.ContinueStmt)) {
                throw new IllegalArgumentException("unsupported statement for static analysis: "
                        + "unknown");
            }
        }

        private void edge(String dest, String kind) {
            outgoing.computeIfAbsent(current, ignored -> new ArrayList<>())
                    .add(new Edge(current, dest, kind));
            if (loopDepth > 0 && (kind.equals("actor_spawn") || kind.equals("task_spawn"))) {
                warn("ORES-AMP-005", kind + " inside a loop; finite total spawn count not proven",
                        List.of(current, dest));
            }
        }

        private void walk(Ast.Expr expr) {
            if (expr == null) return;
            visit();
            if (expr instanceof Ast.CallExpr call) {
                if (call.callee() instanceof Ast.NameExpr n) {
                    String target = key(module, n.name(), call.arguments().size());
                    long candidates = bodies.keySet().stream()
                            .filter(k -> k.equals(target) || k.startsWith(target + "#overload"))
                            .count();
                    if (candidates == 1) edge(target, "call");
                    else if (candidates > 1) warn("ORES-ANALYSIS-001",
                            "overloaded call requires resolved overload identity; recursion cannot be certified",
                            List.of(current, target));
                } else if (call.callee() instanceof Ast.MemberExpr m
                        && m.receiver() instanceof Ast.NameExpr n
                        && (n.name().equals("process") || n.name().equals("ffi"))
                        && (m.member().equals("run") || m.member().equals("exec")
                        || m.member().equals("load"))) {
                    warn("ORES-SEC-010", "host-effect call requires permission and input-flow verification",
                            List.of(current, n.name() + "." + m.member()));
                    // Non-literal operands are not proof of taint. They are
                    // an explicit interprocedural taint-analysis obligation:
                    // untrusted data reaching a privileged sink must be
                    // sanitized and authorized along every feasible path.
                    boolean dynamicInput = call.arguments().stream()
                            .anyMatch(arg -> !(arg instanceof Ast.LiteralExpr));
                    if (dynamicInput) {
                        warn("ORES-SEC-011",
                                "dynamic value reaches a privileged host-effect call; "
                                    + "input provenance and sanitizer proof are unavailable",
                                List.of(current, n.name() + "." + m.member()));
                    }
                }
                walk(call.callee()); for (Ast.Expr arg : call.arguments()) walk(arg);
            } else if (expr instanceof Ast.RuntimeCallExpr rt) {
                if (rt.operation().equals("spawn") && rt.arguments().size() == 1
                        && rt.arguments().get(0) instanceof Ast.NewExpr n) {
                    String actor = module + "::" + n.type().name();
                    String dest = actorHandlers.get(actor);
                    if (dest != null) edge(dest, "actor_spawn");
                    else warn("ORES-ANALYSIS-001", "spawn target handler is unresolved; cannot bound actor creation",
                            List.of(current, actor));
                }
                for (Ast.Expr arg : rt.arguments()) walk(arg);
            } else if (expr instanceof Ast.NewExpr n) {
                for (Ast.Expr arg : n.arguments()) walk(arg);
            } else if (expr instanceof Ast.BinaryExpr b) { walk(b.left()); walk(b.right()); }
            else if (expr instanceof Ast.UnaryExpr u) walk(u.operand());
            else if (expr instanceof Ast.AssignExpr a) { walk(a.target()); walk(a.value()); }
            else if (expr instanceof Ast.ConditionalExpr c) {
                walk(c.condition()); walk(c.whenTrue()); walk(c.whenFalse());
            } else if (expr instanceof Ast.MemberExpr m) walk(m.receiver());
            else if (expr instanceof Ast.IndexExpr i) { walk(i.receiver()); walk(i.index()); }
            else if (expr instanceof Ast.AwaitExpr a) walk(a.expression());
            else if (expr instanceof Ast.TrapExpr t) walk(t.expression());
            else if (expr instanceof Ast.CastExpr c) walk(c.value());
            else if (expr instanceof Ast.TypeTestExpr t) walk(t.value());
            else if (expr instanceof Ast.PatternTestExpr p) walk(p.value());
            else if (expr instanceof Ast.SpreadExpr s) walk(s.expression());
            else if (expr instanceof Ast.MatchExpr m) {
                walk(m.subject());
                for (Ast.MatchValueArm arm : m.arms()) { walk(arm.guard()); walk(arm.value()); }
            } else if (expr instanceof Ast.ChannelOpExpr op) {
                walk(op.channel()); walk(op.value()); walkBody(op.callbackBody());
            } else if (expr instanceof Ast.DynamicSelectExpr d) walk(d.cases());
            else if (expr instanceof Ast.ListExpr l) { for (Ast.Expr e : l.elements()) walk(e); }
            else if (expr instanceof Ast.TupleExpr t) { for (Ast.Expr e : t.elements()) walk(e); }
            else if (expr instanceof Ast.ObjectExpr o) { for (Ast.ObjectField e : o.fields()) walk(e.value()); }
            else if (expr instanceof Ast.LambdaExpr lambda) {
                walk(lambda.expressionBody());
                if (lambda.blockBody() != null) walkBody(lambda.blockBody());
            } else if (!(expr instanceof Ast.LiteralExpr || expr instanceof Ast.NameExpr)) {
                throw new IllegalArgumentException("unsupported expression for static analysis: "
                        + "unknown");
            }
        }

        private void detectCycles() {
            Map<String, Set<String>> forward = new HashMap<>();
            Map<String, Set<String>> reverse = new HashMap<>();
            for (String k : bodies.keySet()) { forward.put(k, new HashSet<>()); reverse.put(k, new HashSet<>()); }
            for (List<Edge> edges : outgoing.values()) for (Edge e : edges) {
                if (forward.containsKey(e.to())) {
                    forward.get(e.from()).add(e.to()); reverse.get(e.to()).add(e.from());
                }
            }
            record Frame(String node, java.util.Iterator<String> children) { }
            Set<String> seen = new HashSet<>();
            List<String> order = new ArrayList<>();
            for (String k : bodies.keySet()) {
                if (!seen.add(k)) continue;
                ArrayDeque<Frame> stack = new ArrayDeque<>();
                stack.push(new Frame(k, forward.get(k).iterator()));
                while (!stack.isEmpty()) {
                    Frame top = stack.peek();
                    if (!top.children().hasNext()) { order.add(top.node()); stack.pop(); }
                    else {
                        String child = top.children().next();
                        if (seen.add(child)) stack.push(new Frame(child, forward.get(child).iterator()));
                    }
                }
            }
            seen.clear();
            for (int i = order.size() - 1; i >= 0; i--) {
                String start = order.get(i);
                if (!seen.add(start)) continue;
                List<String> component = new ArrayList<>();
                ArrayDeque<String> pending = new ArrayDeque<>(); pending.push(start);
                while (!pending.isEmpty()) {
                    String k = pending.pop(); component.add(k);
                    for (String parent : reverse.get(k)) if (seen.add(parent)) pending.push(parent);
                }
                Set<String> set = new HashSet<>(component);
                List<Edge> inside = new ArrayList<>();
                for (String k : component) for (Edge edge : outgoing.getOrDefault(k, List.of()))
                    if (set.contains(edge.to())) inside.add(edge);
                if (component.size() == 1 && inside.stream().noneMatch(e -> e.to().equals(start))) continue;
                if (inside.isEmpty()) continue;
                component.sort(String::compareTo);
                current = component.get(0);
                boolean spawn = inside.stream().anyMatch(e -> e.kind().equals("actor_spawn"));
                String rule = spawn ? "ORES-AMP-001" : "ORES-AMP-004";
                long actorSpawnSites = inside.stream().filter(e -> e.kind().equals("actor_spawn")).count();
                String message = spawn
                        ? "possible unbounded recursive actor creation; " + actorSpawnSites
                            + " static actor-spawn site(s) in this cycle"
                        : "possible infinite recursion / repeated callable cycle";
                warn(rule, message + "; no decreasing rank or total-work bound established", component);
                if (spawn && actorSpawnSites > 1) {
                    warn("ORES-AMP-002",
                            "recursive actor cycle includes " + actorSpawnSites
                            + " static spawn sites; possible multiplicative actor amplification "
                            + "(branches may be mutually exclusive; no feasible-path fanout bound)",
                            component);
                }
            }
        }
    }
}
