package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OptionalCallTempLowering;
import dev.oreslang.types.SameModuleLiteralInvocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Explicit experimental compiler entry point for ONE same-module literal-only
 * named/optional call. This is NOT a change to default source admission.
 *
 * <p>Original AST source and selected declaration are checked by the narrow
 * lexical witness; this pass eliminates staged syntax, converts T? formals
 * into Option<T>, and emits ordinary positional Some/None call AST. Only then
 * does the REAL TypeChecker + OwnershipChecker admit the complete output.
 *
 * <p>All supplied operands MUST be pure scalar literals. Consequently moving
 * their materialization into declared formal order cannot reorder any guest
 * side effects. No generics, receivers, spreads, dynamic calls, mutable
 * borrows, actor targets, or unverified existing Option<T> values are allowed.
 */
public final class LiteralOptionalCallCompiler {
    private LiteralOptionalCallCompiler() { }

    /** Distinguish original source form from a verified, synthetic bridge. */
    public enum SourceOrigin {
        ORIGINAL_STAGED_CALL,
        VERIFIED_IMPLICIT_EMPTY_CALL
    }

    /** Only the successful full-compiler path can issue this checked result. */
    public static final class Result {
        private final Ast.Program checkedProgram;
        private final Ast.FunctionDecl checkedCaller;
        private final Ast.FunctionDecl checkedTarget;
        private final Ast.CallExpr loweredCall;
        private final SourceOrigin sourceOrigin;
        private final List<OptionalCallTempLowering.ArgumentTemp> sourceCaptures;
        private final List<OptionalCallTempLowering.AbiSlot> formalAbiSlots;

        private Result(Ast.Program program, Ast.FunctionDecl caller,
                       Ast.FunctionDecl target, Ast.CallExpr call,
                       SourceOrigin origin, OptionalCallTempLowering.Recipe recipe) {
            checkedProgram = Objects.requireNonNull(program);
            checkedCaller = Objects.requireNonNull(caller);
            checkedTarget = Objects.requireNonNull(target);
            loweredCall = Objects.requireNonNull(call);
            sourceOrigin = Objects.requireNonNull(origin);
            // Keep an immutable trace of the original lexical call-item
            // origins after the real TypeChecker and OwnershipChecker pass.
            // This is diagnostic provenance, NOT evaluated guest values,
            // executable temporaries, or a permission to skip semantic checks.
            Objects.requireNonNull(recipe, "source ABI recipe");
            if (recipe.targetKind()
                    != OptionalCallTempLowering.TargetKind.STATIC_MODULE_FUNCTION) {
                throw new IllegalArgumentException("checked literal call lost static target");
            }
            sourceCaptures = List.copyOf(recipe.argumentTemps());
            formalAbiSlots = List.copyOf(recipe.abiSlots());
            if (formalAbiSlots.size() != target.parameters().size()
                    || formalAbiSlots.size() != call.arguments().size()) {
                throw new IllegalArgumentException(
                        "checked call and source ABI provenance have different arity");
            }
            for (int i = 0; i < formalAbiSlots.size(); i++) {
                if (formalAbiSlots.get(i).formalIndex() != i
                        || !formalAbiSlots.get(i).calleeType()
                                .equals(target.parameters().get(i).type())) {
                    throw new IllegalArgumentException(
                            "checked callee ABI diverges from original formal proof");
                }
            }
        }
        public SourceOrigin sourceOrigin() { return sourceOrigin; }
        public Ast.Program checkedProgram() { return checkedProgram; }
        public Ast.FunctionDecl checkedCaller() { return checkedCaller; }
        public Ast.FunctionDecl checkedTarget() { return checkedTarget; }
        public Ast.CallExpr loweredCall() { return loweredCall; }
        /** Original lexical argument occurrences; source preview metadata only. */
        public List<OptionalCallTempLowering.ArgumentTemp> sourceCaptures() {
            return sourceCaptures;
        }
        /** Full formal-order ABI mapping, including original omission origins. */
        public List<OptionalCallTempLowering.AbiSlot> formalAbiSlots() {
            return formalAbiSlots;
        }
    }

