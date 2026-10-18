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

        int siteOccurrences = 0;
        for (Ast.Stmt statement : caller.body()) {
            if (statement instanceof Ast.ExprStmt expression && expression.expression() == call
                    || statement instanceof Ast.BindingStmt binding && binding.initializer() == call
                    || statement instanceof Ast.ReturnStmt returned && returned.value() == call) {
                siteOccurrences++;
            }
            if (statement instanceof Ast.BindingStmt binding
                    && binding.name().equals(targetName)) {
                throw new IllegalArgumentException("local binding could shadow direct target");
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
            if (declaration instanceof Ast.FieldDecl field
                    && (field.name().equals(targetName)
                    || field.name().equals(declaringModule.name()))) {
                throw new IllegalArgumentException("module field could shadow target namespace");
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
