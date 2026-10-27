package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Narrow source-derived effect closure for statically resolved zero-argument
 * scalar functions. This is NOT a general HIR/MIR effect checker and NOT
 * real-time or Native Image certification.
 *
 * No client may supply call edges or mark a callee pure. Input is reparsed,
 * typechecked and ownership-checked through the real Oreslang frontend.
 * Unhandled syntax contributes UNKNOWN_CALL instead of becoming effect-free.
 */
public final class ScalarSourceEffectFactsV1 {
    private ScalarSourceEffectFactsV1() { }

    private static final String ROOT = Parser.ROOT_MODULE + "/";
    private static final int MAX_NODES = 100_000;
    private static final int MAX_NESTING = 64;

    public record Report(Map<String, Set<EffectContracts.Effect>> closure) {
        public Report {
            var copy = new LinkedHashMap<String, Set<EffectContracts.Effect>>();
            closure.forEach((k, v) -> copy.put(k, Set.copyOf(v)));
            closure = Collections.unmodifiableMap(copy);
        }

        public Set<EffectContracts.Effect> effectsOf(String functionName) {
            Set<EffectContracts.Effect> effects = closure.get(ROOT + functionName + "/0");
            if (effects == null) throw new IllegalArgumentException("unknown scalar function");
            return effects;
        }

        /**
         * Source-derived effect-free subset only. This does not prove bounded
         * CPU time, memory, scheduler, machine-code AOT, or target safety.
         */
        public void requireNoEffects(String functionName) {
            var effects = effectsOf(functionName);
            if (!effects.isEmpty()) {
                throw new IllegalArgumentException(
                        "unproven scalar effect closure for " + functionName + ": " + effects);
            }
        }
    }

    public static Report fromSource(String source) {
        if (source == null || source.length() > 1_048_576) {
            throw new IllegalArgumentException("source exceeds effect-analysis budget");
        }
        Ast.Program checked = TypeChecker.check(Parser.parse(source));
        if (!checked.imports().isEmpty() || checked.modules().size() != 1
                || !Parser.ROOT_MODULE.equals(checked.modules().getFirst().name())) {
            throw new IllegalArgumentException(
                    "scalar effect proof requires one closed root module without imports");
        }

        var declarations = checked.modules().getFirst().declarations();
        var symbols = new LinkedHashMap<String, Ast.FunctionDecl>();
        for (Ast.Decl declaration : declarations) {
            if (!(declaration instanceof Ast.FunctionDecl fn)) {
                throw new IllegalArgumentException(
                        "scalar effect proof does not admit nominal declarations or actor code");
            }
            if (!fn.parameters().isEmpty() || !fn.genericParameters().isEmpty()
                    || !fn.genericSpecs().isEmpty() || !fn.annotations().isEmpty()
                    || fn.structural() || fn.nonLexical() || !fn.effects().isEmpty()) {
                throw new IllegalArgumentException(
                        "scalar effect proof does not admit generic/parametric/annotated callables");
            }
            String key = ROOT + fn.name() + "/0";
            if (symbols.putIfAbsent(key, fn) != null) {
                throw new IllegalArgumentException("duplicate scalar call identity: " + key);
            }
        }
        if (symbols.isEmpty() || symbols.size() > 1024) {
            throw new IllegalArgumentException("scalar function count outside proof budget");
        }

        var graph = new EffectContracts.Graph();
        var scanner = new Scanner(symbols.keySet());
        for (var entry : symbols.entrySet()) {
            Ast.FunctionDecl fn = entry.getValue();
            scanner.reset();
            if (fn.async() || fn.suspends() || fn.generator()) {
                scanner.direct.add(EffectContracts.Effect.SUSPEND);
            }
            if (fn.actorKind() != Ast.ActorKind.NONE) {
                scanner.direct.add(EffectContracts.Effect.ACTOR);
                scanner.direct.add(EffectContracts.Effect.SCHEDULER);
            }
            if (fn.trapped()) scanner.direct.add(EffectContracts.Effect.UNKNOWN_CALL);
            scanner.statements(fn.body(), 0);
            graph.add(EffectContracts.Callable.inferred(
                    entry.getKey(), scanner.direct, scanner.calls));
        }
        return new Report(graph.infer());
    }

