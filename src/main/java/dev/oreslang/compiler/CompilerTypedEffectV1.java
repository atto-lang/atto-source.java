package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Opt-in checked-AST transitive effect admission for a closed scalar module.
 *
 * This is a compiler-owned partial slice, NOT a whole-program effect proof,
 * native-image certificate, real-time guarantee or permission grant. Unknown
 * call/alloc/cleanup/foreign/actor shapes fail closed rather than being pure.
 */
public final class CompilerTypedEffectV1 {
    private CompilerTypedEffectV1() { }

    public enum Profile { PURE, DETERMINISTIC, NO_ALLOC }

    public record Row(String callable, Set<String> inferredEffects, List<String> directCallees) {
        public Row {
            inferredEffects = Set.copyOf(inferredEffects);
            directCallees = List.copyOf(directCallees);
        }
    }

    public record Report(List<Row> rows, Profile checkedProfile, boolean runtimeCertified) {
        public Report {
            rows = List.copyOf(rows);
            if (runtimeCertified) throw new IllegalArgumentException("E_EFFECT_UNKNOWN: certification is unavailable");
        }
    }

    private static final class Local {
        final Set<String> effects = new TreeSet<>();
        final Set<String> calls = new TreeSet<>();
        final Set<String> locals = new HashSet<>();
        int visits;

        void tick(int depth) {
            if (depth > 64 || ++visits > 100_000)
                deny("E_EFFECT_UNKNOWN", "AST traversal budget");
        }

        void statement(Ast.Stmt stmt, int depth) {
            tick(depth);
            if (stmt instanceof Ast.ReturnStmt ret) {
                if (ret.value() != null) expression(ret.value(), depth + 1);
            } else if (stmt instanceof Ast.ExprStmt expr) {
                expression(expr.expression(), depth + 1);
            } else if (stmt instanceof Ast.BindingStmt bind) {
                if (bind.initializer() == null || !scalar(bind.declaredType())
                        || !locals.add(bind.name())) {
                    deny("E_EFFECT_UNKNOWN", "implicit or shadowed binding");
                }
                expression(bind.initializer(), depth + 1);
            } else if (stmt instanceof Ast.BlockStmt block) {
                for (Ast.Stmt child : block.body()) statement(child, depth + 1);
            } else if (stmt instanceof Ast.IfStmt iff) {
                for (Ast.IfBranch branch : iff.branches()) {
                    expression(branch.condition(), depth + 1);
                    // A conservative lexical union may reject shadowing across
                    // different arms, but can never certify an unknown binding.
                    for (Ast.Stmt child : branch.body()) statement(child, depth + 1);
                }
                for (Ast.Stmt child : iff.elseBody()) statement(child, depth + 1);
            } else {
                deny("E_EFFECT_UNKNOWN", "unmodeled statement: " + stmt.getClass().getSimpleName());
            }
        }

        void expression(Ast.Expr expr, int depth) {
            tick(depth);
            if (expr instanceof Ast.LiteralExpr literal) {
                Object v = literal.value();
                if (!(v instanceof Boolean || v instanceof Integer || v instanceof Long ||
                        (v instanceof BigInteger big && big.bitLength() < 63)))
                    deny("E_EFFECT_UNKNOWN", "non-scalar or unbounded literal runtime");
            } else if (expr instanceof Ast.NameExpr name) {
                if (!locals.contains(name.name())) deny("E_EFFECT_UNKNOWN", "unresolved variable");
            } else if (expr instanceof Ast.UnaryExpr unary) {
                if (!Set.of("-", "+", "!", "~").contains(unary.operator()))
                    deny("E_EFFECT_UNKNOWN", "unmodeled unary op");
                if ("-".equals(unary.operator())) effects.add("panic");
                expression(unary.operand(), depth + 1);
            } else if (expr instanceof Ast.BinaryExpr binary) {
                if (!Set.of("+", "-", "*", "==", "!=", "<", "<=", ">", ">=",
                        "eq", "neq", "&&", "||", "and", "or").contains(binary.operator())) {
                    deny("E_EFFECT_UNKNOWN", "unmodeled binary op");
                }
                if (Set.of("+", "-", "*").contains(binary.operator())) effects.add("panic");
                expression(binary.left(), depth + 1);
                expression(binary.right(), depth + 1);
            } else if (expr instanceof Ast.ConditionalExpr conditional) {
                expression(conditional.condition(), depth + 1);
                expression(conditional.whenTrue(), depth + 1);
                expression(conditional.whenFalse(), depth + 1);
            } else if (expr instanceof Ast.CallExpr call) {
                if (!(call.callee() instanceof Ast.NameExpr name)
                        || call.origin() != Ast.CallOrigin.DIRECT
                        || call.typeArgumentsPresent() || !call.typeArguments().isEmpty())
                    deny("E_EFFECT_UNKNOWN", "dynamic/foreign/builtin call summary not authenticated");
                for (Ast.Expr arg : call.arguments()) expression(arg, depth + 1);
                Ast.NameExpr name = (Ast.NameExpr) call.callee();
                if (locals.contains(name.name()))
                    deny("E_EFFECT_UNKNOWN", "callable shadowed by a value");
                calls.add(name.name() + "/" + call.arguments().size());
            } else if (expr instanceof Ast.NewExpr allocation) {
                effects.add("alloc");
                for (Ast.Expr arg : allocation.arguments()) expression(arg, depth + 1);
                // Constructors, implicit fields and drop/exception edges
                // require full authenticated nominal and runtime summaries.
                effects.add("unknown");
            } else {
                deny("E_EFFECT_UNKNOWN", "unmodeled expression: " + expr.getClass().getSimpleName());
            }
        }
    }

