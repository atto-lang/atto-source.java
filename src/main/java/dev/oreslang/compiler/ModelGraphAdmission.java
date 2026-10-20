package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Bounded, pure sequential ModelGraph admission prototype. The registry is
 * constructed by the COMPILER or trusted linker, never from guest source.
 * This pass does not make models executable: TypeChecker currently rejects
 * every ModelDecl until actual runtime/code generation is implemented.
 */
public final class ModelGraphAdmission {
    private ModelGraphAdmission() { }

    public record Port(String dtype, List<Long> shape, String unit) {
        public Port {
            Objects.requireNonNull(dtype);
            shape = List.copyOf(shape);
            if (unit == null) unit = "";
        }
    }

    public record Signature(
            String name, Port input, Port output, String effect,
            String abi, String implDigest, String provenance,
            long scratchBytes, boolean vjp) {
        public Signature {
            Objects.requireNonNull(name);
            Objects.requireNonNull(output);
            Objects.requireNonNull(effect);
            Objects.requireNonNull(abi);
            Objects.requireNonNull(implDigest);
            Objects.requireNonNull(provenance);
        }
    }

    public record Limits(int maxNodes, int maxDepth, long maxBytes) {
        public Limits {
            if (maxNodes <= 0 || maxDepth <= 0 || maxBytes < 0)
                throw new IllegalArgumentException("MODEL_INVALID_BUDGET");
        }
    }

    public record Node(int id, String op, String abi, String implDigest,
                       String provenance, List<String> config,
                       Port input, Port output, long scratchBytes, boolean vjp) {
        public Node { config = List.copyOf(config); }
    }
    public record ModelGraph(String name, Port input, Port output,
                             List<String> formalConfig, List<Node> nodes,
                             long upperBoundBytes) {
        public ModelGraph {
            formalConfig = List.copyOf(formalConfig);
            nodes = List.copyOf(nodes);
        }
    }

    private static long bytes(Port port) {
        if (port == null || port.shape().size() > 8)
            throw new IllegalArgumentException("MODEL_INVALID_PORT");
        long item = switch (port.dtype()) {
            case "bool" -> 1;
            case "i32", "f32" -> 4;
            case "i64", "f64" -> 8;
            default -> throw new IllegalArgumentException("MODEL_INVALID_PORT");
        };
        if (!port.unit().matches("[A-Za-z0-9_/^-]{0,40}"))
            throw new IllegalArgumentException("MODEL_INVALID_PORT");
        boolean zero = false;
        for (long d : port.shape()) {
            if (d < 0) throw new IllegalArgumentException("MODEL_INVALID_EXTENT");
            if (d == 0) zero = true;
        }
        if (zero) return 0;
        try {
            for (long d : port.shape()) item = Math.multiplyExact(item, d);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("MODEL_SIZE_OVERFLOW", overflow);
        }
        return item;
    }

    // Parser checks are not a trust boundary: the compiler also accepts
    // programmatically built ASTs, which must not bypass syntax/budget rules.
    static void validScalar(Ast.ModelValue value, boolean allowFormal) {
        String raw = value.source();
        if (raw.length() > 256)
            throw new IllegalArgumentException("MODEL_NONCONSTANT_CONFIG");
        if (value.kind() == Ast.ModelValueKind.FORMAL) {
            if (!allowFormal || !raw.matches("[A-Za-z][A-Za-z0-9_]{0,127}"))
                throw new IllegalArgumentException("MODEL_NONCONSTANT_CONFIG");
            return;
        }
        switch (value.kind()) {
            case INTEGER -> {
                String canonical = raw.replace("_", "");
                if (!canonical.matches("-?(0|[1-9][0-9]*)"))
                    throw new IllegalArgumentException("MODEL_INVALID_CONST");
                try { new java.math.BigInteger(canonical).longValueExact(); }
                catch (ArithmeticException overflow) {
                    throw new IllegalArgumentException("MODEL_CONST_OVERFLOW", overflow);
                }
            }
            case FLOAT -> {
                if (!raw.matches("[0-9]+\\.[0-9]+([eE][+-]?[0-9]+)?"))
                    throw new IllegalArgumentException("MODEL_INVALID_CONST");
                if (!Double.isFinite(Double.parseDouble(raw)))
                    throw new IllegalArgumentException("MODEL_NONFINITE_CONST");
            }
            case BOOLEAN -> {
                if (!"true".equals(raw) && !"false".equals(raw))
                    throw new IllegalArgumentException("MODEL_INVALID_CONST");
            }
            case STRING -> {
                // Preserve source metadata only. String-to-operator type
                // binding is still owned by the resolved lowering phase.
                if (raw.length() < 2 || raw.charAt(0) != '"' ||
                        raw.charAt(raw.length() - 1) != '"')
                    throw new IllegalArgumentException("MODEL_INVALID_CONST");
            }
            default -> throw new IllegalArgumentException("MODEL_INVALID_CONST");
        }
    }

    static void validDefault(Ast.ModelFormal formal) {
        if (formal.defaultValue() == null) return;
        String type = formal.type().name();
        Ast.ModelValueKind literal = formal.defaultValue().kind();
        boolean compatible = switch (type) {
            case "int", "i32", "i64", "uint" -> literal == Ast.ModelValueKind.INTEGER;
            case "float", "f32", "f64" -> literal == Ast.ModelValueKind.FLOAT;
            case "bool" -> literal == Ast.ModelValueKind.BOOLEAN;
            case "String" -> literal == Ast.ModelValueKind.STRING;
            default -> false; // Typed symbolic defaults require compiler-specific checking.
        };
        if (!compatible) throw new IllegalArgumentException("MODEL_FORMAL_DEFAULT_TYPE_MISMATCH");
        validScalar(formal.defaultValue(), false);
    }

