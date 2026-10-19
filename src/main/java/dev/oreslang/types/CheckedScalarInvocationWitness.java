package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A checker-issued, deliberately NARROW lexical + scalar type witness.
 *
 * <p>This proves an original same-module call's selected declaration, exact
 * source literal types and immediate expression-result context. It does not
 * perform the full TypeChecker pass, model subtyping or generics, prove
 * ownership/effects or generate a runnable Option<T> ABI. Consequently the
 * ordinary compiler/runtime staged-call rejection MUST remain in place.
 *
 * <p>Only TypeChecker can issue this factory-owned witness; callers cannot
 * provide asserted TypeRefs or substitute a different call-site AST.
 */
public final class CheckedScalarInvocationWitness {
    public enum ResultSite { BINDING, RETURN, EXPRESSION_STATEMENT }

    private final SameModuleLiteralInvocation lexical;
    private final Ast.FunctionDecl selected;
    private final Ast.TypeRef resultType;
    private final ResultSite resultSite;
    private final List<Ast.TypeRef> checkedLiteralTypes;

    private CheckedScalarInvocationWitness(SameModuleLiteralInvocation lexical,
                                           Ast.FunctionDecl selected,
                                           Ast.TypeRef resultType,
                                           ResultSite site,
                                           List<Ast.TypeRef> checkedLiteralTypes) {
        this.lexical = Objects.requireNonNull(lexical);
        this.selected = Objects.requireNonNull(selected);
        this.resultType = Objects.requireNonNull(resultType);
        this.resultSite = Objects.requireNonNull(site);
        this.checkedLiteralTypes = List.copyOf(checkedLiteralTypes);
    }

    public SameModuleLiteralInvocation lexical() { return lexical; }
    public Ast.FunctionDecl selectedDeclaration() { return selected; }
    public Ast.TypeRef resultType() { return resultType; }
    public ResultSite resultSite() { return resultSite; }
    public List<Ast.TypeRef> checkedLiteralTypes() { return checkedLiteralTypes; }
    public BoundInvocation.Readiness readiness() {
        return BoundInvocation.Readiness.SHAPE_ONLY;
    }
    public void requireExecutable() {
        throw new IllegalStateException(
                "scalar type preview is not an ownership/effect/Option ABI execution proof");
    }

