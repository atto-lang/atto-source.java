package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Suspension-aware liveness analysis for compiler-lowered async callables.
 *
 * <p>The source evaluator currently builds functional AsyncPlan graphs. This
 * pass is deliberately independent of that evaluator: it computes the compact
 * frame layout a production state-machine lowering needs. Only lexical values
 * that are live after at least one await receive persistent spill slots.
 * Expression temporaries that must survive an await are counted separately so
 * generated frames can reuse a small temporary spill bank.</p>
 *
 * <p>At every suspension point the result also identifies spill slots that are
 * dead for that suspension. Generated code should clear reference-valued dead
 * slots before returning control to the scheduler. That prevents a suspended,
 * long-lived continuation from retaining dead object graphs.</p>
 *
 * <p>Bindings are keyed by source name for now because the current AST does not
 * carry resolved lexical-binding identities. Shadowed names are therefore
 * conservatively coalesced into one spill slot. That can over-spill but cannot
 * make the generated frame unsound; a later name-resolution IR can replace the
 * key with stable binding IDs without changing this API's semantics.</p>
 */
public final class AsyncSuspensionAnalysis {
    private AsyncSuspensionAnalysis() { }

    public record SpillSlot(
            int index,
            String name,
            Ast.TypeRef declaredType,
            boolean referenceLike) {
        public SpillSlot {
            if (index < 0) throw new IllegalArgumentException("spill slot index must be non-negative");
            Objects.requireNonNull(name, "name");
        }
    }

    public record SuspensionPoint(
            int pc,
            Ast.AwaitExpr await,
            Set<String> liveLocals,
            List<Integer> liveSpillSlots,
            List<Integer> clearSpillSlots,
            int temporarySpillCount) {
        public SuspensionPoint {
            if (pc < 0) throw new IllegalArgumentException("pc must be non-negative");
            Objects.requireNonNull(await, "await");
            liveLocals = Set.copyOf(liveLocals);
            liveSpillSlots = List.copyOf(liveSpillSlots);
            clearSpillSlots = List.copyOf(clearSpillSlots);
            if (temporarySpillCount < 0) {
                throw new IllegalArgumentException("temporary spill count must be non-negative");
            }
        }
    }

    public record Result(
            List<SpillSlot> spillSlots,
            int temporarySpillSlots,
            List<SuspensionPoint> suspensionPoints) {
        public Result {
            spillSlots = List.copyOf(spillSlots);
            suspensionPoints = List.copyOf(suspensionPoints);
            if (temporarySpillSlots < 0) {
                throw new IllegalArgumentException("temporary spill count must be non-negative");
            }
        }

        /** Persistent lexical slots plus the reusable expression-temporary bank. */
        public int totalPersistentSlots() {
            return spillSlots.size() + temporarySpillSlots;
        }

        public Set<String> spilledLocalNames() {
            LinkedHashSet<String> names = new LinkedHashSet<>();
            for (SpillSlot slot : spillSlots) names.add(slot.name());
            return Set.copyOf(names);
        }
    }

    public static Result analyze(Ast.FunctionDecl function) {
        Objects.requireNonNull(function, "function");
        Analyzer analyzer = new Analyzer();
        for (Ast.Param parameter : function.parameters()) {
            analyzer.declare(parameter.name(), parameter.type());
        }
        analyzer.collectDeclarations(function.body());
        analyzer.collectSuspensions(function.body());
        analyzer.liveStatements(function.body(), Set.of());
        return analyzer.finish();
    }

    public static Result analyze(Ast.MethodDecl method) {
        Objects.requireNonNull(method, "method");
        Analyzer analyzer = new Analyzer();
        if (!method.isStatic()) analyzer.declare("self", null);
        for (Ast.Param parameter : method.parameters()) {
            analyzer.declare(parameter.name(), parameter.type());
        }
        analyzer.collectDeclarations(method.body());
        analyzer.collectSuspensions(method.body());
        analyzer.liveStatements(method.body(), Set.of());
        return analyzer.finish();
    }

