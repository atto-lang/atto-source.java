package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.types.EffectContracts;
import dev.oreslang.types.EffectContracts.Call;
import dev.oreslang.types.EffectContracts.Callable;
import dev.oreslang.types.EffectContracts.Effect;
import dev.oreslang.types.EffectContracts.Graph;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Narrow, fail-closed source-to-effect-graph integration for inline
 * Option/Result callbacks. The caller must FIRST run the real parser,
 * TypeChecker and OwnershipChecker; this is an optional compiler analysis
 * report, not a new source-level pure modifier or a runtime authorization.
 *
 * This first slice reports only top-level function parameter receivers and
 * straightforward return, binding and expression statements. Any unsupported
 * statement marks the whole report incomplete. Unresolved calls and captured
 * variables are UNKNOWN_CALL, never silently classified as pure.
 */
public final class FunctionalCallbackEffectAnalysisV1 {
    private FunctionalCallbackEffectAnalysisV1() { }

    private static final Set<String> COMBINATORS = Set.of(
            "map", "map_err", "and_then", "filter", "or_else",
            "ok_or_else", "unwrap_or_else");

    public record Site(String module, String function, String operation,
                       Set<Effect> inferredEffects, boolean eligibleForPure) {
        public Site {
            inferredEffects = Set.copyOf(inferredEffects);
        }
    }

    public record Report(List<Site> sites, boolean complete) {
        public Report {
            sites = List.copyOf(sites);
        }
    }

