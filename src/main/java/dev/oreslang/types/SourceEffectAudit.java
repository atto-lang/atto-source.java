package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Conservative opt-in AST effect audit AFTER the ordinary source type and
 * ownership checks. It makes no claim of certified HIR-level call resolution.
 *
 * A zero effect row here is limited to an intentionally tiny syntactic subset.
 * Calls, operators, ambient names and unclassified AST nodes fail closed.
 * Effects in deferred/lambda bodies are included eagerly as an overapproximation;
 * this is not yet a latent-effect type system.
 */
public final class SourceEffectAudit {
    private SourceEffectAudit() { }

    private static final int MAX_VISITS = 100_000;
    private static final int MAX_DEPTH = 128;
    private static final int MAX_DECLARATIONS = 1024;

    public record Report(Map<String, Set<EffectContracts.Effect>> functions,
                         List<String> unsupportedDeclarations) {
        public Report {
            Map<String, Set<EffectContracts.Effect>> snapshot = new LinkedHashMap<>();
            functions.forEach((key, value) -> snapshot.put(key, Set.copyOf(value)));
            functions = Collections.unmodifiableMap(snapshot);
            unsupportedDeclarations = List.copyOf(unsupportedDeclarations);
        }

        /** Never a substitute for signed codegen/effect receipts. */
        public boolean conservativelyEffectFree() {
            return unsupportedDeclarations.isEmpty()
                    && functions.values().stream().allMatch(Set::isEmpty);
        }
    }

    /** Do not accept an unchecked user-provided AST as a trusted source fact. */
    public static Report inspect(String source) {
        return inspectChecked(OresCompiler.parseAndTypeCheck(source));
    }

    private static Report inspectChecked(Ast.Program checked) {
        Map<String, Set<EffectContracts.Effect>> functions = new LinkedHashMap<>();
        List<String> unsupported = new ArrayList<>();

        // Imported functions and ambient module initializers are not yet
        // authenticated or sealed against future changes.
        if (!checked.imports().isEmpty()) {
            unsupported.add("imports require verified effect metadata");
        }
        if (checked.modules().isEmpty()) {
            unsupported.add("empty source cannot establish a compilation unit effect proof");
        }
        if (checked.modules().size() > MAX_DECLARATIONS) {
            throw new IllegalArgumentException("effect module budget exceeded");
        }
        VisitBudget budget = new VisitBudget();
        int declarations = 0;
        for (Ast.ModuleDecl module : checked.modules()) {
            if (module.declarations().size() > MAX_DECLARATIONS - declarations) {
                throw new IllegalArgumentException("effect declaration budget exceeded");
            }
            declarations += module.declarations().size();
            if (!module.annotations().isEmpty() || !module.contracts().isEmpty()) {
                unsupported.add(module.name() + ": module annotations/contracts need effect elaboration");
            }
            if (module.declarations().isEmpty()) {
                unsupported.add(module.name() + ": no analyzable declarations");
            }
            for (Ast.Decl decl : module.declarations()) {
                if (!(decl instanceof Ast.FunctionDecl fn)) {
                    unsupported.add(module.name() + ": unclassified "
                            + decl.getClass().getSimpleName());
                    continue;
                }
                String symbol = module.name() + "." + fn.name() + "/"
                        + fn.parameters().size() + ":" + fn.kind();
                EnumSet<EffectContracts.Effect> direct =
                        EnumSet.noneOf(EffectContracts.Effect.class);
                if (fn.async() || fn.suspends() || fn.generator()) {
                    direct.add(EffectContracts.Effect.SCHEDULER);
                }
                if (!fn.annotations().isEmpty() || fn.kind() != Ast.CallableKind.FNC) {
                    // Annotation and routine semantics are not yet certified
                    // by the closed-subset effect elaborator.
                    direct.add(EffectContracts.Effect.UNKNOWN_CALL);
                }
                if (fn.actorKind() != Ast.ActorKind.NONE) {
                    direct.add(EffectContracts.Effect.ACTOR);
                    direct.add(EffectContracts.Effect.UNKNOWN_CALL);
                }
                // Callable-level trap metadata is not a verified handler proof.
                // Never use an annotation to subtract ordinary THROW here.
                if (fn.trapped()) direct.add(EffectContracts.Effect.UNKNOWN_CALL);
                Walker walker = new Walker(direct, budget);
                walker.walk(fn.body(), 0);
                if (functions.putIfAbsent(symbol, Set.copyOf(direct)) != null) {
                    throw new IllegalArgumentException("effect symbol collision: " + symbol);
                }
            }
        }
        return new Report(functions, unsupported);
    }

