package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic scalar-literal ABI materialization from the compiler's narrow
 * same-module, original-AST source/type witness.
 *
 * <p>This is NOT an executable Oreslang function call or a general runtime
 * Option implementation. It demonstrates concrete, typed Option payload
 * construction, once-per-source capture, and declaration-order ABI placement
 * without evaluating guest expressions. Before passing a value into a guest
 * function, TypeChecker/OwnershipChecker and the real runtime must establish
 * the full lexical, effect, borrow and ABI contract.
 */
public final class CheckedLiteralOptionAbi {
    private CheckedLiteralOptionAbi() { }

    /** A scalar copied from its exact original literal, not a guest value. */
    public record CapturedScalar(Ast.TypeRef type, Object value) {
        public CapturedScalar {
            Objects.requireNonNull(type, "captured type");
            Objects.requireNonNull(value, "captured value");
            // Only immutable Java equivalents of Oreslang primitive literals
            // may be carried by this narrow, source-only preview.
            Ast.TypeRef actual = OptionalCallLiteralStage.exactScalarLiteralType(
                    new Ast.LiteralExpr(value));
            if (!type.equals(actual)) {
                throw new IllegalArgumentException("captured scalar type mismatch");
            }
        }
    }

    /** Explicitly distinguish supplied scalars from typed Option envelopes. */
    public sealed interface AbiValue permits RequiredValue, SomeValue, NoneValue {
        Ast.TypeRef calleeType();
    }

    public record RequiredValue(CapturedScalar scalar) implements AbiValue {
        public RequiredValue {
            Objects.requireNonNull(scalar, "required scalar");
        }
        @Override public Ast.TypeRef calleeType() { return scalar.type(); }
    }

    public record SomeValue(Ast.TypeRef elementType, CapturedScalar scalar)
            implements AbiValue {
        public SomeValue {
            requireScalarElementType(elementType);
            Objects.requireNonNull(scalar, "Some<T> payload");
            if (!elementType.equals(scalar.type())) {
                throw new IllegalArgumentException("Some<T> payload type mismatch");
            }
        }
        @Override public Ast.TypeRef calleeType() {
            return new Ast.TypeRef("Option", List.of(elementType), false);
        }
    }

    public record NoneValue(Ast.TypeRef elementType) implements AbiValue {
        public NoneValue {
            requireScalarElementType(elementType);
        }
        @Override public Ast.TypeRef calleeType() {
            return new Ast.TypeRef("Option", List.of(elementType), false);
        }
    }

    /**
     * Factory-owned immutable snapshot. A public record constructor would
     * allow a consumer to forge a "checked" preview from arbitrary values.
     * Only materialize() may create this evidence object.
     */
    public static final class Preview {
        private final List<CapturedScalar> sourceTemps;
        private final List<AbiValue> abiSlots;

        private Preview(List<CapturedScalar> sourceTemps, List<AbiValue> abiSlots) {
            this.sourceTemps = List.copyOf(sourceTemps);
            this.abiSlots = List.copyOf(abiSlots);
        }

        public List<CapturedScalar> sourceTemps() { return sourceTemps; }
        public List<AbiValue> abiSlots() { return abiSlots; }
        public BoundInvocation.Readiness readiness() {
            return BoundInvocation.Readiness.SHAPE_ONLY;
        }
        public void requireExecutable() {
            throw new IllegalStateException(
                    "literal Option ABI materialization is not an executable guest call");
        }
    }

    private static void requireScalarElementType(Ast.TypeRef type) {
        Objects.requireNonNull(type, "Option<T> element type");
        if (type.inferArguments() || !type.arguments().isEmpty()
                || type.isBorrow() || type.isOmittableFormal()
                || !List.of("int", "float", "bool", "string").contains(type.name())) {
            throw new IllegalArgumentException(
                    "literal Option ABI requires an exact concrete scalar element type");
        }
    }

