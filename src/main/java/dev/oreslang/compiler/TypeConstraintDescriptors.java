package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compiler-owned, deterministic descriptors for constrained type aliases.
 *
 * <p>This v1 schema deliberately supports only inclusive signed-i64 min/max
 * predicates. It is a data contract for validators such as Zorde; it is not
 * itself a runtime validation bridge or an unchecked int-to-alias conversion.
 */
public final class TypeConstraintDescriptors {
    public static final int SCHEMA_VERSION = 1;
    public static final String SCHEMA_ID = "ores.type-constraint.v1";
    public static final String REPRESENTATION_I64 = "i64";

    private TypeConstraintDescriptors() { }

    public record Predicate(
            String id,
            String operator,
            long operand,
            String errorId) {
        public Predicate {
            Objects.requireNonNull(id, "constraint predicate id");
            Objects.requireNonNull(operator, "constraint predicate operator");
            Objects.requireNonNull(errorId, "constraint predicate error id");
        }
    }

    public record Descriptor(
            int schemaVersion,
            String schemaId,
            String qualifiedTypeName,
            String representation,
            List<Predicate> predicates,
            boolean validationRequired,
            String fingerprint) {
        public Descriptor {
            if (schemaVersion != SCHEMA_VERSION) {
                throw new IllegalArgumentException("unsupported constraint descriptor schema");
            }
            if (!SCHEMA_ID.equals(schemaId)) {
                throw new IllegalArgumentException("invalid constraint descriptor schema id");
            }
            Objects.requireNonNull(qualifiedTypeName, "qualified type name");
            Objects.requireNonNull(representation, "constraint representation");
            predicates = List.copyOf(predicates);
            Objects.requireNonNull(fingerprint, "constraint descriptor fingerprint");
        }
    }

    public record Catalog(Map<String, Descriptor> descriptors) {
        public Catalog {
            descriptors = Collections.unmodifiableMap(new LinkedHashMap<>(descriptors));
        }

        public static Catalog empty() { return new Catalog(Map.of()); }

        public Descriptor find(String qualifiedTypeName) {
            return descriptors.get(qualifiedTypeName);
        }
    }

    /** Compile and normalize every constrained alias in one checked source unit. */
    public static Catalog compile(Ast.Program program) {
        Objects.requireNonNull(program, "program");
        Map<String, Descriptor> result = new LinkedHashMap<>();
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (!(declaration instanceof Ast.TypeAliasDecl alias) || !alias.constrained()) {
                    continue;
                }
                Descriptor descriptor = compileAlias(program.namespace(), module, alias);
                if (result.putIfAbsent(descriptor.qualifiedTypeName(), descriptor) != null) {
                    throw new IllegalArgumentException(
                            "duplicate constrained type descriptor '"
                                    + descriptor.qualifiedTypeName() + "'");
                }
            }
        }
        return new Catalog(result);
    }

    private static Descriptor compileAlias(
            String namespace, Ast.ModuleDecl module, Ast.TypeAliasDecl alias) {
        if (!alias.genericParameters().isEmpty()) {
            throw new IllegalArgumentException(
                    "CONSTRAINT_GENERIC_UNSUPPORTED: constrained alias '" + alias.name()
                            + "' cannot be generic in descriptor schema v1");
        }
        if (!alias.target().arguments().isEmpty() || alias.target().inferArguments()
                || !(alias.target().name().equals("int") || alias.target().name().equals("i64"))) {
            throw new IllegalArgumentException(
                    "CONSTRAINT_REPRESENTATION_UNSUPPORTED: constrained alias '"
                            + alias.name() + "' must directly target int/i64 in descriptor schema v1");
        }

        Long minimum = null;
        Long maximum = null;
        for (Ast.TypeConstraint constraint : alias.constraints()) {
            switch (constraint.kind()) {
                case MIN_INCLUSIVE -> {
                    if (minimum != null) {
                        throw new IllegalArgumentException(
                                "CONSTRAINT_DUPLICATE_MIN: duplicate min on '" + alias.name() + "'");
                    }
                    minimum = constraint.value();
                }
                case MAX_INCLUSIVE -> {
                    if (maximum != null) {
                        throw new IllegalArgumentException(
                                "CONSTRAINT_DUPLICATE_MAX: duplicate max on '" + alias.name() + "'");
                    }
                    maximum = constraint.value();
                }
            }
        }
        if (minimum != null && maximum != null && minimum > maximum) {
            throw new IllegalArgumentException(
                    "CONSTRAINT_EMPTY_RANGE: min exceeds max on '" + alias.name() + "'");
        }

        List<Predicate> predicates = new ArrayList<>(2);
        if (minimum != null) {
            predicates.add(new Predicate(
                    "min_inclusive_i64", ">=", minimum,
                    "ORES_CONSTRAINT_MIN_INCLUSIVE_I64"));
        }
        if (maximum != null) {
            predicates.add(new Predicate(
                    "max_inclusive_i64", "<=", maximum,
                    "ORES_CONSTRAINT_MAX_INCLUSIVE_I64"));
        }
        if (predicates.isEmpty()) {
            throw new IllegalArgumentException("constrained alias has no predicates");
        }

        String localName = module.name().equals(Parser.ROOT_MODULE)
                ? alias.name()
                : module.name() + "." + alias.name();
        String qualifiedName = namespace == null || namespace.isBlank()
                ? localName
                : namespace + "." + localName;
        String canonical = canonicalForm(qualifiedName, predicates);
        return new Descriptor(
                SCHEMA_VERSION,
                SCHEMA_ID,
                qualifiedName,
                REPRESENTATION_I64,
                predicates,
                true,
                sha256(canonical));
    }

    /** Stable text used only for cross-backend descriptor fingerprinting. */
    public static String canonicalForm(String qualifiedName, List<Predicate> predicates) {
        StringBuilder canonical = new StringBuilder();
        canonical.append(SCHEMA_ID)
                .append("|type=").append(qualifiedName)
                .append("|repr=").append(REPRESENTATION_I64);
        for (Predicate predicate : predicates) {
            canonical.append("|").append(predicate.id())
                    .append("=").append(predicate.operand())
                    .append("|error=").append(predicate.errorId());
        }
        return canonical.toString();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format("%02x", b & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
