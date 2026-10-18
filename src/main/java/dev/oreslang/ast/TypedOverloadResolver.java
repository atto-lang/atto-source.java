package dev.oreslang.ast;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure, immutable selection model for the future type-directed callable ABI.
 *
 * <p>It deliberately does not modify the existing arity-only compiler or runtime
 * dispatch. A compiler may enable typed overload declarations only after the
 * selected signature is carried to ownership analysis, interpreter lowering,
 * native code, callbacks and method vtables. Until then this is an executable
 * specification/oracle for arity-first, nominal-enum-only selection.
 * Primitive conversions and payload values never select a declaration.
 */
public final class TypedOverloadResolver {
    private TypedOverloadResolver() { }

    public enum Domain { MODULE, INSTANCE, STATIC }
    public enum Kind { PRIMITIVE, NOMINAL_ENUM, DYNAMIC }

    public record TypeKey(Kind kind, String identity) {
        public TypeKey {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(identity, "identity");
            if (identity.isBlank()) throw new IllegalArgumentException("blank overload type identity");
            requireValidUtf16(identity);
            if (kind == Kind.PRIMITIVE && !PRIMITIVES.contains(identity)) {
                throw new IllegalArgumentException("unsupported overload primitive: " + identity);
            }
            if (kind == Kind.NOMINAL_ENUM && (!identity.contains(".")
                    || identity.startsWith(".") || identity.endsWith(".")
                    || identity.contains(".."))) {
                throw new IllegalArgumentException(
                        "enum overload identity must be module-qualified, got: " + identity);
            }
            if (kind == Kind.DYNAMIC && !identity.equals("Any") && !identity.equals("Unknown")) {
                throw new IllegalArgumentException("invalid dynamic type: " + identity);
            }
        }

        public static TypeKey primitive(String name) {
            // Both source spellings of the same built-in bool are one type.
            return new TypeKey(Kind.PRIMITIVE,
                    name.equals("boolean") ? "bool" : name);
        }

        public static TypeKey nominalEnum(String qualifiedName) {
            return new TypeKey(Kind.NOMINAL_ENUM, qualifiedName);
        }

        /**
         * Bridge for parsed Oreslang parameter/argument type references.
         *
         * <p>The caller must provide enum identities produced by lexical
         * resolution, NOT a guessed short-name comparison. Generic payload
         * types cannot be overload discriminants without reified descriptors.
         * Type aliases must be expanded by TypeChecker before using this.
         */
        public static TypeKey fromSource(
                Ast.TypeRef type, Map<String, String> resolvedEnumTypes) {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(resolvedEnumTypes, "resolvedEnumTypes");
            if (type.inferArguments() || !type.arguments().isEmpty()) {
                throw new IllegalArgumentException(
                        "generic/erased/compound type cannot distinguish an overload: " + type);
            }
            return switch (type.name()) {
                case "i8", "i16", "i32", "i64", "u8", "u16", "u32", "u64",
                        "int", "uint", "bigint" -> primitive("int");
                case "f32", "f64", "float" -> primitive("float");
                case "decimal" -> primitive("decimal");
                case "complex64", "complex128", "complex" -> primitive("complex");
                case "bool", "Bool" -> primitive("bool");
                case "boolean" -> primitive("bool");
                case "string", "String" -> primitive("String");
                case "Any" -> any();
                case "Unknown" -> unknown();
                default -> {
                    String identity = resolvedEnumTypes.get(type.name());
                    if (identity == null) {
                        throw new IllegalArgumentException(
                                "unresolved or unsupported overload-discriminating type: " + type);
                    }
                    yield nominalEnum(identity);
                }
            };
        }

        public static TypeKey any() {
            return new TypeKey(Kind.DYNAMIC, "Any");
        }

        public static TypeKey unknown() {
            return new TypeKey(Kind.DYNAMIC, "Unknown");
        }
    }

    // Deliberately restricted to primitives with a specified source-level
    // conversion relation. Erased generic payloads, nominal classes and
    // structural records are not safe type-discriminating overload keys.
    private static final Set<String> PRIMITIVES =
            Set.of("bool", "int", "float", "decimal", "complex", "String");

