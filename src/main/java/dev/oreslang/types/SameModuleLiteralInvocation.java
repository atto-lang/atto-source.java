package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.Objects;

/**
 * A deliberately narrow and non-executable lexical-origin witness.
 *
 * <p>It is intentionally less capable than the full TypeChecker. It only
 * admits a literal-only staged call which appears directly as a statement,
 * return or initializer in an original function declaration owned by a
 * source module in the same parsed Program. The selected function must
 * belong to that same module, and no imports or local/module shadow names
 * are admitted. It never guesses an outer lexical environment.
 *
 * <p>This witness does not prove the complete TypeChecker/OwnershipChecker
 * contracts, constructor initialization, effects, or runtime Option ABI.
 * It must not become a surrogate trusted executable token.
 */
public final class SameModuleLiteralInvocation {
    private final Ast.Program program;
    private final Ast.ModuleDecl module;
    private final Ast.FunctionDecl callingDeclaration;
    private final Ast.StagedCallExpr originalCall;
    private final BoundInvocation bound;
    private final OptionalCallTempLowering.Recipe temps;

    private SameModuleLiteralInvocation(Ast.Program program, Ast.ModuleDecl module,
                                        Ast.FunctionDecl caller, Ast.StagedCallExpr call,
                                        BoundInvocation bound) {
        this.program = program;
        this.module = module;
        this.callingDeclaration = caller;
        this.originalCall = call;
        this.bound = bound;
        this.temps = OptionalCallTempLowering.from(bound);
    }

    public Ast.Program program() { return program; }
    public Ast.ModuleDecl module() { return module; }
    public Ast.FunctionDecl caller() { return callingDeclaration; }
    public Ast.StagedCallExpr source() { return originalCall; }
    public BoundInvocation bound() { return bound; }
    public OptionalCallTempLowering.Recipe tempRecipe() { return temps; }
    public BoundInvocation.Readiness readiness() {
        return BoundInvocation.Readiness.SHAPE_ONLY;
    }
    public void requireExecutable() { bound.requireExecutable(); }

