package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Verifies an intentionally narrow, literal-only named call by creating a
 * positional projection and invoking the REAL TypeChecker and OwnershipChecker.
 *
 * <p>The projection is not the original program and does NOT authorize a
 * staged call, named-call evaluation, Option<T> lowering, or backend execution.
 * It is evidence only that the equivalent direct positional scalar program
 * is accepted by existing semantic passes. Original AST admission remains
 * fail-closed.
 */
public final class NamedCallPositionalProjection {
    private NamedCallPositionalProjection() { }

    public static final class Result {
        private final LiteralNamedCallPreflight.Result original;
        private final int checkedScalarFormalCount;

        private Result(LiteralNamedCallPreflight.Result original, int count) {
            this.original = Objects.requireNonNull(original);
            if (count <= 0 || count != original.recipe().abiSlots().size()) {
                throw new IllegalArgumentException("invalid scalar projection width");
            }
            this.checkedScalarFormalCount = count;
        }

        public LiteralNamedCallPreflight.Result original() { return original; }
        public int checkedScalarFormalCount() { return checkedScalarFormalCount; }

        public void requireExecutable() {
            throw new IllegalStateException(
                    "checked positional projection is not named-call execution authority");
        }
    }

    /**
     * Each supplied argument must be a primitive literal of EXACTLY the
     * selected scalar formal type. No omissions, optional ABI, coercions,
     * generic arguments, rest, receiver dispatch, nonliteral values or effects
     * may be represented by this conservative projection.
     *
     * The resulting AST is checked with TypeChecker.check, including the
     * ownership pass, and is never substituted into the original program.
     */
    public static Result check(LiteralNamedCallPreflight.Result original) {
        Objects.requireNonNull(original, "literal preflight");
        BoundInvocation bound = original.bound();
        OptionalCallTempLowering.Recipe recipe = original.recipe();
        if (bound.readiness() != BoundInvocation.Readiness.SHAPE_ONLY
                || bound.kind() != BoundInvocation.Kind.MODULE_FUNCTION
                || recipe.bound() != bound
                || recipe.targetKind()
                        != OptionalCallTempLowering.TargetKind.STATIC_MODULE_FUNCTION
                || !(bound.selectedDeclaration() instanceof Ast.FunctionDecl)
                || bound.source() != original.call()
                || bound.owner() != original.module()) {
            throw new IllegalArgumentException("projection lacks exact declared module target");
        }

        Ast.StagedCallExpr call = original.call();
        // The diagnostic checker resolves ordinary calls by name + value arity.
        // Freeze this bridge to a two-declaration module: a unique selected
        // function and its distinct lexical caller. Otherwise annotations,
        // unrelated declarations or alternate overloads could alter ordinary
        // call resolution without changing the shape-only selected target.
        Ast.ModuleDecl owner = original.module();
        Ast.FunctionDecl selected = (Ast.FunctionDecl) bound.selectedDeclaration();
        Ast.FunctionDecl caller = original.caller();
        if (!(call.callee() instanceof Ast.NameExpr named)
                || owner.declarations().size() != 2
                || selected == caller
                || !named.name().equals(selected.name())
                || owner.declarations().stream().filter(x -> x == selected).count() != 1
                || owner.declarations().stream().filter(x -> x == caller).count() != 1
                || selected.parameters().size() != recipe.abiSlots().size()) {
            throw new IllegalArgumentException(
                    "positional projection requires unique original name/arity target and caller");
        }
        if (!(call.callee() instanceof Ast.NameExpr)
                || call.typeArgumentsPresent()
                || call.arguments().isEmpty()
                || call.arguments().stream().anyMatch(item ->
                        item.omitted() || item.spread() || item.label() == null)) {
            throw new IllegalArgumentException("projection admits named, supplied scalar items only");
        }

        List<OptionalCallTempLowering.ArgumentTemp> temps = recipe.argumentTemps();
        List<OptionalCallTempLowering.AbiSlot> slots = recipe.abiSlots();
        if (slots.size() != temps.size()) {
            throw new IllegalArgumentException(
                    "positional projection cannot synthesize omitted or variadic arguments");
        }
        boolean[] visited = new boolean[temps.size()];
        List<Ast.Expr> declaredOrder = new ArrayList<>(slots.size());
        for (int index = 0; index < slots.size(); index++) {
            OptionalCallTempLowering.AbiSlot slot = slots.get(index);
            int tempIndex = slot.tempIndex();
            if (slot.formalIndex() != index
                    || slot.action() != OptionalCallAbiPreview.Action.PASS_REQUIRED
                    || tempIndex < 0 || tempIndex >= temps.size()
                    || visited[tempIndex]) {
                throw new IllegalArgumentException(
                        "projection needs exactly one required source per formal");
            }
            visited[tempIndex] = true;
            OptionalCallTempLowering.ArgumentTemp temp = temps.get(tempIndex);
            if (temp.tempIndex() != tempIndex || temp.evaluatedIndex() != tempIndex
                    || temp.rawCallItemIndex() != slot.rawCallItemIndex()
                    || temp.rawCallItemIndex() >= call.arguments().size()
                    || call.arguments().get(temp.rawCallItemIndex()).value() != temp.expression()
                    || !(temp.expression() instanceof Ast.LiteralExpr literal)
                    || !scalarLiteralType(literal).equals(slot.calleeType())) {
                throw new IllegalArgumentException(
                        "positional projection lacks exact scalar type or source provenance");
            }
            declaredOrder.add(temp.expression());
        }
        for (boolean seen : visited) {
            if (!seen) throw new IllegalArgumentException("projection lost a source value");
        }

        Ast.CallExpr positional = new Ast.CallExpr(call.callee(), declaredOrder);
        if (caller.body().isEmpty()
                || !(caller.body().getFirst() instanceof Ast.BindingStmt binding)
                || binding.initializer() != call) {
            throw new IllegalArgumentException("caller no longer owns original source");
        }
        Ast.BindingStmt substituted = new Ast.BindingStmt(
                binding.kind(), binding.declaredType(), binding.name(),
                positional, binding.mutableReferent(), binding.inferredTypeName());
        List<Ast.Stmt> body = new ArrayList<>(caller.body());
        body.set(0, substituted);
        Ast.FunctionDecl projectedCaller = new Ast.FunctionDecl(
                caller.name(), caller.kind(), caller.visibility(), caller.async(),
                caller.suspends(), caller.generator(), caller.structural(),
                caller.nonLexical(), caller.trapped(), caller.actorKind(),
                caller.genericParameters(), caller.parameters(), caller.returnType(),
                caller.annotations(), body, caller.genericSpecs());

        Ast.ModuleDecl module = owner;
        List<Ast.Decl> declarations = new ArrayList<>(module.declarations());
        boolean replaced = false;
        for (int index = 0; index < declarations.size(); index++) {
            if (declarations.get(index) == caller) {
                if (replaced) {
                    throw new IllegalArgumentException("ambiguous duplicated caller identity");
                }
                declarations.set(index, projectedCaller);
                replaced = true;
            }
        }
        if (!replaced) throw new IllegalArgumentException("caller declaration disappeared");
        Ast.ModuleDecl projectedModule = new Ast.ModuleDecl(
                module.name(), module.annotations(), module.contracts(), declarations);
        Ast.Program source = original.program();
        Ast.Program positionalProgram = new Ast.Program(
                source.namespace(), source.imports(), List.of(projectedModule));

        // Only ordinary, existing positional call semantics are checked.
        // This is NOT the staged call and the returned AST is never executed.
        TypeChecker.check(positionalProgram);
        // Only issue a non-executable receipt, never a runnable replacement AST.
        return new Result(original, declaredOrder.size());
    }

    private static Ast.TypeRef scalarLiteralType(Ast.LiteralExpr literal) {
        Object value = literal.value();
        if (value instanceof Long) return Ast.TypeRef.simple("int");
        if (value instanceof Double) return Ast.TypeRef.simple("float");
        if (value instanceof Boolean) return Ast.TypeRef.simple("bool");
        if (value instanceof String) return Ast.TypeRef.simple("string");
        throw new IllegalArgumentException("non-scalar or null literal requires real type analysis");
    }
}