    public static Result lowerAndAnalyze(String source, String moduleName,
                                         String callerName) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(moduleName);
        Objects.requireNonNull(callerName);
        return lowerAndAnalyzeParsed(Parser.parse(source), moduleName, callerName,
                SourceOrigin.ORIGINAL_STAGED_CALL);
    }

    /**
     * Opt-in bridge for an original zero-argument positional call whose
     * uniquely selected same-module function has ONLY T? formal parameters.
     * This does not rewrite ordinary calls in the default compiler, nor does
     * it infer omissions for required arguments, overloads or imports.
     *
     * The source origin is verified from a freshly parsed CallExpr. A single
     * synthetic StagedCallExpr is then introduced into a private intermediate
     * program, where the existing lexical/ABI and full checker gates apply.
     */
    public static Result lowerImplicitAndAnalyze(
            String source, String moduleName, String callerName) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(moduleName);
        Objects.requireNonNull(callerName);
        Ast.Program parsed = Parser.parse(source);
        return lowerAndAnalyzeParsed(
                stageImplicitEmptyCall(parsed, moduleName, callerName),
                moduleName, callerName, SourceOrigin.VERIFIED_IMPLICIT_EMPTY_CALL);
    }

    private static Ast.Program stageImplicitEmptyCall(
            Ast.Program parsed, String moduleName, String callerName) {
        if (!parsed.imports().isEmpty() || parsed.modules().size() != 1) {
            throw new IllegalArgumentException("implicit omission requires one unimported module");
        }
        Ast.ModuleDecl module = parsed.modules().getFirst();
        if (!module.name().equals(moduleName)
                || !module.annotations().isEmpty() || !module.contracts().isEmpty()) {
            throw new IllegalArgumentException("implicit omission module is not uniquely resolved");
        }
        Ast.FunctionDecl caller = null;
        for (Ast.Decl declaration : module.declarations()) {
            if (declaration instanceof Ast.FunctionDecl fn
                    && fn.name().equals(callerName)) {
                if (caller != null) throw new IllegalArgumentException("ambiguous caller");
                caller = fn;
            }
        }
        if (caller == null || !plain(caller)) {
            throw new IllegalArgumentException("implicit omission needs an ordinary caller");
        }
        Ast.CallExpr originalCall = null;
        for (Ast.Stmt statement : caller.body()) {
            Ast.Expr expression = immediateExpression(statement);
            if (expression instanceof Ast.CallExpr call) {
                if (originalCall != null) {
                    throw new IllegalArgumentException("ambiguous ordinary call site");
                }
                originalCall = call;
            } else if (expression instanceof Ast.StagedCallExpr) {
                throw new IllegalArgumentException("mixed staged and implicit call sites");
            }
        }
        if (originalCall == null
                || !(originalCall.callee() instanceof Ast.NameExpr name)
                || originalCall.typeArgumentsPresent()
                || !originalCall.typeArguments().isEmpty()
                || !originalCall.arguments().isEmpty()
                || name.name().equals(callerName)) {
            throw new IllegalArgumentException("only direct non-generic empty calls qualify");
        }
        Ast.FunctionDecl target = null;
        for (Ast.Decl declaration : module.declarations()) {
            if (declaration instanceof Ast.FunctionDecl fn
                    && fn.name().equals(name.name())) {
                if (target != null) {
                    throw new IllegalArgumentException("overloaded target needs true resolution");
                }
                target = fn;
            }
        }
        if (target == null || !plain(target) || target.parameters().isEmpty()
                || target.parameters().stream().anyMatch(p -> !p.optional()
                        || !exactScalar(p.valueType()))) {
            throw new IllegalArgumentException(
                    "implicit omission requires a nonempty all-optional scalar signature");
        }
        // Reconstruct only the one immediate, original source occurrence.
        // The lexical binder and full type/ownership checks independently
        // validate its same-module destination and complete transformed body.
        Ast.StagedCallExpr staged = new Ast.StagedCallExpr(
                originalCall.callee(), List.of(), false, List.of());
        List<Ast.Stmt> body = new ArrayList<>(caller.body().size());
        int replaced = 0;
        for (Ast.Stmt statement : caller.body()) {
            if (immediateExpression(statement) == originalCall) {
                replaced++;
                if (statement instanceof Ast.ReturnStmt) {
                    body.add(new Ast.ReturnStmt(staged));
                } else if (statement instanceof Ast.ExprStmt) {
                    body.add(new Ast.ExprStmt(staged));
                } else if (statement instanceof Ast.BindingStmt binding) {
                    body.add(new Ast.BindingStmt(
                            binding.kind(), binding.declaredType(),
                            binding.name(), staged,
                            binding.mutableReferent(), binding.inferredTypeName()));
                } else {
                    throw new IllegalArgumentException("unsupported call site");
                }
            } else {
                body.add(statement);
            }
        }
        if (replaced != 1) {
            throw new IllegalArgumentException("original call site not unique");
        }
        Ast.FunctionDecl stagedCaller = copyFunction(caller, caller.parameters(), body);
        List<Ast.Decl> declarations = new ArrayList<>(module.declarations().size());
        for (Ast.Decl declaration : module.declarations()) {
            declarations.add(declaration == caller ? stagedCaller : declaration);
        }
        return new Ast.Program(parsed.namespace(), parsed.imports(),
                List.of(new Ast.ModuleDecl(
                        module.name(), module.annotations(), module.contracts(), declarations)));
    }

    private static Result lowerAndAnalyzeParsed(
            Ast.Program original, String moduleName, String callerName,
            SourceOrigin sourceOrigin) {
        if (!original.imports().isEmpty() || original.modules().size() != 1) {
            throw new IllegalArgumentException(
                    "experimental optional call needs a single unimported module");
        }
        Ast.ModuleDecl module = original.modules().getFirst();
        for (Ast.Decl decl : module.declarations()) {
            if (decl instanceof Ast.FunctionDecl fn
                    && (fn.name().equals("Some") || fn.name().equals("None"))) {
                throw new IllegalArgumentException(
                        "custom Some/None would shadow the runtime Option constructors");
            }
        }
        if (!module.name().equals(moduleName)
                || !module.annotations().isEmpty() || !module.contracts().isEmpty()) {
            throw new IllegalArgumentException("module has unverified lexical expansion");
        }
        Ast.FunctionDecl caller = null;
        for (Ast.Decl decl : module.declarations()) {
            if (decl instanceof Ast.FunctionDecl fn && fn.name().equals(callerName)) {
                if (caller != null) throw new IllegalArgumentException("ambiguous caller");
                caller = fn;
            } else if (!(decl instanceof Ast.FunctionDecl)) {
                throw new IllegalArgumentException("experimental module accepts functions only");
            }
        }
        if (caller == null || !plain(caller) || caller.body().isEmpty()) {
            throw new IllegalArgumentException("caller is not an ordinary source function");
        }
        for (Ast.Param p : caller.parameters()) {
            if (p.name().equals("Some") || p.name().equals("None")) {
                throw new IllegalArgumentException("caller shadows Option constructors");
            }
        }
        for (Ast.Stmt statement : caller.body()) {
            if (statement instanceof Ast.BindingStmt binding
                    && (binding.name().equals("Some")
                    || binding.name().equals("None"))) {
                throw new IllegalArgumentException("local binding shadows Option constructors");
            }
        }

        Ast.StagedCallExpr staged = null;
        for (Ast.Stmt stmt : caller.body()) {
            Ast.Expr expression = immediateExpression(stmt);
            if (expression instanceof Ast.StagedCallExpr call) {
                if (staged != null) throw new IllegalArgumentException("multiple staged sites");
                staged = call;
            }
        }
        if (staged == null || !(staged.callee() instanceof Ast.NameExpr)) {
            throw new IllegalArgumentException("need one direct named/omitted source call");
        }
        SameModuleLiteralInvocation lexical =
                SameModuleLiteralInvocation.inspect(original, caller, staged);
        if (!(lexical.bound().selectedDeclaration() instanceof Ast.FunctionDecl target)
                || target == caller || !plain(target)) {
            throw new IllegalArgumentException("selected target needs pure ordinary signature");
        }
        // The source binder may distinguish same-named candidates by argument
        // labels, but those labels disappear in the positional Some/None ABI.
        // Until the full checker returns a retained declaration-resolution
        // witness for the rewritten call, forbid every other overload of this
        // target name, including overloads with different source arity.
        for (Ast.Decl declaration : module.declarations()) {
            if (declaration instanceof Ast.FunctionDecl competing
                    && competing != target
                    && competing.name().equals(target.name())) {
                throw new IllegalArgumentException(
                        "target overload set needs post-lowering declaration resolution proof");
            }
        }
        // The future runtime representation is Option<T>; this experimental
        // rewrite admits only already-proven exact scalar T literals.
        OptionalCallTempLowering.Recipe recipe = lexical.tempRecipe();
        if (recipe.targetKind() !=
                OptionalCallTempLowering.TargetKind.STATIC_MODULE_FUNCTION
                || recipe.receiverTempIndex() != -1) {
            throw new IllegalArgumentException("dynamic receiver/target is unsupported");
        }
        List<Ast.Expr> operands = new ArrayList<>();
        for (OptionalCallTempLowering.ArgumentTemp item : recipe.argumentTemps()) {
            if (!(item.expression() instanceof Ast.LiteralExpr)
                    || item.evaluatedIndex() != operands.size()) {
                throw new IllegalArgumentException("nonliteral/effectful operand forbidden");
            }
            operands.add(item.expression());
        }
        List<Ast.Expr> arguments = new ArrayList<>();
        List<Ast.Param> loweredFormals = new ArrayList<>();
        if (target.parameters().size() != recipe.abiSlots().size()) {
            throw new IllegalArgumentException("formal/slot count mismatch");
        }
        for (int i = 0; i < target.parameters().size(); i++) {
            Ast.Param formal = target.parameters().get(i);
            if (formal.optional() && !exactScalar(formal.valueType())) {
                throw new IllegalArgumentException(
                        "experimental Option ABI requires an exact scalar T");
            }
            OptionalCallTempLowering.AbiSlot slot = recipe.abiSlots().get(i);
            if (slot.formalIndex() != i || !slot.name().equals(formal.name())
                    || !slot.calleeType().equals(formal.calleeType())) {
                throw new IllegalArgumentException("source ABI slot mismatch");
            }
            Ast.Expr value = switch (slot.action()) {
                case PASS_REQUIRED -> {
                    if (formal.optional() || slot.tempIndex() < 0
                            || slot.tempIndex() >= operands.size()) {
                        throw new IllegalArgumentException("invalid required operand");
                    }
                    yield operands.get(slot.tempIndex());
                }
                case WRAP_SOME -> {
                    if (!formal.optional() || slot.tempIndex() < 0
                            || slot.tempIndex() >= operands.size()) {
                        throw new IllegalArgumentException("invalid optional operand");
                    }
                    yield new Ast.CallExpr(new Ast.NameExpr("Some"),
                            List.of(operands.get(slot.tempIndex())));
                }
                case INJECT_NONE -> {
                    if (!formal.optional() || slot.tempIndex() != -1) {
                        throw new IllegalArgumentException("required value was omitted");
                    }
                    yield new Ast.NameExpr("None");
                }
                case PASS_OPTION -> throw new IllegalArgumentException(
                        "existing Option<T> needs complete value/ownership proof");
            };
            arguments.add(value);
            loweredFormals.add(formal.optional()
                    ? new Ast.Param(formal.calleeType(), formal.name(), false, false)
                    : formal);
        }
        Ast.CallExpr normal = new Ast.CallExpr(staged.callee(), arguments);
        List<Ast.Decl> declarations = new ArrayList<>(module.declarations().size());
        Ast.FunctionDecl loweredTarget = null;
        Ast.FunctionDecl loweredCaller = null;
        for (Ast.Decl declaration : module.declarations()) {
            if (declaration == target) {
                loweredTarget = copyFunction(target, loweredFormals, target.body());
                declarations.add(loweredTarget);
            } else if (declaration == caller) {
                List<Ast.Stmt> body = new ArrayList<>(caller.body().size());
                for (Ast.Stmt statement : caller.body()) {
                    if (immediateExpression(statement) == staged) {
                        if (statement instanceof Ast.ReturnStmt) {
                            body.add(new Ast.ReturnStmt(normal));
                        } else if (statement instanceof Ast.ExprStmt) {
                            body.add(new Ast.ExprStmt(normal));
                        } else if (statement instanceof Ast.BindingStmt binding) {
                            body.add(new Ast.BindingStmt(
                                    binding.kind(), binding.declaredType(),
                                    binding.name(), normal,
                                    binding.mutableReferent(), binding.inferredTypeName()));
                        } else {
                            throw new IllegalArgumentException("unsupported call site");
                        }
                    } else {
                        body.add(statement);
                    }
                }
                loweredCaller = copyFunction(caller, caller.parameters(), body);
                declarations.add(loweredCaller);
            } else {
                declarations.add(declaration);
            }
        }
        Ast.ModuleDecl rewrittenModule = new Ast.ModuleDecl(
                module.name(), module.annotations(), module.contracts(), declarations);
        Ast.Program rewritten = new Ast.Program(
                original.namespace(), original.imports(), List.of(rewrittenModule));
        // Critical trust transition. Do not issue Result until both real
        // checkers pass the ENTIRE transformed program including unused code.
        Ast.Program checked = OresCompiler.analyze(rewritten);
        if (checked.modules().size() != 1) {
            throw new IllegalArgumentException("unexpected compiler module expansion");
        }
        boolean callerRetained = false, targetRetained = false, callRetained = false;
        for (Ast.Decl declaration : checked.modules().getFirst().declarations()) {
            if (declaration == loweredCaller) callerRetained = true;
            if (declaration == loweredTarget) targetRetained = true;
        }
        for (Ast.Stmt statement : loweredCaller.body()) {
            if (immediateExpression(statement) == normal) callRetained = true;
        }
        if (!callerRetained || !targetRetained || !callRetained) {
            throw new IllegalArgumentException(
                    "checked compiler output lost source binding identity");
        }
        return new Result(checked, loweredCaller, loweredTarget, normal,
                sourceOrigin, recipe);
    }

    private static Ast.Expr immediateExpression(Ast.Stmt statement) {
        if (statement instanceof Ast.ReturnStmt ret) return ret.value();
        if (statement instanceof Ast.ExprStmt expr) return expr.expression();
        if (statement instanceof Ast.BindingStmt bind) return bind.initializer();
        return null;
    }

    private static boolean plain(Ast.FunctionDecl fn) {
        if (fn.kind() != Ast.CallableKind.FNC || fn.actorKind() != Ast.ActorKind.NONE
                || fn.async() || fn.suspends() || fn.generator() || fn.trapped()
                || fn.structural() || fn.nonLexical() || !fn.annotations().isEmpty()
                || !fn.genericParameters().isEmpty() || !fn.genericSpecs().isEmpty()) return false;
        for (Ast.Param p : fn.parameters()) {
            if (p.structural() || p.mutable()) return false;
        }
        return true;
    }

    private static boolean exactScalar(Ast.TypeRef type) {
        return type != null && !type.inferArguments() && type.arguments().isEmpty()
                && !type.isBorrow() && !type.isOmittableFormal()
                && List.of("int", "float", "bool", "string").contains(type.name());
    }

    private static Ast.FunctionDecl copyFunction(
            Ast.FunctionDecl fn, List<Ast.Param> params, List<Ast.Stmt> body) {
        return new Ast.FunctionDecl(
                fn.name(), fn.kind(), fn.visibility(), fn.async(), fn.suspends(),
                fn.generator(), fn.structural(), fn.nonLexical(), fn.trapped(),
                fn.actorKind(), fn.genericParameters(), params, fn.returnType(),
                fn.annotations(), body, fn.genericSpecs());
    }
}
