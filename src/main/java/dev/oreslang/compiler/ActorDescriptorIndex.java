package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorContractWitness;
import dev.oreslang.types.ActorReceiverBinder;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Immutable, manifest-bound STATIC descriptor of a declaration-only actor.
 *
 * This is NOT executable HIR/MIR, a guest class, an ActorRef, an ActorContext,
 * a state container, or a mailbox/critical-loan capability. Creation is
 * package-private and may be called only by the trusted manifest builder
 * after source-set binding, signature checking and source SHA-256 collection.
 */
public final class ActorDescriptorIndex {
    public static final String SCHEMA = "atto-static-actor-descriptor-v1";

    public record Contract(String name, String sourceUnit) {
        public Contract {
            nonblank(name, "contract name");
            nonblank(sourceUnit, "contract source unit");
        }
    }

    public record StateField(
            int ordinal, String name, Ast.BindingKind binding,
            String typeName, String initialLiteral) {
        public StateField {
            if (ordinal < 0 || ordinal >= 32) {
                throw new IllegalArgumentException("invalid actor state field ordinal");
            }
            nonblank(name, "actor state field name");
            if (binding != Ast.BindingKind.LET && binding != Ast.BindingKind.VAL) {
                throw new IllegalArgumentException("invalid actor state binding");
            }
            if (!typeName.equals("int") && !typeName.equals("bool")) {
                throw new IllegalArgumentException("unsupported actor state field type");
            }
            nonblank(initialLiteral, "actor state initial literal");
        }
    }

    public record Method(
            String name, int arity, String sourceUnit,
            Ast.Visibility visibility,
            List<String> parameterTypes, List<Boolean> mutableParameters,
            String returnType) {
        public Method {
            nonblank(name, "method name");
            if (arity < 0) throw new IllegalArgumentException("negative method arity");
            nonblank(sourceUnit, "method source unit");
            Objects.requireNonNull(visibility, "method visibility");
            parameterTypes = List.copyOf(parameterTypes);
            mutableParameters = List.copyOf(mutableParameters);
            nonblank(returnType, "method return type");
            if (arity != parameterTypes.size() || arity != mutableParameters.size()) {
                throw new IllegalArgumentException("method signature/arity mismatch");
            }
        }
    }

    /**
     * No public constructor: only the package-owned manifest linker can mint
     * a descriptor. Even this value is NOT an executable actor capability.
     */
    public static final class Descriptor {
        private final ActorMethodIndex.ActorKey key;
        private final String actorUnit;
        private final Ast.Visibility visibility;
        private final Ast.ActorKind actorKind;
        private final List<Contract> contracts;
        private final String stateUnit;
        private final List<StateField> state;
        private final List<Method> methods;
        private final String manifestSha256;
        private final String abiSha256;
        private final String sourceSha256;

        private Descriptor(
                ActorMethodIndex.ActorKey key,
                String actorUnit, Ast.Visibility visibility, Ast.ActorKind actorKind,
                List<Contract> contracts, String stateUnit, List<StateField> state,
                List<Method> methods, String manifestSha256,
                String abiSha256, String sourceSha256) {
            this.key = Objects.requireNonNull(key, "actor key");
            nonblank(actorUnit, "actor declaration unit");
            this.actorUnit = actorUnit;
            this.visibility = Objects.requireNonNull(visibility, "actor visibility");
            if (actorKind != Ast.ActorKind.SHARED) {
                throw new IllegalArgumentException("v1 only supports shared declared actors");
            }
            this.actorKind = actorKind;
            this.contracts = List.copyOf(contracts);
            this.stateUnit = stateUnit;
            this.state = List.copyOf(state);
            this.methods = List.copyOf(methods);
            if (stateUnit == null && !state.isEmpty()) {
                throw new IllegalArgumentException("state fields without attached state unit");
            }
            sha(manifestSha256, "manifest hash");
            sha(abiSha256, "actor ABI hash");
            sha(sourceSha256, "actor source hash");
            this.manifestSha256 = manifestSha256;
            this.abiSha256 = abiSha256;
            this.sourceSha256 = sourceSha256;
        }