    /**
     * Package-private issuance entry point, called by TypeChecker only.
     * No external type claims are accepted. Literal types are independently
     * derived from the exact AST supplied to the lexical witness.
     */
    static CheckedScalarInvocationWitness issueFromTypeChecker(
            Ast.Program program, Ast.FunctionDecl caller, Ast.StagedCallExpr call) {
        SameModuleLiteralInvocation lexical =
                SameModuleLiteralInvocation.inspect(program, caller, call);
        if (!(lexical.bound().selectedDeclaration() instanceof Ast.FunctionDecl selected)) {
            throw new IllegalArgumentException("selected target is not an original module function");
        }
        // The narrow issuer never claims to validate exotic caller signature
        // effects or unresolved/captured parameter types. Those belong to the
        // ordinary recursive semantic and ownership checker.
        if (caller.returnType() == null || !exactScalarOrVoid(caller.returnType())) {
            throw new IllegalArgumentException(
                    "caller return signature requires full semantic type resolution");
        }
        requireDistinctFormals(caller.parameters(), "caller");
        for (Ast.Param formal : caller.parameters()) {
            if (formal.optional() || formal.structural() || formal.mutable()
                    || !exactScalar(formal.valueType())) {
                throw new IllegalArgumentException(
                        "caller parameter signature requires full compiler checking");
            }
        }
        if (selected.returnType() == null || !exactScalarOrVoid(selected.returnType())) {
            throw new IllegalArgumentException(
                    "callee return type requires full semantic type resolution");
        }
        requireDistinctFormals(selected.parameters(), "selected callee");
        for (Ast.Param formal : selected.parameters()) {
            if (formal.structural() || formal.mutable()
                    || !exactScalar(formal.valueType())) {
                throw new IllegalArgumentException(
                        "formal type/ownership requires full compiler resolution");
            }
        }
        // A return signature is only a source declaration, not a proof of
        // the implementation's type or effects. This tiny issuer may inspect
        // only a single expression-free return of a scalar literal or
        // immutable, required scalar formal (or bare return for void).
        // All other callee bodies require the real recursive checker.
        requireLocallyProvableCalleeBody(selected);
        // Indexes are source expressions, not raw call items. Each omission has
        // NO expression and no type; typed None is not inferred from an AST name.
        List<Ast.TypeRef> literalTypes = new ArrayList<>();
        for (Ast.CallArgument item : call.arguments()) {
            if (item.omitted()) continue;
            if (item.spread() || !(item.value() instanceof Ast.LiteralExpr literal)) {
                throw new IllegalArgumentException(
                        "nonliteral source requires full checker type/effect analysis");
            }
            literalTypes.add(OptionalCallLiteralStage.exactScalarLiteralType(literal));
        }
        if (!literalTypes.equals(lexical.bound().assertedSourceTypes())) {
            throw new IllegalArgumentException(
                    "derived literal types disagree with original declaration binding");
        }

        int sites = 0;
        ResultSite site = null;
        boolean callerReturned = false;
        for (Ast.Stmt statement : caller.body()) {
            // A scalar literal initializer says nothing about a mutable
            // binding's loan/move or rebinding effects. This narrow witness
            // cannot certify let/val or forged mutable const locals; require
            // the full ownership checker for those call environments.
            if (statement instanceof Ast.BindingStmt binding
                    && (binding.kind() != Ast.BindingKind.CONST
                        || binding.mutableReferent())) {
                throw new IllegalArgumentException(
                        "mutable caller binding requires ownership/effect proof");
            }
            // The lexical preview accepts only linear top-level statements.
            // A typed binding unrelated to this call must still have an
            // independently valid scalar literal result; it cannot smuggle
            // an ill-typed caller body into a checker-issued witness.
            if (statement instanceof Ast.BindingStmt binding
                    && binding.initializer() instanceof Ast.LiteralExpr literal) {
                requireMatchingCallerScalarResult(
                        binding.declaredType(),
                        OptionalCallLiteralStage.exactScalarLiteralType(literal),
                        "other local literal binding");
            }
            if (statement instanceof Ast.ReturnStmt returned) {
                callerReturned = true;
                if (returned.value() == call) {
                    if (!exactScalar(caller.returnType())
                            || !caller.returnType().equals(selected.returnType())) {
                        throw new IllegalArgumentException(
                                "caller return type differs from selected callable result");
                    }
                } else if (returned.value() == null) {
                    if (!isVoid(caller.returnType())) {
                        throw new IllegalArgumentException(
                                "non-void caller needs explicit return value");
                    }
                } else if (returned.value() instanceof Ast.LiteralExpr literal) {
                    requireMatchingCallerScalarResult(
                            caller.returnType(),
                            OptionalCallLiteralStage.exactScalarLiteralType(literal),
                            "caller return");
                } else {
                    // The tiny lexical witness already refuses other direct
                    // expression shapes, but keep the result proof independent.
                    throw new IllegalArgumentException(
                            "caller return requires full semantic type checking");
                }
            }
            if (statement instanceof Ast.BindingStmt binding
                    && binding.initializer() == call) {
                if (selected.returnType().name().equals("void")) {
                    throw new IllegalArgumentException("void call cannot initialize a value binding");
                }
                Ast.TypeRef declared = binding.declaredType();
                if (declared != null
                        && (!declared.equals(Ast.TypeRef.inferred()))
                        && (!exactScalar(declared)
                            || !declared.equals(selected.returnType()))) {
                    throw new IllegalArgumentException(
                            "binding declared type differs from selected callable result");
                }
                site = ResultSite.BINDING;
                sites++;
            } else if (statement instanceof Ast.ReturnStmt returned
                    && returned.value() == call) {
                if (caller.returnType() == null
                        || !exactScalar(caller.returnType())
                        || !caller.returnType().equals(selected.returnType())) {
                    throw new IllegalArgumentException(
                            "caller return type differs from selected callable result");
                }
                site = ResultSite.RETURN;
                sites++;
            } else if (statement instanceof Ast.ExprStmt expression
                    && expression.expression() == call) {
                site = ResultSite.EXPRESSION_STATEMENT;
                sites++;
            }
        }
        if (sites != 1) {
            throw new IllegalArgumentException(
                    "call must occupy one unambiguous immediate result context");
        }
        if (!isVoid(caller.returnType()) && !callerReturned) {
            throw new IllegalArgumentException(
                    "non-void caller must explicitly return a value on every path");
        }
        return new CheckedScalarInvocationWitness(
                lexical, selected, selected.returnType(), site, literalTypes);
    }