    public static Result analyze(Ast.LambdaExpr lambda) {
        Objects.requireNonNull(lambda, "lambda");
        Analyzer analyzer = new Analyzer();
        for (Ast.Param parameter : lambda.parameters()) {
            analyzer.declare(parameter.name(), parameter.type());
        }
        if (lambda.blockBody() != null) {
            analyzer.collectDeclarations(lambda.blockBody());
            analyzer.collectSuspensions(lambda.blockBody());
            analyzer.liveStatements(lambda.blockBody(), Set.of());
        } else if (lambda.expressionBody() != null) {
            analyzer.collectSuspensions(lambda.expressionBody(), 0);
            analyzer.liveExpression(lambda.expressionBody(), Set.of());
        }
        return analyzer.finish();
    }

    private static final class Analyzer {
        private static final int MAX_FIXED_POINT_ITERATIONS = 256;

        private final LinkedHashMap<String, Ast.TypeRef> locals = new LinkedHashMap<>();
        private final IdentityHashMap<Ast.AwaitExpr, LinkedHashSet<String>> liveAtAwait =
                new IdentityHashMap<>();
        private final IdentityHashMap<Ast.AwaitExpr, Integer> tempsAtAwait =
                new IdentityHashMap<>();
        private final ArrayList<Ast.AwaitExpr> awaitOrder = new ArrayList<>();

        private void declare(String name, Ast.TypeRef type) {
            if (name == null || name.isBlank() || "_".equals(name)) return;
            locals.putIfAbsent(name, type);
        }

        private void collectDeclarations(List<Ast.Stmt> statements) {
            for (Ast.Stmt statement : statements) collectDeclarations(statement);
        }

        private void collectDeclarations(Ast.Stmt statement) {
            if (statement instanceof Ast.BindingStmt binding) {
                declare(binding.name(), binding.declaredType());
                return;
            }
            if (statement instanceof Ast.DestructureStmt destructure) {
                for (Ast.DestructureBinding binding : destructure.bindings()) {
                    if (!binding.isDiscard()) declare(binding.name(), null);
                }
                return;
            }
            if (statement instanceof Ast.IfStmt conditional) {
                for (Ast.IfBranch branch : conditional.branches()) {
                    collectDeclarations(branch.body());
                }
                collectDeclarations(conditional.elseBody());
                return;
            }
            if (statement instanceof Ast.TryStmt tried) {
                collectDeclarations(tried.body());
                declare(tried.errorName(), null);
                collectDeclarations(tried.catchBody());
                collectDeclarations(tried.finallyBody());
                return;
            }
            if (statement instanceof Ast.ForOfStmt loop) {
                declare(loop.bindingName(), null);
                collectDeclarations(loop.body());
                return;
            }
            if (statement instanceof Ast.ForStmt loop) {
                if (loop.initializer() != null) collectDeclarations(loop.initializer());
                collectDeclarations(loop.body());
            }
        }

        private void collectSuspensions(List<Ast.Stmt> statements) {
            for (Ast.Stmt statement : statements) collectSuspensions(statement);
        }

        private void collectSuspensions(Ast.Stmt statement) {
            if (statement instanceof Ast.BindingStmt binding) {
                collectSuspensions(binding.initializer(), 0);
                return;
            }
            if (statement instanceof Ast.DestructureStmt destructure) {
                collectSuspensions(destructure.initializer(), 0);
                return;
            }
            if (statement instanceof Ast.ReturnStmt returned) {
                if (returned.value() != null) collectSuspensions(returned.value(), 0);
                return;
            }
            if (statement instanceof Ast.ExprStmt expression) {
                collectSuspensions(expression.expression(), 0);
                return;
            }
            if (statement instanceof Ast.DeferStmt deferred) {
                collectSuspensions(deferred.expression(), 0);
                return;
            }
            if (statement instanceof Ast.IfStmt conditional) {
                for (Ast.IfBranch branch : conditional.branches()) {
                    collectSuspensions(branch.condition(), 0);
                    collectSuspensions(branch.body());
                }
                collectSuspensions(conditional.elseBody());
                return;
            }
            if (statement instanceof Ast.TryStmt tried) {
                collectSuspensions(tried.body());
                collectSuspensions(tried.catchBody());
                collectSuspensions(tried.finallyBody());
                return;
            }
            if (statement instanceof Ast.ForOfStmt loop) {
                collectSuspensions(loop.iterable(), 0);
                collectSuspensions(loop.body());
                return;
            }
            if (statement instanceof Ast.ForStmt loop) {
                if (loop.initializer() != null) collectSuspensions(loop.initializer());
                if (loop.condition() != null) collectSuspensions(loop.condition(), 0);
                collectSuspensions(loop.body());
                if (loop.update() != null) collectSuspensions(loop.update(), 0);
            }
        }

