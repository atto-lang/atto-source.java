package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.RefinementRules;
import dev.oreslang.types.StaticZordeSchema;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Experimental compile-time Zorde descriptor planner. It consumes only the
 * compiler AST and emits deterministic, reflection-free scalar schemas.
 *
 * Important: this API does NOT admit refinements into executable Oreslang.
 * Guest zorde.schema<T>(), T.try_from(), mutable ownership and trust-boundary
 * enforcement still require dedicated compiler/runtime lowering.
 */
public final class ZordeSchemaGenerator {
    private ZordeSchemaGenerator() { }

    /**
     * Immutable compile-time descriptor registry. The constructor is private:
     * callers cannot inject their own descriptors into a trusted plan.
     */
    public static final class Plan {
        private final Map<String, RefinementRules.Descriptor> schemas;
        private final Map<String, InferredScalarType> inferredTypes;

        private Plan(Map<String, RefinementRules.Descriptor> schemas,
                     Map<String, InferredScalarType> inferredTypes) {
            this.schemas = Collections.unmodifiableMap(new LinkedHashMap<>(schemas));
            this.inferredTypes = Collections.unmodifiableMap(new LinkedHashMap<>(inferredTypes));
        }

        public Map<String, RefinementRules.Descriptor> schemas() { return schemas; }
        public Map<String, InferredScalarType> inferredTypes() { return inferredTypes; }

        /** This type projection must eventually be installed into TypeChecker. */
        public InferredScalarType inferType(String qualifiedName) {
            InferredScalarType result = inferredTypes.get(qualifiedName);
            if (result == null)
                throw new IllegalArgumentException("no inferred Zorde type for " + qualifiedName);
            return result;
        }

        /** Schema lookup requires a fully qualified compilation-unit identity. */
        public RefinementRules.Descriptor schema(String qualifiedTypeName) {
            RefinementRules.Descriptor found = schemas.get(qualifiedTypeName);
            if (found == null) {
                throw new IllegalArgumentException(
                        "no generated Zorde scalar schema for " + qualifiedTypeName);
            }
            return found;
        }

        /**
         * Reference checked conversion for immutable scalar values only.
         * Guest T.try_from() remains unavailable until codegen/ownership
         * enforcement creates a true guest-language nominal value.
         */
        public Conversion tryFrom(String qualifiedTypeName, Object raw) {
            RefinementRules.Descriptor contract = schema(qualifiedTypeName);
            if (!contract.accepts(raw)) {
                return new Conversion(null, new ConstraintError(
                        "constraint_violation", qualifiedTypeName, contract.fingerprint()));
            }
            return new Conversion(
                    new CheckedScalar(qualifiedTypeName, contract.fingerprint(), raw), null);
        }
    }

    /**
     * Derived Oreslang type from a statically known schema. Input is the
     * untrusted primitive; output is a possibly refined or nominal alias.
     * Not a callable guest type constructor or an implicit cast.
     */
    public record InferredScalarType(String qualifiedName, Ast.TypeRef inputBase,
                                     Ast.RefinementSpec outputRefinement,
                                     String nominalIdentity, String schemaFingerprint) {
        public InferredScalarType {
            Objects.requireNonNull(qualifiedName);
            Objects.requireNonNull(inputBase);
            Objects.requireNonNull(nominalIdentity);
            Objects.requireNonNull(schemaFingerprint);
        }
    }

    private static void insertSchema(
            Map<String, RefinementRules.Descriptor> schemas,
            Map<String, InferredScalarType> inferred,
            String qualifiedName, StaticZordeSchema schema, boolean nominal) {
        String identity = nominal ? qualifiedName : "";
        RefinementRules.Descriptor descriptor =
                schema.descriptor(nominal ? qualifiedName : null, nominal);
        if (schemas.putIfAbsent(qualifiedName, descriptor) != null)
            throw new IllegalArgumentException("duplicate generated Zorde schema " + qualifiedName);
        inferred.put(qualifiedName, new InferredScalarType(
                qualifiedName, schema.baseType(), schema.refinement(nominal),
                identity, descriptor.fingerprint()));
    }

    /**
     * Schema-first compile-time substrate: the same descriptor and refined
     * type projection as annotation/with declarations. Builders MUST be
     * compile-time known, immutable and from the approved scalar vocabulary.
     * No arbitrary callback, transform, coercion or runtime-created schema
     * can statically infer a refined type.
     */
    public static Plan planStaticSchemas(
            String namespace, Map<String, StaticZordeSchema> declarations,
            Set<String> nominalNames) {
        if (namespace == null || !namespace.matches("[A-Za-z_][A-Za-z_0-9]*"))
            throw new IllegalArgumentException("schema-first types require source namespace");
        Objects.requireNonNull(declarations);
        Objects.requireNonNull(nominalNames);
        if (!declarations.keySet().containsAll(nominalNames))
            throw new IllegalArgumentException("nominal schema has no declared type");
        Map<String, RefinementRules.Descriptor> descriptors = new TreeMap<>();
        Map<String, InferredScalarType> inferred = new TreeMap<>();
        for (Map.Entry<String, StaticZordeSchema> entry : declarations.entrySet()) {
            String name = entry.getKey();
            if (name == null || !name.matches("[A-Za-z_][A-Za-z_0-9]*"))
                throw new IllegalArgumentException("invalid static schema declaration name");
            insertSchema(descriptors, inferred, namespace + "." + name,
                    Objects.requireNonNull(entry.getValue()), nominalNames.contains(name));
        }
        return new Plan(descriptors, inferred);
    }