    private static final class Scanner {
        final Set<String> known;
        final EnumSet<EffectContracts.Effect> direct =
                EnumSet.noneOf(EffectContracts.Effect.class);
        final List<EffectContracts.Call> calls = new ArrayList<>();
        int nodes;

        Scanner(Set<String> known) {
            this.known = Set.copyOf(known);
        }

        void reset() {
            direct.clear();
            calls.clear();
            nodes = 0;
        }

        void budget(int depth) {
            if (++nodes > MAX_NODES || depth > MAX_NESTING) {
                throw new IllegalArgumentException(
                        "scalar effect source exceeds static traversal budget");
            }
        }

        void statements(List<Ast.Stmt> statements, int depth) {
            budget(depth);
            for (Ast.Stmt stmt : statements) statement(stmt, depth + 1);
        }

        void statement(Ast.Stmt stmt, int depth) {
            budget(depth);
            if (stmt instanceof Ast.ReturnStmt ret) {
                expression(ret.value(), depth + 1);
            } else if (stmt instanceof Ast.ExprStmt expr) {
                expression(expr.expression(), depth + 1);
            } else if (stmt instanceof Ast.BlockStmt block) {
                statements(block.body(), depth + 1);
            } else if (stmt instanceof Ast.YieldStmt yield) {
                direct.add(EffectContracts.Effect.SUSPEND);
                expression(yield.value(), depth + 1);
            } else if (stmt instanceof Ast.TaskScopeStmt task) {
                direct.add(EffectContracts.Effect.SUSPEND);
                direct.add(EffectContracts.Effect.SCHEDULER);
                statements(task.body(), depth + 1);
            } else {
                // This includes lexical binding/shadowing, defer, match,
                // select, loops, exception handling and borrowed scopes.
                // Until resolved HIR facts exist, no one may infer purity.
                direct.add(EffectContracts.Effect.UNKNOWN_CALL);
            }
        }

        void expression(Ast.Expr expr, int depth) {
            budget(depth);
            if (expr == null) return; // void return
            if (expr instanceof Ast.LiteralExpr literal) {
                Object v = literal.value();
                if (v instanceof String) direct.add(EffectContracts.Effect.ALLOC);
                else if (!(v instanceof Integer) && !(v instanceof Long)
                        && !(v instanceof Boolean)) {
                    direct.add(EffectContracts.Effect.UNKNOWN_CALL);
                }
            } else if (expr instanceof Ast.CallExpr call) {
                // Only direct name/zero-argument calls can be proven here.
                // Any inferred receiver, pipeline, generic argument, alias,
                // callback, named argument or indirect call is UNKNOWN.
                if (call.callee() instanceof Ast.NameExpr target
                        && call.origin() == Ast.CallOrigin.DIRECT
                        && call.arguments().isEmpty()
                        && call.typeArguments().isEmpty()
                        && !call.typeArgumentsPresent()) {
                    String symbol = ROOT + target.name() + "/0";
                    if (known.contains(symbol)) calls.add(EffectContracts.Call.direct(symbol));
                    else direct.add(EffectContracts.Effect.UNKNOWN_CALL);
                } else {
                    direct.add(EffectContracts.Effect.UNKNOWN_CALL);
                }
            } else if (expr instanceof Ast.AwaitExpr await) {
                direct.add(EffectContracts.Effect.SUSPEND);
                expression(await.expression(), depth + 1);
            } else if (expr instanceof Ast.TaskExpr task) {
                direct.add(EffectContracts.Effect.SUSPEND);
                direct.add(EffectContracts.Effect.SCHEDULER);
                direct.add(EffectContracts.Effect.ALLOC);
                statements(task.body(), depth + 1);
            } else if (expr instanceof Ast.NewExpr) {
                direct.add(EffectContracts.Effect.ALLOC);
                direct.add(EffectContracts.Effect.UNKNOWN_CALL);
            } else if (expr instanceof Ast.ChannelOpExpr
                    || expr instanceof Ast.DynamicSelectExpr) {
                direct.add(EffectContracts.Effect.SUSPEND);
                direct.add(EffectContracts.Effect.IO);
                direct.add(EffectContracts.Effect.UNKNOWN_CALL);
            } else {
                // Includes operators until typed scalar evidence, runtime
                // intrinsics, lambdas, assignment, indexing, interpolated
                // forms, aggregate layouts and all future AST additions.
                direct.add(EffectContracts.Effect.UNKNOWN_CALL);
            }
        }
    }
}