        public ActorMethodIndex.ActorKey key() { return key; }
        public String actorUnit() { return actorUnit; }
        public Ast.Visibility visibility() { return visibility; }
        public Ast.ActorKind actorKind() { return actorKind; }
        public List<Contract> contracts() { return contracts; }
        public String stateUnit() { return stateUnit; }
        public List<StateField> state() { return state; }
        public List<Method> methods() { return methods; }
        public String manifestSha256() { return manifestSha256; }
        public String abiSha256() { return abiSha256; }
        public String sourceSha256() { return sourceSha256; }
    }

    private final Map<ActorMethodIndex.ActorKey, Descriptor> actors;

    private ActorDescriptorIndex(Map<ActorMethodIndex.ActorKey, Descriptor> actors) {
        this.actors = Collections.unmodifiableMap(new LinkedHashMap<>(actors));
    }

    public Map<ActorMethodIndex.ActorKey, Descriptor> actors() { return actors; }
    public Descriptor actor(ActorMethodIndex.ActorKey key) { return actors.get(key); }

    /**
     * A strict source-level descriptor: it cannot prove that external method
     * bodies have been typechecked or lowered. Never expose this builder as an
     * authorization boundary for arbitrary maps supplied by a guest.
     */
    static ActorDescriptorIndex collect(
            String packageIdentity,
            Map<String, Ast.Program> parsed,
            ActorContractWitness.Result witness,
            ActorMethodIndex methodIndex,
            Map<String, String> verifiedSourceHashes,
            String verifiedManifestHash) {
        nonblank(packageIdentity, "package identity");
        Objects.requireNonNull(parsed, "parsed source set");
        Objects.requireNonNull(witness, "contract witness");
        Objects.requireNonNull(methodIndex, "actor method index");
        Objects.requireNonNull(verifiedSourceHashes, "source fingerprints");
        sha(verifiedManifestHash, "verified manifest fingerprint");
        if (!packageIdentity.equals(witness.packageIdentity())
                || parsed.size() != verifiedSourceHashes.size()
                || !parsed.keySet().equals(verifiedSourceHashes.keySet())) {
            throw new IllegalArgumentException(
                    "ACTOR_DESCRIPTOR_PROVENANCE_MISMATCH: inconsistent package/source-set identity");
        }
        for (String hash : verifiedSourceHashes.values()) sha(hash, "verified source fingerprint");

        Map<ActorMethodIndex.ActorKey, Descriptor> entries = new LinkedHashMap<>();
        List<ActorContractWitness.ActorKey> ordered = new ArrayList<>(witness.actors().keySet());
        ordered.sort(Comparator.comparing(ActorContractWitness.ActorKey::namespace)
                .thenComparing(ActorContractWitness.ActorKey::actor));

        for (var id : ordered) {
            if (!packageIdentity.equals(id.packageIdentity())) {
                throw new IllegalArgumentException("foreign actor proof package: " + id);
            }
            var proof = witness.actors().get(id);
            var actor = locate(parsed, proof.actorUnit(), id.namespace(),
                    Ast.DeclaredActorDecl.class, id.actor());
            var methodActor = new ActorMethodIndex.ActorKey(
                    packageIdentity, id.namespace(), id.actor());
            var actorIndex = methodIndex.actor(methodActor);
            if (actorIndex == null || !proof.actorUnit().equals(actorIndex.declaringUnit())
                    || actor.visibility() != actorIndex.visibility()
                    || actor.actorKind() != actorIndex.kind() || actorIndex.generic()) {
                throw new IllegalArgumentException(
                        "ACTOR_DESCRIPTOR_PROVENANCE_MISMATCH: actor index conflict: " + methodActor);
            }

            List<Contract> contracts = new ArrayList<>();
            Set<String> needed = new LinkedHashSet<>();
            for (Ast.TypeRef wanted : actor.contracts()) {
                if (!needed.add(wanted.name())) {
                    throw new IllegalArgumentException("duplicate declared actor contract");
                }
                String foundAt = null;
                for (var unit : new TreeMap<>(parsed).entrySet()) {
                    if (!id.namespace().equals(unit.getValue().namespace())) continue;
                    for (var module : unit.getValue().modules()) {
                        if (!Parser.ROOT_MODULE.equals(module.name())) continue;
                        for (var decl : module.declarations()) {
                            if (decl instanceof Ast.InterfaceDecl iface
                                    && iface.actorContract() && iface.name().equals(wanted.name())) {
                                if (foundAt != null) {
                                    throw new IllegalArgumentException(
                                            "duplicate declared actor contract source: " + wanted.name());
                                }
                                foundAt = unit.getKey();
                            }
                        }
                    }
                }
                if (foundAt == null || !verifiedSourceHashes.containsKey(foundAt)) {
                    throw new IllegalArgumentException(
                            "ACTOR_DESCRIPTOR_PROVENANCE_MISMATCH: missing actor contract: "
                                    + wanted.name());
                }
                contracts.add(new Contract(wanted.name(), foundAt));
            }

            List<StateField> fields = new ArrayList<>();
            if (proof.stateUnit() != null) {
                var state = locate(parsed, proof.stateUnit(), id.namespace(),
                        Ast.ActorStateAttachmentDecl.class, id.actor());
                int ordinal = 0;
                for (Ast.AttachedActorStateField field : state.fields()) {
                    if (!(field.initializer() instanceof Ast.LiteralExpr literal)) {
                        throw new IllegalArgumentException(
                                "nonliteral declared actor state initializer");
                    }
                    Object initial = literal.value();
                    if ((field.type().name().equals("int") && !(initial instanceof Long))
                            || (field.type().name().equals("bool")
                                    && !(initial instanceof Boolean))) {
                        throw new IllegalArgumentException(
                                "declared actor state literal/type mismatch");
                    }
                    fields.add(new StateField(ordinal++, field.name(), field.bindingKind(),
                            field.type().name(), initial.toString()));
                }
            }

            List<Method> methods = new ArrayList<>();
            for (var entry : methodIndex.methods().entrySet()) {
                var selector = entry.getKey();
                if (!selector.actor().equals(methodActor)) continue;
                var signature = entry.getValue();
                var sourceSlot = new ActorReceiverBinder.SourceSlot(
                        packageIdentity, id.namespace(), id.actor(),
                        selector.methodName(), selector.arity());
                var origin = witness.boundMethods().get(sourceSlot);
                if (origin == null || !origin.methodUnit().equals(signature.declaringUnit())
                        || signature.async() || signature.suspends() || signature.generic()
                        || signature.structural() || signature.checkedEffects()
                        || signature.abstractMethod()) {
                    throw new IllegalArgumentException(
                            "ACTOR_DESCRIPTOR_UNVERIFIED_METHOD: " + sourceSlot);
                }
                if (!verifiedSourceHashes.containsKey(origin.methodUnit())) {
                    throw new IllegalArgumentException(
                            "actor external method lacks source fingerprint");
                }
                List<String> arguments = new ArrayList<>();
                for (var type : signature.argumentTypes()) {
                    arguments.add(simpleType(type, false));
                }
                methods.add(new Method(selector.methodName(), selector.arity(),
                        origin.methodUnit(), signature.visibility(),
                        arguments, signature.mutableArguments(),
                        simpleType(signature.returnType(), true)));
            }
            methods.sort(Comparator.comparing(Method::name)
                    .thenComparingInt(Method::arity));

            for (var required : proof.methods()) {
                if (!needed.contains(required.contract())) {
                    throw new IllegalArgumentException(
                            "ACTOR_DESCRIPTOR_PROVENANCE_MISMATCH: unclaimed contract witness");
                }
                if (contracts.stream().noneMatch(c -> c.name().equals(required.contract())
                        && c.sourceUnit().equals(required.contractUnit()))) {
                    throw new IllegalArgumentException(
                            "ACTOR_DESCRIPTOR_PROVENANCE_MISMATCH: contract source changed");
                }
                if (methods.stream().noneMatch(m -> m.name().equals(required.name())
                        && m.arity() == required.arity()
                        && m.sourceUnit().equals(required.methodUnit())
                        && m.visibility() == Ast.Visibility.PUBLIC)) {
                    throw new IllegalArgumentException(
                            "ACTOR_DESCRIPTOR_PROVENANCE_MISMATCH: missing required method slot");
                }
            }

            var abi = new FramedHash();
            abi.add(SCHEMA); abi.add(packageIdentity); abi.add(id.namespace());
            abi.add(id.actor()); abi.add(actor.visibility().name());
            abi.add(actor.actorKind().name());
            abi.add(Integer.toString(contracts.size()));
            for (var contract : contracts) { abi.add(contract.name()); }
            abi.add(Integer.toString(fields.size()));
            for (var field : fields) {
                abi.add(Integer.toString(field.ordinal())); abi.add(field.name());
                abi.add(field.binding().name()); abi.add(field.typeName());
                abi.add(field.initialLiteral());
            }
            abi.add(Integer.toString(methods.size()));
            for (var m : methods) {
                abi.add(m.name()); abi.add(Integer.toString(m.arity()));
                abi.add(m.visibility().name()); abi.add(m.returnType());
                for (int j = 0; j < m.arity(); j++) {
                    abi.add(m.parameterTypes().get(j));
                    abi.add(m.mutableParameters().get(j).toString());
                }
            }

            var sourceHash = new FramedHash();
            sourceHash.add(SCHEMA); sourceHash.add(verifiedManifestHash);
            sourceHash.add(packageIdentity); sourceHash.add(id.namespace());
            sourceHash.add(id.actor());
            Map<String, String> involved = new TreeMap<>();
            involved.put(proof.actorUnit(), verifiedSourceHashes.get(proof.actorUnit()));
            if (proof.stateUnit() != null) {
                involved.put(proof.stateUnit(), verifiedSourceHashes.get(proof.stateUnit()));
            }
            for (var contract : contracts) {
                involved.put(contract.sourceUnit(),
                        verifiedSourceHashes.get(contract.sourceUnit()));
            }
            for (var method : methods) {
                involved.put(method.sourceUnit(), verifiedSourceHashes.get(method.sourceUnit()));
            }
            for (var unit : involved.entrySet()) {
                if (unit.getValue() == null) {
                    throw new IllegalArgumentException("missing source fingerprint: " + unit.getKey());
                }
                sourceHash.add(unit.getKey()); sourceHash.add(unit.getValue());
            }

            var descriptor = new Descriptor(methodActor, proof.actorUnit(),
                    actor.visibility(), actor.actorKind(), contracts,
                    proof.stateUnit(), fields, methods, verifiedManifestHash,
                    abi.finish(), sourceHash.finish());
            if (entries.putIfAbsent(methodActor, descriptor) != null) {
                throw new IllegalArgumentException(
                        "duplicate actor descriptor identity: " + methodActor);
            }
        }

        return new ActorDescriptorIndex(entries);
    }