    /** Error data, not an exception that exposes raw untrusted content. */
    public record ConstraintError(String code, String qualifiedTypeName,
                                  String schemaFingerprint) { }

    /**
     * A checked host-side scalar payload. Constructors are inaccessible to
     * consumers, values are Java String/Long (both immutable), and arbitrary
     * objects or mutable containers cannot be wrapped by this API.
     */
    public static final class CheckedScalar {
        private final String qualifiedTypeName;
        private final String schemaFingerprint;
        private final Object underlying;

        private CheckedScalar(String qualifiedTypeName, String schemaFingerprint,
                              Object underlying) {
            this.qualifiedTypeName = qualifiedTypeName;
            this.schemaFingerprint = schemaFingerprint;
            this.underlying = underlying;
        }

        public String qualifiedTypeName() { return qualifiedTypeName; }
        public String schemaFingerprint() { return schemaFingerprint; }
        public Object intoInner() { return underlying; }
    }

    /** Result<T,E> substrate for the compiler, not the guest Oreslang API. */
    public static final class Conversion {
        private final CheckedScalar value;
        private final ConstraintError error;

        private Conversion(CheckedScalar value, ConstraintError error) {
            this.value = value;
            this.error = error;
        }

        public boolean ok() { return value != null; }
        public CheckedScalar value() {
            if (value == null) throw new IllegalStateException("checked conversion failed");
            return value;
        }
        public ConstraintError error() {
            if (error == null) throw new IllegalStateException("checked conversion succeeded");
            return error;
        }
    }

    /**
     * Parse source into a schema plan without enabling guest execution.
     * This is deliberately separate from OresCompiler.parseAndTypeCheck,
     * which must continue to reject unimplemented refined declarations.
     */
    public static Plan planSource(String source) {
        return plan(Parser.parse(source));
    }

    public static Plan plan(Ast.Program program) {
        Objects.requireNonNull(program, "program");
        // Import resolution and external schema fingerprints are not yet
        // integrated. Reject rather than issue potentially ambiguous schemas.
        if (!program.imports().isEmpty()) {
            throw new IllegalArgumentException(
                    "Zorde schema planning requires resolved imports");
        }
        String namespace = program.namespace();
        if (namespace != null && !namespace.matches("[A-Za-z_][A-Za-z_0-9]*")) {
            throw new IllegalArgumentException("invalid schema namespace");
        }

        Map<String, RefinementRules.Descriptor> collected = new TreeMap<>();
        Map<String, InferredScalarType> inferred = new TreeMap<>();
        for (Ast.ModuleDecl module : program.modules()) {
            String moduleName = module.name();
            if (!moduleName.matches("[A-Za-z_][A-Za-z_0-9]*")) {
                throw new IllegalArgumentException("invalid schema module name " + moduleName);
            }
            String prefix = (namespace == null ? "" : namespace + ".")
                    + (moduleName.equals(Parser.ROOT_MODULE) ? "" : moduleName + ".");
            for (Ast.Decl declaration : module.declarations()) {
                if (!(declaration instanceof Ast.TypeAliasDecl alias)
                        || alias.refinement() == null) {
                    continue;
                }
                if (!alias.name().matches("[A-Za-z_][A-Za-z_0-9]*")) {
                    throw new IllegalArgumentException("invalid refined type name");
                }
                if (!alias.genericParameters().isEmpty()
                        || !alias.target().arguments().isEmpty()
                        || alias.target().inferArguments()) {
                    throw new IllegalArgumentException(
                            "generic/parameterized refined schemas need instantiated lowering: "
                                    + alias.name());
                }
                String qualifiedName = prefix + alias.name();
                if (alias.refinement().nominal() && namespace == null) {
                    throw new IllegalArgumentException(
                            "nominal Zorde schema requires a namespace: " + alias.name());
                }
                // Annotation-first and schema-first MUST lower via the same
                // pure schema builder and the same descriptor construction.
                StaticZordeSchema staticSchema =
                        StaticZordeSchema.fromRefinement(alias.target(), alias.refinement());
                insertSchema(collected, inferred, qualifiedName, staticSchema,
                        alias.refinement().nominal());
            }
        }
        return new Plan(collected, inferred);
    }
}
