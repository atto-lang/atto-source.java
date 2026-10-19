package dev.oreslang.types;

import java.util.List;
import java.util.Objects;

/**
 * Staged, compiler-only semantics for a parameter's @Default(literal).
 *
 * No runtime expression evaluation, Option allocation, source parser admission,
 * or type-checker authority is granted by this class. The caller must pass
 * a nominally resolved OptionFormalPresencePolicy.Formal and its proven
 * underlying primitive type. Generic types, user-defined Option lookalikes,
 * nested Option lifting, and mutable defaults fail closed until verified.
 */
public final class DefaultArgumentPolicy {
    private DefaultArgumentPolicy() { }

    public enum ScalarType { INT, BOOL, STRING }
    public enum LiteralKind { INT, BOOL, STRING, NONE }

    /** Immutable closed compile-time constant; NONE has no payload. */
    public record Literal(LiteralKind kind, Object value) {
        public Literal {
            Objects.requireNonNull(kind, "literal kind");
            boolean valid = switch (kind) {
                case INT -> value instanceof Long;
                case BOOL -> value instanceof Boolean;
                case STRING -> value instanceof String;
                case NONE -> value == null;
            };
            if (!valid) throw new IllegalArgumentException("invalid @Default literal payload");
        }
        public static Literal integer(long n) { return new Literal(LiteralKind.INT, n); }
        public static Literal bool(boolean b) { return new Literal(LiteralKind.BOOL, b); }
        public static Literal string(String s) {
            return new Literal(LiteralKind.STRING, Objects.requireNonNull(s));
        }
        public static Literal none() { return new Literal(LiteralKind.NONE, null); }
    }

    /**
     * The parser must explicitly prove the annotation used parenthesized syntax.
     * Never accept @Default<...>, unknown expressions, or a constant's printed
     * spelling as proof that it was safe to compile.
     */
    public record Annotation(String name, List<Literal> arguments, boolean parenthesized) {
        public Annotation {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("missing annotation name");
            arguments = List.copyOf(arguments);
        }
        public static Annotation defaultOf(Literal value) {
            return new Annotation("Default", List.of(Objects.requireNonNull(value)), true);
        }
    }

    /** Null means no default is declared, never a default None. */
    public static Literal extractDefault(List<Annotation> annotations) {
        Objects.requireNonNull(annotations, "annotations");
        Literal result = null;
        boolean found = false;
        for (Annotation annotation : annotations) {
            Objects.requireNonNull(annotation, "annotation");
            if (!annotation.name().equals("Default")) continue;
            if (found) throw new IllegalArgumentException("duplicate @Default annotation");
            found = true;
            if (!annotation.parenthesized() || annotation.arguments().size() != 1) {
                throw new IllegalArgumentException("@Default requires exactly one parenthesized constant");
            }
            result = annotation.arguments().getFirst();
        }
        return result;
    }

    /**
     * A *checked* formal. The primitive type represents either T or the
     * contained T in intrinsic Option<T>. Nested Option does not qualify
     * for scalar defaults beyond the explicit NONE constant.
     */
    public record Formal(
            OptionFormalPresencePolicy.Formal signature,
            ScalarType underlyingType,
            Literal defaultValue) {
        public Formal {
            Objects.requireNonNull(signature, "signature");
            Objects.requireNonNull(underlyingType, "verified underlying type");
            if (defaultValue != null) {
                if (signature.variadic()) {
                    throw new IllegalArgumentException("@Default is forbidden on rest parameters");
                }
                if (signature.mutableOrBorrowed()) {
                    throw new IllegalArgumentException("@Default needs immutable by-value formal");
                }
                if (signature.presence() == OptionFormalPresencePolicy.Presence.REQUIRED) {
                    throw new IllegalArgumentException("required Option<T> conflicts with @Default");
                }
                boolean option = signature.kind() != OptionFormalPresencePolicy.TypeKind.VALUE;
                if (defaultValue.kind() == LiteralKind.NONE) {
                    if (!option) throw new IllegalArgumentException("@Default(None) requires Option<T>");
                } else {
                    if (signature.nestedOption()) {
                        throw new IllegalArgumentException(
                                "nested Option defaults require an explicit verified outer Option constant");
                    }
                    LiteralKind expected = switch (underlyingType) {
                        case INT -> LiteralKind.INT;
                        case BOOL -> LiteralKind.BOOL;
                        case STRING -> LiteralKind.STRING;
                    };
                    if (defaultValue.kind() != expected) {
                        throw new IllegalArgumentException("@Default literal does not match formal type "
                                + underlyingType);
                    }
                }
            }
        }
        public static Formal checked(
                OptionFormalPresencePolicy.Formal signature,
                ScalarType underlyingType,
                List<Annotation> annotations) {
            return new Formal(signature, underlyingType, extractDefault(annotations));
        }
        public boolean hasDefault() { return defaultValue != null; }
    }