        /**
         * Forward evaluation-order walk used only to determine how many already
         * computed expression values must survive each await.
         */
        private void collectSuspensions(Ast.Expr expression, int heldTemporaries) {
            if (expression == null) return;

            if (expression instanceof Ast.AwaitExpr awaited) {
                collectSuspensions(awaited.expression(), heldTemporaries);
                rememberAwait(awaited, heldTemporaries);
                return;
            }
            if (expression instanceof Ast.NameExpr
                    || expression instanceof Ast.LiteralExpr
                    || expression instanceof Ast.LambdaExpr) {
                return;
            }
            if (expression instanceof Ast.UnaryExpr unary) {
                collectSuspensions(unary.operand(), heldTemporaries);
                return;
            }
            if (expression instanceof Ast.BinaryExpr binary) {
                collectSuspensions(binary.left(), heldTemporaries);
                collectSuspensions(binary.right(), heldTemporaries + 1);
                return;
            }
            if (expression instanceof Ast.ConditionalExpr conditional) {
                collectSuspensions(conditional.condition(), heldTemporaries);
                collectSuspensions(conditional.whenTrue(), heldTemporaries);
                collectSuspensions(conditional.whenFalse(), heldTemporaries);
                return;
            }
            if (expression instanceof Ast.AssignExpr assignment) {
                collectSuspensions(assignment.value(), heldTemporaries);
                // If an exotic assignable expression itself suspends, the
                // already-computed RHS must be retained while it is evaluated.
                collectSuspensions(assignment.target(), heldTemporaries + 1);
                return;
            }
            if (expression instanceof Ast.CallExpr call) {
                collectCallSuspensions(call, heldTemporaries);
                return;
            }
            if (expression instanceof Ast.MemberExpr member) {
                collectSuspensions(member.receiver(), heldTemporaries);
                return;
            }
            if (expression instanceof Ast.IndexExpr indexed) {
                collectSuspensions(indexed.receiver(), heldTemporaries);
                collectSuspensions(indexed.index(), heldTemporaries + 1);
                return;
            }
            if (expression instanceof Ast.NewExpr created) {
                for (int i = 0; i < created.arguments().size(); i++) {
                    collectSuspensions(created.arguments().get(i), heldTemporaries + i);
                }
                return;
            }
            if (expression instanceof Ast.SpawnExpr spawned) {
                collectCallSuspensions(spawned.call(), heldTemporaries);
                return;
            }
            if (expression instanceof Ast.ListExpr list) {
                for (int i = 0; i < list.elements().size(); i++) {
                    collectSuspensions(list.elements().get(i), heldTemporaries + i);
                }
                return;
            }
            if (expression instanceof Ast.TupleExpr tuple) {
                for (int i = 0; i < tuple.elements().size(); i++) {
                    collectSuspensions(tuple.elements().get(i), heldTemporaries + i);
                }
                return;
            }
            if (expression instanceof Ast.ObjectExpr object) {
                for (int i = 0; i < object.fields().size(); i++) {
                    collectSuspensions(object.fields().get(i).value(), heldTemporaries + i);
                }
                return;
            }

            throw new IllegalArgumentException(
                    "unsupported async liveness expression " + expression.getClass().getName());
        }

