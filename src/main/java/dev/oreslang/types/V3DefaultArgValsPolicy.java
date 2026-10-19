package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * V3 source-level presence/default contract for the staged parser.
 *
 * This deliberately proves only a syntactically closed scalar preview; it
 * does NOT establish nominal Option identity, imported/generic resolution,
 * ownership rights, or permission to invoke guest code.
 *
 * Option<T> is a TYPE in v3; the independent Ast.Param.argumentOmittable()
 * flag (or a declared default) controls caller omission. In legacy mode,
 * Ast.Param.optional() retains the old T?-formal meaning.
 */
public final class V3DefaultArgValsPolicy {
    private V3DefaultArgValsPolicy() { }

    public record FormalPreview(
            String name, Ast.TypeRef bodyType, boolean omittable,
            boolean optionTyped, DefaultArgumentPolicy.Literal defaultValue) {
        public FormalPreview {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(bodyType, "type");
        }
    }

    public static List<FormalPreview> validateDeclaration(
            List<Ast.Param> parameters, List<Ast.Annotation> annotations) {
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(annotations, "annotations");

        Map<String, Ast.Param> byName = new LinkedHashMap<>();
        for (Ast.Param param : parameters) {
            Objects.requireNonNull(param, "formal");
            if (param.name().equals("self")) {
                throw new IllegalArgumentException(
                        "construction-scoped/receiver self is not a caller argument");
            }
            if (byName.putIfAbsent(param.name(), param) != null) {
                throw new IllegalArgumentException("duplicate formal parameter '" + param.name() + "'");
            }
            if (param.optional()) {
                throw new IllegalArgumentException(
                        "legacy T? omission marker cannot appear in v3 signature");
            }
        }
        Map<String, DefaultArgumentPolicy.Literal> defaults = new LinkedHashMap<>();
        boolean hasDefaultArgVals = false;
        for (Ast.Annotation annotation : annotations) {
            Objects.requireNonNull(annotation, "annotation");
            if (!annotation.name().equals("DefaultArgVals")) continue;
            if (hasDefaultArgVals) throw new IllegalArgumentException("duplicate @DefaultArgVals annotation");
            hasDefaultArgVals = true;
            if (annotation.namedDefaults().isEmpty() || !annotation.arguments().isEmpty()) {
                throw new IllegalArgumentException("malformed @DefaultArgVals annotation");
            }
            for (Ast.NamedDefault item : annotation.namedDefaults()) {
                if (!byName.containsKey(item.formal())) {
                    throw new IllegalArgumentException(
                            "@DefaultArgVals refers to unknown formal '" + item.formal() + "'");
                }
                defaults.put(item.formal(), asClosedLiteral(item.literal()));
            }
        }

        List<FormalPreview> formals = new ArrayList<>(parameters.size());
        for (Ast.Param param : parameters) {
            if (param.hasDefault()) {
                if (defaults.containsKey(param.name())) {
                    throw new IllegalArgumentException(
                            "conflicting @Default and @DefaultArgVals for formal '" + param.name() + "'");
                }
                defaults.put(param.name(), DefaultArgumentPolicy.stagedLiteralFromAst(param));
            }
            // Preview type only: a staged '?' marker must remain in the source
            // AST and cannot masquerade as a nominally resolved Option<T>.
            Ast.TypeRef bodyType = param.type().stagedV3PreviewType();
            boolean option = bodyType.name().equals("Option") && bodyType.arguments().size() == 1
                    && !bodyType.inferArguments();
            boolean declaredDefault = defaults.containsKey(param.name());
            var literal = defaults.get(param.name());
            // A default or caller omission needs a safe value representation.
            // Checking only bodyType.isBorrow() permits an unverified borrow
            // through Option<List<borrow T>>, including with default None.
            if ((declaredDefault || param.argumentOmittable())
                    && containsNonOwnedOrInferredType(bodyType)) {
                throw new IllegalArgumentException(
                        "default/omittable formal '" + param.name()
                                + "' has nested borrow, inference or legacy optional type");
            }
            if (param.argumentOmittable() && !option && !declaredDefault) {
                throw new IllegalArgumentException(
                        "non-Option omittable formal '" + param.name() + "' requires a default");
            }
            if (declaredDefault) {
                if (param.mutable() || param.structural() || bodyType.isBorrow()) {
                    throw new IllegalArgumentException(
                            "defaulted formal must be immutable owned value '" + param.name() + "'");
                }
                Ast.TypeRef payload = option ? bodyType.arguments().getFirst() : bodyType;
                if (literal.kind() == DefaultArgumentPolicy.LiteralKind.NONE) {
                    if (!option) {
                        throw new IllegalArgumentException(
                                "None default requires Option<T> formal '" + param.name() + "'");
                    }
                } else {
                    if (payload.name().equals("Option")) {
                        throw new IllegalArgumentException(
                                "nested Option default requires a separately checked explicit constant");
                    }
                    boolean valid = switch (literal.kind()) {
                        case INT -> payload.name().equals("int");
                        case BOOL -> payload.name().equals("bool");
                        case STRING -> payload.name().equals("string");
                        case NONE -> false;
                    };
                    if (!valid || !payload.arguments().isEmpty() || payload.inferArguments()) {
                        throw new IllegalArgumentException(
                                "@DefaultArgVals constant incompatible with formal '" + param.name() + "'");
                    }
                }
            }
            formals.add(new FormalPreview(param.name(), bodyType,
                    param.argumentOmittable() || declaredDefault, option, literal));
        }
        return List.copyOf(formals);
    }

    /** Stage-only conservative ownership check; not a nominal type proof. */
    private static boolean containsNonOwnedOrInferredType(Ast.TypeRef type) {
        if (type.isBorrow() || type.isOmittableFormal()
                || type.name().equals("$infer$") || type.inferArguments()) {
            return true;
        }
        for (Ast.TypeRef nested : type.arguments()) {
            if (containsNonOwnedOrInferredType(nested)) return true;
        }
        return false;
    }

    private static DefaultArgumentPolicy.Literal asClosedLiteral(Ast.TypeRef type) {
        Objects.requireNonNull(type, "constant AST");
        if (type.isIntLiteral()) {
            try {
                return DefaultArgumentPolicy.Literal.integer(type.intLiteralValue());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("@DefaultArgVals int64 value overflow", ex);
            }
        }
        if (type.isBoolLiteral()) return DefaultArgumentPolicy.Literal.bool(type.boolLiteralValue());
        if (type.isStringLiteral()) return DefaultArgumentPolicy.Literal.string(type.stringLiteralValue());
        if (type.name().equals("None") && type.arguments().isEmpty() && !type.inferArguments()) {
            return DefaultArgumentPolicy.Literal.none();
        }
        throw new IllegalArgumentException("@DefaultArgVals needs closed int/bool/string/None");
    }
}