    public record Signature(Domain domain, String qualifiedName, List<TypeKey> parameters) {
        public Signature {
            Objects.requireNonNull(domain, "domain");
            Objects.requireNonNull(qualifiedName, "qualifiedName");
            Objects.requireNonNull(parameters, "parameters");
            if (qualifiedName.isBlank()) throw new IllegalArgumentException("missing callable name");
            requireValidUtf16(qualifiedName);
            parameters = List.copyOf(parameters);
            if (parameters.stream().anyMatch(t -> t.kind() == Kind.DYNAMIC)) {
                throw new IllegalArgumentException(
                        "Any/Unknown may not distinguish a typed overload declaration");
            }
        }

        /**
         * Extract a future ABI signature from a concrete, non-generic source
         * function. Fnc and routine intentionally share Domain.MODULE.
         */
        public static Signature ofFunction(
                String module, Ast.FunctionDecl function,
                Map<String, String> resolvedEnums) {
            Objects.requireNonNull(module, "module");
            Objects.requireNonNull(function, "function");
            if (module.isBlank() || !function.genericParameters().isEmpty()) {
                throw new IllegalArgumentException(
                        "unresolved generic/module function is not an overload discriminator");
            }
            return new Signature(Domain.MODULE, module + "." + function.name(),
                    function.parameters().stream()
                            .map(p -> admittedParameterType(p, resolvedEnums))
                            .toList());
        }

        /**
         * The static/instance domain is part of method slot identity, but
         * explicit self is not a source call argument and is not in params.
         * Class generic parameters require substitution before using this.
         */
        public static Signature ofMethod(
                String module, Ast.ClassDecl declaringClass, Ast.MethodDecl method,
                Map<String, String> resolvedEnums) {
            Objects.requireNonNull(module, "module");
            Objects.requireNonNull(declaringClass, "declaringClass");
            Objects.requireNonNull(method, "method");
            if (module.isBlank() || !declaringClass.genericParameters().isEmpty()
                    || !method.genericParameters().isEmpty()) {
                throw new IllegalArgumentException(
                        "unresolved generic class/method cannot supply a typed overload ABI key");
            }
            // A call site may dispatch through a subclass, but an overload
            // symbol belongs to the actual declaring class. Never let a
            // caller spoof an owner's slot using a method from elsewhere.
            if (declaringClass.methods().stream().noneMatch(slot -> slot == method)) {
                throw new IllegalArgumentException(
                        "method does not belong to the supplied declaring class");
            }
            return new Signature(method.isStatic() ? Domain.STATIC : Domain.INSTANCE,
                    module + "." + declaringClass.name() + "." + method.name(),
                    method.parameters().stream()
                            .map(p -> admittedParameterType(p, resolvedEnums))
                            .toList());
        }

        public int arity() {
            return parameters.size();
        }

        /**
         * Canonical, deterministic, injective symbol fragment. Length-hex
         * encoding avoids collisions from dots, angle brackets, unicode,
         * delimiter characters and otherwise plausible enum names.
         * No Java reflection or erased generic type is consulted.
         */
        public String symbolId() {
            StringBuilder result = new StringBuilder("$overload$v1$");
            appendPart(result, domain.name());
            appendPart(result, qualifiedName);
            result.append(arity()).append('$');
            for (TypeKey parameter : parameters) {
                appendPart(result, parameter.kind().name());
                appendPart(result, parameter.identity());
            }
            return result.toString();
        }

        private static void appendPart(StringBuilder out, String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            out.append(bytes.length).append('_')
                    .append(HexFormat.of().formatHex(bytes)).append('$');
        }
    }

    private static TypeKey admittedParameterType(
            Ast.Param parameter, Map<String, String> resolvedEnums) {
        Objects.requireNonNull(parameter, "parameter");
        // Structural compatibility is deliberately not a runtime overload
        // discriminator. A tagged enum's nominal identity cannot stand in
        // for a structural parameter's shape-matching contract.
        if (parameter.structural()) {
            throw new IllegalArgumentException(
                    "structural parameter cannot distinguish an overload: " + parameter.name());
        }
        return TypeKey.fromSource(parameter.type(), resolvedEnums);
    }

    public record Candidate<T>(T declaration, Signature signature) {
        public Candidate {
            Objects.requireNonNull(declaration, "declaration");
            Objects.requireNonNull(signature, "signature");
        }
    }

    public static final int MAX_SAME_ARITY_CANDIDATES = 128;
    public static final int MAX_OVERLOAD_ARITY = 64;

