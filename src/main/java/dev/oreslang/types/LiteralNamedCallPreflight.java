package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.List;
import java.util.Objects;

/**
 * Lexical/provenance preflight for a deliberately tiny, literal-only subset of
 * staged named calls. This checks that the actual source expression is inside
 * its actual caller/owner rather than merely accepting a supplied signature.
 *
 * <p>IMPORTANT: this is NOT TypeChecker-issued semantic evidence. It does not
 * prove ownership, effects, runtime dispatch or an Option<T> ABI. Its result is
 * not executable and it never replaces TypeChecker or OwnershipChecker.
 */
public final class LiteralNamedCallPreflight {
    private LiteralNamedCallPreflight() { }

    public static final class Result {
        private final Ast.Program program;
        private final Ast.ModuleDecl module;
        private final Ast.FunctionDecl caller;
        private final Ast.StagedCallExpr call;
        private final BoundInvocation bound;
        private final OptionalCallTempLowering.Recipe recipe;

        private Result(Ast.Program program, Ast.ModuleDecl module,
                       Ast.FunctionDecl caller, Ast.StagedCallExpr call,
                       BoundInvocation bound, OptionalCallTempLowering.Recipe recipe) {
            this.program = Objects.requireNonNull(program);
            this.module = Objects.requireNonNull(module);
            this.caller = Objects.requireNonNull(caller);
            this.call = Objects.requireNonNull(call);
            this.bound = Objects.requireNonNull(bound);
            this.recipe = Objects.requireNonNull(recipe);
            if (bound.source() != call || bound.owner() != module
                    || recipe.bound() != bound
                    || bound.readiness() != BoundInvocation.Readiness.SHAPE_ONLY) {
                throw new IllegalArgumentException("preflight source and bound declaration diverged");
            }
        }

        public Ast.Program program() { return program; }
        public Ast.ModuleDecl module() { return module; }
        public Ast.FunctionDecl caller() { return caller; }
        public Ast.StagedCallExpr call() { return call; }
        public BoundInvocation bound() { return bound; }
        public OptionalCallTempLowering.Recipe recipe() { return recipe; }
        public void requireExecutable() {
            throw new IllegalStateException(
                    "literal preflight is not checker/ownership/runtime authorization");
        }
    }

    /**
     * A conservative lexical slice: one imported-free module, a direct call
     * in the first top-level binding initializer of the declared caller, and
     * only compiler-recognized primitive literal argument values. The caller
     * cannot shadow the callee through its parameters, and module fields
     * cannot shadow callable names. More complex lexical scopes require the
     * real TypeChecker's resolver, not guesses made in this preview.
     */
    public static Result inspect(Ast.Program program, Ast.ModuleDecl module,
                                 Ast.FunctionDecl caller, Ast.StagedCallExpr call) {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(call, "call");
        if (program.namespace() != null || !program.imports().isEmpty()
                || program.modules().size() != 1
                || program.modules().getFirst() != module
                || !module.annotations().isEmpty() || !module.contracts().isEmpty()) {
            throw new IllegalArgumentException(
                    "literal preflight requires an imported-free exact module owner");
        }
        if (module.declarations().stream().noneMatch(decl -> decl == caller)
                || caller.kind() != Ast.CallableKind.FNC
                || caller.actorKind() != Ast.ActorKind.NONE || caller.async()
                || caller.suspends() || caller.generator() || caller.trapped()
                || caller.structural() || caller.nonLexical()
                || !caller.genericParameters().isEmpty()
                || !caller.genericSpecs().isEmpty()
                || !caller.annotations().isEmpty()
                || caller.name().equals("init")
                || caller.parameters().stream().anyMatch(p ->
                        p.optional() || p.structural() || p.mutable()
                                || p.type().isBorrow())) {
            throw new IllegalArgumentException("unproved lexical caller identity or effects");
        }
        if (caller.body().isEmpty()
                || !(caller.body().getFirst() instanceof Ast.BindingStmt binding)
                || binding.initializer() != call
                || binding.kind() != Ast.BindingKind.CONST
                || binding.mutableReferent()
                || binding.declaredType() != null
                || binding.inferredTypeName() != null) {
            throw new IllegalArgumentException("staged call not the original first caller binding");
        }
        if (!(call.callee() instanceof Ast.NameExpr callee)
                || call.typeArgumentsPresent()
                || call.arguments().isEmpty()
                || call.arguments().stream().anyMatch(item -> item.label() == null)
                || binding.name().equals(callee.name())
                || callee.name().equals("init")
                || caller.parameters().stream().anyMatch(p -> p.name().equals(callee.name()))
                || module.declarations().stream().anyMatch(decl ->
                        (decl instanceof Ast.FieldDecl field
                                && field.name().equals(callee.name()))
                        || (decl instanceof Ast.ClassDecl klass
                                && klass.name().equals(callee.name()))
                        || (decl instanceof Ast.InterfaceDecl iface
                                && iface.name().equals(callee.name()))
                        || (decl instanceof Ast.TypeAliasDecl alias
                                && alias.name().equals(callee.name())))) {
            throw new IllegalArgumentException("unproved direct lexical callable resolution");
        }
        // No arbitrary expression callbacks are accepted as type evidence:
        // BoundInvocation derives primitive literal types directly from the
        // original AST and rejects all nonliteral, null and spread items.
        BoundInvocation bound = BoundInvocation.bindLiteralModuleFunction(module, call);
        OptionalCallTempLowering.Recipe recipe = OptionalCallTempLowering.from(bound);
        if (recipe.targetKind() != OptionalCallTempLowering.TargetKind.STATIC_MODULE_FUNCTION
                || recipe.receiverExpression() != null
                || recipe.argumentTemps().size() != bound.schedule().sourceBindings().size()
                || recipe.abiSlots().size() != bound.preview().arguments().fullArity()) {
            throw new IllegalArgumentException("preflight and temp/ABI recipe disagree");
        }
        return new Result(program, module, caller, call, bound, recipe);
    }
}