    static Report analyzeChecked(Ast.Program checked) {
        Objects.requireNonNull(checked, "checked AttoLang AST");
        List<Site> sites = new ArrayList<>();
        boolean complete = true;
        for (Ast.ModuleDecl module : checked.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (!(declaration instanceof Ast.FunctionDecl fn)) {
                    // Actor/method bodies are not included in the v1 coverage.
                    complete = false;
                    continue;
                }
                Walker walker = new Walker(module.name(), fn, sites);
                for (Ast.Stmt statement : fn.body()) {
                    walker.statement(statement);
                }
                complete &= walker.complete;
            }
        }
        return new Report(sites, complete);
    }

    private static final class Walker {
        private final String module;
        private final Ast.FunctionDecl function;
        private final List<Site> sites;
        private boolean complete = true;

        Walker(String module, Ast.FunctionDecl function, List<Site> sites) {
            this.module = module;
            this.function = function;
            this.sites = sites;
        }

        void statement(Ast.Stmt statement) {
            if (statement instanceof Ast.ReturnStmt ret) {
                if (ret.value() != null) expression(ret.value());
            } else if (statement instanceof Ast.BindingStmt binding) {
                if (binding.initializer() != null) expression(binding.initializer());
            } else if (statement instanceof Ast.ExprStmt value) {
                expression(value.expression());
            } else if (statement instanceof Ast.BlockStmt block) {
                for (Ast.Stmt next : block.body()) statement(next);
            } else {
                complete = false;
            }
        }

        void expression(Ast.Expr expression) {
            if (expression instanceof Ast.CallExpr call) {
                inspectCombinator(call);
                expression(call.callee());
                for (Ast.Expr arg : call.arguments()) {
                    // A lambda body is not invoked merely by being constructed.
                    if (!(arg instanceof Ast.LambdaExpr)) expression(arg);
                }
            } else if (expression instanceof Ast.MemberExpr member) {
                expression(member.receiver());
            } else if (expression instanceof Ast.BinaryExpr binary) {
                expression(binary.left());
                expression(binary.right());
            } else if (expression instanceof Ast.UnaryExpr unary) {
                expression(unary.operand());
            } else if (expression instanceof Ast.ConditionalExpr cond) {
                expression(cond.condition());
                expression(cond.whenTrue());
                expression(cond.whenFalse());
            } else if (expression instanceof Ast.AssignExpr assign) {
                expression(assign.target());
                expression(assign.value());
            } else if (expression instanceof Ast.LiteralExpr
                    || expression instanceof Ast.NameExpr
                    || expression instanceof Ast.LambdaExpr) {
                // Leaf value, not an invocation.
            } else {
                complete = false;
            }
        }

        private void inspectCombinator(Ast.CallExpr call) {
            if (!(call.callee() instanceof Ast.MemberExpr member)
                    || !COMBINATORS.contains(member.member())) {
                return;
            }
            if (call.arguments().size() != 1
                    || !(member.receiver() instanceof Ast.NameExpr)) {
                complete = false; // unexamined combinator shape, never a full scan
                return;
            }
            Ast.NameExpr receiver = (Ast.NameExpr) member.receiver();
            // The same syntax can name a user method. Classify only a
            // directly declared Option/Result parameter, checked by the
            // real frontend; a shadowed local binding invalidates coverage.
            Ast.Param parameter = function.parameters().stream()
                    .filter(p -> p.name().equals(receiver.name()))
                    .findFirst().orElse(null);
            if (parameter == null || parameter.type() == null
                    || !Set.of("Option", "Result").contains(parameter.type().name())
                    || shadows(function.body(), receiver.name())) {
                complete = false; // user-defined method or shadowed parameter
                return;
            }
            Set<Effect> callbackEffects =
                    inlineEffects(call.arguments().getFirst());
            Graph graph = new Graph();
            graph.add(Callable.pure("sum::combinator", Set.of(Effect.ALLOC),
                    List.of(Call.callback("callback"))));
            graph.add(Callable.inferred("sum::inline_callback",
                    callbackEffects, List.of()));
            // This is a real, per-invocation specialization of the
            // compiler's fail-closed higher-order effect graph.
            Set<Effect> inferred = graph.inferInvocation(
                    "sum::combinator", Map.of("callback", "sum::inline_callback"));
            sites.add(new Site(module, function.name(), member.member(),
                    inferred, EffectContracts.pureAllowance().containsAll(inferred)));
        }
    }

    /** Nested blocks cannot shadow a recognized formal sum receiver. */
    private static boolean shadows(List<Ast.Stmt> statements, String name) {
        for (Ast.Stmt statement : statements) {
            if (statement instanceof Ast.BindingStmt binding
                    && binding.name().equals(name)) return true;
            if (statement instanceof Ast.BlockStmt block
                    && shadows(block.body(), name)) return true;
        }
        return false;
    }

    private static Set<Effect> inlineEffects(Ast.Expr expr) {
        EnumSet<Effect> effects = EnumSet.noneOf(Effect.class);
        if (!(expr instanceof Ast.LambdaExpr lambda)
                || lambda.expressionBody() == null) {
            effects.add(Effect.UNKNOWN_CALL);
            return Set.copyOf(effects);
        }
        Set<String> parameters = new java.util.HashSet<>();
        for (Ast.Param p : lambda.parameters()) parameters.add(p.name());
        inspectExpression(lambda.expressionBody(), parameters, effects, 0);
        return Set.copyOf(effects);
    }

    /** Conservative expression classifier: no host reflection or guesses. */
    private static void inspectExpression(
            Ast.Expr expr, Set<String> locals, EnumSet<Effect> effects, int depth) {
        if (depth >= 64) {
            effects.add(Effect.UNKNOWN_CALL);
            return;
        }
        if (expr instanceof Ast.LiteralExpr) return;
        if (expr instanceof Ast.NameExpr name) {
            if (!locals.contains(name.name())) effects.add(Effect.UNKNOWN_CALL);
        } else if (expr instanceof Ast.BinaryExpr binary) {
            // Checked arithmetic, conversions and comparator failures are
            // permitted by observational purity, not assumed impossible.
            effects.add(Effect.THROW);
            inspectExpression(binary.left(), locals, effects, depth + 1);
            inspectExpression(binary.right(), locals, effects, depth + 1);
        } else if (expr instanceof Ast.UnaryExpr unary) {
            effects.add(Effect.THROW);
            inspectExpression(unary.operand(), locals, effects, depth + 1);
        } else if (expr instanceof Ast.ConditionalExpr conditional) {
            inspectExpression(conditional.condition(), locals, effects, depth + 1);
            inspectExpression(conditional.whenTrue(), locals, effects, depth + 1);
            inspectExpression(conditional.whenFalse(), locals, effects, depth + 1);
        } else if (expr instanceof Ast.CallExpr call) {
            if (call.callee() instanceof Ast.MemberExpr member
                    && member.receiver() instanceof Ast.NameExpr namespace
                    && namespace.name().equals("stdio")
                    && (member.member().equals("print")
                            || member.member().equals("println"))) {
                effects.add(Effect.IO);
            } else {
                // Direct calls need overload-resolved function effect
                // metadata, including imports and callback captures.
                effects.add(Effect.UNKNOWN_CALL);
            }
            for (Ast.Expr arg : call.arguments()) {
                inspectExpression(arg, locals, effects, depth + 1);
            }
        } else if (expr instanceof Ast.AssignExpr assign) {
            effects.add(Effect.EXTERNAL_WRITE); // local-vs-escaped proof pending
            inspectExpression(assign.target(), locals, effects, depth + 1);
            inspectExpression(assign.value(), locals, effects, depth + 1);
        } else if (expr instanceof Ast.AwaitExpr) {
            effects.add(Effect.SUSPEND);
        } else if (expr instanceof Ast.LambdaExpr) {
            effects.add(Effect.UNKNOWN_CALL); // captured closure not proven
        } else {
            effects.add(Effect.UNKNOWN_CALL);
        }
    }
}