    public static SameModuleLiteralInvocation inspect(
            Ast.Program program, Ast.FunctionDecl caller, Ast.StagedCallExpr call) {
        Objects.requireNonNull(program, "parsed program");
        Objects.requireNonNull(caller, "calling function declaration");
        Objects.requireNonNull(call, "parsed invocation");
        // External names require import/namespace/visibility proof from the
        // actual compiler. Reject even unused imports in this narrow witness.
        if (!program.imports().isEmpty()) {
            throw new IllegalArgumentException("imported lexical scope requires compiler resolution");
        }
        if (caller.actorKind() != Ast.ActorKind.NONE
                || caller.kind() != Ast.CallableKind.FNC
                || caller.async() || caller.suspends() || caller.generator()
                || caller.nonLexical() || caller.structural() || caller.trapped()
                || !caller.genericParameters().isEmpty()
                || !caller.annotations().isEmpty()) {
            throw new IllegalArgumentException(
                    "actor, routine, generic, effectful or annotated caller needs trusted lexical authority");
        }
        Ast.ModuleDecl declaringModule = null;
        int ownerHits = 0;
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration == caller) {
                    declaringModule = module;
                    ownerHits++;
                }
            }
        }
        if (ownerHits != 1) {
            throw new IllegalArgumentException("calling function is not unique in parsed program");
        }
        // Program ASTs may be constructed directly, outside the parser's
        // namespace validation. A call in one module cannot acquire static
        // resolution authority merely because another module has the same
        // unqualified name. Keep this local witness narrower than TypeChecker.
        int matchingModules = 0;
        for (Ast.ModuleDecl module : program.modules()) {
            if (module.name().equals(declaringModule.name())) matchingModules++;
        }
        if (matchingModules != 1) {
            throw new IllegalArgumentException(
                    "module namespace is not unique in parsed program");
        }
        // Annotations and contracts can synthesize or redirect declarations.
        // This narrow witness does not execute annotation expansion or prove
        // adherence, so never claim lexical origin from that unexpanded AST.
        if (!declaringModule.annotations().isEmpty()
                || !declaringModule.contracts().isEmpty()) {
            throw new IllegalArgumentException(
                    "annotated or contracted module requires expanded scope proof");
        }

        String targetName;
        if (call.callee() instanceof Ast.NameExpr direct) {
            targetName = direct.name();
        } else if (call.callee() instanceof Ast.MemberExpr member
                && member.receiver() instanceof Ast.NameExpr qualifier
                && qualifier.name().equals(declaringModule.name())) {
            targetName = member.member();
        } else {
            throw new IllegalArgumentException("only same-module direct/static call targets are inspectable");
        }

        // An unqualified target name needs a real cross-module resolver.
        // Even with no imports, a second module may define the same callable
        // or type spelling. This tiny lexical witness cannot prove which
        // declaration the ordinary linker would select in such a program.
        if (call.callee() instanceof Ast.NameExpr) {
            for (Ast.ModuleDecl other : program.modules()) {
                if (other == declaringModule) continue;
                for (Ast.Decl declaration : other.declarations()) {
                    String competingName = null;
                    if (declaration instanceof Ast.FunctionDecl fn) {
                        competingName = fn.name();
                    } else if (declaration instanceof Ast.FieldDecl field) {
                        competingName = field.name();
                    } else if (declaration instanceof Ast.ClassDecl klass) {
                        competingName = klass.name();
                    } else if (declaration instanceof Ast.InterfaceDecl iface) {
                        competingName = iface.name();
                    } else if (declaration instanceof Ast.TypeAliasDecl alias) {
                        competingName = alias.name();
                    }
                    if (targetName.equals(competingName)) {
                        throw new IllegalArgumentException(
                                "cross-module unqualified target needs lexical resolution proof");
                    }
                }
            }
        }

        // This preview has no lexical-scope builder. A synthesized AST can
        // reuse a formal/local name inside this one linear function body.
        // The full semantic checker decides richer shadowing semantics;
        // here even apparent same-scope rebinding must fail closed.
        java.util.Set<String> localNames = new java.util.HashSet<>();
        for (Ast.Param formal : caller.parameters()) {
            if (!localNames.add(formal.name())) {
                throw new IllegalArgumentException(
                        "duplicate caller formal needs checked lexical scope");
            }
        }
        int siteOccurrences = 0;
        boolean terminated = false;
        for (Ast.Stmt statement : caller.body()) {
            // This witness recognizes only a linear body. No statement after a
            // direct return is reachable, even if its staged-call AST matches.
            if (terminated) {
                throw new IllegalArgumentException(
                        "unreachable statement requires trusted CFG reachability proof");
            }
            // This tiny preview has no recursive lexical CFG/effect checker.
            // Do not let an unrelated nested expression, lambda, defer, or
            // branch hide a second use of the same AST node or a conflicting
            // side effect. Normal semantic/ownership checking must be used
            // before accepting any richer calling body.
            Ast.Expr value;
            if (statement instanceof Ast.ExprStmt expression) {
                if (expression.expression() == null) {
                    throw new IllegalArgumentException(
                            "empty expression statement requires parser/semantic proof");
                }
                value = expression.expression();
            } else if (statement instanceof Ast.BindingStmt binding) {
                if (binding.name() == null || binding.name().isBlank()
                        || !localNames.add(binding.name())) {
                    throw new IllegalArgumentException(
                            "duplicate or invalid local binding requires checked lexical scope");
                }
                if (binding.initializer() == null) {
                    throw new IllegalArgumentException(
                            "uninitialized local requires definite-assignment proof");
                }
                if (binding.name().equals(targetName)
                        || binding.name().equals(declaringModule.name())
                        || binding.inferredTypeName() != null) {
                    throw new IllegalArgumentException(
                            "local binding or inferred type can shadow selected target");
                }
                value = binding.initializer();
            } else if (statement instanceof Ast.ReturnStmt returned) {
                value = returned.value();
            } else {
                throw new IllegalArgumentException(
                        "complex statement requires trusted recursive lexical/effect analysis");
            }
            if (value == call) {
                siteOccurrences++;
            } else if (value != null) {
                if (!(value instanceof Ast.LiteralExpr scalar)) {
                    throw new IllegalArgumentException(
                            "other expression requires lexical/effect and nested callsite proof");
                }
                // AST construction is public and LiteralExpr accepts Object.
                // A fabricated object, null or opaque guest literal is not
                // independently known to be a pure scalar expression.
                OptionalCallLiteralStage.exactScalarLiteralType(scalar);
            }
            if (statement instanceof Ast.ReturnStmt) {
                terminated = true;
            }
        }
        if (siteOccurrences != 1) {
            throw new IllegalArgumentException("call is not a unique direct statement in selected caller");
        }
        for (Ast.Param formal : caller.parameters()) {
            if (formal.name().equals(targetName) || formal.name().equals(declaringModule.name())) {
                throw new IllegalArgumentException("calling parameter could shadow target or module namespace");
            }
        }
        for (Ast.Decl declaration : declaringModule.declarations()) {
            // A qualified app.target(...) candidate still needs proof that
            // "app" resolves to the module namespace rather than a callable
            // value. This small witness does not own the value-vs-module name
            // resolver. A same-named module function is therefore ambiguous,
            // regardless of arity or whether the function body is pure.
            if (call.callee() instanceof Ast.MemberExpr
                    && declaration instanceof Ast.FunctionDecl competing
                    && competing.name().equals(declaringModule.name())) {
                throw new IllegalArgumentException(
                        "callable shadows module qualifier; requires lexical resolution");
            }
            if (declaration instanceof Ast.FieldDecl field
                    && (field.name().equals(targetName)
                    || field.name().equals(declaringModule.name()))) {
                throw new IllegalArgumentException("module field could shadow target namespace");
            }
            // Type declarations have distinct lookup rules and may also
            // introduce callable/constructor or member-qualified names.
            // Do not rely on an unproved value/type namespace collision rule.
            String competingName = null;
            if (declaration instanceof Ast.ClassDecl klass) {
                competingName = klass.name();
            } else if (declaration instanceof Ast.InterfaceDecl iface) {
                competingName = iface.name();
            } else if (declaration instanceof Ast.TypeAliasDecl alias) {
                competingName = alias.name();
            }
            if (competingName != null
                    && (competingName.equals(targetName)
                    || competingName.equals(declaringModule.name()))) {
                throw new IllegalArgumentException(
                        "competing type declaration requires verified lexical resolution");
            }
        }
        BoundInvocation bound =
                BoundInvocation.bindLiteralModuleFunction(declaringModule, call);
        if (!(bound.selectedDeclaration() instanceof Ast.FunctionDecl function)
                || !function.name().equals(targetName)) {
            throw new IllegalArgumentException("resolved function does not match lexical target");
        }
        return new SameModuleLiteralInvocation(
                program, declaringModule, caller, call, bound);
    }
}
