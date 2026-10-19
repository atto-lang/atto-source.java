package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import java.util.List;
import java.util.Objects;

/**
 * Conservative nominal Result error preflight for direct constructors.
 * This verifier cannot prove general expression or variable typing and never
 * authorizes executable nominal source. The TypeChecker/RootNode denial stays.
 */
public final class NominalResultErrorAudit {
    private NominalResultErrorAudit() { }

    public static void verify(Ast.Program program, TaggedMessageContracts.Registry registry) {
        Objects.requireNonNull(program);
        Objects.requireNonNull(registry);
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl fn) {
                    auditStatements(fn.returnType(), fn.body(), registry);
                } else if (decl instanceof Ast.ClassDecl cls) {
                    for (Ast.MethodDecl method : cls.methods())
                        auditStatements(method.returnType(), method.body(), registry);
                }
            }
        }
    }

    private static void auditStatements(Ast.TypeRef expected, List<Ast.Stmt> body,
                                        TaggedMessageContracts.Registry registry) {
        for (Ast.Stmt statement : body) {
            if (statement instanceof Ast.ReturnStmt returned) {
                auditDirectErr(expected, returned.value(), registry);
            } else if (statement instanceof Ast.BindingStmt binding) {
                auditDirectErr(binding.declaredType(), binding.initializer(), registry);
            } else if (statement instanceof Ast.BlockStmt block) {
                auditStatements(expected, block.body(), registry);
            } else if (statement instanceof Ast.MatchStmt match) {
                for (Ast.MatchArm arm : match.arms())
                    auditStatements(expected, arm.body(), registry);
            } else if (statement instanceof Ast.IfStmt conditional) {
                for (Ast.IfBranch branch : conditional.branches())
                    auditStatements(expected, branch.body(), registry);
                auditStatements(expected, conditional.elseBody(), registry);
            }
        }
    }

    private static void auditDirectErr(Ast.TypeRef resultType, Ast.Expr value,
                                       TaggedMessageContracts.Registry registry) {
        if (resultType == null || !"Result".equals(resultType.name())
                || resultType.arguments().size() != 2) return;
        String expectedOwner = resultType.arguments().get(1).name();
        if (!registry.enums().containsKey(expectedOwner)) return;
        if (!(value instanceof Ast.CallExpr call)
                || !(call.callee() instanceof Ast.NameExpr name)
                || !"Err".equals(name.name())) return;
        if (call.typeArgumentsPresent() || call.arguments().size() != 1)
            throw new IllegalArgumentException("Err requires one nominal error argument");
        Ast.Expr payload = call.arguments().get(0);
        if (!(payload instanceof Ast.CallExpr ctor)
                || !(ctor.callee() instanceof Ast.MemberExpr member)
                || !(member.receiver() instanceof Ast.NameExpr owner))
            throw new IllegalArgumentException(
                    "unverified nominal Err payload: direct enum constructor required");
        if (!expectedOwner.equals(owner.name()))
            throw new IllegalArgumentException(
                    "foreign nominal error " + owner.name() + " for " + expectedOwner);
        TaggedMessageContracts.Case variant =
                registry.requireCase(expectedOwner + "." + member.member());
        if (ctor.typeArgumentsPresent())
            throw new IllegalArgumentException(
                    "nominal enum case constructors cannot take type arguments");
        int expectedArity = variant.sourcePayload() == null ? 0 : 1;
        if (ctor.arguments().size() != expectedArity)
            throw new IllegalArgumentException(
                    "enum case " + variant.qualifiedName() + " requires "
                            + expectedArity + " payload arguments");
        // Payload expression types, borrow capabilities and effects are
        // outside this *direct-case* audit and remain unapproved at runtime.
    }
}
