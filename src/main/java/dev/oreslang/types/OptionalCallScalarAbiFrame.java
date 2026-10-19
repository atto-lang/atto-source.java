package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Backend-neutral, NON-EXECUTABLE reference frame for the narrow literal-only
 * call witness. This materializes actual Java scalar *reference values* but
 * does not construct Oreslang runtime Options, run guest expressions, dispatch
 * calls or authorize an unproved ownership/effect boundary.
 *
 * <p>The frame intentionally exposes three different semantic variants:
 * required scalar, Some(scalar), and typed None. An explicit Option<T> argument
 * cannot be self-typed from a scalar literal and must be checked by the full
 * lexical TypeChecker before PASS_OPTION becomes supported here.
 */
public final class OptionalCallScalarAbiFrame {
    private OptionalCallScalarAbiFrame() { }

    public sealed interface Value permits Required, Some, None {
        Ast.TypeRef abiType();
    }

    /** Required, concrete scalar: false and zero are values, never omission. */
    public record Required(Ast.TypeRef abiType, Object scalar) implements Value {
        public Required {
            requireScalar(abiType, scalar);
        }
    }

    /** Option<T>::Some preserves exactly the supplied T, including false/zero. */
    public record Some(Ast.TypeRef abiType, Object scalar) implements Value {
        public Some {
            requireScalar(optionPayload(abiType), scalar);
        }
    }

    /** Option<T>::None has a typed ABI slot and no scalar payload. */
    public record None(Ast.TypeRef abiType) implements Value {
        public None {
            optionPayload(abiType);
        }
    }

    /**
     * One declaration-order slot; raw call-item -1 means an implicit omission,
     * whereas an explicit '_' / 'omit' has a real raw index but no source temp.
     */
    public record Slot(int formalIndex, int originalCallItemIndex,
                       int evaluatedSourceIndex, Value value) {
        public Slot {
            if (formalIndex < 0 || originalCallItemIndex < -1
                    || evaluatedSourceIndex < -1) {
                throw new IllegalArgumentException("invalid ABI source provenance");
            }
            Objects.requireNonNull(value, "typed ABI value");
            if ((evaluatedSourceIndex == -1) != (value instanceof None)) {
                throw new IllegalArgumentException(
                        "only omitted formals may lack an evaluated source expression");
            }
            if (evaluatedSourceIndex >= 0 && originalCallItemIndex < 0) {
                throw new IllegalArgumentException(
                        "supplied ABI scalar must have an original call item");
            }
        }
    }

    /**
     * Factory-only reference frame. Public ABI value/slot records are merely
     * inert data; only from(CheckedScalarInvocationWitness) may create a frame
     * carrying a verified original declaration and source-origin mapping.
     */
    public static final class Frame {
        private final CheckedScalarInvocationWitness checked;
        private final Ast.FunctionDecl selected;
        private final List<Slot> slots;

        private Frame(CheckedScalarInvocationWitness checked, List<Slot> slots) {
            this.checked = Objects.requireNonNull(checked, "checked callsite origin");
            this.selected = checked.selectedDeclaration();
            this.slots = List.copyOf(slots);
            var recipe = checked.lexical().tempRecipe();
            if (recipe.bound().selectedDeclaration() != selected
                    || this.slots.size() != selected.parameters().size()
                    || recipe.abiSlots().size() != this.slots.size()) {
                throw new IllegalArgumentException("frame must cover original selected declaration");
            }
            for (int i = 0; i < this.slots.size(); i++) {
                Slot slot = this.slots.get(i);
                var origin = recipe.abiSlots().get(i);
                if (slot.formalIndex() != i
                        || !slot.value().abiType().equals(selected.parameters().get(i).calleeType())
                        || slot.originalCallItemIndex() != origin.rawCallItemIndex()
                        || slot.evaluatedSourceIndex() != origin.tempIndex()
                        || (origin.action() == OptionalCallAbiPreview.Action.INJECT_NONE)
                                != (slot.value() instanceof None)
                        || (origin.action() == OptionalCallAbiPreview.Action.WRAP_SOME)
                                != (slot.value() instanceof Some)) {
                    throw new IllegalArgumentException(
                            "frame ABI value/origin diverged from checked source and formal");
                }
            }
        }

        /** Exact lexical source/selected declaration evidence (still non-executable). */
        public CheckedScalarInvocationWitness checkedSource() { return checked; }
        public Ast.FunctionDecl selected() { return selected; }
        public List<Slot> slots() { return slots; }
        public BoundInvocation.Readiness readiness() {
            return BoundInvocation.Readiness.SHAPE_ONLY;
        }
        public void requireExecutable() {
            throw new IllegalStateException(
                    "reference Option values are not proven runtime or ownership lowering");
        }
    }

