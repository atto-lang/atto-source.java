package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;

/**
 * Conservative nominal Result error preflight for direct constructors.
 * This verifier cannot prove general expression or variable typing and never
 * authorizes executable nominal source. The TypeChecker/RootNode denial stays.
 */
public final class NominalResultErrorAudit {
    private NominalResultErrorAudit() { }

    // This is a transitional fail-closed pass, not a replacement for CFG typing.
    // Bound its work independently of parser limits to avoid recursive JVM stack
    // exhaustion or unbounded compilation for hostile ASTs.
    private static final int MAX_AUDIT_STATEMENTS = 8192;
    private record Pending(Ast.TypeRef returns, List<Ast.Stmt> body) { }

    public static void verify(Ast.Program program, TaggedMessageContracts.Registry registry) {
        Objects.requireNonNull(program);
        Objects.requireNonNull(registry);
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl fn) {
                    auditStatements(fn.returnType(), fn.body(), registry);
                } else if (decl instanceof Ast.ClassDecl cls) {
                    for (Ast.FieldDecl field : cls.fields())
                        auditDirectErr(field.type(), field.initializer(), registry);
                    if (cls.constructor() != null)
                        auditStatements(null, cls.constructor().body(), registry);
                    for (Ast.MethodDecl method : cls.methods())
                        auditStatements(method.returnType(), method.body(), registry);
                } else if (decl instanceof Ast.FieldDecl field) {
                    auditDirectErr(field.type(), field.initializer(), registry);
                }
            }
        }
    }

    private static void auditStatements(Ast.TypeRef returns, List<Ast.Stmt> body,
                                        TaggedMessageContracts.Registry registry) {
        ArrayDeque<Pending> pending = new ArrayDeque<>();
        pending.addLast(new Pending(returns, body));
        int seen = 0;
        while (!pending.isEmpty()) {
            Pending work = pending.removeLast();
            for (Ast.Stmt statement : work.body()) {
                if (++seen > MAX_AUDIT_STATEMENTS)
                    throw new IllegalArgumentException(
                            "nominal Result preflight exceeds statement work budget");
                if (statement instanceof Ast.ReturnStmt returned) {
                    auditDirectErr(work.returns(), returned.value(), registry);
                } else if (statement instanceof Ast.BindingStmt binding) {
                    auditDirectErr(binding.declaredType(), binding.initializer(), registry);
                } else if (statement instanceof Ast.BlockStmt block) {
                    pending.addLast(new Pending(work.returns(), block.body()));
                } else if (statement instanceof Ast.MatchStmt match) {
                    for (Ast.MatchArm arm : match.arms())
                        pending.addLast(new Pending(work.returns(), arm.body()));
                } else if (statement instanceof Ast.IfStmt conditional) {
                    for (Ast.IfBranch branch : conditional.branches())
                        pending.addLast(new Pending(work.returns(), branch.body()));
                    pending.addLast(new Pending(work.returns(), conditional.elseBody()));
                } else if (statement instanceof Ast.TryStmt tried) {
                    pending.addLast(new Pending(work.returns(), tried.body()));
                    pending.addLast(new Pending(work.returns(), tried.catchBody()));
                    pending.addLast(new Pending(work.returns(), tried.finallyBody()));
                } else if (statement instanceof Ast.SwitchStmt switched) {
                    for (Ast.SwitchCase selected : switched.cases())
                        pending.addLast(new Pending(work.returns(), selected.body()));
                    pending.addLast(new Pending(work.returns(), switched.defaultBody()));
                } else if (statement instanceof Ast.ForStmt loop) {
                    if (loop.initializer() != null)
                        pending.addLast(new Pending(work.returns(), List.of(loop.initializer())));
                    pending.addLast(new Pending(work.returns(), loop.body()));
                } else if (statement instanceof Ast.ForOfStmt loop) {
                    pending.addLast(new Pending(work.returns(), loop.body()));
                } else if (statement instanceof Ast.ForOfDestructureStmt loop) {
                    pending.addLast(new Pending(work.returns(), loop.body()));
                } else if (statement instanceof Ast.LoopStmt loop) {
                    pending.addLast(new Pending(work.returns(), loop.body()));
                } else if (statement instanceof Ast.SelectStmt select) {
                    for (Ast.SelectArm arm : select.arms())
                        pending.addLast(new Pending(work.returns(), arm.body()));
                }
            }
        }
    }

    private static void auditDirectErr(Ast.TypeRef resultType, Ast.Expr value,
                                       TaggedMessageContracts.Registry registry) {
        if (resultType == null || !"Result".equals(resultType.name())
                || resultType.arguments().size() != 2) return;
        Ast.TypeRef errorType = resultType.arguments().get(1);
        String expectedOwner = errorType.name();
        if (!registry.enums().containsKey(expectedOwner)) return;
        if (!errorType.arguments().isEmpty() || errorType.inferArguments())
            throw new IllegalArgumentException(
                    "nominal error type cannot accept generic/inferred case parameters");
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
        // Arity alone cannot prove the payload type or ownership. In
        // particular, a single string passed to a case expecting int
        // must NOT receive a positive type-only audit. Restrict positive
        // proofs to zero-payload cases until the real TypeChecker validates
        // the full expression, lifetime and ownership.
        if (expectedArity != 0)
            throw new IllegalArgumentException(
                    "unverified nominal case payload type or ownership for "
                            + variant.qualifiedName());

    }
}
