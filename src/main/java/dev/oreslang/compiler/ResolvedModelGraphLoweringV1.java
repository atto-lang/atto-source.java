package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.PortableModelGraphDigestV1.Constant;
import dev.oreslang.compiler.PortableModelGraphDigestV1.Graph;
import dev.oreslang.compiler.PortableModelGraphDigestV1.Node;
import dev.oreslang.compiler.PortableModelGraphDigestV1.Signature;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * First source->resolved-graph bridge for the ordered model v1 subset.
 * Concrete configuration is resolved before portable graph hashing.
 */
public final class ResolvedModelGraphLoweringV1 {
    private ResolvedModelGraphLoweringV1() { }

    private static final BigInteger MIN_I64 = BigInteger.ONE.shiftLeft(63).negate();
    private static final BigInteger MAX_I64 = BigInteger.ONE.shiftLeft(63).subtract(BigInteger.ONE);

    public static Graph lower(
            Ast.ModelDecl source,
            String modelTypeId,
            Map<String, Signature> trusted,
            Map<String, Ast.ModelValue> overrides) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(modelTypeId);
        Objects.requireNonNull(trusted);
        Objects.requireNonNull(overrides);

        Map<String, Ast.ModelValue> config = new HashMap<>();
        for (Ast.ModelFormal formal : source.formals()) {
            Ast.ModelValue value = overrides.get(formal.name());
            if (value == null) value = formal.defaultValue();
            if (value == null) throw new IllegalArgumentException("MODEL_NONCONSTANT_CONFIG");
            if (value.kind() == Ast.ModelValueKind.FORMAL)
                throw new IllegalArgumentException("MODEL_NONCONSTANT_CONFIG");
            config.put(formal.name(), value);
        }
        for (String name : overrides.keySet())
            if (!config.containsKey(name)) throw new IllegalArgumentException("MODEL_UNKNOWN_FORMAL");

        List<Node> nodes = new ArrayList<>();
        String prior = null;
        for (int index = 0; index < source.operations().size(); index++) {
            Ast.ModelOperation op = source.operations().get(index);
            Signature sig = trusted.get(op.name());
            if (sig == null) throw new IllegalArgumentException("MODEL_UNKNOWN_OP");
            if (!"pure".equals(sig.effect())) throw new IllegalArgumentException("MODEL_EFFECTFUL_OP");
            if (index == 0 && sig.inputs().size() > 1)
                throw new IllegalArgumentException("MODEL_ARITY_MISMATCH");
            if (index > 0 && sig.inputs().size() != 1)
                throw new IllegalArgumentException("MODEL_ARITY_MISMATCH");
            if (op.arguments().size() != sig.constKinds().size())
                throw new IllegalArgumentException("MODEL_CONST_ARITY_MISMATCH");

            List<Constant> constants = new ArrayList<>();
            for (int i = 0; i < op.arguments().size(); i++) {
                Ast.ModelValue value = op.arguments().get(i).value();
                if (value.kind() == Ast.ModelValueKind.FORMAL) {
                    value = config.get(value.source());
                    if (value == null) throw new IllegalArgumentException("MODEL_UNKNOWN_FORMAL");
                }
                constants.add(canonical(value, sig.constKinds().get(i)));
            }

            String id = "n" + index;
            nodes.add(new Node(id, op.name(),
                    prior == null ? List.of() : List.of(prior),
                    constants, sig));
            prior = id;
        }
        if (nodes.isEmpty()) throw new IllegalArgumentException("MODEL_EMPTY_BODY");
        return new Graph(1, modelTypeId, nodes, List.of(prior));
    }

    private static Constant canonical(Ast.ModelValue value, String expectedKind) {
        return switch (expectedKind) {
            case "i64" -> {
                if (value.kind() != Ast.ModelValueKind.INTEGER)
                    throw new IllegalArgumentException("MODEL_CONST_TYPE_MISMATCH");
                String text = value.source().replace("_", "");
                if (!text.matches("-?(0|[1-9][0-9]*)"))
                    throw new IllegalArgumentException("MODEL_INVALID_CONST");
                BigInteger n = new BigInteger(text);
                if (n.compareTo(MIN_I64) < 0 || n.compareTo(MAX_I64) > 0)
                    throw new IllegalArgumentException("MODEL_CONST_OVERFLOW");
                yield new Constant("i64", n.toString());
            }
            case "bool" -> {
                if (value.kind() != Ast.ModelValueKind.BOOLEAN)
                    throw new IllegalArgumentException("MODEL_CONST_TYPE_MISMATCH");
                yield new Constant("bool", value.source());
            }
            default -> throw new IllegalArgumentException("MODEL_NONCONSTANT_CONFIG");
        };
    }
}
