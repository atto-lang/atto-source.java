package dev.oreslang.parser;

import dev.oreslang.ast.Ast;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * First closed-world, compiler-owned slice of the AttoLang annotation registry.
 * Only source-only @Doc is activated here. Safety/ABI/effect annotations must
 * have separate checked lowering before they are ever added to this registry.
 *
 * Builtins have canonical identities; user-defined namespace resolution and
 * generated annotations are NOT implemented by this class.
 */
public final class AnnotationRegistry {
    private AnnotationRegistry() { }

    public enum Target {
        MODULE, CLASS, ACTOR, FUNCTION, METHOD, CONSTRUCTOR, FIELD
    }

    public enum Phase {
        SOURCE_METADATA, COMPILE_FILTER, TYPE_EFFECT, AOT_DERIVE, ABI, OPT_HINT, TEST_ONLY
    }

    public enum ArgumentKind {
        STRING_LITERAL, TYPE_REFERENCE, INTEGER_LITERAL, BOOLEAN_LITERAL
    }

    public record Definition(
            String qualifiedName,
            String sourceName,
            Set<Target> allowedTargets,
            Phase phase,
            List<ArgumentKind> arguments,
            boolean repeatable) {
        public Definition {
            Objects.requireNonNull(qualifiedName, "annotation identity");
            Objects.requireNonNull(sourceName, "annotation spelling");
            Objects.requireNonNull(phase, "annotation phase");
            allowedTargets = Set.copyOf(allowedTargets);
            arguments = List.copyOf(arguments);
            if (qualifiedName.isBlank() || sourceName.isBlank() || allowedTargets.isEmpty()) {
                throw new IllegalArgumentException("annotation definition is incomplete");
            }
        }
    }

    private static final Definition DOC = new Definition(
            "atto.core.Doc", "Doc",
            EnumSet.of(Target.MODULE, Target.CLASS, Target.ACTOR,
                    Target.FUNCTION, Target.METHOD, Target.CONSTRUCTOR, Target.FIELD),
            Phase.SOURCE_METADATA, List.of(ArgumentKind.STRING_LITERAL), false);

    // Deliberately not a general-purpose registry of effect/authority grants.
    private static final Map<String, Definition> IMPLEMENTED = Map.of(DOC.sourceName(), DOC);

    public static Definition resolveImplemented(String name) {
        return IMPLEMENTED.get(name);
    }

    /**
     * Validate a checked, source-only annotation at its declaration target.
     * A caller must NOT apply this check to arbitrary legacy annotation names:
     * existing @Ret/@FromJson lowering remains independently enforced.
     */
    public static void validateSourceMetadata(Ast.Annotation annotation, Target target) {
        Objects.requireNonNull(annotation, "annotation");
        Objects.requireNonNull(target, "target");
        Definition definition = IMPLEMENTED.get(annotation.name());
        if (definition == null) {
            throw new IllegalArgumentException("@" + annotation.name()
                    + " is not registered for typed " + target + " annotation admission");
        }
        if (!definition.allowedTargets().contains(target)) {
            throw new IllegalArgumentException("@" + annotation.name()
                    + " is not valid on " + target);
        }
        if (definition.phase() != Phase.SOURCE_METADATA) {
            throw new IllegalArgumentException("annotation requires a checked compiler phase");
        }
        List<Ast.TypeRef> actual = annotation.arguments();
        if (actual.size() != definition.arguments().size()) {
            throw new IllegalArgumentException("@" + annotation.name()
                    + " expects exactly " + definition.arguments().size() + " argument(s)");
        }
        for (int i = 0; i < actual.size(); i++) {
            Ast.TypeRef value = actual.get(i);
            if (value.isNamedParameter() || !matchesKind(value, definition.arguments().get(i))) {
                throw new IllegalArgumentException("@" + annotation.name()
                        + " argument " + i + " has an invalid compile-time type");
            }
            if (value.isStringLiteral() && (value.stringLiteralValue().length() > 4096
                    || value.stringLiteralValue().indexOf('\u0000') >= 0)) {
                throw new IllegalArgumentException("annotation documentation is too large or contains NUL");
            }
        }
    }

    private static boolean matchesKind(Ast.TypeRef value, ArgumentKind kind) {
        return switch (kind) {
            case STRING_LITERAL -> value.isStringLiteral();
            case INTEGER_LITERAL -> value.isIntLiteral();
            case BOOLEAN_LITERAL -> value.isBoolLiteral();
            case TYPE_REFERENCE -> !value.isStringLiteral() && !value.isIntLiteral()
                    && !value.isBoolLiteral() && !value.isNamedParameter();
        };
    }
}