    public enum Lowering {
        /** No supplied expression; synthesize the checked constant directly. */
        DEFAULT_SCALAR,
        DEFAULT_SOME,
        DEFAULT_NONE,
        /** Omitted Option without an annotated default. */
        OPTION_NONE_UNBOUND,
        OPTION_NONE_EXPLICIT_OMIT,
        /** The actual caller did provide an expression. */
        SOME_OF_SUPPLIED_VALUE,
        PASS_SUPPLIED_OPTION,
        PASS_SUPPLIED_VALUE
    }

    public record Decision(Lowering lowering, Literal compileTimeDefault) {
        public Decision {
            Objects.requireNonNull(lowering, "lowering");
            boolean isDefault = switch (lowering) {
                case DEFAULT_SCALAR, DEFAULT_SOME, DEFAULT_NONE -> true;
                default -> false;
            };
            if (isDefault != (compileTimeDefault != null)) {
                throw new IllegalArgumentException("invalid default-lowering witness");
            }
        }
    }

    public static boolean omittable(Formal formal, OptionFormalPresencePolicy.LanguageMode mode) {
        Objects.requireNonNull(formal, "formal");
        Objects.requireNonNull(mode, "mode");
        // Always run the ordinary presence/borrow gate even when defaulted.
        boolean intrinsicOmittable = OptionFormalPresencePolicy.omittable(formal.signature(), mode);
        return formal.hasDefault() || intrinsicOmittable;
    }

    public static Decision classify(
            Formal formal,
            OptionFormalPresencePolicy.LanguageMode mode,
            OptionFormalPresencePolicy.ActualKind actual) {
        Objects.requireNonNull(formal, "formal");
        Objects.requireNonNull(actual, "actual");
        boolean canOmit = omittable(formal, mode);
        if (formal.signature().variadic()) {
            throw new IllegalArgumentException("rest packing has separate lowering");
        }
        switch (actual) {
            case IMPLICIT_OMISSION, EXPLICIT_OMISSION -> {
                if (!canOmit) {
                    throw new IllegalArgumentException("missing required parameter '"
                            + formal.signature().name() + "'");
                }
                if (formal.hasDefault()) {
                    boolean option = formal.signature().kind()
                            != OptionFormalPresencePolicy.TypeKind.VALUE;
                    Lowering lowering = formal.defaultValue().kind() == LiteralKind.NONE
                            ? Lowering.DEFAULT_NONE
                            : option ? Lowering.DEFAULT_SOME : Lowering.DEFAULT_SCALAR;
                    return new Decision(lowering, formal.defaultValue());
                }
                return new Decision(actual == OptionFormalPresencePolicy.ActualKind.EXPLICIT_OMISSION
                        ? Lowering.OPTION_NONE_EXPLICIT_OMIT : Lowering.OPTION_NONE_UNBOUND, null);
            }
            case UNDERLYING_VALUE, TYPED_OPTION -> {
                // A supplied expression ALWAYS defeats the default, including
                // an explicit, correctly typed Option.None. Even in legacy mode,
                // an explicitly defaulted Option<T> becomes an optional-call
                // boundary; permit exactly T -> Some(T) there (not a general
                // implicit Option conversion). Nested Option stays fail-closed.
                if (actual == OptionFormalPresencePolicy.ActualKind.UNDERLYING_VALUE
                        && formal.hasDefault()
                        && formal.signature().kind()
                                == OptionFormalPresencePolicy.TypeKind.INTRINSIC_OPTION
                        && !formal.signature().nestedOption()) {
                    return new Decision(Lowering.SOME_OF_SUPPLIED_VALUE, null);
                }
                var original = OptionFormalPresencePolicy.classify(
                        formal.signature(), mode, actual);
                Lowering lowering = switch (original) {
                    case SOME_OF_VALUE -> Lowering.SOME_OF_SUPPLIED_VALUE;
                    case PASS_OPTION -> Lowering.PASS_SUPPLIED_OPTION;
                    case PASS_REQUIRED_VALUE -> Lowering.PASS_SUPPLIED_VALUE;
                    default -> throw new IllegalStateException("unexpected supplied-value plan");
                };
                return new Decision(lowering, null);
            }
        }
        throw new IllegalStateException("unhandled actual-kind");
    }
}