    /**
     * Compile immutable signatures into a source-ordered typed chain. The first
     * operation determines the external model input port; subsequent operations
     * must exactly accept the previous output. There are no implicit casts.
     */
    public static ModelGraph admit(Ast.ModelDecl source, Map<String, Signature> trusted,
                                   Limits limits) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(trusted);
        Objects.requireNonNull(limits);
        if (source.operations().isEmpty()) throw new IllegalArgumentException("MODEL_EMPTY_BODY");
        if (source.operations().size() > limits.maxNodes())
            throw new IllegalArgumentException("MODEL_NODE_BUDGET");
        if (source.operations().size() > limits.maxDepth())
            throw new IllegalArgumentException("MODEL_DEPTH_BUDGET");
        if (source.formals().size() > 16)
            throw new IllegalArgumentException("MODEL_FORMAL_BUDGET");
        Set<String> formals = new HashSet<>();
        List<String> formalConfig = new ArrayList<>();
        for (Ast.ModelFormal formal : source.formals()) {
            if (formal.name().length() > 128 ||
                    !formal.name().matches("[A-Za-z][A-Za-z0-9_]*"))
                throw new IllegalArgumentException("MODEL_NONCONSTANT_CONFIG");
            if (!formals.add(formal.name()))
                throw new IllegalArgumentException("MODEL_DUPLICATE_FORMAL");
            validDefault(formal);
            String d = formal.defaultValue() == null ? "none"
                    : formal.defaultValue().source();
            formalConfig.add(formal.name().length() + ":" + formal.name() + "|"
                    + formal.type().name().length() + ":" + formal.type().name() + "|" + d);
        }
        List<Node> nodes = new ArrayList<>();
        Port input = null;
        Port prior = null;
        long used = 0L;
        for (Ast.ModelOperation op : source.operations()) {
            if (op.arguments().size() > 16)
                throw new IllegalArgumentException("MODEL_ARGUMENT_BUDGET");
            Signature signature = trusted.get(op.name());
            if (signature == null || !op.name().equals(signature.name()))
                throw new IllegalArgumentException("MODEL_UNKNOWN_OP");
            if (!"pure".equals(signature.effect()))
                throw new IllegalArgumentException("MODEL_EFFECTFUL_OP");
            if (signature.abi().isBlank() || signature.abi().length() > 64
                    || !signature.abi().matches("[A-Za-z0-9_.-]+"))
                throw new IllegalArgumentException("MODEL_INVALID_OP_ABI");
            if (!signature.implDigest().matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("MODEL_INVALID_IMPL_DIGEST");
            if (!Set.of("verified-ores", "trusted-native").contains(signature.provenance()))
                throw new IllegalArgumentException("MODEL_INVALID_OP_PROVENANCE");
            if (signature.scratchBytes() < 0)
                throw new IllegalArgumentException("MODEL_INVALID_SCRATCH");
            // The parser's op arguments are configuration, NOT values read
            // from the previous graph node. Preserve them in a length-tagged
            // canonical descriptor so distinct static configs cannot alias.
            List<String> config = new ArrayList<>();
            for (Ast.ModelArgument argument : op.arguments()) {
                if (argument.label() != null)
                    throw new IllegalArgumentException("MODEL_NAMED_ARGUMENT_UNRESOLVED");
                validScalar(argument.value(), true);
                if (argument.value().kind() == Ast.ModelValueKind.FORMAL
                        && !formals.contains(argument.value().source()))
                    throw new IllegalArgumentException("MODEL_UNKNOWN_FORMAL");
                String label = argument.label() == null ? "" : argument.label();
                String value = argument.value().source();
                String kind = argument.value().kind() == Ast.ModelValueKind.FORMAL
                        ? "FORMAL" : "LITERAL";
                config.add(label.length() + ":" + label + "|"
                        + kind + "|" + value.length() + ":" + value);
            }
            long outputBytes = bytes(signature.output());
            if (signature.input() != null) bytes(signature.input());
            if (nodes.isEmpty()) {
                input = signature.input();
                // Conservatively reserve the incoming buffer too, even when
                // it is owned by the caller, to prevent a budget bypass.
                if (input != null) {
                    try {
                        used = Math.addExact(used, bytes(input));
                    } catch (ArithmeticException overflow) {
                        throw new IllegalArgumentException("MODEL_SIZE_OVERFLOW", overflow);
                    }
                    if (used > limits.maxBytes())
                        throw new IllegalArgumentException("MODEL_MEMORY_BUDGET");
                }
            } else if (!Objects.equals(prior, signature.input())) {
                throw new IllegalArgumentException("MODEL_PORT_TYPE_MISMATCH");
            }
            try {
                used = Math.addExact(used, Math.addExact(outputBytes, signature.scratchBytes()));
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException("MODEL_SIZE_OVERFLOW", overflow);
            }
            if (used > limits.maxBytes())
                throw new IllegalArgumentException("MODEL_MEMORY_BUDGET");
            nodes.add(new Node(nodes.size(), op.name(), signature.abi(),
                    signature.implDigest(), signature.provenance(), config,
                    signature.input(), signature.output(), signature.scratchBytes(),
                    signature.vjp()));
            prior = signature.output();
        }
        return new ModelGraph(source.name(), input, prior, formalConfig, nodes, used);
    }

}
