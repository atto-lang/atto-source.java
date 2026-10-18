package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import java.util.*;
import java.util.function.Consumer;

/** Advisory failure analysis. Unresolved dispatch is reported, never certified as safe. */
public final class FailureAnalysis {
    private FailureAnalysis() { }
    public record Diagnostic(String code, String symbol, String message) {
        @Override public String toString() { return code + " " + symbol + ": " + message; }
    }
    private record Definition(String symbol, String module, Set<Ast.FailureEffect> declared,
                              List<Ast.Stmt> body, boolean trapped, Set<String> locals) { }
    private record Edge(String target, boolean caught, boolean unchecked) { }
    private static final class Summary {
        final Set<Ast.FailureEffect> effects = EnumSet.noneOf(Ast.FailureEffect.class);
        final Set<Ast.FailureEffect> assumed = EnumSet.noneOf(Ast.FailureEffect.class);
        final List<Edge> edges = new ArrayList<>();
        boolean unknown;
    }

    public static List<Diagnostic> analyze(Ast.Program program) {
        Map<String, Definition> definitions = new LinkedHashMap<>();
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl fn) {
                    add(definitions, module.name(), fn.name(), fn.parameters(), fn.failureEffects(), fn.body(), fn.trapped());
                } else if (decl instanceof Ast.ClassDecl klass) {
                    for (Ast.MethodDecl method : klass.methods()) {
                        add(definitions, module.name() + "." + klass.name(), method.name(), method.parameters(),
                                method.failureEffects(), method.body(), false);
                    }
                }
            }
        }
        Map<String, Summary> summaries = new LinkedHashMap<>();
        for (Definition def : definitions.values()) {
            Summary summary = new Summary();
            walk(def.body(), def, summary, definitions, false);
            if (def.trapped()) summary.effects.remove(Ast.FailureEffect.THROWS);
            summaries.put(def.symbol(), summary);
        }
        boolean changed;
        do {
            changed = false;
            for (Definition def : definitions.values()) {
                Summary summary = summaries.get(def.symbol());
                int before = summary.effects.size() + summary.assumed.size();
                boolean unknown = summary.unknown;
                for (Edge edge : summary.edges) {
                    Summary target = summaries.get(edge.target());
                    Definition callee = definitions.get(edge.target());
                    boolean handlesThrow = edge.caught() || def.trapped() || callee.trapped();
                    addEffects(summary.effects, target.effects, handlesThrow);
                    if (!edge.unchecked()) {
                        addEffects(summary.assumed, target.assumed, handlesThrow);
                        addEffects(summary.assumed, callee.declared(), handlesThrow);
                    }
                    summary.unknown |= target.unknown;
                }
                changed |= before != summary.effects.size() + summary.assumed.size() || unknown != summary.unknown;
            }
        } while (changed);
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (Definition def : definitions.values()) {
            Summary summary = summaries.get(def.symbol());
            Set<Ast.FailureEffect> missing = EnumSet.noneOf(Ast.FailureEffect.class);
            missing.addAll(summary.effects);
            missing.addAll(summary.assumed);
            missing.removeAll(def.declared());
            if (!missing.isEmpty()) diagnostics.add(new Diagnostic(def.declared().isEmpty() ? "failure-suggestion" : "failure-warning",
                    def.symbol(), "may propagate " + keywords(missing) + "; handle explicitly or add signature markers"));
            if (summary.unknown) diagnostics.add(new Diagnostic("failure-unknown", def.symbol(),
                    "unresolved call, callback, or dynamic dispatch; failure effects are not fully known"));
        }
        return List.copyOf(diagnostics);
    }

    private static void addEffects(Set<Ast.FailureEffect> to, Set<Ast.FailureEffect> from, boolean handlesThrow) {
        for (Ast.FailureEffect effect : from) {
            if (effect != Ast.FailureEffect.THROWS || !handlesThrow) to.add(effect);
        }
    }

    /** Recovery may exit before a required return; reject until a recovery result is modeled. */
    public static void validateRecoveryResult(List<Ast.Stmt> body, boolean requiresResult) {
        if (requiresResult && hasRecoveringDefer(body)) {
            throw new IllegalArgumentException("deferred recover in a non-void callable requires an explicit recovery result; use a void handler");
        }
    }

    private static boolean hasRecoveringDefer(Object node) {
        if (node == null || node instanceof Ast.LambdaExpr) return false;
        if (node instanceof Ast.DeferStmt defer) {
            boolean[] found = {false};
            visit(defer.expression(), nested -> {
                if (nested instanceof Ast.CallExpr call && call.callee() instanceof Ast.NameExpr name
                        && name.name().equals("$failure$recover")) found[0] = true;
            });
            return found[0];
        }
        boolean[] found = {false};
        children(node, child -> { if (!found[0]) found[0] = hasRecoveringDefer(child); });
        return found[0];
    }

    private static String keywords(Set<Ast.FailureEffect> effects) {
        return effects.stream().sorted().map(e -> e.name().toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.joining(" "));
    }

    private static void add(Map<String, Definition> definitions, String owner, String name, List<Ast.Param> params,
                            Set<Ast.FailureEffect> declared, List<Ast.Stmt> body, boolean trapped) {
        Set<String> locals = new HashSet<>();
        params.forEach(param -> locals.add(param.name()));
        visit(body, node -> {
            if (node instanceof Ast.BindingStmt binding) locals.add(binding.name());
            if (node instanceof Ast.Param param) locals.add(param.name());
            if (node instanceof Ast.DestructureBinding binding) locals.add(binding.name());
            if (node instanceof Ast.TryStmt attempted) locals.add(attempted.errorName());
            if (node instanceof Ast.ForOfStmt loop) locals.add(loop.bindingName());
        });
        String symbol = owner + "." + name + "/" + params.size();
        if (definitions.putIfAbsent(symbol, new Definition(symbol, owner, declared, body, trapped, locals)) != null) {
            throw new IllegalArgumentException("duplicate failure analysis symbol: " + symbol);
        }
    }

    private static void walk(Object node, Definition def, Summary summary, Map<String, Definition> definitions, boolean caught) {
        if (node == null) return;
        if (node instanceof Ast.TrapExpr trapped) {
            walk(trapped.expression(), def, summary, definitions, true);
            return;
        }
        if (node instanceof Ast.NewExpr) summary.unknown = true;
        if (node instanceof Ast.LambdaExpr lambda) {
            // A closure can run outside the catch surrounding its construction.
            walk(lambda.expressionBody(), def, summary, definitions, false);
            walk(lambda.blockBody(), def, summary, definitions, false);
            return;
        }
        if (node instanceof Ast.TryStmt attempted) {
            walk(attempted.body(), def, summary, definitions, true);
            walk(attempted.catchBody(), def, summary, definitions, caught);
            walk(attempted.finallyBody(), def, summary, definitions, caught);
            return;
        }
        if (node instanceof Ast.CallExpr call) {
            if (call.callee() instanceof Ast.NameExpr name && name.name().startsWith("$failure$")) {
                Ast.FailureEffect effect = switch (name.name()) {
                    case "$failure$throw" -> Ast.FailureEffect.THROWS;
                    case "$failure$raise" -> Ast.FailureEffect.RAISES;
                    case "$failure$panic" -> Ast.FailureEffect.PANICS;
                    default -> null;
                };
                if (effect != null && !(effect == Ast.FailureEffect.THROWS && caught)) summary.effects.add(effect);
            } else {
                String target = null;
                if (call.callee() instanceof Ast.NameExpr name && !def.locals().contains(name.name())) {
                    target = def.module() + "." + name.name() + "/" + call.arguments().size();
                }
                if (call.arguments().stream().anyMatch(Ast.SpreadExpr.class::isInstance)) target = null;
                if (target != null && definitions.containsKey(target)) summary.edges.add(new Edge(target, caught, call.uncheckedEffects()));
                else summary.unknown = true;
            }
        }
        children(node, child -> walk(child, def, summary, definitions, caught));
    }

    // Records make this traversal exhaustive as expression/statement shapes grow.
    // Declarations are deliberately excluded: closures are conservatively analyzed in their owner.
    private static void visit(Object node, Consumer<Object> consumer) {
        if (node == null) return;
        consumer.accept(node);
        children(node, child -> visit(child, consumer));
    }
    private static void children(Object node, Consumer<Object> consumer) {
        if (node instanceof Iterable<?> items) { items.forEach(consumer); return; }
        if (node.getClass().getEnclosingClass() != Ast.class || !node.getClass().isRecord()
                || node instanceof Ast.Decl || node instanceof Ast.Program) return;
        for (var component : node.getClass().getRecordComponents()) {
            try { consumer.accept(component.getAccessor().invoke(node)); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException("cannot traverse AST", failure); }
        }
    }
}