    private record Internal(Set<String> localEffects, Set<String> calls) { }

    private static boolean scalar(Ast.TypeRef type) {
        return type != null && type.arguments().isEmpty() && !type.inferArguments()
                && Set.of("int", "bool", "void").contains(type.name());
    }

    static Report analyzeChecked(Ast.Program checked, Profile profile) {
        Objects.requireNonNull(checked, "checked AST");
        Objects.requireNonNull(profile, "profile");
        if (checked.namespace() != null || !checked.imports().isEmpty()
                || checked.modules().size() != 1)
            deny("E_EFFECT_UNKNOWN", "unresolved module imports or namespace");
        Ast.ModuleDecl module = checked.modules().getFirst();
        if (!"__root__".equals(module.name()) || !module.annotations().isEmpty()
                || !module.contracts().isEmpty() || module.declarations().isEmpty()
                || module.declarations().size() > 4096)
            deny("E_EFFECT_UNKNOWN", "module metadata or callable budget");

        Map<String, Internal> rows = new TreeMap<>();
        int edges = 0;
        for (Ast.Decl declaration : module.declarations()) {
            if (!(declaration instanceof Ast.FunctionDecl))
                deny("E_EFFECT_UNKNOWN", "actors/classes/imported declarations not covered");
            Ast.FunctionDecl fn = (Ast.FunctionDecl) declaration;
            if (fn.kind() != Ast.CallableKind.FNC || fn.actorKind() != Ast.ActorKind.NONE
                    || fn.async() || fn.suspends() || fn.generator() || fn.trapped()
                    || fn.structural() || fn.nonLexical() || !fn.annotations().isEmpty()
                    || !fn.genericParameters().isEmpty() || !fn.effects().isEmpty()
                    || !scalar(fn.returnType()))
                deny("E_EFFECT_UNKNOWN", "unverified callable signature");
            Local local = new Local();
            for (Ast.Param param : fn.parameters()) {
                if (!scalar(param.type()) || param.mutable() || param.structural()
                        || !local.locals.add(param.name()))
                    deny("E_EFFECT_UNKNOWN", "unverified parameter or shadowing");
            }
            for (Ast.Stmt stmt : fn.body()) local.statement(stmt, 0);
            String key = fn.name() + "/" + fn.parameters().size();
            if (rows.containsKey(key)) deny("E_EFFECT_UNKNOWN", "ambiguous callable overload");
            edges += local.calls.size();
            if (edges > 65_536) deny("E_EFFECT_UNKNOWN", "call graph budget");
            rows.put(key, new Internal(local.effects, local.calls));
        }

        // Monotone finite-lattice inference. Import/dynamic resolution is never
        // guessed: all direct targets must be present in the checked module.
        Map<String, Set<String>> closure = new TreeMap<>();
        Map<String, Set<String>> parents = new HashMap<>();
        for (String key : rows.keySet()) {
            closure.put(key, new TreeSet<>(rows.get(key).localEffects()));
            parents.put(key, new HashSet<>());
        }
        for (var entry : rows.entrySet()) {
            for (String target : entry.getValue().calls()) {
                if (!rows.containsKey(target))
                    deny("E_EFFECT_UNKNOWN", "unresolved target " + target);
                parents.get(target).add(entry.getKey());
            }
        }
        ArrayDeque<String> work = new ArrayDeque<>(rows.keySet());
        Set<String> queued = new HashSet<>(work);
        while (!work.isEmpty()) {
            String caller = work.removeFirst();
            queued.remove(caller);
            Set<String> merged = new TreeSet<>(rows.get(caller).localEffects());
            for (String target : rows.get(caller).calls())
                merged.addAll(closure.get(target));
            if (!merged.equals(closure.get(caller))) {
                closure.put(caller, merged);
                for (String parent : parents.get(caller)) {
                    if (queued.add(parent)) work.addLast(parent);
                }
            }
        }
        List<Row> result = new ArrayList<>();
        for (String name : rows.keySet()) {
            Set<String> effects = closure.get(name);
            // A confirmed allocation is independently disqualifying for
            // no-alloc even when the constructor's other effects are unknown.
            if (profile == Profile.NO_ALLOC && effects.contains("alloc"))
                deny("E_HARD_RT_ALLOCATION", "transitive runtime allocation at " + name);
            // Unknown is never equivalent to the empty effect set.
            if (effects.contains("unknown"))
                deny("E_EFFECT_UNKNOWN", "constructor/cleanup effect not authenticated: " + name);
            if ((profile == Profile.PURE || profile == Profile.DETERMINISTIC)
                    && effects.stream().anyMatch(x -> Set.of(
                        "io", "nondet", "ffi", "state.read", "state.write", "spawns",
                        "actor.send").contains(x)))
                deny(profile == Profile.PURE ? "E_PURITY_EFFECT" : "E_NONDETERMINISTIC",
                        "transitive externally observable effect at " + name);
            result.add(new Row(name, effects, new ArrayList<>(rows.get(name).calls())));
        }
        return new Report(result, profile, false);
    }

    private static void deny(String code, String message) {
        throw new IllegalArgumentException(code + ": " + message);
    }
}
