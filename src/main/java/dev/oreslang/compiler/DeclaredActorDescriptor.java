package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorContractWitness;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Immutable, source-snapshot-bound metadata for a statically witnessed
 * declaration-only actor. This is NOT a runtime actor type, callable method
 * table, proof that an external method BODY typechecks, or a capability.
 *
 * Instances are produced only by the manifest-owned package audit. The
 * fingerprint is for change detection, NOT a signature/authentication token.
 * Runtime admission remains deliberately unsupported until typed actor
 * dispatch, initialization, state loans and generation fencing are lowered.
 */
public final class DeclaredActorDescriptor {
    public static final String SCHEMA = "atto.declared-actor-descriptor.v1";

    public record SealedField(
            String name, String binding, String type, String initialValue) { }

    public record RequiredMethod(
            String contractUnit, String contractName, String methodUnit,
            String name, int arity, List<String> argumentTypes,
            List<Boolean> mutableArguments, String returnType) {
        public RequiredMethod {
            argumentTypes = List.copyOf(argumentTypes);
            mutableArguments = List.copyOf(mutableArguments);
            if (arity != argumentTypes.size() || arity != mutableArguments.size()) {
                throw new IllegalArgumentException("actor descriptor signature arity mismatch");
            }
        }
    }

    private final ActorMethodIndex.ActorKey identity;
    private final String actorUnit;
    private final Ast.Visibility visibility;
    private final Ast.ActorKind actorKind;
    private final List<SealedField> sealedFields;
    private final List<RequiredMethod> requiredMethods;
    private final String manifestSha256;
    private final String sourceSetSha256;
    private final String descriptorSha256;

    private DeclaredActorDescriptor(
            ActorMethodIndex.ActorKey identity, String actorUnit,
            Ast.Visibility visibility, Ast.ActorKind actorKind,
            List<SealedField> sealedFields, List<RequiredMethod> requiredMethods,
            String manifestSha256, String sourceSetSha256) {
        this.identity = Objects.requireNonNull(identity);
        this.actorUnit = Objects.requireNonNull(actorUnit);
        this.visibility = Objects.requireNonNull(visibility);
        this.actorKind = Objects.requireNonNull(actorKind);
        this.sealedFields = List.copyOf(sealedFields);
        this.requiredMethods = List.copyOf(requiredMethods);
        this.manifestSha256 = Objects.requireNonNull(manifestSha256);
        this.sourceSetSha256 = Objects.requireNonNull(sourceSetSha256);

        var digest = sha256();
        put(digest, SCHEMA);
        put(digest, identity.packageId());
        put(digest, identity.namespace());
        put(digest, identity.actorName());
        put(digest, actorUnit);
        put(digest, visibility.name());
        put(digest, actorKind.name());
        put(digest, manifestSha256);
        put(digest, sourceSetSha256);
        put(digest, Integer.toString(this.sealedFields.size()));
        for (var field : this.sealedFields) {
            put(digest, field.name());
            put(digest, field.binding());
            put(digest, field.type());
            put(digest, field.initialValue());
        }
        put(digest, Integer.toString(this.requiredMethods.size()));
        for (var method : this.requiredMethods) {
            put(digest, method.contractUnit());
            put(digest, method.contractName());
            put(digest, method.methodUnit());
            put(digest, method.name());
            put(digest, Integer.toString(method.arity()));
            for (int i = 0; i < method.arity(); i++) {
                put(digest, method.argumentTypes().get(i));
                put(digest, Boolean.toString(method.mutableArguments().get(i)));
            }
            put(digest, method.returnType());
        }
        this.descriptorSha256 = java.util.HexFormat.of().formatHex(digest.digest());
    }

    public ActorMethodIndex.ActorKey identity() { return identity; }
    public String actorUnit() { return actorUnit; }
    public Ast.Visibility visibility() { return visibility; }
    public Ast.ActorKind actorKind() { return actorKind; }
    public List<SealedField> sealedFields() { return sealedFields; }
    public List<RequiredMethod> requiredMethods() { return requiredMethods; }
    public String manifestSha256() { return manifestSha256; }
    public String sourceSetSha256() { return sourceSetSha256; }
    public String descriptorSha256() { return descriptorSha256; }