    /**
     * Materialize ONLY source-literal values of the checked, static, same-module
     * candidate. No lambda/callee/receiver evaluation and no guest invocation.
     * PASS_OPTION cannot be reached from exact scalar literals: accepting it
     * would confuse a lexical NameExpr("None") with a proved Option<T> value.
     */
    public static Preview materialize(CheckedScalarInvocationWitness checked) {
        Objects.requireNonNull(checked, "checked scalar witness");
        if (checked.readiness() != BoundInvocation.Readiness.SHAPE_ONLY) {
            throw new IllegalArgumentException("unexpected witness readiness");
        }
        SameModuleLiteralInvocation lexical = checked.lexical();
        OptionalCallTempLowering.Recipe recipe = lexical.tempRecipe();
        if (recipe.targetKind() != OptionalCallTempLowering.TargetKind.STATIC_MODULE_FUNCTION
                || recipe.receiverExpression() != null
                || recipe.receiverTempIndex() != -1
                || recipe.bound().selectedDeclaration() != checked.selectedDeclaration()) {
            throw new IllegalArgumentException(
                    "scalar Option ABI requires an original static module function");
        }
        Ast.StagedCallExpr call = lexical.source();

        List<OptionalCallTempLowering.ArgumentTemp> arguments = recipe.argumentTemps();
        if (checked.checkedLiteralTypes().size() != arguments.size()) {
            throw new IllegalArgumentException("checked literal count mismatch");
        }
        List<CapturedScalar> captures = new ArrayList<>(arguments.size());
        for (int i = 0; i < arguments.size(); i++) {
            var argument = arguments.get(i);
            if (argument.evaluatedIndex() != i || argument.tempIndex() != i
                    || argument.rawCallItemIndex() >= call.arguments().size()
                    || call.arguments().get(argument.rawCallItemIndex()).omitted()
                    || call.arguments().get(argument.rawCallItemIndex()).spread()
                    || call.arguments().get(argument.rawCallItemIndex()).value()
                            != argument.expression()
                    || !(argument.expression() instanceof Ast.LiteralExpr literal)) {
                throw new IllegalArgumentException(
                        "literal capture disagrees with original source occurrence");
            }
            captures.add(new CapturedScalar(
                    checked.checkedLiteralTypes().get(i), literal.value()));
        }

        List<Ast.Param> formals = checked.selectedDeclaration().parameters();
        if (formals.size() != recipe.abiSlots().size()) {
            throw new IllegalArgumentException("ABI arity differs from selected formals");
        }
        List<AbiValue> slots = new ArrayList<>(formals.size());
        for (int i = 0; i < formals.size(); i++) {
            Ast.Param formal = formals.get(i);
            OptionalCallTempLowering.AbiSlot slot = recipe.abiSlots().get(i);
            if (slot.formalIndex() != i || !slot.name().equals(formal.name())
                    || !slot.calleeType().equals(formal.calleeType())) {
                throw new IllegalArgumentException(
                        "ABI slot differs from original parsed formal");
            }
            AbiValue materialized;
            switch (slot.action()) {
                case PASS_REQUIRED -> {
                    if (formal.optional() || slot.tempIndex() < 0
                            || slot.tempIndex() >= captures.size()) {
                        throw new IllegalArgumentException("invalid required scalar slot");
                    }
                    materialized = new RequiredValue(captures.get(slot.tempIndex()));
                }
                case WRAP_SOME -> {
                    if (!formal.optional() || slot.tempIndex() < 0
                            || slot.tempIndex() >= captures.size()) {
                        throw new IllegalArgumentException("invalid Some<T> scalar slot");
                    }
                    materialized = new SomeValue(
                            formal.valueType(), captures.get(slot.tempIndex()));
                }
                case INJECT_NONE -> {
                    if (!formal.optional() || slot.tempIndex() != -1) {
                        throw new IllegalArgumentException("invalid typed None<T> slot");
                    }
                    // An explicit '_' is an omitted raw item, not a value.
                    if (slot.rawCallItemIndex() >= 0
                            && (slot.rawCallItemIndex() >= call.arguments().size()
                                || !call.arguments().get(slot.rawCallItemIndex()).omitted())) {
                        throw new IllegalArgumentException(
                                "None<T> injection has a supplied raw source");
                    }
                    materialized = new NoneValue(formal.valueType());
                }
                case PASS_OPTION -> throw new IllegalArgumentException(
                        "existing Option<T> values require real lexical/ownership proof");
                default -> throw new IllegalArgumentException("unknown ABI adaptation");
            }
            if (!materialized.calleeType().equals(slot.calleeType())) {
                throw new IllegalArgumentException(
                        "materialized ABI value differs from callee slot type");
            }
            slots.add(materialized);
        }
        return new Preview(captures, slots);
    }
}