    /**
     * Select by domain/name/arity, then only by proven nominal enum identity.
     * The registry must contain compiler-resolved enum declaration IDs whose
     * complete payload layouts have already passed eligibility validation.
     * This is not a runtime type registry and cannot be populated from values.
     *
     * <p>Validate the entire same-arity family before considering arguments:
     * an exact match must not hide an illegal primitive-distinguished pair.
     * All differing positions require static enum proofs. The caller must
     * check assignability, visibility, ownership and effects of the selected
     * declaration separately, even for a single arity slot.
     */
    public static <T> Candidate<T> select(
            Domain domain, String qualifiedName, List<TypeKey> arguments,
            List<Candidate<T>> candidates, Set<String> verifiedEligibleEnumIds) {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(qualifiedName, "qualifiedName");
        arguments = List.copyOf(arguments);
        candidates = List.copyOf(candidates);
        verifiedEligibleEnumIds = Set.copyOf(verifiedEligibleEnumIds);
        if (arguments.size() > MAX_OVERLOAD_ARITY) {
            throw new IllegalArgumentException("nominal enum overload arity bound exceeded");
        }
        List<Candidate<T>> family = new ArrayList<>();
        for (Candidate<T> candidate : candidates) {
            Signature signature = candidate.signature();
            if (signature.domain() == domain && signature.qualifiedName().equals(qualifiedName)
                    && signature.arity() == arguments.size()) family.add(candidate);
        }
        if (family.isEmpty()) {
            throw new IllegalArgumentException("no matching overload of " + qualifiedName
                    + " with arity " + arguments.size());
        }
        if (family.size() > MAX_SAME_ARITY_CANDIDATES) {
            throw new IllegalArgumentException("nominal enum overload candidate bound exceeded");
        }
        Set<Integer> discriminators = new java.util.TreeSet<>();
        boolean duplicate = false, invalid = false;
        for (int i = 0; i < family.size(); i++) {
            Signature left = family.get(i).signature();
            for (int j = 0; j < i; j++) {
                Signature right = family.get(j).signature();
                if (left.equals(right)) duplicate = true;
                for (int p = 0; p < left.arity(); p++) {
                    TypeKey a = left.parameters().get(p), b = right.parameters().get(p);
                    if (a.equals(b)) continue;
                    if (a.kind() != Kind.NOMINAL_ENUM || b.kind() != Kind.NOMINAL_ENUM) {
                        invalid = true;
                    } else {
                        discriminators.add(p);
                    }
                }
            }
        }
        // Stable diagnostics regardless of declaration order.
        if (duplicate) throw new IllegalArgumentException("duplicate typed overload of " + qualifiedName);
        if (invalid) throw new IllegalArgumentException(
                "invalid same-arity overload family of " + qualifiedName
                        + "; only distinct eligible nominal enums may distinguish parameters");
        for (int position : discriminators) {
            for (Candidate<T> candidate : family) {
                TypeKey key = candidate.signature().parameters().get(position);
                if (!verifiedEligibleEnumIds.contains(key.identity())) {
                    throw new IllegalArgumentException("unverified or ineligible nominal enum overload symbol");
                }
            }
            if (arguments.get(position).kind() != Kind.NOMINAL_ENUM) {
                throw new IllegalArgumentException("missing static nominal enum proof at position "
                        + position + "; narrow or cast arguments explicitly");
            }
        }
        Candidate<T> chosen = null;
        for (Candidate<T> candidate : family) {
            boolean matches = true;
            for (int position : discriminators) {
                if (!candidate.signature().parameters().get(position).equals(arguments.get(position))) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                if (chosen != null) throw new IllegalArgumentException("ambiguous overload of " + qualifiedName);
                chosen = candidate;
            }
        }
        if (chosen == null) throw new IllegalArgumentException(
                "cannot statically bind a unique enum overload of " + qualifiedName);
        return chosen;
    }

    /** Reject malformed UTF-16 rather than silently replacing its code units
     * with identical '?' bytes while generating a supposed injective ABI key. */
    private static void requireValidUtf16(String value) {
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (i + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    throw new IllegalArgumentException("unpaired UTF-16 surrogate in overload identity");
                }
                i++;
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException("unpaired UTF-16 surrogate in overload identity");
            }
        }
    }

}