    /**
     * Conservative declaration-body guard, NOT general function checking.
     *
     * The preview must not mistake a source-declared result type for an
     * independently verified implementation: a malformed return body can be
     * constructed directly in the AST without going through TypeChecker.
     * Admitting only a single pure return removes this ambiguity without
     * inventing ownership, effect or runtime authority.
     */
    private static void requireLocallyProvableCalleeBody(Ast.FunctionDecl selected) {
        if (selected.body().size() != 1
                || !(selected.body().getFirst() instanceof Ast.ReturnStmt ret)) {
            throw new IllegalArgumentException(
                    "callee body requires full semantic and effect checking");
        }
        if ("void".equals(selected.returnType().name())) {
            if (ret.value() != null) {
                throw new IllegalArgumentException(
                        "void callee return value requires semantic validation");
            }
            return;
        }
        if (ret.value() instanceof Ast.LiteralExpr literal) {
            if (!selected.returnType().equals(
                    OptionalCallLiteralStage.exactScalarLiteralType(literal))) {
                throw new IllegalArgumentException(
                        "callee literal return contradicts its declared scalar result");
            }
            return;
        }
        if (ret.value() instanceof Ast.NameExpr name) {
            Ast.Param returned = null;
            for (Ast.Param param : selected.parameters()) {
                if (!param.name().equals(name.name())) continue;
                if (returned != null) {
                    throw new IllegalArgumentException(
                            "duplicate formal name requires declaration checking");
                }
                returned = param;
            }
            if (returned != null && !returned.optional()
                    && !returned.mutable() && !returned.structural()
                    && selected.returnType().equals(returned.valueType())) {
                return;
            }
        }
        throw new IllegalArgumentException(
                "callee return expression requires full semantic and effect checking");
    }

    private static void requireDistinctFormals(List<Ast.Param> formals, String owner) {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (Ast.Param formal : formals) {
            // In Oreslang, constructor self is a special receiver binder,
            // never an ordinary guest value slot. The public AST permits
            // synthesizing a module-function formal named self without using
            // the parser, so the narrow preview must fail closed rather than
            // pretend that it has verified receiver erasure and ABI arity.
            if ("self".equals(formal.name())) {
                throw new IllegalArgumentException(
                        owner + " cannot use constructor self as an ordinary scalar formal");
            }
            if (!names.add(formal.name())) {
                throw new IllegalArgumentException(
                        owner + " has duplicate formal '" + formal.name()
                                + "'; lexical binding requires the full checker");
            }
        }
    }

    private static void requireMatchingCallerScalarResult(
            Ast.TypeRef declared, Ast.TypeRef actual, String context) {
        if (declared != null && !Ast.TypeRef.inferred().equals(declared)
                && (!exactScalar(declared) || !declared.equals(actual))) {
            throw new IllegalArgumentException(
                    context + " type contradicts the original scalar expression");
        }
    }

    private static boolean isVoid(Ast.TypeRef t) {
        return t != null && !t.inferArguments() && t.arguments().isEmpty()
                && "void".equals(t.name());
    }

    private static boolean exactScalar(Ast.TypeRef t) {
        return t != null && !t.inferArguments() && t.arguments().isEmpty()
                && !t.isBorrow() && !t.isOmittableFormal()
                && List.of("int", "float", "bool", "string").contains(t.name());
    }

    private static boolean exactScalarOrVoid(Ast.TypeRef t) {
        return exactScalar(t) || (t != null && !t.inferArguments()
                && t.arguments().isEmpty() && "void".equals(t.name()));
    }
}