    /**
     * Only callable by the manifest audit, after its single-source-snapshot
     * witness/index consistency check. No ambient type or import fallback.
     */
    static Map<ActorMethodIndex.ActorKey, DeclaredActorDescriptor> collect(
            String packageId, ActorContractWitness.Result witness,
            ActorMethodIndex index, Map<String, Ast.Program> parsed,
            Map<String, String> sourceHashes, String manifestHash) {
        if (!packageId.equals(witness.packageIdentity())) {
            throw new IllegalArgumentException("actor descriptor package mismatch");
        }
        var snapshotDigest = sha256();
        put(snapshotDigest, "atto.manifest-source-set.v1");
        put(snapshotDigest, manifestHash);
        for (var source : new TreeMap<>(sourceHashes).entrySet()) {
            put(snapshotDigest, source.getKey());
            put(snapshotDigest, source.getValue());
        }
        String sourceSetHash =
                java.util.HexFormat.of().formatHex(snapshotDigest.digest());

        Map<ActorMethodIndex.ActorKey, DeclaredActorDescriptor> descriptors =
                new LinkedHashMap<>();
        var entries = new ArrayList<>(witness.actors().entrySet());
        entries.sort(Comparator.comparing(
                (Map.Entry<ActorContractWitness.ActorKey, ActorContractWitness.ActorProof> e)
                        -> e.getKey().namespace())
                .thenComparing(e -> e.getKey().actor()));
        for (var entry : entries) {
            ActorContractWitness.ActorKey sourceKey = entry.getKey();
            ActorContractWitness.ActorProof proof = entry.getValue();
            var identity = new ActorMethodIndex.ActorKey(
                    sourceKey.packageIdentity(), sourceKey.namespace(), sourceKey.actor());
            ActorMethodIndex.ActorDeclaration indexed = index.actor(identity);
            if (indexed == null || !proof.actorUnit().equals(indexed.declaringUnit())) {
                throw new IllegalArgumentException(
                        "actor descriptor lacks indexed nominal origin: " + identity);
            }

            Ast.DeclaredActorDecl actor = null;
            for (Ast.Decl declaration : rootDeclarations(
                    parsed, proof.actorUnit(), sourceKey.namespace())) {
                if (declaration instanceof Ast.DeclaredActorDecl candidate
                        && candidate.name().equals(identity.actorName())) {
                    if (actor != null) throw new IllegalArgumentException(
                            "multiple declared actors at " + proof.actorUnit());
                    actor = candidate;
                }
            }
            if (actor == null || actor.actorKind() != indexed.kind()
                    || actor.visibility() != indexed.visibility()) {
                throw new IllegalArgumentException(
                        "actor descriptor nominal origin mismatch: " + identity);
            }

            List<SealedField> fields = new ArrayList<>();
            if (proof.stateUnit() != null) {
                Ast.ActorStateAttachmentDecl state = null;
                for (Ast.Decl declaration : rootDeclarations(
                        parsed, proof.stateUnit(), sourceKey.namespace())) {
                    if (declaration instanceof Ast.ActorStateAttachmentDecl candidate
                            && candidate.actorName().equals(identity.actorName())) {
                        if (state != null) throw new IllegalArgumentException(
                                "duplicate actor descriptor state origin: " + identity);
                        state = candidate;
                    }
                }
                if (state == null) throw new IllegalArgumentException(
                        "actor descriptor missing verified sealed state: " + identity);
                for (var field : state.fields()) {
                    if (!(field.initializer() instanceof Ast.LiteralExpr literal)
                            || !(literal.value() instanceof Long
                                    || literal.value() instanceof Boolean)) {
                        throw new IllegalArgumentException(
                                "actor descriptor requires a scalar literal state default");
                    }
                    fields.add(new SealedField(field.name(),
                            field.bindingKind().name(), field.type().name(),
                            literal.value().toString()));
                }
            }

            List<RequiredMethod> methods = new ArrayList<>();
            for (var method : proof.methods()) {
                var signature = index.method(new ActorMethodIndex.MethodKey(
                        identity, method.name(), method.arity()));
                if (signature == null || !signature.declarationEligible()
                        || !signature.declaringUnit().equals(method.methodUnit())) {
                    throw new IllegalArgumentException(
                            "actor descriptor method witness mismatch: " + identity);
                }
                methods.add(new RequiredMethod(
                        method.contractUnit(), method.contract(), method.methodUnit(),
                        method.name(), method.arity(),
                        signature.argumentTypes().stream().map(Ast.TypeRef::name).toList(),
                        signature.mutableArguments(), signature.returnType().name()));
            }
            methods.sort(Comparator.comparing(RequiredMethod::name)
                    .thenComparingInt(RequiredMethod::arity)
                    .thenComparing(RequiredMethod::contractName));
            var descriptor = new DeclaredActorDescriptor(
                    identity, proof.actorUnit(), actor.visibility(), actor.actorKind(),
                    fields, methods, manifestHash, sourceSetHash);
            if (descriptors.putIfAbsent(identity, descriptor) != null) {
                throw new IllegalArgumentException(
                        "duplicate declared actor descriptor: " + identity);
            }
        }
        return Collections.unmodifiableMap(descriptors);
    }

    private static List<Ast.Decl> rootDeclarations(
            Map<String, Ast.Program> parsed, String unit, String namespace) {
        Ast.Program program = parsed.get(unit);
        if (program == null || !namespace.equals(program.namespace())) {
            throw new IllegalArgumentException(
                    "actor descriptor source unit/namespace mismatch: " + unit);
        }
        List<Ast.Decl> result = new ArrayList<>();
        for (Ast.ModuleDecl module : program.modules()) {
            if (Parser.ROOT_MODULE.equals(module.name())) {
                result.addAll(module.declarations());
            }
        }
        return result;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void put(MessageDigest digest, String value) {
        byte[] bytes = Objects.requireNonNull(value).getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