        private void collectCallSuspensions(Ast.CallExpr call, int heldTemporaries) {
            int heldByCallee;
            if (call.callee() instanceof Ast.NameExpr) {
                // Statically named call targets can be re-resolved after an
                // await; no runtime callable value must be spilled.
                heldByCallee = 0;
            } else if (call.callee() instanceof Ast.MemberExpr member) {
                // Member calls retain only the evaluated receiver across later
                // argument evaluation.
                collectSuspensions(member.receiver(), heldTemporaries);
                heldByCallee = 1;
            } else {
                collectSuspensions(call.callee(), heldTemporaries);
                heldByCallee = 1;
            }

            for (int i = 0; i < call.arguments().size(); i++) {
                collectSuspensions(
                        call.arguments().get(i),
                        heldTemporaries + heldByCallee + i);
            }
        }

        private void rememberAwait(Ast.AwaitExpr awaited, int heldTemporaries) {
            if (!tempsAtAwait.containsKey(awaited)) {
                awaitOrder.add(awaited);
                tempsAtAwait.put(awaited, heldTemporaries);
            } else {
                tempsAtAwait.merge(awaited, heldTemporaries, Math::max);
            }
        }

        private Set<String> liveStatements(List<Ast.Stmt> statements, Set<String> liveAfter) {
            LinkedHashSet<String> live = copy(liveAfter);

            // Defer executes at lexical-scope exit, so values referenced by any
            // deferred expression must remain live across intervening awaits.
            // Until defer is represented explicitly in CFG IR, keeping these
            // names live for the whole block is conservative and GC-safe.
            for (Ast.Stmt statement : statements) {
                if (statement instanceof Ast.DeferStmt deferred) {
                    live.addAll(freeNames(deferred.expression()));
                }
            }

            for (int i = statements.size() - 1; i >= 0; i--) {
                live = copy(liveStatement(statements.get(i), live));
            }
            return Set.copyOf(live);
        }

        private Set<String> liveStatement(Ast.Stmt statement, Set<String> liveAfter) {
            if (statement instanceof Ast.BindingStmt binding) {
                LinkedHashSet<String> beforeInit = copy(liveAfter);
                beforeInit.remove(binding.name());
                return liveExpression(binding.initializer(), beforeInit);
            }
            if (statement instanceof Ast.DestructureStmt destructure) {
                LinkedHashSet<String> beforeInit = copy(liveAfter);
                for (Ast.DestructureBinding binding : destructure.bindings()) {
                    if (!binding.isDiscard()) beforeInit.remove(binding.name());
                }
                return liveExpression(destructure.initializer(), beforeInit);
            }
            if (statement instanceof Ast.ReturnStmt returned) {
                return returned.value() == null
                        ? Set.of()
                        : liveExpression(returned.value(), Set.of());
            }
            if (statement instanceof Ast.ExprStmt expression) {
                return liveExpression(expression.expression(), liveAfter);
            }
            if (statement instanceof Ast.DeferStmt deferred) {
                LinkedHashSet<String> live = copy(liveAfter);
                live.addAll(freeNames(deferred.expression()));
                // Analyze the deferred expression too so awaits inside defer
                // receive frame metadata. The surrounding live set is
                // conservative because exact defer-stack ordering belongs in
                // the future CFG lowering.
                return liveExpression(deferred.expression(), live);
            }
            if (statement instanceof Ast.IfStmt conditional) {
                LinkedHashSet<String> alternatives = new LinkedHashSet<>();
                for (Ast.IfBranch branch : conditional.branches()) {
                    alternatives.addAll(liveStatements(branch.body(), liveAfter));
                }
                alternatives.addAll(liveStatements(conditional.elseBody(), liveAfter));

                Set<String> before = alternatives;
                for (int i = conditional.branches().size() - 1; i >= 0; i--) {
                    before = liveExpression(
                            conditional.branches().get(i).condition(),
                            before);
                }
                return before;
            }
            if (statement instanceof Ast.TryStmt tried) {
                Set<String> beforeFinally =
                        liveStatements(tried.finallyBody(), liveAfter);
                Set<String> beforeBody =
                        liveStatements(tried.body(), beforeFinally);
                LinkedHashSet<String> beforeCatch =
                        copy(liveStatements(tried.catchBody(), beforeFinally));
                beforeCatch.remove(tried.errorName());
                return union(beforeBody, beforeCatch);
            }
            if (statement instanceof Ast.ForOfStmt loop) {
                Set<String> head = Set.copyOf(liveAfter);
                for (int iteration = 0; iteration < MAX_FIXED_POINT_ITERATIONS; iteration++) {
                    LinkedHashSet<String> beforeBody =
                            copy(liveStatements(loop.body(), head));
                    beforeBody.remove(loop.bindingName());
                    Set<String> next = union(liveAfter, beforeBody);
                    if (next.equals(head)) {
                        return liveExpression(loop.iterable(), next);
                    }
                    head = next;
                }
                throw new IllegalStateException("for-of async liveness did not converge");
            }
            if (statement instanceof Ast.ForStmt loop) {
                Set<String> head = Set.copyOf(liveAfter);
                for (int iteration = 0; iteration < MAX_FIXED_POINT_ITERATIONS; iteration++) {
                    Set<String> beforeUpdate = loop.update() == null
                            ? head
                            : liveExpression(loop.update(), head);
                    Set<String> beforeBody =
                            liveStatements(loop.body(), beforeUpdate);
                    Set<String> conditionAfter = union(liveAfter, beforeBody);
                    Set<String> next = loop.condition() == null
                            ? conditionAfter
                            : liveExpression(loop.condition(), conditionAfter);
                    if (next.equals(head)) {
                        return loop.initializer() == null
                                ? next
                                : liveStatement(loop.initializer(), next);
                    }
                    head = next;
                }
                throw new IllegalStateException("for-loop async liveness did not converge");
            }

            throw new IllegalArgumentException(
                    "unsupported async liveness statement " + statement.getClass().getName());
        }