    private static final class VisitBudget {
        private int visits;
    }

    private static final class Walker {
        private final EnumSet<EffectContracts.Effect> direct;
        private final VisitBudget budget;

        private Walker(EnumSet<EffectContracts.Effect> direct, VisitBudget budget) {
            this.direct = direct;
            this.budget = budget;
        }

        private void walk(Object node, int depth) {
            if (node == null) return;
            if (++budget.visits > MAX_VISITS || depth > MAX_DEPTH) {
                throw new IllegalArgumentException("effect AST traversal budget exceeded");
            }
            if (node instanceof List<?> nodes) {
                for (Object child : nodes) walk(child, depth + 1);
                return;
            }
            if (node instanceof Ast.LiteralExpr) return;

            // No symbolic-name resolution occurs in this audit. An unrelated
            // declaration with the same spelling cannot launder a callback.
            if (node instanceof Ast.CallExpr
                    || node instanceof Ast.RuntimeCallExpr
                    || node instanceof Ast.NewExpr
                    || node instanceof Ast.BinaryExpr
                    || node instanceof Ast.UnaryExpr
                    || node instanceof Ast.LambdaExpr
                    || node instanceof Ast.MatchExpr
                    || node instanceof Ast.PatternTestExpr
                    || node instanceof Ast.TypeTestExpr) {
                direct.add(EffectContracts.Effect.UNKNOWN_CALL);
            }
            if (node instanceof Ast.RuntimeCallExpr) {
                direct.add(EffectContracts.Effect.FFI_UNKNOWN);
            }
            if (node instanceof Ast.NameExpr
                    || node instanceof Ast.MemberExpr
                    || node instanceof Ast.IndexExpr) {
                direct.add(EffectContracts.Effect.AMBIENT_READ);
            }
            if (node instanceof Ast.MemberExpr || node instanceof Ast.IndexExpr) {
                direct.add(EffectContracts.Effect.UNKNOWN_CALL);
            }
            if (node instanceof Ast.AssignExpr) {
                direct.add(EffectContracts.Effect.EXTERNAL_WRITE);
            }
            if (node instanceof Ast.AwaitExpr
                    || node instanceof Ast.ChannelOpExpr
                    || node instanceof Ast.DynamicSelectExpr
                    || node instanceof Ast.SelectStmt) {
                direct.add(EffectContracts.Effect.SCHEDULER);
                direct.add(EffectContracts.Effect.SYNCHRONIZATION);
                direct.add(EffectContracts.Effect.SUSPEND);
            }
            if (node instanceof Ast.ChannelOpExpr
                    || node instanceof Ast.DynamicSelectExpr
                    || node instanceof Ast.SelectStmt) {
                direct.add(EffectContracts.Effect.ACTOR);
            }
            if ((node instanceof Ast.ChannelOpExpr op
                        && op.mode() == Ast.WaitMode.BLOCKING)
                    || (node instanceof Ast.DynamicSelectExpr op
                        && op.mode() == Ast.WaitMode.BLOCKING)
                    || (node instanceof Ast.SelectStmt op
                        && op.mode() == Ast.WaitMode.BLOCKING)) {
                direct.add(EffectContracts.Effect.BLOCK);
            }
            if (node instanceof Ast.DynamicSelectExpr) {
                direct.add(EffectContracts.Effect.UNKNOWN_CALL);
            }
            if (node instanceof Ast.YieldStmt) {
                // Yield/cooperate is a scheduling boundary even if it yields a
                // constant; never certify a generator's body as effect-free.
                direct.add(EffectContracts.Effect.SCHEDULER);
                direct.add(EffectContracts.Effect.SUSPEND);
            }
            if (node instanceof Ast.CastExpr || node instanceof Ast.SpreadExpr) {
                // Conversion, iteration and generic spreads may invoke
                // compiler hooks or user-defined protocol implementations.
                direct.add(EffectContracts.Effect.UNKNOWN_CALL);
            }

            // Never silently treat a newly introduced syntax node as pure.
            if (node instanceof Ast.Expr expr
                    && !(expr instanceof Ast.NameExpr
                      || expr instanceof Ast.BinaryExpr
                      || expr instanceof Ast.UnaryExpr
                      || expr instanceof Ast.CallExpr
                      || expr instanceof Ast.RuntimeCallExpr
                      || expr instanceof Ast.NewExpr
                      || expr instanceof Ast.MemberExpr
                      || expr instanceof Ast.IndexExpr
                      || expr instanceof Ast.AssignExpr
                      || expr instanceof Ast.AwaitExpr
                      || expr instanceof Ast.ChannelOpExpr
                      || expr instanceof Ast.DynamicSelectExpr
                      || expr instanceof Ast.LambdaExpr
                      || expr instanceof Ast.MatchExpr
                      || expr instanceof Ast.PatternTestExpr
                      || expr instanceof Ast.TypeTestExpr
                      || expr instanceof Ast.TrapExpr
                      || expr instanceof Ast.CastExpr
                      || expr instanceof Ast.ConditionalExpr
                      || expr instanceof Ast.ListExpr
                      || expr instanceof Ast.TupleExpr
                      || expr instanceof Ast.ObjectExpr
                      || expr instanceof Ast.SpreadExpr)) {
                direct.add(EffectContracts.Effect.UNKNOWN_CALL);
            }
            if (node instanceof Ast.Stmt stmt
                    && !(stmt instanceof Ast.BindingStmt
                      || stmt instanceof Ast.DestructureStmt
                      || stmt instanceof Ast.ReturnStmt
                      || stmt instanceof Ast.ExprStmt
                      || stmt instanceof Ast.DeferStmt
                      || stmt instanceof Ast.BlockStmt
                      || stmt instanceof Ast.BreakStmt
                      || stmt instanceof Ast.ContinueStmt
                      || stmt instanceof Ast.IfStmt
                      || stmt instanceof Ast.TryStmt
                      || stmt instanceof Ast.YieldStmt)) {
                // Match, loops, selects and all future statements may execute
                // implicit protocol hooks, backpressure or user code.
                direct.add(EffectContracts.Effect.UNKNOWN_CALL);
            }
            // Visit every embedded expression in deferred, try/finally and
            // handler forms. A trap never automatically discharges ambient
            // effects and a finally does not inherit an exception waiver.
            if (!node.getClass().isRecord()
                    || !node.getClass().getName().startsWith("dev.oreslang.ast.Ast$")) {
                return;
            }
            // Record traversal is used only to conservatively find nested
            // execution sites; metadata TypeRefs/enums never authorize effects.
            for (RecordComponent component : node.getClass().getRecordComponents()) {
                try {
                    Object child = component.getAccessor().invoke(node);
                    if (child instanceof Ast.Expr || child instanceof Ast.Stmt
                            || child instanceof List<?> || child instanceof Record) {
                        walk(child, depth + 1);
                    }
                } catch (ReflectiveOperationException | SecurityException error) {
                    direct.add(EffectContracts.Effect.UNKNOWN_CALL);
                }
            }
        }
    }
}