    private static String simpleType(Ast.TypeRef type, boolean allowVoid) {
        if (!type.arguments().isEmpty() || type.inferArguments()
                || !(type.name().equals("int") || type.name().equals("bool")
                || type.name().equals("string")
                || (allowVoid && type.name().equals("void")))) {
            throw new IllegalArgumentException(
                    "actor descriptor v1 refuses non-scalar method ABI: " + type.name());
        }
        return type.name();
    }

    private static <T extends Ast.Decl> T locate(
            Map<String, Ast.Program> sources, String unit,
            String namespace, Class<T> type, String actor) {
        var program = sources.get(unit);
        if (program == null || !namespace.equals(program.namespace())) {
            throw new IllegalArgumentException(
                    "ACTOR_DESCRIPTOR_PROVENANCE_MISMATCH: source/namespace: " + unit);
        }
        T result = null;
        for (var module : program.modules()) {
            if (!Parser.ROOT_MODULE.equals(module.name())) continue;
            for (var decl : module.declarations()) {
                String target = decl instanceof Ast.DeclaredActorDecl a ? a.name()
                        : decl instanceof Ast.ActorStateAttachmentDecl s ? s.actorName() : null;
                if (type.isInstance(decl) && actor.equals(target)) {
                    if (result != null) {
                        throw new IllegalArgumentException("duplicate actor constituent: " + unit);
                    }
                    result = type.cast(decl);
                }
            }
        }
        if (result == null) {
            throw new IllegalArgumentException(
                    "ACTOR_DESCRIPTOR_PROVENANCE_MISMATCH: missing constituent: " + unit);
        }
        return result;
    }

    private static void sha(String value, String label) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid " + label);
        }
    }

    private static void nonblank(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing " + label);
        }
    }

    /** Length-prefixed hashes prevent ambiguous concatenation collisions. */
    private static final class FramedHash {
        private final MessageDigest md;

        private FramedHash() {
            try {
                md = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException missing) {
                throw new IllegalStateException("SHA-256 unavailable", missing);
            }
        }

        private void add(String value) {
            Objects.requireNonNull(value, "descriptor field");
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            md.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            md.update(bytes);
        }

        private String finish() {
            return java.util.HexFormat.of().formatHex(md.digest());
        }
    }
}