        private Set<String> liveExpression(Ast.Expr expression, Set<String> liveAfter) {
            if (expression == null) return Set.copyOf(liveAfter);

            if (expression instanceof Ast.LiteralExpr) {
                return Set.copyOf(liveAfter);
            }
            if (expression instanceof Ast.NameExpr name) {
                LinkedHashSet<String> live = copy(liveAfter);
                if (locals.containsKey(name.name())) live.add(name.name());
                return Set.copyOf(live);
            }
            if (expression instanceof Ast.AwaitExpr awaited) {
                recordLive(awaited, liveAfter);
                return liveExpression(awaited.expression(), liveAfter);
            }
            if (expression instanceof Ast.UnaryExpr unary) {
                return liveExpression(unary.operand(), liveAfter);
            }
            if (expression instanceof Ast.BinaryExpr binary) {
                Set<String> beforeRight = liveExpression(binary.right(), liveAfter);
                return liveExpression(binary.left(), beforeRight);
            }
            if (expression instanceof Ast.ConditionalExpr conditional) {
                Set<String> trueLive =
                        liveExpression(conditional.whenTrue(), liveAfter);
                Set<String> falseLive =
                        liveExpression(conditional.whenFalse(), liveAfter);
                return liveExpression(
                        conditional.condition(),
                        union(trueLive, falseLive));
            }
            if (expression instanceof Ast.AssignExpr assignment) {
                if (assignment.target() instanceof Ast.NameExpr name
                        && locals.containsKey(name.name())) {
                    LinkedHashSet<String> beforeValue = copy(liveAfter);
                    beforeValue.remove(name.name());
                    return liveExpression(assignment.value(), beforeValue);
                }

                // The current evaluator computes the RHS before applying a
                // member/index assignment. Keep target dependencies live while
                // the RHS (and any await inside it) is evaluated.
                Set<String> beforeTarget =
                        liveExpression(assignment.target(), liveAfter);
                return liveExpression(assignment.value(), beforeTarget);
            }
            if (expression instanceof Ast.CallExpr call) {
                Set<String> live = Set.copyOf(liveAfter);
                for (int i = call.arguments().size() - 1; i >= 0; i--) {
                    live = liveExpression(call.arguments().get(i), live);
                }
                return liveExpression(call.callee(), live);
            }
            if (expression instanceof Ast.MemberExpr member) {
                return liveExpression(member.receiver(), liveAfter);
            }
            if (expression instanceof Ast.IndexExpr indexed) {
                Set<String> beforeIndex =
                        liveExpression(indexed.index(), liveAfter);
                return liveExpression(indexed.receiver(), beforeIndex);
            }
            if (expression instanceof Ast.NewExpr created) {
                Set<String> live = Set.copyOf(liveAfter);
                for (int i = created.arguments().size() - 1; i >= 0; i--) {
                    live = liveExpression(created.arguments().get(i), live);
                }
                return live;
            }
            if (expression instanceof Ast.SpawnExpr spawned) {
                return liveExpression(spawned.call(), liveAfter);
            }
            if (expression instanceof Ast.ListExpr list) {
                Set<String> live = Set.copyOf(liveAfter);
                for (int i = list.elements().size() - 1; i >= 0; i--) {
                    live = liveExpression(list.elements().get(i), live);
                }
                return live;
            }
            if (expression instanceof Ast.TupleExpr tuple) {
                Set<String> live = Set.copyOf(liveAfter);
                for (int i = tuple.elements().size() - 1; i >= 0; i--) {
                    live = liveExpression(tuple.elements().get(i), live);
                }
                return live;
            }
            if (expression instanceof Ast.ObjectExpr object) {
                Set<String> live = Set.copyOf(liveAfter);
                for (int i = object.fields().size() - 1; i >= 0; i--) {
                    live = liveExpression(object.fields().get(i).value(), live);
                }
                return live;
            }
            if (expression instanceof Ast.LambdaExpr) {
                // Closure conversion owns captured values. The lambda object,
                // if it crosses an await, is represented either by a lexical
                // spill or an expression temporary spill.
                return Set.copyOf(liveAfter);
            }

            throw new IllegalArgumentException(
                    "unsupported async liveness expression " + expression.getClass().getName());
        }