    /**
     * Produce a reference frame only from a checker-issued scalar witness.
     * Literal values are read once per evaluated *position* from its verified
     * source-order temp recipe. Never evaluate AST code or accept arbitrary
     * caller-supplied types/values as an executable instruction.
     */
    public static Frame from(CheckedScalarInvocationWitness checked) {
        Objects.requireNonNull(checked, "checker-issued scalar witness");
        if (checked.readiness() != BoundInvocation.Readiness.SHAPE_ONLY) {
            throw new IllegalArgumentException("unexpected invocation readiness");
        }
        var lexical = checked.lexical();
        var recipe = lexical.tempRecipe();
        if (recipe.targetKind() != OptionalCallTempLowering.TargetKind.STATIC_MODULE_FUNCTION
                || recipe.receiverExpression() != null || recipe.receiverTempIndex() != -1
                || recipe.bound() != lexical.bound()
                || recipe.bound().selectedDeclaration() != checked.selectedDeclaration()) {
            throw new IllegalArgumentException("reference frame requires an exact static target");
        }

        List<OptionalCallTempLowering.ArgumentTemp> argumentTemps = recipe.argumentTemps();
        List<Ast.TypeRef> independentlyChecked = checked.checkedLiteralTypes();
        if (argumentTemps.size() != independentlyChecked.size()) {
            throw new IllegalArgumentException("literal source/type count mismatch");
        }
        List<Object> evaluatedScalars = new ArrayList<>(argumentTemps.size());
        for (int i = 0; i < argumentTemps.size(); i++) {
            var temp = argumentTemps.get(i);
            if (temp.evaluatedIndex() != i || temp.tempIndex() != i
                    || !(temp.expression() instanceof Ast.LiteralExpr literal)) {
                throw new IllegalArgumentException("nonliteral or reordered source temp");
            }
            Ast.TypeRef type = OptionalCallLiteralStage.exactScalarLiteralType(literal);
            if (!type.equals(independentlyChecked.get(i))) {
                throw new IllegalArgumentException("literal scalar type/proof mismatch");
            }
            evaluatedScalars.add(literal.value());
        }

        List<Slot> ordered = new ArrayList<>();
        List<Ast.Param> formals = checked.selectedDeclaration().parameters();
        if (recipe.abiSlots().size() != formals.size()) {
            throw new IllegalArgumentException("frame and selected arity disagree");
        }
        for (int i = 0; i < formals.size(); i++) {
            var abi = recipe.abiSlots().get(i);
            Ast.Param formal = formals.get(i);
            if (abi.formalIndex() != i || !abi.name().equals(formal.name())
                    || !abi.calleeType().equals(formal.calleeType())) {
                throw new IllegalArgumentException("selected formal/ABI mapping diverged");
            }
            int source = abi.tempIndex();
            Value value;
            switch (abi.action()) {
                case PASS_REQUIRED -> {
                    if (formal.optional() || source < 0 || source >= evaluatedScalars.size()
                            || !independentlyChecked.get(source).equals(formal.valueType())) {
                        throw new IllegalArgumentException("required scalar proof disagrees with ABI");
                    }
                    value = new Required(abi.calleeType(), evaluatedScalars.get(source));
                }
                case WRAP_SOME -> {
                    if (!formal.optional() || source < 0 || source >= evaluatedScalars.size()
                            || !independentlyChecked.get(source).equals(formal.valueType())) {
                        throw new IllegalArgumentException("Some scalar proof disagrees with ABI");
                    }
                    value = new Some(abi.calleeType(), evaluatedScalars.get(source));
                }
                case INJECT_NONE -> {
                    if (!formal.optional() || source != -1) {
                        throw new IllegalArgumentException("None source/optionality mismatch");
                    }
                    value = new None(abi.calleeType());
                }
                case PASS_OPTION -> throw new IllegalArgumentException(
                        "explicit Option<T> values require full lexical type/ownership checking");
                default -> throw new IllegalArgumentException("unreviewed ABI adaptation");
            }
            int evaluatedSource = source;
            ordered.add(new Slot(i, abi.rawCallItemIndex(), evaluatedSource, value));
        }
        return new Frame(checked, ordered);
    }

    private static Ast.TypeRef optionPayload(Ast.TypeRef option) {
        Objects.requireNonNull(option, "Option ABI type");
        if (!"Option".equals(option.name()) || option.inferArguments()
                || option.arguments().size() != 1) {
            throw new IllegalArgumentException("typed None/Some requires exact Option<T>");
        }
        return option.arguments().getFirst();
    }

    private static void requireScalar(Ast.TypeRef type, Object value) {
        Objects.requireNonNull(type, "scalar type");
        if (!(value instanceof Long || value instanceof Double
                || value instanceof Boolean || value instanceof String)) {
            throw new IllegalArgumentException("not an exact scalar literal value");
        }
        if (!(type.equals(scalarType(value)))) {
            throw new IllegalArgumentException("scalar value contradicts declared type");
        }
    }

    private static Ast.TypeRef scalarType(Object value) {
        if (value instanceof Long) return Ast.TypeRef.simple("int");
        if (value instanceof Double) return Ast.TypeRef.simple("float");
        if (value instanceof Boolean) return Ast.TypeRef.simple("bool");
        if (value instanceof String) return Ast.TypeRef.simple("string");
        throw new IllegalArgumentException("unsupported scalar");
    }
}
