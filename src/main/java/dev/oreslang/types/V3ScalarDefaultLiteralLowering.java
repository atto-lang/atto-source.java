package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A deliberately narrow executable-shape intermediate: lower an already
 * selected v3 direct function call with only immutable scalar *literal* values
 * and scalar constant defaults to an ordinary full-arity positional CallExpr.
 *
 * This component NEVER authorizes guest invocation. The caller must still
 * prove lexical owner/visibility, the exact declaration fingerprint, imports,
 * shadowing, return/effect/ownership rules and backend dispatch. The legacy
 * interpreter/type checker continue rejecting v3 programs.
 *
 * No Option<T>, aliases, generics, methods, constructors, lambdas, runtime
 * defaults, indirect calls, or effectful source expressions may enter here.
 */
public final class V3ScalarDefaultLiteralLowering {
    private V3ScalarDefaultLiteralLowering() { }

    /**
     * Factory-only carrier: callers must not be allowed to construct a
     * fingerprint-looking Preview around arbitrary AST call expressions.
     * This remains NON-EXECUTABLE and never replaces backend admission.
     */
    public static final class Preview {
        private final Ast.CallExpr fullArityCall;
        private final List<Integer> evaluatedSourceCallItemIndices;
        private final List<Boolean> formalUsesDefault;
        private final String selectedSourceContractFingerprint;

        private Preview(
                Ast.CallExpr fullArityCall,
                List<Integer> evaluatedSourceCallItemIndices,
                List<Boolean> formalUsesDefault,
                String selectedSourceContractFingerprint) {
            this.fullArityCall = Objects.requireNonNull(fullArityCall, "lowered call");
            this.evaluatedSourceCallItemIndices =
                    List.copyOf(evaluatedSourceCallItemIndices);
            this.formalUsesDefault = List.copyOf(formalUsesDefault);
            if (selectedSourceContractFingerprint == null
                    || !selectedSourceContractFingerprint.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid v3 source contract fingerprint");
            }
            this.selectedSourceContractFingerprint = selectedSourceContractFingerprint;
            if (fullArityCall.arguments().size() != formalUsesDefault.size()) {
                throw new IllegalArgumentException(
                        "default plan must retain full formal ABI arity");
            }
        }

        public Ast.CallExpr fullArityCall() { return fullArityCall; }
        public List<Integer> evaluatedSourceCallItemIndices() {
            return evaluatedSourceCallItemIndices;
        }
        public List<Boolean> formalUsesDefault() { return formalUsesDefault; }
        public String selectedSourceContractFingerprint() {
            return selectedSourceContractFingerprint;
        }
    }

