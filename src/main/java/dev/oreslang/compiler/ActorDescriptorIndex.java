package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.types.ActorReceiverBinder;
import dev.oreslang.types.ActorStateBinder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Compiler-owned, immutable, NONEXECUTABLE namespace actor descriptors.
 *
 * This index has no caller-facing constructor or registration API. It is
 * assembled only after PackageNamespaceBuild's manifest source scan and the
 * existing actor state/receiver binders. A descriptor is intentionally NOT an
 * ActorRef, typechecked implementation witness, allocation, state-loan token,
 * physical actor frame, dispatcher or source of guest execution authority.
 */
public final class ActorDescriptorIndex {
    public record StateField(String name, long initialI64) {
        public StateField {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("actor descriptor state name is required");
            }
        }
    }

    /** A runtime-only ABI slot. Never placed into public method lookup. */
    public record Ingress(String name, int arity, String declaringUnit) {
        public Ingress {
            if (name == null || (!(name.equals("init") && arity == 0)
                    && !(name.equals("receive") && arity == 1))) {
                throw new IllegalArgumentException("unsupported actor runtime ingress ABI");
            }
            if (declaringUnit == null || declaringUnit.isBlank()) {
                throw new IllegalArgumentException("missing actor ingress source provenance");
            }
        }
    }

    public record Descriptor(
            ActorMethodIndex.ActorKey actor,
            ActorMethodIndex.ActorDeclaration declaration,
            Map<String, StateField> stateFields,
            Map<ActorMethodIndex.MethodKey, ActorMethodIndex.MethodSignature> methods,
            Map<String, Ingress> runtimeIngress,
            String sourceSetSha256) {
        public Descriptor {
            java.util.Objects.requireNonNull(actor, "actor identity");
            java.util.Objects.requireNonNull(declaration, "actor declaration");
            stateFields = Map.copyOf(stateFields);
            methods = Map.copyOf(methods);
            runtimeIngress = Map.copyOf(runtimeIngress);
            if (sourceSetSha256 == null || !sourceSetSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("descriptor requires a complete SHA-256 inventory receipt");
            }
        }

        /** This is admission metadata, not a qualified executable witness. */
        public boolean guestExecutable() { return false; }
    }

    private final Map<ActorMethodIndex.ActorKey, Descriptor> descriptors;

    private ActorDescriptorIndex(Map<ActorMethodIndex.ActorKey, Descriptor> descriptors) {
        this.descriptors = Map.copyOf(descriptors);
    }

    public Descriptor descriptor(ActorMethodIndex.ActorKey key) {
        return descriptors.get(key);
    }

    public Map<ActorMethodIndex.ActorKey, Descriptor> descriptors() {
        return descriptors;
    }

    /**
     * Build one source-provenance-checked view across the three already
     * authenticated indexes. Do not let partial or foreign records acquire
     * an actor identity through a same-named namespace.
     */
    static ActorDescriptorIndex collect(
            String packageIdentity,
            Map<String, String> sourceSha256,
            ActorMethodIndex actorMethods,
            Map<ActorStateBinder.Slot, ActorStateBinder.Binding> stateBindings,
            Map<ActorReceiverBinder.SourceSlot, ActorReceiverBinder.SourceBinding> receivers) {
        java.util.Objects.requireNonNull(packageIdentity, "manifest package identity");
        java.util.Objects.requireNonNull(sourceSha256, "manifest source receipts");
        java.util.Objects.requireNonNull(actorMethods, "nominal actors");
        java.util.Objects.requireNonNull(stateBindings, "sealed actor states");
        java.util.Objects.requireNonNull(receivers, "out-of-line actor methods");

        String inventoryDigest = inventoryDigest(packageIdentity, sourceSha256);
        Map<ActorMethodIndex.ActorKey, Map<String, StateField>> states = new LinkedHashMap<>();
        Map<ActorMethodIndex.ActorKey,
                Map<ActorMethodIndex.MethodKey, ActorMethodIndex.MethodSignature>> methods =
                new LinkedHashMap<>();
        Map<ActorMethodIndex.ActorKey, Map<String, Ingress>> ingresses = new LinkedHashMap<>();
        for (var entry : actorMethods.actors().entrySet()) {
            var key = entry.getKey();
            if (!packageIdentity.equals(key.packageId())) {
                throw new IllegalArgumentException("actor identity outside package manifest");
            }
            requireUnit(sourceSha256, entry.getValue().declaringUnit());
            states.put(key, new LinkedHashMap<>());
            methods.put(key, new LinkedHashMap<>());
            ingresses.put(key, new LinkedHashMap<>());
        }

        for (var entry : stateBindings.entrySet()) {
            var slot = entry.getKey();
            var state = entry.getValue();
            var actor = new ActorMethodIndex.ActorKey(
                    slot.packageIdentity(), slot.namespace(), slot.actor());
            if (!packageIdentity.equals(actor.packageId())
                    || !states.containsKey(actor)
                    || !actorMethods.actor(actor).declaringUnit().equals(state.actorUnit())
                    || !slot.actor().equals(state.schema().actorName())) {
                throw new IllegalArgumentException(
                        "attached state has no exact manifest-owned nominal actor");
            }
            requireUnit(sourceSha256, state.stateUnit());
            Map<String, StateField> fields = states.get(actor);
            for (Ast.ProtectedStateField field : state.schema().fields()) {
                if (!"int".equals(field.type().name())
                        || !field.type().arguments().isEmpty()
                        || field.type().inferArguments()
                        || field.type().isBorrow()
                        || !(field.initializer() instanceof Ast.LiteralExpr literal)
                        || !(literal.value() instanceof Long initial)) {
                    throw new IllegalArgumentException(
                            "actor descriptor accepts only sealed signed-i64 state literals");
                }
                if (fields.putIfAbsent(field.name(),
                        new StateField(field.name(), initial)) != null) {
                    throw new IllegalArgumentException("duplicate actor descriptor state field");
                }
            }
        }

        for (var entry : actorMethods.methods().entrySet()) {
            var key = entry.getKey();
            var signature = entry.getValue();
            if (!methods.containsKey(key.actor())) {
                throw new IllegalArgumentException("method has no manifest-owned actor");
            }
            requireUnit(sourceSha256, signature.declaringUnit());
            if (key.methodName().equals("init") || key.methodName().equals("receive")) {
                throw new IllegalArgumentException("lifecycle ingress appeared as a callable method");
            }
            methods.get(key.actor()).put(key, signature);
        }

        for (var entry : receivers.entrySet()) {
            var slot = entry.getKey();
            var binding = entry.getValue();
            var actor = new ActorMethodIndex.ActorKey(
                    slot.packageIdentity(), slot.namespace(), slot.actor());
            if (!packageIdentity.equals(actor.packageId())
                    || !ingresses.containsKey(actor)
                    || !actorMethods.actor(actor).declaringUnit().equals(binding.actorUnit())) {
                throw new IllegalArgumentException("foreign or stale actor receiver provenance");
            }
            requireUnit(sourceSha256, binding.methodUnit());
            var m = binding.nominalBinding().method();
            if (!m.actorName().equals(slot.actor()) || !m.name().equals(slot.name())
                    || m.parameters().size() != slot.arity()) {
                throw new IllegalArgumentException("receiver ABI disagrees with method metadata");
            }
            if (!m.runtimeIngress()) {
                var methodKey = new ActorMethodIndex.MethodKey(actor, slot.name(), slot.arity());
                var signature = actorMethods.method(methodKey);
                if (signature == null || !signature.unloweredExternal()
                        || !signature.declaringUnit().equals(binding.methodUnit())) {
                    throw new IllegalArgumentException(
                            "out-of-line actor method is missing its fail-closed type index");
                }
                continue;
            }
            if (!m.name().equals("init") && !m.name().equals("receive")) {
                throw new IllegalArgumentException("runtime ingress cannot export a user method");
            }
            if (m.visibility() != Ast.Visibility.PRIVATE
                    || (m.name().equals("init") && !m.parameters().isEmpty())
                    || (m.name().equals("receive") && m.parameters().size() != 1)
                    || !m.returnType().name().equals("void")
                    || !m.returnType().arguments().isEmpty()
                    || m.returnType().inferArguments()) {
                throw new IllegalArgumentException("invalid private runtime ingress ABI");
            }
            if (m.name().equals("receive")) {
                var mail = m.parameters().getFirst().type();
                if (!mail.name().equals("ActorMail")
                        || mail.inferArguments()
                        || mail.arguments().size() != 1
                        || mail.arguments().getFirst().isBorrow()
                        || mail.arguments().getFirst().isClosedAggregateType()) {
                    throw new IllegalArgumentException(
                            "runtime receive requires one unborrowed ActorMail<T> value");
                }
            }
            if (actorMethods.method(new ActorMethodIndex.MethodKey(
                    actor, slot.name(), slot.arity())) != null) {
                throw new IllegalArgumentException("runtime ingress leaked into public method index");
            }
            var ingress = new Ingress(m.name(), slot.arity(), binding.methodUnit());
            if (ingresses.get(actor).putIfAbsent(ingress.name(), ingress) != null) {
                throw new IllegalArgumentException("duplicate actor ingress descriptor");
            }
        }

        Map<ActorMethodIndex.ActorKey, Descriptor> result = new LinkedHashMap<>();
        for (var entry : actorMethods.actors().entrySet()) {
            var key = entry.getKey();
            result.put(key, new Descriptor(key, entry.getValue(),
                    states.get(key), methods.get(key), ingresses.get(key), inventoryDigest));
        }
        return new ActorDescriptorIndex(result);
    }

    private static void requireUnit(Map<String, String> receipts, String sourceUnit) {
        String hash = receipts.get(sourceUnit);
        if (hash == null || !hash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "actor descriptor source is missing from manifest receipts: " + sourceUnit);
        }
    }

    private static String inventoryDigest(String packageIdentity, Map<String, String> receipts) {
        if (packageIdentity.isBlank() || receipts.isEmpty()) {
            throw new IllegalArgumentException("no authenticated actor source inventory");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateLengthDelimited(digest, packageIdentity);
            for (var entry : new TreeMap<>(receipts).entrySet()) {
                requireUnit(receipts, entry.getKey());
                updateLengthDelimited(digest, entry.getKey());
                updateLengthDelimited(digest, entry.getValue());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required for actor provenance", unavailable);
        }
    }

    private static void updateLengthDelimited(MessageDigest digest, String value) {
        byte[] raw = value.getBytes(StandardCharsets.UTF_8);
        digest.update(new byte[] {
                (byte) (raw.length >>> 24),
                (byte) (raw.length >>> 16),
                (byte) (raw.length >>> 8),
                (byte) raw.length });
        digest.update(raw);
    }
}