        private void recordLive(Ast.AwaitExpr awaited, Set<String> liveAfter) {
            LinkedHashSet<String> live = liveAtAwait.computeIfAbsent(
                    awaited,
                    ignored -> new LinkedHashSet<>());
            for (String name : liveAfter) {
                if (locals.containsKey(name)) live.add(name);
            }
            // Programmatically-constructed ASTs can reach the backward pass
            // without the forward temporary walk.
            if (!tempsAtAwait.containsKey(awaited)) rememberAwait(awaited, 0);
        }

        private Set<String> freeNames(Ast.Expr expression) {
            LinkedHashSet<String> names = new LinkedHashSet<>();
            collectFreeNames(expression, names);
            names.retainAll(locals.keySet());
            return Set.copyOf(names);
        }

        private void collectFreeNames(Ast.Expr expression, Set<String> names) {
            if (expression == null) return;
            if (expression instanceof Ast.NameExpr name) {
                names.add(name.name());
                return;
            }
            if (expression instanceof Ast.LiteralExpr
                    || expression instanceof Ast.LambdaExpr) {
                return;
            }
            if (expression instanceof Ast.AwaitExpr awaited) {
                collectFreeNames(awaited.expression(), names);
                return;
            }
            if (expression instanceof Ast.UnaryExpr unary) {
                collectFreeNames(unary.operand(), names);
                return;
            }
            if (expression instanceof Ast.BinaryExpr binary) {
                collectFreeNames(binary.left(), names);
                collectFreeNames(binary.right(), names);
                return;
            }
            if (expression instanceof Ast.ConditionalExpr conditional) {
                collectFreeNames(conditional.condition(), names);
                collectFreeNames(conditional.whenTrue(), names);
                collectFreeNames(conditional.whenFalse(), names);
                return;
            }
            if (expression instanceof Ast.AssignExpr assignment) {
                collectFreeNames(assignment.target(), names);
                collectFreeNames(assignment.value(), names);
                return;
            }
            if (expression instanceof Ast.CallExpr call) {
                collectFreeNames(call.callee(), names);
                for (Ast.Expr argument : call.arguments()) collectFreeNames(argument, names);
                return;
            }
            if (expression instanceof Ast.MemberExpr member) {
                collectFreeNames(member.receiver(), names);
                return;
            }
            if (expression instanceof Ast.IndexExpr indexed) {
                collectFreeNames(indexed.receiver(), names);
                collectFreeNames(indexed.index(), names);
                return;
            }
            if (expression instanceof Ast.NewExpr created) {
                for (Ast.Expr argument : created.arguments()) collectFreeNames(argument, names);
                return;
            }
            if (expression instanceof Ast.SpawnExpr spawned) {
                collectFreeNames(spawned.call(), names);
                return;
            }
            if (expression instanceof Ast.ListExpr list) {
                for (Ast.Expr element : list.elements()) collectFreeNames(element, names);
                return;
            }
            if (expression instanceof Ast.TupleExpr tuple) {
                for (Ast.Expr element : tuple.elements()) collectFreeNames(element, names);
                return;
            }
            if (expression instanceof Ast.ObjectExpr object) {
                for (Ast.ObjectField field : object.fields()) collectFreeNames(field.value(), names);
                return;
            }

            throw new IllegalArgumentException(
                    "unsupported async free-name expression " + expression.getClass().getName());
        }