    /**
     * Validate exact input AST before constructing ANY replacement call nodes.
     * This is a source literal proof: no compiler pass should execute the
     * returned CallExpr without passing the global type/ownership checks.
     */
    public static Preview lower(
            Ast.FunctionDecl selected,
            Ast.StagedCallExpr original) {
        Objects.requireNonNull(selected, "selected declaration");
        Objects.requireNonNull(original, "original call");
        if (original.typeArgumentsPresent() || !(original.callee() instanceof Ast.NameExpr callee)
                || !callee.name().equals(selected.name())
                || selected.kind() != Ast.CallableKind.FNC
                || selected.async() || selected.suspends() || selected.generator()
                || selected.structural() || selected.nonLexical() || selected.trapped()
                || selected.actorKind() != Ast.ActorKind.NONE
                || !selected.genericParameters().isEmpty()) {
            throw new IllegalArgumentException("scalar default lowering requires a plain local direct fnc");
        }

        // This proof is deliberately not polymorphic over declaration
        // annotations: caller roles, capabilities, ownership or codegen
        // annotations must never disappear in a scalar-only rewrite.
        for (Ast.Annotation annotation : selected.annotations()) {
            if (!annotation.name().equals("DefaultArgVals")) {
                throw new IllegalArgumentException(
                        "scalar literal rewriting cannot erase callable annotation '"
                                + annotation.name() + "'");
            }
        }

        List<V3DefaultArgValsPolicy.FormalPreview> formals =
                V3DefaultArgValsPolicy.validateDeclaration(
                        selected.parameters(), selected.annotations());
        List<OptionalCallShapeBinder.Parameter> shapes =
                OptionalCallShapeBinder.stagedV3ParameterShapes(
                        selected.parameters(), selected.annotations());
        OptionalCallShapeBinder.Plan bound = OptionalCallShapeBinder.bindParsed(
                shapes, original.arguments());
        V3CallLoweringPreview.Preview checked =
                V3CallLoweringPreview.plan(
                        selected.parameters(), selected.annotations(), original.arguments(), bound);
        if (checked.formalOrderSlots().size() != selected.parameters().size()) {
            throw new IllegalArgumentException("staged call lost declared ABI arity");
        }
        String fingerprint = V3SourceContractFingerprint.sha256Hex(
                selected.parameters(), selected.annotations());

        List<Ast.Expr> fullArguments = new ArrayList<>(formals.size());
        List<Boolean> fromDefault = new ArrayList<>(formals.size());
        List<Ast.Expr> evaluated = checked.unevaluatedExpressionsInSourceOrder();

        for (int i = 0; i < formals.size(); i++) {
            V3DefaultArgValsPolicy.FormalPreview formal = formals.get(i);
            V3CallLoweringPreview.Slot slot = checked.formalOrderSlots().get(i);
            // Never guess nominal Option identity from the printed Option name,
            // even for an explicitly supplied value.
            if (formal.optionTyped() || formal.bodyType().containsStagedV3Option()
                    || formal.bodyType().inferArguments()
                    || formal.bodyType().isBorrow()
                    || !isPlainScalar(formal.bodyType())) {
                throw new IllegalArgumentException("scalar lowering cannot materialize Option or unresolved type");
            }
            Ast.Expr expression;
            boolean usesDefault;
            if (slot.action() == V3CallLoweringPreview.SlotAction.CHECK_SUPPLIED) {
                int source = slot.evaluatedSourceIndex();
                if (source < 0 || source >= evaluated.size()) {
                    throw new IllegalArgumentException("supplied source index out of bounds");
                }
                expression = evaluated.get(source);
                if (!matchesScalarLiteral(expression, formal.bodyType())) {
                    throw new IllegalArgumentException(
                            "source argument lacks exact independent scalar literal type");
                }
                usesDefault = false;
            } else if (slot.action() == V3CallLoweringPreview.SlotAction.DEFAULT_SCALAR) {
                if (slot.closedDefault() == null) {
                    throw new IllegalArgumentException("missing compiler-validated default literal");
                }
                expression = literalNode(slot.closedDefault(), formal.bodyType());
                usesDefault = true;
            } else {
                throw new IllegalArgumentException(
                        "scalar lowering rejects Option materialization and nonconstant omissions");
            }
            fullArguments.add(expression);
            fromDefault.add(usesDefault);
        }
        return new Preview(new Ast.CallExpr(
                new Ast.NameExpr(selected.name()), List.copyOf(fullArguments)),
                bound.callItemIndexByEvaluatedSource(), fromDefault, fingerprint);
    }

    private static boolean isPlainScalar(Ast.TypeRef type) {
        return type.arguments().isEmpty() && !type.inferArguments()
                && (type.name().equals("int") || type.name().equals("bool")
                    || type.name().equals("string"));
    }

    private static boolean matchesScalarLiteral(Ast.Expr expression, Ast.TypeRef type) {
        if (!(expression instanceof Ast.LiteralExpr literal)) return false;
        Object value = literal.value();
        return switch (type.name()) {
            case "int" -> value instanceof Long;
            case "bool" -> value instanceof Boolean;
            case "string" -> value instanceof String;
            default -> false;
        };
    }

    private static Ast.Expr literalNode(
            DefaultArgumentPolicy.Literal defaultValue, Ast.TypeRef expected) {
        Object value = switch (defaultValue.kind()) {
            case INT -> defaultValue.value();
            case BOOL -> defaultValue.value();
            case STRING -> defaultValue.value();
            case NONE -> throw new IllegalArgumentException("scalar default cannot be None");
        };
        Ast.LiteralExpr result = new Ast.LiteralExpr(value);
        if (!matchesScalarLiteral(result, expected)) {
            throw new IllegalArgumentException("default literal has wrong primitive type");
        }
        return result;
    }
}
