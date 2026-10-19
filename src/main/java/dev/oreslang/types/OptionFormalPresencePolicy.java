package dev.oreslang.types;

import java.util.Objects;

/**
 * Staged semantic policy for an Option-typed formal parameter. This class
 * deliberately does not parse source types or authorize runtime lowering.
 * The caller must prove INTRINSIC_OPTION by nominal type resolution, not by
 * string comparison with an untrusted or shadowed type name.
 */
public final class OptionFormalPresencePolicy {
    private OptionFormalPresencePolicy() { }

    public enum TypeKind {
        VALUE,
        INTRINSIC_OPTION,
        QUESTION_MARK_SUGAR
    }

    public enum Presence {
        /** In the next language mode, a proven Option<T> formal is omittable. */
        INFER_FROM_TYPE,
        /** Require a supplied Option<T>, even though None is a valid value. */
        REQUIRED
    }

    public enum LanguageMode {
        /** Existing source meaning until compiler/runtime migrations complete. */
        LEGACY,
        /** Planned: Option<T> is omittable, T? is equivalent sugar. */
        IMPLICIT_OPTION_OMISSION
    }

    public enum ActualKind {
        /** The caller did not bind this formal at all. */
        IMPLICIT_OMISSION,
        /** A complete _/omit call item occupied the formal, with no Expr. */
        EXPLICIT_OMISSION,
        /** Source expression has exact underlying T, not Option<T>. */
        UNDERLYING_VALUE,
        /** Source expression has exact resolved Option<T>, including None. */
        TYPED_OPTION
    }

    public enum Lowering {
        NONE_UNBOUND,
        NONE_EXPLICIT_OMIT,
        SOME_OF_VALUE,
        PASS_OPTION,
        PASS_REQUIRED_VALUE
    }

    /**
     * Classifier output from a checked signature, not raw source parsing.
     * A variadic Option<T> is a rest element type and never creates None for
     * the absent entire rest pack. Nested Option<T> has no implicit T lifting.
     */
    public record Formal(
            String name,
            TypeKind kind,
            Presence presence,
            boolean variadic,
            boolean mutableOrBorrowed,
            boolean nestedOption) {
        public Formal {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("formal name must be nonempty");
            }
            Objects.requireNonNull(kind, "type kind");
            Objects.requireNonNull(presence, "presence");
            if (kind == TypeKind.QUESTION_MARK_SUGAR && presence == Presence.REQUIRED) {
                throw new IllegalArgumentException("required T? contradicts optional marker");
            }
            if (kind == TypeKind.VALUE && presence == Presence.REQUIRED) {
                throw new IllegalArgumentException("required is only needed for Option<T>");
            }
            if (kind == TypeKind.QUESTION_MARK_SUGAR && variadic) {
                throw new IllegalArgumentException("variadic T? is forbidden");
            }
            if (nestedOption && kind != TypeKind.INTRINSIC_OPTION) {
                throw new IllegalArgumentException("nestedOption requires a checked Option<Option<T>>");
            }
        }
        public static Formal value(String name) {
            return new Formal(name, TypeKind.VALUE, Presence.INFER_FROM_TYPE, false, false, false);
        }
        public static Formal option(String name) {
            return new Formal(name, TypeKind.INTRINSIC_OPTION, Presence.INFER_FROM_TYPE, false, false, false);
        }
        public static Formal requiredOption(String name) {
            return new Formal(name, TypeKind.INTRINSIC_OPTION, Presence.REQUIRED, false, false, false);
        }
        public static Formal questionMark(String name) {
            return new Formal(name, TypeKind.QUESTION_MARK_SUGAR, Presence.INFER_FROM_TYPE, false, false, false);
        }
    }

    public static boolean omittable(Formal formal, LanguageMode mode) {
        Objects.requireNonNull(formal, "formal");
        Objects.requireNonNull(mode, "mode");
        boolean optional = !formal.variadic()
                && formal.presence() != Presence.REQUIRED
                && (formal.kind() == TypeKind.QUESTION_MARK_SUGAR
                || (mode == LanguageMode.IMPLICIT_OPTION_OMISSION
                && formal.kind() == TypeKind.INTRINSIC_OPTION));
        if (optional && formal.mutableOrBorrowed()) {
            throw new IllegalArgumentException(
                    "optional mutable/borrowed formal requires verified loan and ownership lowering");
        }
        return optional;
    }

    /**
     * Pure classification only: evaluation, borrow/move checks and the actual
     * Some/None object construction remain responsibilities of checked ABI
     * lowering. T -> Some(T) is allowed at an omittable formal boundary,
     * not for unrelated assignments or a required Option<T> parameter.
     */
    public static Lowering classify(Formal formal, LanguageMode mode, ActualKind actual) {
        Objects.requireNonNull(actual, "actual");
        boolean optional = omittable(formal, mode);
        if (formal.variadic()) {
            throw new IllegalArgumentException(
                    "variadic rest packing is separate from Option slot classification");
        }
        return switch (actual) {
            case IMPLICIT_OMISSION -> {
                if (!optional) throw new IllegalArgumentException("missing required argument '" + formal.name() + "'");
                yield Lowering.NONE_UNBOUND;
            }
            case EXPLICIT_OMISSION -> {
                if (!optional) throw new IllegalArgumentException("cannot omit required argument '" + formal.name() + "'");
                yield Lowering.NONE_EXPLICIT_OMIT;
            }
            case UNDERLYING_VALUE -> {
                if (formal.kind() == TypeKind.VALUE) yield Lowering.PASS_REQUIRED_VALUE;
                if (!optional) throw new IllegalArgumentException("required Option<T> must be supplied as Option<T>");
                if (formal.nestedOption()) {
                    throw new IllegalArgumentException("nested Option requires an explicit outer Option<T> value");
                }
                yield Lowering.SOME_OF_VALUE;
            }
            case TYPED_OPTION -> {
                if (formal.kind() == TypeKind.VALUE) {
                    throw new IllegalArgumentException("Option<T> cannot bind an unrelated T parameter");
                }
                yield Lowering.PASS_OPTION;
            }
        };
    }
}