        private Result finish() {
            LinkedHashSet<String> spilledNames = new LinkedHashSet<>();
            for (Ast.AwaitExpr awaited : awaitOrder) {
                Set<String> live = liveAtAwait.get(awaited);
                if (live != null) spilledNames.addAll(live);
            }

            ArrayList<SpillSlot> slots = new ArrayList<>();
            LinkedHashMap<String, Integer> slotByName = new LinkedHashMap<>();
            for (Map.Entry<String, Ast.TypeRef> local : locals.entrySet()) {
                if (!spilledNames.contains(local.getKey())) continue;
                int index = slots.size();
                slotByName.put(local.getKey(), index);
                slots.add(new SpillSlot(
                        index,
                        local.getKey(),
                        local.getValue(),
                        isReferenceLike(local.getValue())));
            }

            ArrayList<SuspensionPoint> points = new ArrayList<>(awaitOrder.size());
            int maxTemps = 0;
            for (int pc = 0; pc < awaitOrder.size(); pc++) {
                Ast.AwaitExpr awaited = awaitOrder.get(pc);
                Set<String> rawLive = liveAtAwait.getOrDefault(
                        awaited,
                        new LinkedHashSet<>());
                LinkedHashSet<String> liveNames = new LinkedHashSet<>();
                ArrayList<Integer> liveSlots = new ArrayList<>();
                ArrayList<Integer> clearSlots = new ArrayList<>();

                for (SpillSlot slot : slots) {
                    if (rawLive.contains(slot.name())) {
                        liveNames.add(slot.name());
                        liveSlots.add(slot.index());
                    } else if (slot.referenceLike()) {
                        clearSlots.add(slot.index());
                    }
                }

                int temporaryCount = tempsAtAwait.getOrDefault(awaited, 0);
                maxTemps = Math.max(maxTemps, temporaryCount);
                points.add(new SuspensionPoint(
                        pc,
                        awaited,
                        liveNames,
                        liveSlots,
                        clearSlots,
                        temporaryCount));
            }

            return new Result(slots, maxTemps, points);
        }

        private static boolean isReferenceLike(Ast.TypeRef type) {
            if (type == null) return true;
            return switch (type.name()) {
                case "bool", "boolean",
                        "byte", "short", "int", "long",
                        "float", "double", "char",
                        "i8", "i16", "i32", "i64",
                        "u8", "u16", "u32", "u64",
                        "f32", "f64" -> false;
                default -> true;
            };
        }

        private static LinkedHashSet<String> copy(Set<String> values) {
            return new LinkedHashSet<>(values);
        }

        private static Set<String> union(Set<String> left, Set<String> right) {
            LinkedHashSet<String> result = copy(left);
            result.addAll(right);
            return Set.copyOf(result);
        }
    }
}
